package app.sila.feature.contacts.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sila.core.designsystem.ActionSheetItem
import app.sila.core.designsystem.SilaActionSheet
import app.sila.core.designsystem.SilaColors
import app.sila.core.designsystem.SilaDimens
import app.sila.core.designsystem.SilaUndoBar
import app.sila.core.model.CallDirection
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.ImportantDate
import app.sila.core.model.RecentInteraction

/**
 * شاشة تفاصيل جهة الاتصال (T6 · Issue #27 · docs/features/03 §أ).
 *
 * Stateful رقيقة: تجمع [ContactDetailsUiState] وتفوض العرض إلى [ContactDetailsContent]
 * العديم الحالة (نمط T3/T5). آثار المنصة (اتصال/رسالة/بريد/رابط/تطبيق متصل/مشاركة vCard)
 * تُجمَع في غلاف التطبيق عبر [ContactDetailsEffect] — الشاشة لا تحمل منطق أعمال ولا
 * تلمس Room/المنصة (ARCHITECTURE.md §7). الحقول الفارغة لا تُعرض (AC) — كل صف يفحص
 * بياناته قبل أن يُرسم.
 */
@Composable
fun ContactDetailsScreen(
    viewModel: ContactDetailsViewModel,
    strings: ContactDetailsStrings,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    ContactDetailsContent(
        state = state,
        strings = strings,
        onIntent = viewModel::onIntent,
        modifier = modifier,
    )
}

/** العرض العديم الحالة — كل التفاعل عبر [ContactDetailsIntent] حصراً (لا منطق أعمال هنا). */
@Composable
fun ContactDetailsContent(
    state: ContactDetailsUiState,
    strings: ContactDetailsStrings,
    onIntent: (ContactDetailsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showActionsSheet by remember { mutableStateOf(false) }
    var showCategorySheet by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            state.isLoading -> CenteredText(strings.loading)
            state.isNotFound -> CenteredText(strings.notFoundMessage)
            else -> DetailsBody(
                state = state,
                strings = strings,
                onIntent = onIntent,
                onShowActions = { showActionsSheet = true },
            )
        }

        // شريط التراجع (J-5) — المكوّن الموحد [SilaUndoBar] المستخرج من هذه الشاشة.
        if (state.isUndoWindowActive) {
            SilaUndoBar(
                message = strings.deleteUndoMessage,
                undoLabel = strings.undo,
                onUndo = { onIntent(ContactDetailsIntent.UndoDelete) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    if (showActionsSheet) {
        val contact = state.contact
        if (contact != null) {
            SilaActionSheet(
                items = listOf(
                    ActionSheetItem(iconEmoji = "🔗", title = strings.actionShare) {
                        onIntent(ContactDetailsIntent.ShareVCard(contact.id))
                    },
                    ActionSheetItem(iconEmoji = "🏷️", title = strings.actionChangeCategory) {
                        showCategorySheet = true
                    },
                    ActionSheetItem(iconEmoji = "🗑️", title = strings.actionDelete, subtitle = strings.deleteUndoHint, destructive = true) {
                        onIntent(ContactDetailsIntent.RequestDelete)
                    },
                ),
                cancelLabel = strings.cancel,
                onDismiss = { showActionsSheet = false },
            )
        }
    }

    if (showCategorySheet) {
        SilaActionSheet(
            items = Category.entries.map { category ->
                ActionSheetItem(
                    iconEmoji = "🏷️",
                    title = strings.categoryTitle(category),
                    selected = state.contact?.category == category,
                ) {
                    state.contact?.let { onIntent(ContactDetailsIntent.ChangeCategory(it.id, category)) }
                }
            },
            cancelLabel = strings.cancel,
            onDismiss = { showCategorySheet = false },
        )
    }
}

@Composable
private fun DetailsBody(
    state: ContactDetailsUiState,
    strings: ContactDetailsStrings,
    onIntent: (ContactDetailsIntent) -> Unit,
    onShowActions: () -> Unit,
) {
    val contact = state.contact ?: return
    var showEmergencyWarning by remember { mutableStateOf(false) }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item { TopBar(state, strings, onIntent, onShowActions) }
        item { HeaderBlock(state, strings, contact) }
        item { QuickActions(state, strings, contact, onIntent) }

        // رنين الطوارئ (T8-11 · docs/features/06): جهة EMERGENCY حصراً — حوار التحذير
        // أولاً، والبدء الفعلي قرار المضيف (بوابة CALL_PHONE + استخدام أرحام).
        if (contact.category == Category.EMERGENCY) {
            item { EmergencyRingButton(strings = strings, onAsk = { showEmergencyWarning = true }) }
        }

        item { SectionDivider() }

        // الأرقام — الحقول الفارغة لا تظهر (AC).
        if (contact.phones.isNotEmpty()) {
            item { SectionTitle(strings.phonesSection) }
            itemsIndexed(contact.phones) { _, phone ->
                PhoneRow(
                    phone = phone,
                    strings = strings,
                    onClick = { onIntent(ContactDetailsIntent.CallNumber(phone.e164)) },
                    onLongClick = { onIntent(ContactDetailsIntent.Message(phone.e164)) },
                )
            }
            item { SectionDivider() }
        }

        // التطبيقات المتصلة — المثبتة فعلاً حصراً (AC).
        if (state.connectedApps.isNotEmpty()) {
            item { SectionTitle(strings.appsSection) }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = SilaDimens.Space16, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(SilaDimens.Space8),
                ) {
                    state.connectedApps.forEach { app ->
                        val phone = primaryPhone(contact)
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable(enabled = phone != null) {
                                    phone?.let { onIntent(ContactDetailsIntent.OpenConnectedApp(app, it)) }
                                }
                                .padding(2.dp),
                        ) {
                            Text(
                                text = strings.connectedAppName(app.id),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = SilaDimens.Space16, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
            item { SectionDivider() }
        }

        // أرحام — الوتيرة وآخر اتصال (القرابة تحريرها تفصيل T8).
        if (state.arhamFrequency != null) {
            item { SectionTitle(strings.arhamSection) }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = SilaDimens.Space16, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(SilaDimens.Space8),
                ) {
                    Text(
                        text = strings.arhamFrequencyLabel(state.arhamFrequency),
                        style = MaterialTheme.typography.bodyMedium,
                        color = SilaColors.forCategory(contact.category),
                        fontWeight = FontWeight.Medium,
                    )
                    state.arhamDaysSinceLastContact?.let { days ->
                        Text(
                            text = strings.arhamDaysSinceLastContact(days),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item { SectionDivider() }
        }

        // السجل — قراءة Room حصراً (بلا READ_CALL_LOG في مسار التفاصيل).
        if (state.recentInteractions.isNotEmpty()) {
            item { SectionTitle(strings.historySection) }
            itemsIndexed(state.recentInteractions) { _, interaction ->
                InteractionRow(interaction, strings)
            }
            item { SectionDivider() }
        }

        if (contact.emails.isNotEmpty()) {
            item { SectionTitle(strings.emailsSection) }
            itemsIndexed(contact.emails) { _, email ->
                ValueRow(value = email.address, secondary = strings.emailTypeName(email.type))
            }
            item { SectionDivider() }
        }

        if (contact.websites.isNotEmpty()) {
            item { SectionTitle(strings.websitesSection) }
            itemsIndexed(contact.websites) { _, url ->
                ValueRow(value = url, onLongClick = { onIntent(ContactDetailsIntent.OpenWebsite(url)) })
            }
            item { SectionDivider() }
        }

        if (contact.addresses.isNotEmpty()) {
            item { SectionTitle(strings.addressesSection) }
            itemsIndexed(contact.addresses) { _, address ->
                ValueRow(value = address.toString(), secondary = strings.addressTypeName(address.type))
            }
            item { SectionDivider() }
        }

        if (contact.dates.isNotEmpty()) {
            item { SectionTitle(strings.datesSection) }
            itemsIndexed(contact.dates) { _, date ->
                ValueRow(value = formatDate(date), secondary = strings.dateTypeName(date.type))
            }
            item { SectionDivider() }
        }

        if (contact.organization != null) {
            item { SectionTitle(strings.companySection) }
            item {
                Column {
                    contact.organization?.company?.takeIf { it.isNotBlank() }?.let {
                        ValueRow(value = it)
                    }
                    contact.organization?.department?.takeIf { it.isNotBlank() }?.let {
                        ValueRow(value = it)
                    }
                    contact.organization?.jobTitles?.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
                        ValueRow(value = it)
                    }
                }
            }
            item { SectionDivider() }
        }

        contact.notes?.takeIf { it.isNotBlank() }?.let { notes ->
            item { SectionTitle(strings.notesSection) }
            item { ValueRow(value = notes) }
            item { SectionDivider() }
        }

        if (contact.customFields.isNotEmpty()) {
            item { SectionTitle(strings.customFieldsSection) }
            itemsIndexed(contact.customFields) { _, field ->
                ValueRow(value = field.value, secondary = field.label)
            }
            item { SectionDivider() }
        }

        // الحساب: يُعرض عند وجوده حصراً («الحقول الفارغة لا تُعرض» — AC)؛ غياب
        // accountName يعني حساب الجهاز المحلي (نمط iOS ContactDetailsView حرفياً).
        contact.account?.let { account ->
            item {
                SectionTitle(strings.accountSection)
                ValueRow(
                    value = account.accountName ?: strings.accountDevice,
                    secondary = account.accountType,
                )
                Spacer(modifier = Modifier.height(SilaDimens.Space24))
            }
        }
    }

    // حوار التحذير قبل بدء الرنين — الشرح الكامل للأثر قبل أي التزام (spec §102-103).
    if (showEmergencyWarning) {
        AlertDialog(
            onDismissRequest = { showEmergencyWarning = false },
            title = { Text(text = strings.emergencyWarningTitle) },
            text = { Text(text = strings.emergencyWarningBody) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showEmergencyWarning = false
                        onIntent(ContactDetailsIntent.EmergencyRing(contact.id))
                    },
                ) {
                    Text(
                        text = strings.emergencyWarningConfirm,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmergencyWarning = false }) {
                    Text(text = strings.cancel)
                }
            },
        )
    }
}

/** زر رنين الطوارئ (T8-11) — لون فئة الطوارئ وعرضٌ كامل؛ اللمس يفتح التحذير أولاً. */
@Composable
private fun EmergencyRingButton(strings: ContactDetailsStrings, onAsk: () -> Unit) {
    val emergencyColor = SilaColors.forCategory(Category.EMERGENCY)
    Surface(
        shape = RoundedCornerShape(SilaDimens.CardCornerRadius),
        color = emergencyColor.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, emergencyColor),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SilaDimens.Space16),
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onAsk)
                .padding(horizontal = SilaDimens.Space16, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = strings.emergencyRingButton,
                style = MaterialTheme.typography.labelLarge,
                color = emergencyColor,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

// ===== الرأس والأفعال السريعة =====

@Composable
private fun TopBar(
    state: ContactDetailsUiState,
    strings: ContactDetailsStrings,
    onIntent: (ContactDetailsIntent) -> Unit,
    onShowActions: () -> Unit,
) {
    val contact = state.contact
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SilaDimens.Space8, vertical = SilaDimens.Space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EmojiAction("←", strings.backDescription) { onIntent(ContactDetailsIntent.Back) }
        Spacer(modifier = Modifier.weight(1f))
        // النجمة — المفضلة/إلغاؤها فوراً (تصنيف عائم).
        EmojiAction(
            icon = if (contact?.isFavorite == true) "❤️" else "☆",
            description = if (contact?.isFavorite == true) strings.unfavoriteDescription else strings.favoriteDescription,
        ) {
            contact?.let { onIntent(ContactDetailsIntent.ToggleFavorite(it.id, !it.isFavorite)) }
        }
        EmojiAction("✎", strings.editDescription) {
            contact?.let { onIntent(ContactDetailsIntent.Edit(it.id)) }
        }
        EmojiAction("⋯", strings.moreOptionsDescription) { onShowActions() }
    }
}

@Composable
private fun EmojiAction(icon: String, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(SilaDimens.MinTouchTarget)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = icon, fontSize = 20.sp, modifier = Modifier.semantics { contentDescription = description })
    }
}

/** كتلة الرأس — صورة كبيرة بالأحرف الأولى + الاسم + سطر المنظمة + شريحة التصنيف والقرابة. */
@Composable
private fun HeaderBlock(state: ContactDetailsUiState, strings: ContactDetailsStrings, contact: Contact) {
    val categoryColor = SilaColors.forCategory(contact.category)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = SilaDimens.Space12),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(categoryColor.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = contact.name.displayName.take(1),
                fontSize = 36.sp,
                color = categoryColor,
            )
        }
        Spacer(modifier = Modifier.height(SilaDimens.Space12))
        Text(
            text = contact.name.displayName,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = SilaDimens.Space24),
        )
        val orgLine = listOfNotNull(
            contact.organization?.jobTitles?.firstOrNull()?.takeIf { it.isNotBlank() },
            contact.organization?.company?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        if (orgLine.isNotBlank()) {
            Text(
                text = orgLine,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        // شريحة التصنيف + القرابة (❤ عائلة · خال).
        val relation = contact.arham?.relation
        if (contact.category != Category.UNCATEGORIZED || relation != null) {
            Surface(
                shape = CircleShape,
                color = categoryColor.copy(alpha = 0.14f),
                modifier = Modifier.padding(top = SilaDimens.Space8),
            ) {
                Text(
                    text = listOf(
                        strings.categoryTitle(contact.category),
                        relation?.let { relationName -> strings.relationName(relationName) },
                    )
                        .filterNotNull()
                        .joinToString(" · "),
                    style = MaterialTheme.typography.labelLarge,
                    color = categoryColor,
                    modifier = Modifier.padding(horizontal = SilaDimens.Space12, vertical = 6.dp),
                )
            }
        }
    }
}

/** الأزرار السريعة — الرقم الافتراضي حصراً (الأساسي ثم الوحيد — العقد في الـ VM). */
@Composable
private fun QuickActions(
    state: ContactDetailsUiState,
    strings: ContactDetailsStrings,
    contact: Contact,
    onIntent: (ContactDetailsIntent) -> Unit,
) {
    val phone = primaryPhone(contact)
    val email = contact.emails.firstOrNull()?.address
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SilaDimens.Space16),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        QuickAction("📞", strings.quickActionCall, enabled = phone != null) {
            phone?.let { onIntent(ContactDetailsIntent.CallNumber(it)) }
        }
        QuickAction("💬", strings.quickActionMessage, enabled = phone != null) {
            phone?.let { onIntent(ContactDetailsIntent.Message(it)) }
        }
        // فيديو — عبر التطبيقات المتصلة المثبتة فعلاً حصراً (AC).
        QuickAction("🎥", strings.quickActionVideo, enabled = state.connectedApps.isNotEmpty()) {
            phone?.let { p ->
                state.connectedApps.firstOrNull()?.let { app ->
                    onIntent(ContactDetailsIntent.OpenConnectedApp(app, p))
                }
            }
        }
        QuickAction("✉️", strings.quickActionEmail, enabled = email != null) {
            email?.let { onIntent(ContactDetailsIntent.Email(it)) }
        }
    }
}

@Composable
private fun QuickAction(icon: String, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(SilaDimens.Space12))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = SilaDimens.Space12, vertical = SilaDimens.Space8)
            .semantics(mergeDescendants = true) { contentDescription = label },
    ) {
        Text(text = icon, fontSize = 22.sp, color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
        )
    }
}

// ===== صفوف القيم =====

/** صف هاتف: نقرة = اتصال، مطولة = رسالة (AC) — مع زر نسخ مطول على النص. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhoneRow(
    phone: app.sila.core.model.Phone,
    strings: ContactDetailsStrings,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = SilaDimens.Space16, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SilaDimens.Space12),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = phone.raw,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val typeLabel = buildString {
                append(strings.phoneTypeName(phone.type))
                if (phone.isPrimary) append(" (${strings.primaryPhone})")
            }
            Text(
                text = typeLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        EmojiAction("⧉", strings.copyNumber) {
            clipboard.setText(AnnotatedString(phone.raw))
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = SilaDimens.Space16))
}

/** صف تفاعل سجل — ↗ صادر · ↙ وارد · ✕ فائتة. */
@Composable
private fun InteractionRow(interaction: RecentInteraction, strings: ContactDetailsStrings) {
    val (icon, color) = when (interaction.direction) {
        CallDirection.OUT -> "↗" to MaterialTheme.colorScheme.primary
        CallDirection.IN -> "↙" to MaterialTheme.colorScheme.primary
        CallDirection.MISSED -> "✕" to MaterialTheme.colorScheme.error
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SilaDimens.Space16, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SilaDimens.Space12),
    ) {
        Text(text = icon, fontSize = 18.sp, color = color)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = strings.formatInteractionTime(interaction.timestampMillis),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = strings.directionLabel(interaction.direction),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (interaction.direction != CallDirection.MISSED && interaction.durationSec > 0) {
            Text(
                text = strings.formatDurationSeconds(interaction.durationSec),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (interaction.direction == CallDirection.MISSED) {
            Text(
                text = strings.interactionMissed,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = SilaDimens.Space16))
}

@Composable
private fun ValueRow(value: String, secondary: String? = null, onLongClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SilaDimens.Space16, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SilaDimens.Space12),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (secondary != null) {
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = SilaDimens.Space16))
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = SilaDimens.Space16, top = SilaDimens.Space8, bottom = 4.dp),
    )
}

@Composable
private fun SectionDivider() {
    Spacer(modifier = Modifier.height(SilaDimens.Space8))
    HorizontalDivider(thickness = 8.dp, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    Spacer(modifier = Modifier.height(SilaDimens.Space8))
}

@Composable
private fun CenteredText(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(SilaDimens.Space24),
        )
    }
}

/** الرقم الافتراضي — الأساسي ثم الوحيد (نفس عقد الـ VM). */
private fun primaryPhone(contact: Contact): String? {
    val phones = contact.phones
    return (phones.firstOrNull { it.isPrimary } ?: phones.singleOrNull())?.e164
}

/** تنسيق تاريخ مهم — ISO خالص بلا اعتماد منصوي (التنسيق المحلي تفصيل لاحق). */
private fun formatDate(date: ImportantDate): String {
    val yearPart = date.year?.toString() ?: ""
    val monthPart = date.month.toString().padStart(2, '0')
    val dayPart = date.day.toString().padStart(2, '0')
    return listOf(yearPart, monthPart, dayPart).filter { it.isNotBlank() }.joinToString("-")
}
