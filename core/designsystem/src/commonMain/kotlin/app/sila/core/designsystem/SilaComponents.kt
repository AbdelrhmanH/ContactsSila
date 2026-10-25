package app.sila.core.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.sila.core.model.ArhamItem
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.Service
import app.sila.core.model.ServiceVisibility

/**
 * عنصر إجراء داخل SilaActionSheet (DESIGN_SYSTEM.md §4.1).
 */
@Immutable
data class ActionSheetItem(
    val iconEmoji: String,
    val title: String,
    val subtitle: String? = null,
    val destructive: Boolean = false,
    val selected: Boolean = false,
    val onClick: () -> Unit
)

/**
 * 4.1 SilaActionSheet — المعيار الموحد للنوافذ المنبثقة مع زر إلغاء Pill منفصل.
 */
@Composable
fun SilaActionSheet(
    items: List<ActionSheetItem>,
    cancelLabel: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = SilaDimens.Space8),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = RoundedCornerShape(SilaDimens.SheetCornerRadius),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (title != null) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(
                                horizontal = SilaDimens.Space20,
                                vertical = SilaDimens.Space16
                            )
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    }

                    items.forEachIndexed { index, item ->
                        val titleColor = if (item.destructive) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 64.dp)
                                .clickable {
                                    onDismiss()
                                    item.onClick()
                                }
                                .padding(
                                    horizontal = SilaDimens.Space20,
                                    vertical = SilaDimens.Space12
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(SilaDimens.Space16)
                        ) {
                            Text(text = item.iconEmoji, fontSize = 22.sp)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = titleColor
                                )
                                if (item.subtitle != null) {
                                    Text(
                                        text = item.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (item.selected) {
                                Text(
                                    text = "✔",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        if (index < items.lastIndex) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(horizontal = SilaDimens.Space16)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(SilaDimens.Space12))

            // زر الإلغاء المنفصل (Pill) بعرض ~60%
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth(0.6f)
            ) {
                TextButton(
                    onClick = onDismiss,
                    contentPadding = PaddingValues(vertical = SilaDimens.Space12),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Text(
                        text = cancelLabel,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}

/**
 * 4.3 Chips التصنيف مع العداد الدائري (DESIGN_SYSTEM.md §4.3).
 */
@Composable
fun SilaCategoryChip(
    label: String,
    count: Int,
    selected: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor by animateColorAsState(
        if (selected) accentColor.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface
    )
    val textColor by animateColorAsState(
        if (selected) accentColor else MaterialTheme.colorScheme.onSurfaceVariant
    )
    val borderColor = if (selected) accentColor.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outline

    Surface(
        shape = CircleShape,
        color = bgColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
            .heightIn(min = 38.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = SilaDimens.Space12, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = textColor
            )
            if (count > 0) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(if (selected) accentColor else MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = count.toString(),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 4.5 صف جهة الاتصال (DESIGN_SYSTEM.md §4.5).
 */
@Composable
fun SilaContactRow(
    contact: Contact,
    categoryLabel: String,
    callLabel: String,
    onCallClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val categoryColor = SilaColors.forCategory(contact.category)
    val primaryPhone = contact.phones.firstOrNull()?.e164 ?: ""

    Surface(
        shape = RoundedCornerShape(SilaDimens.SettingsGroupCornerRadius),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "${contact.name.displayName}, $categoryLabel"
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SilaDimens.Space16, vertical = SilaDimens.Space12),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SilaDimens.Space12)
        ) {
            // صورة رمزية 44dp بالحرف الأول وحلقة التصنيف
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(categoryColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = contact.name.displayName.take(1),
                    style = MaterialTheme.typography.titleMedium,
                    color = categoryColor
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = contact.name.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SilaDimens.Space8)
                ) {
                    Text(
                        text = categoryLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = categoryColor,
                        fontWeight = FontWeight.Medium
                    )
                    if (primaryPhone.isNotEmpty()) {
                        Text(
                            text = "· $primaryPhone",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // زر اتصال سريع (بأبعاد لمس >= 48dp للوصولية WCAG AA)
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                modifier = Modifier
                    .size(SilaDimens.MinTouchTarget)
                    .clip(CircleShape)
                    .clickable(enabled = primaryPhone.isNotEmpty()) {
                        onCallClick(primaryPhone)
                    }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "📞",
                        fontSize = 18.sp,
                        modifier = Modifier.semantics { contentDescription = callLabel }
                    )
                }
            }
        }
    }
}

/**
 * 4.6 بطاقة الخدمة في الدليل السحابي الحي (DESIGN_SYSTEM.md §4.6).
 */
@Composable
fun SilaServiceCard(
    service: Service,
    verifiedLabel: String,
    communityLabel: String,
    callLabel: String,
    onCallClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val isVerified = service.visibility == ServiceVisibility.VERIFIED

    Surface(
        shape = RoundedCornerShape(SilaDimens.CardCornerRadius),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SilaDimens.Space16),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SilaDimens.Space12)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "🔧", fontSize = 22.sp)
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SilaDimens.Space8)
                ) {
                    Text(
                        text = service.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Surface(
                        shape = CircleShape,
                        color = if (isVerified) SilaColors.BadgeVerifiedBg else SilaColors.BadgeCommunityBg
                    ) {
                        Text(
                            text = if (isVerified) verifiedLabel else communityLabel,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isVerified) SilaColors.BadgeVerifiedFg else SilaColors.BadgeCommunityFg,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }

                Text(
                    text = buildString {
                        append(service.professionNameAr.ifBlank { service.professionNameEn })
                        service.distanceKm?.let { append(" · $it كم") }
                        if (service.isHot) append(" · 🔥 رائج")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "مؤشر الثقة: ${service.trustScore.toInt()}% · ${service.uniqueCallers} متصل",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }

            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(SilaDimens.MinTouchTarget)
                    .clip(CircleShape)
                    .clickable { onCallClick(service.phoneE164) }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "📞",
                        fontSize = 18.sp,
                        modifier = Modifier.semantics { contentDescription = callLabel }
                    )
                }
            }
        }
    }
}

/**
 * 4.8 عنصر القائمة المرقمة لأرحام (DESIGN_SYSTEM.md §4.8 — 01، 02، 03 بخط Light 28sp).
 */
@Composable
fun SilaArhamRankedRow(
    item: ArhamItem,
    statusText: String,
    callLabel: String,
    onCallClick: (Long, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val statusColor by animateColorAsState(SilaColors.forArhamStatus(item.status))
    val rankFormatted = item.rank.toString().padStart(2, '0')

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SilaDimens.Space16, vertical = SilaDimens.Space12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SilaDimens.Space16)
    ) {
        Text(
            text = rankFormatted,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = statusColor,
                fontWeight = FontWeight.Medium
            )
        }

        Surface(
            shape = CircleShape,
            color = statusColor.copy(alpha = 0.16f),
            modifier = Modifier
                .size(SilaDimens.MinTouchTarget)
                .clip(CircleShape)
                .clickable { onCallClick(item.contactId, item.primaryPhone) }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "📞",
                    fontSize = 18.sp,
                    modifier = Modifier.semantics { contentDescription = callLabel }
                )
            }
        }
    }
}

/**
 * 4.9 مجموعة إعدادات نظيفة بعناوين أقسام (DESIGN_SYSTEM.md §4.9).
 */
@Composable
fun SilaSettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                start = SilaDimens.Space16,
                bottom = SilaDimens.Space8
            )
        )
        Surface(
            shape = RoundedCornerShape(SilaDimens.SettingsGroupCornerRadius),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(content = content)
        }
        Spacer(modifier = Modifier.height(SilaDimens.Space24))
    }
}

/**
 * 4.9 عنصر إعدادات فردي يعرض القيمة الحالية inline أو مفتاح تبديل.
 */
@Composable
fun SilaSettingsItem(
    iconEmoji: String,
    title: String,
    value: String? = null,
    toggle: Boolean? = null,
    onClick: (() -> Unit)? = null,
    onToggle: ((Boolean) -> Unit)? = null,
    showDivider: Boolean = false
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .let { mod -> if (onClick != null) mod.clickable(onClick = onClick) else mod }
                .padding(horizontal = SilaDimens.Space16, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SilaDimens.Space12)
        ) {
            Text(text = iconEmoji, fontSize = 18.sp)
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (value != null) {
                Text(
                    text = "$value ›",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (toggle != null && onToggle != null) {
                Switch(
                    checked = toggle,
                    onCheckedChange = onToggle
                )
            }
        }
        if (showDivider) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = SilaDimens.Space16)
            )
        }
    }
}
