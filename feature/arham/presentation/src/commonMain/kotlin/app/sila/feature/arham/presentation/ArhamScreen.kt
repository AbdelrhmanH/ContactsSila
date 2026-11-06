package app.sila.feature.arham.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.sila.core.designsystem.ActionSheetItem
import app.sila.core.designsystem.SilaActionSheet
import app.sila.core.designsystem.SilaArhamRankedRow
import app.sila.core.designsystem.SilaColors
import app.sila.core.designsystem.SilaDimens
import app.sila.core.model.ArhamPeriod
import app.sila.core.model.ArhamStatus
import app.sila.core.model.EmergencyRingingSession
import app.sila.feature.arham.domain.ConfirmationAnswer
import kotlinx.coroutines.delay

/** حدّ المحاولات الموثق (§N.4) — ثابت عرضي فقط (المصدر في السياسة). */
private const val EMERGENCY_MAX_ATTEMPTS = 10

/**
 * شاشة أرحام Stateful (CODING_GUIDELINES.md §2-3: XScreen stateful + XContent stateless)
 * — T8-11: كل النصوص من [ArhamStrings] (بلا نص ثابت — D.3-3)، والألوان دلالية من
 * النظام التصميمي (داكن/فاتح تلقائياً)، والاتجاه RTL/LTR من البيئة (بلا قيد).
 * الأثر المنصوي (الاتصال/طلب الدور AC-4) يُصعَّد عبر [onPlatformEffect].
 */
@Composable
fun ArhamScreen(
    viewModel: ArhamViewModel,
    strings: ArhamStrings,
    onPlatformEffect: (ArhamPlatformEffect) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()

    var celebrateVisible by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ArhamEffect.Dial -> onPlatformEffect(ArhamPlatformEffect.OpenDialer(effect.phone))
                ArhamEffect.ShowCelebrationConfetti -> {
                    celebrateVisible = true
                    delay(2_000)
                    celebrateVisible = false
                }
                ArhamEffect.RequestScreeningRole -> onPlatformEffect(ArhamPlatformEffect.RequestScreeningRole)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        ArhamContent(state = state, strings = strings, onIntent = viewModel::onIntent)
        if (celebrateVisible) {
            CelebrationOverlay(title = strings.celebrationTitle, modifier = Modifier.align(Alignment.Center))
        }
    }
}

/** غلاف الاحتفال (AC-12) — نظرة قصيرة ثم تختفي (السياسة تملقّ التكرار والتفضيلات). */
@Composable
private fun CelebrationOverlay(title: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(SilaDimens.CardCornerRadius),
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 6.dp,
        modifier = modifier.padding(SilaDimens.Space16),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(SilaDimens.Space20),
        )
    }
}

/** واجهة أرحام Stateless (:feature:arham:presentation) — كل النصوص عبر [strings]. */
@Composable
fun ArhamContent(
    state: ArhamUiState,
    strings: ArhamStrings,
    onIntent: (ArhamIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.showTree) {
        ArhamTreeScreen(tree = state.tree, strings = strings, onIntent = onIntent, modifier = modifier)
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(SilaDimens.Space16),
        verticalArrangement = Arrangement.spacedBy(SilaDimens.Space16),
    ) {
        item(key = "arham_header") {
            PeriodHeader(
                isWeek = state.period == ArhamPeriod.WEEK,
                strings = strings,
                onChangePeriod = { onIntent(ArhamIntent.ChangePeriod(it)) },
                onOpenTree = { onIntent(ArhamIntent.ToggleTree) },
            )
        }

        item(key = "arham_podium") {
            DashboardCard(state = state, strings = strings)
        }

        if (state.emergencySession != null) {
            item(key = "arham_emergency_banner") {
                EmergencyStopBanner(
                    session = state.emergencySession,
                    strings = strings,
                    onStop = { onIntent(ArhamIntent.StopEmergency) },
                )
            }
        }

        if (state.screeningConsent == ScreeningConsentUi.NEEDED) {
            item(key = "arham_screening_consent") {
                ScreeningConsentCard(
                    strings = strings,
                    onRequest = { onIntent(ArhamIntent.RequestScreeningConsent) },
                    onDismiss = { onIntent(ArhamIntent.DismissScreeningConsent) },
                )
            }
        }

        if (state.pendingConfirmations.isNotEmpty()) {
            item(key = "arham_confirmations_entry") {
                ConfirmationsEntry(
                    count = state.pendingConfirmations.size,
                    strings = strings,
                    onOpen = { onIntent(ArhamIntent.ToggleConfirmations) },
                )
            }
        }

        if (state.occasions.isNotEmpty()) {
            item(key = "arham_occasions") {
                OccasionsCard(occasions = state.occasions, strings = strings)
            }
        }

        if (state.items.isEmpty()) {
            item(key = "arham_empty") {
                EmptyState(strings = strings)
            }
        } else {
            item(key = "arham_ranked_list") {
                RankedList(items = state.items, strings = strings, onIntent = onIntent)
            }
        }
    }

    if (state.showConfirmations && state.pendingConfirmations.isNotEmpty()) {
        ConfirmationSheet(
            event = state.pendingConfirmations.first(),
            remaining = state.pendingConfirmations.size,
            strings = strings,
            onAnswer = { eventId, answer -> onIntent(ArhamIntent.ResolveConfirmation(eventId, answer)) },
            onDismiss = { onIntent(ArhamIntent.ToggleConfirmations) },
        )
    }

    state.kinshipEditor?.let { editor ->
        val parentCandidates = state.tree
            ?.generations
            ?.flatMap { it.nodes }
            ?.filter { it.contactId != editor.contactId }
            ?.map { it.contactId to it.displayName }
            .orEmpty()
        KinshipEditorSheet(
            editor = editor,
            parentCandidates = parentCandidates,
            strings = strings,
            onIntent = onIntent,
        )
    }

    state.generationAddPicker?.let { picker ->
        SilaActionSheet(
            title = strings.treePickCandidate,
            items = picker.candidates.map { (contactId, displayName) ->
                ActionSheetItem(
                    iconEmoji = "👤",
                    title = displayName,
                    onClick = {
                        onIntent(ArhamIntent.DismissGenerationAddPicker)
                        onIntent(ArhamIntent.OpenKinshipEditor(contactId = contactId, restrictToLayer = picker.layer))
                    },
                )
            },
            cancelLabel = strings.actionCancel,
            onDismiss = { onIntent(ArhamIntent.DismissGenerationAddPicker) },
        )
    }

    state.treeNodeSheet?.let { node ->
        SilaActionSheet(
            title = node.displayName,
            items = listOfNotNull(
                node.phone?.let { phone ->
                    ActionSheetItem(
                        iconEmoji = "📞",
                        title = strings.treeNodeCall,
                        onClick = { onIntent(ArhamIntent.RecordCall(contactId = node.contactId, phone = phone)) },
                    )
                },
                ActionSheetItem(
                    iconEmoji = "✏️",
                    title = strings.treeNodeEditKinship,
                    onClick = {
                        onIntent(ArhamIntent.DismissTreeNodeSheet)
                        onIntent(ArhamIntent.OpenKinshipEditor(contactId = node.contactId))
                    },
                ),
            ),
            cancelLabel = strings.actionCancel,
            onDismiss = { onIntent(ArhamIntent.DismissTreeNodeSheet) },
        )
    }
}

@Composable
private fun PeriodHeader(
    isWeek: Boolean,
    strings: ArhamStrings,
    onChangePeriod: (ArhamPeriod) -> Unit,
    onOpenTree: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = strings.screenTitle,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(SilaDimens.Space8)) {
            PeriodPill(label = strings.periodWeek, selected = isWeek, onClick = { onChangePeriod(ArhamPeriod.WEEK) })
            PeriodPill(label = strings.periodMonth, selected = !isWeek, onClick = { onChangePeriod(ArhamPeriod.MONTH) })
            PeriodPill(label = strings.openTree, selected = false, onClick = onOpenTree)
        }
    }
}

@Composable
private fun PeriodPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(SilaDimens.MinTouchTarget / 2),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = SilaDimens.Space12, vertical = 8.dp),
        )
    }
}

/** بطاقة اللوحة (AC-2): إحصاء حقيقي + عدّ المتوقفين + منصة أعلى-3 بحلقة الحالة. */
@Composable
private fun DashboardCard(state: ArhamUiState, strings: ArhamStrings) {
    Surface(
        shape = RoundedCornerShape(SilaDimens.PodiumCornerRadius),
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(SilaDimens.Space20),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SilaDimens.Space12),
        ) {
            Text(
                text = strings.podiumTitle(isWeek = state.period == ArhamPeriod.WEEK),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondary,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ArhamStatBadge(
                    label = strings.statCalls,
                    value = state.stats.totalFamilyCalls.toString(),
                    color = MaterialTheme.colorScheme.onSecondary,
                )
                ArhamStatBadge(
                    label = strings.statPaused,
                    value = state.stats.pausedCount.toString(),
                    color = MaterialTheme.colorScheme.onSecondary,
                )
                ArhamStatBadge(
                    label = strings.statConnectedLabel,
                    value = strings.statConnected(state.stats.contactedRelatives, state.items.size),
                    color = MaterialTheme.colorScheme.onSecondary,
                )
                ArhamStatBadge(
                    label = strings.statGoalLabel,
                    value = strings.statGoal((state.stats.goalCompletion * 100).toInt()),
                    color = MaterialTheme.colorScheme.onSecondary,
                )
            }

            LinearProgressIndicator(
                progress = { state.stats.goalCompletion },
                color = MaterialTheme.colorScheme.onSecondary,
                trackColor = MaterialTheme.colorScheme.onSecondary.copy(alpha = 0.3f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape),
            )

            state.stats.topThree.forEach { item ->
                PodiumRow(
                    label = item.displayName,
                    status = item.status,
                    statusText = strings.statusText(item.status, item.daysSinceLastContact),
                )
            }
        }
    }
}

/** صف المنصة — الحلقة دلالية بلون الحالة (التضمين نشاطٌ واللون حالة — T807-R2 كما في المواصفة). */
@Composable
private fun PodiumRow(label: String, status: ArhamStatus, statusText: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SilaDimens.Space12),
    ) {
        Box(
            modifier = Modifier
                .height(12.dp)
                .fillMaxWidth(0.03f)
                .clip(CircleShape)
                .background(SilaColors.forArhamStatus(status)),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondary.copy(alpha = 0.9f),
            maxLines = 1,
        )
    }
}

@Composable
private fun ArhamStatBadge(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            color = color,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = color.copy(alpha = 0.9f),
        )
    }
}

/** لافتة إيقاف الطوارئ (AC-14) — الحالة قراءة Room حصراً (SSOT) والإيقاف عبر use case. */
@Composable
private fun EmergencyStopBanner(
    session: EmergencyRingingSession,
    strings: ArhamStrings,
    onStop: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(SilaDimens.CardCornerRadius),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(SilaDimens.Space16),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SilaDimens.Space12),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = strings.emergencyBannerTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = strings.emergencyAttempts(session.attempts, EMERGENCY_MAX_ATTEMPTS),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Surface(
                shape = RoundedCornerShape(SilaDimens.MinTouchTarget / 2),
                color = MaterialTheme.colorScheme.error,
            ) {
                Text(
                    text = strings.emergencyStop,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onError,
                    modifier = Modifier
                        .clickable(onClick = onStop)
                        .padding(horizontal = SilaDimens.Space16, vertical = 10.dp),
                )
            }
        }
    }
}

/** بطاقة موافقة فحص المكالمات (AC-4) — شرح قبل طلب الدور المنصوي. */
@Composable
private fun ScreeningConsentCard(
    strings: ArhamStrings,
    onRequest: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(SilaDimens.CardCornerRadius),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(SilaDimens.Space16),
            verticalArrangement = Arrangement.spacedBy(SilaDimens.Space8),
        ) {
            Text(
                text = strings.screeningConsentTitle,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = strings.screeningConsentBody,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(SilaDimens.Space8)) {
                Surface(
                    shape = RoundedCornerShape(SilaDimens.MinTouchTarget / 2),
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Text(
                        text = strings.screeningConsentAction,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .clickable(onClick = onRequest)
                            .padding(horizontal = SilaDimens.Space16, vertical = 10.dp),
                    )
                }
                Surface(
                    shape = RoundedCornerShape(SilaDimens.MinTouchTarget / 2),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Text(
                        text = strings.screeningConsentDismiss,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .clickable(onClick = onDismiss)
                            .padding(horizontal = SilaDimens.Space16, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

/** مدخل ورقة التأكيد (AC-7) — شارة العدد فقط؛ الحسم داخل الورقة. */
@Composable
private fun ConfirmationsEntry(count: Int, strings: ArhamStrings, onOpen: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(SilaDimens.CardCornerRadius),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
    ) {
        Text(
            text = strings.confirmationsBadge(count),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(SilaDimens.Space16),
        )
    }
}

/** بطاقة المناسبات (O-5/O-8) — حقول خام، والأسطر تُبنى من النصوص المترجمة هنا. */
@Composable
private fun OccasionsCard(occasions: List<OccasionUi>, strings: ArhamStrings) {
    Surface(
        shape = RoundedCornerShape(SilaDimens.CardCornerRadius),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(SilaDimens.Space16),
            verticalArrangement = Arrangement.spacedBy(SilaDimens.Space8),
        ) {
            Text(
                text = strings.occasionsTitle,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
            )
            occasions.forEach { occasion ->
                // سياق القرابة مع الاسم (O-8) — بلا قرابة محلولة يُعرض الاسم وحده.
                val personLine = occasion.relation
                    ?.let { relation -> strings.occasionPerson(strings.relationLabel(relation), occasion.displayName) }
                    ?: occasion.displayName
                val dayLine = if (occasion.isToday) {
                    strings.occasionToday(personLine)
                } else {
                    strings.occasionInDays(occasion.daysUntil, personLine)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = personLine,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    Text(
                        text = dayLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(strings: ArhamStrings) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = SilaDimens.Space32),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SilaDimens.Space8),
    ) {
        Text(
            text = strings.emptyTitle,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = strings.emptyBody,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** القائمة المرتبة (AC-2: الترقيم/الألوان/العناوين الفرعية من النظام التصميمي الموحد). */
@Composable
private fun RankedList(
    items: List<app.sila.core.model.ArhamItem>,
    strings: ArhamStrings,
    onIntent: (ArhamIntent) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(SilaDimens.PodiumCornerRadius),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(vertical = SilaDimens.Space8)) {
            items.forEach { item ->
                SilaArhamRankedRow(
                    item = item,
                    statusText = strings.statusText(item.status, item.daysSinceLastContact),
                    callLabel = strings.rowCallLabel,
                    onCallClick = { contactId, phone ->
                        onIntent(ArhamIntent.RecordCall(contactId = contactId, phone = phone))
                    },
                )
            }
        }
    }
}

/** ورقة التأكيد (AC-7) — الحدث الأول من قائمة Room؛ أزرار الإجابات الأربعة الموثقة. */
@Composable
private fun ConfirmationSheet(
    event: app.sila.core.model.CallEvent,
    remaining: Int,
    strings: ArhamStrings,
    onAnswer: (String, ConfirmationAnswer) -> Unit,
    onDismiss: () -> Unit,
) {
    val directionLabel = when (event.direction) {
        app.sila.core.model.CallDirection.OUT -> strings.directionOutgoing
        app.sila.core.model.CallDirection.IN -> strings.directionIncoming
        app.sila.core.model.CallDirection.MISSED -> strings.directionMissed
        null -> strings.directionUnknown
    }
    SilaActionSheet(
        title = strings.confirmationEventLine(
            directionLabel = directionLabel,
            durationMin = event.durationSec?.let { it / 60 },
        ),
        items = listOf(
            ActionSheetItem(
                iconEmoji = "✅",
                title = strings.confirmContacted,
                onClick = { onAnswer(event.id, ConfirmationAnswer.CONTACTED) },
            ),
            ActionSheetItem(
                iconEmoji = "📵",
                title = strings.confirmNoAnswer,
                onClick = { onAnswer(event.id, ConfirmationAnswer.NO_ANSWER) },
            ),
            ActionSheetItem(
                iconEmoji = "🚫",
                title = strings.confirmNotRelated,
                onClick = { onAnswer(event.id, ConfirmationAnswer.NOT_RELATED) },
            ),
            ActionSheetItem(
                iconEmoji = "⏰",
                title = strings.confirmLater,
                onClick = { onAnswer(event.id, ConfirmationAnswer.LATER) },
            ),
        ),
        cancelLabel = strings.actionCancel,
        onDismiss = onDismiss,
    )
}
