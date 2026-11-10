package app.sila.feature.settings.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import app.sila.core.designsystem.SilaDimens
import app.sila.core.designsystem.SilaSettingsGroup
import app.sila.core.designsystem.SilaSettingsItem
import app.sila.core.model.AppDigits
import app.sila.core.model.AppLanguage
import app.sila.core.model.AppThemeMode
import app.sila.core.model.Category
import app.sila.core.model.ContactSwipeAction
import app.sila.core.model.DefaultTab
import app.sila.core.model.NameOrder
import app.sila.core.model.UserPreferences
import app.sila.feature.settings.domain.RingtoneItem

/**
 * أقسام شاشة الإعدادات المستخرجة (S2-17 — تذكرة #67): كل قسم وحدة مستقلة تُسمّى
 * ذاتَ معنى وتقرأ نصوصها المترجمة من [SettingsStrings] وتبعت نواياها عبر
 * `onIntent` — لا منطق أعمال في الواجهة (النية وحدها مخرج القسم).
 */

/** مجموعة «المظهر واللغة»: السمة واللغة والأرقام وترتيب الأسماء. */
@Composable
internal fun SettingsAppearanceSection(
    prefs: UserPreferences,
    strings: SettingsStrings,
    onIntent: (SettingsIntent) -> Unit
) {
    SilaSettingsGroup(title = strings.sectionAppearance) {
        SilaSettingsItem(
            iconEmoji = "🎨",
            title = strings.theme,
            value = when (prefs.themeMode) {
                AppThemeMode.LIGHT -> strings.themeLight
                AppThemeMode.DARK -> strings.themeDark
                AppThemeMode.SYSTEM -> strings.themeSystem
            },
            onClick = {
                // S2-12 (تذكرة #69): دورة السمة قرار في الـ ViewModel حصراً —
                // الشاشة ترسل النية فحسب (شاشة إسناد صرف).
                onIntent(SettingsIntent.CycleThemeMode)
            },
            showDivider = true
        )
        SilaSettingsItem(
            iconEmoji = "🌍",
            title = strings.language,
            value = if (prefs.language == AppLanguage.ARABIC) "العربية" else "English",
            onClick = {
                val next = if (prefs.language == AppLanguage.ARABIC) AppLanguage.ENGLISH else AppLanguage.ARABIC
                onIntent(SettingsIntent.SetLanguage(next))
            },
            showDivider = true
        )
        SilaSettingsItem(
            iconEmoji = "🔢",
            title = strings.digits,
            value = if (prefs.digits == AppDigits.ARABIC) strings.digitsArabic else strings.digitsHindi,
            onClick = {
                val next = if (prefs.digits == AppDigits.ARABIC) AppDigits.HINDI else AppDigits.ARABIC
                onIntent(SettingsIntent.SetDigits(next))
            },
            showDivider = true
        )
        SilaSettingsItem(
            iconEmoji = "🔤",
            title = strings.nameOrder,
            value = if (prefs.nameOrder == NameOrder.FIRST_LAST) strings.nameOrderFirstLast else strings.nameOrderLastFirst,
            onClick = {
                val next = if (prefs.nameOrder == NameOrder.FIRST_LAST) NameOrder.LAST_FIRST else NameOrder.FIRST_LAST
                onIntent(SettingsIntent.SetNameOrder(next))
            }
        )
    }
}

/** مجموعة «عام وسلوك التطبيق»: التبويب الافتراضي وإجراءات السحب والشريحة المفضلة. */
@Composable
internal fun SettingsGeneralSection(
    prefs: UserPreferences,
    strings: SettingsStrings,
    isArabic: Boolean,
    onIntent: (SettingsIntent) -> Unit
) {
    SilaSettingsGroup(title = strings.sectionGeneral) {
        SilaSettingsItem(
            iconEmoji = "📑",
            title = strings.defaultTab,
            value = when (prefs.defaultTab) {
                DefaultTab.CONTACTS -> strings.tabContacts
                DefaultTab.SERVICES -> strings.tabServices
                DefaultTab.ARHAM -> strings.tabArham
            },
            onClick = {
                val next = when (prefs.defaultTab) {
                    DefaultTab.CONTACTS -> DefaultTab.SERVICES
                    DefaultTab.SERVICES -> DefaultTab.ARHAM
                    DefaultTab.ARHAM -> DefaultTab.CONTACTS
                }
                onIntent(SettingsIntent.SetDefaultTab(next))
            },
            showDivider = true
        )
        SilaSettingsItem(
            iconEmoji = "👆",
            title = "${strings.swipeStart} / ${strings.swipeEnd}",
            value = "${formatSwipeAction(prefs.swipeStartAction, strings)} / ${formatSwipeAction(prefs.swipeEndAction, strings)}",
            onClick = {
                // الترتيب الدوري محسوب في دالة نقية خارج التركيب (لتخفيض تعقيد الوحدة)
                val nextStart = nextSwipeAction(prefs.swipeStartAction)
                onIntent(SettingsIntent.SetSwipeActions(nextStart, prefs.swipeEndAction))
            },
            showDivider = true
        )
        SilaSettingsItem(
            iconEmoji = "📶",
            title = strings.simSlot,
            value = prefs.defaultSimSlot?.let { "SIM $it" } ?: (if (isArabic) "السؤال دائماً" else "Always Ask"),
            onClick = {
                // ترتيب الشريحة الدوري محسوب في دالة نقية خارج التركيب كذلك.
                onIntent(SettingsIntent.SetDefaultSimSlot(nextSimSlot(prefs.defaultSimSlot)))
            }
        )
    }
}

/** الترتيب الدوري لأفعال السحب (دالة نقية خارج التركيب): اتصال ← رسالة ← معطّل. */
private fun nextSwipeAction(current: ContactSwipeAction): ContactSwipeAction = when (current) {
    ContactSwipeAction.CALL -> ContactSwipeAction.MESSAGE
    ContactSwipeAction.MESSAGE -> ContactSwipeAction.NONE
    ContactSwipeAction.NONE -> ContactSwipeAction.CALL
}

/** الترتيب الدوري لشريحة SIM الافتراضية (دالة نقية): سؤال دائماً ← 1 ← 2 ← سؤال دائماً. */
private fun nextSimSlot(current: Int?): Int? = when (current) {
    null -> 1
    1 -> 2
    else -> null
}

/** مجموعة «صلة الرحم (أرحام)»: التفعيل والوقت والحد اليومي والساعات الهادئة. */
@Composable
internal fun SettingsArhamSection(
    prefs: UserPreferences,
    strings: SettingsStrings,
    onIntent: (SettingsIntent) -> Unit
) {
    var showQuietHoursDialog by remember { mutableStateOf(false) }
    SilaSettingsGroup(title = strings.sectionArham) {
        SilaSettingsItem(
            iconEmoji = "🔔",
            title = strings.arhamNotifications,
            toggle = prefs.arhamNotificationsEnabled,
            onToggle = { onIntent(SettingsIntent.SetArhamNotifications(it)) },
            showDivider = true
        )
        if (prefs.arhamNotificationsEnabled) {
            SilaSettingsItem(
                iconEmoji = "⏰",
                title = strings.arhamTime,
                value = "${prefs.arhamNotificationHour.toString().padStart(2, '0')}:" +
                    "${prefs.arhamNotificationMinute.toString().padStart(2, '0')}",
                onClick = {
                    // S2-12 (تذكرة #69): لفّ الساعة وتصفير الدقيقة قرار في الـ VM —
                    // مع إعادة الجدولة الفورية داخل معالج النية (تذكرة #71).
                    onIntent(SettingsIntent.CycleArhamNotificationHour)
                },
                showDivider = true
            )
            SilaSettingsItem(
                iconEmoji = "📊",
                title = strings.arhamMaxPerDay,
                value = "${prefs.arhamMaxPerDay}",
                onClick = {
                    // S2-12 (تذكرة #69): حدود الدورة 1..5 قرار في الـ VM حصراً.
                    onIntent(SettingsIntent.CycleArhamMaxPerDay)
                },
                showDivider = true
            )
            SilaSettingsItem(
                iconEmoji = "🌙",
                title = strings.quietHours,
                value = "${prefs.quietHoursStartHour.toString().padStart(2, '0')}:00 - " +
                    "${prefs.quietHoursEndHour.toString().padStart(2, '0')}:00",
                onClick = { showQuietHoursDialog = true }
            )
        }
    }
    if (showQuietHoursDialog) {
        QuietHoursDialog(
            strings = strings,
            startHour = prefs.quietHoursStartHour,
            endHour = prefs.quietHoursEndHour,
            onSave = { startHour, endHour ->
                onIntent(SettingsIntent.SetQuietHours(startHour, endHour))
                showQuietHoursDialog = false
            },
            onDismiss = { showQuietHoursDialog = false }
        )
    }
}

/** مجموعة «التنظيم والذكاء الاصطناعي المحلي»: الفلترة الذكية وفحص المكررات والوسوم. */
@Composable
internal fun SettingsOrganizationSection(
    prefs: UserPreferences,
    strings: SettingsStrings,
    isArabic: Boolean,
    onIntent: (SettingsIntent) -> Unit
) {
    SilaSettingsGroup(title = strings.sectionOrganization) {
        SilaSettingsItem(
            iconEmoji = "🤖",
            title = strings.smartFilter,
            toggle = prefs.smartFilterEnabled,
            onToggle = { onIntent(SettingsIntent.SetSmartFilter(it)) },
            showDivider = true
        )
        SilaSettingsItem(
            iconEmoji = "👥",
            title = strings.duplicateScan,
            toggle = prefs.duplicateScanEnabled,
            onToggle = { onIntent(SettingsIntent.SetDuplicateScanEnabled(it)) },
            showDivider = true
        )
        CustomCategoryKeywordsEditor(prefs, strings, isArabic, onIntent)
    }
}

/** محرر الكلمات المفتاحية المخصصة: إدخال جديد + رقائق الوسوم بإمكانية الإزالة. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomCategoryKeywordsEditor(
    prefs: UserPreferences,
    strings: SettingsStrings,
    isArabic: Boolean,
    onIntent: (SettingsIntent) -> Unit
) {
    var newKeywordInput by remember { mutableStateOf("") }
    Column(modifier = Modifier.padding(SilaDimens.Space16)) {
        Text(
            text = strings.customKeywords,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(SilaDimens.Space8))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = newKeywordInput,
                onValueChange = { newKeywordInput = it },
                placeholder = { Text(if (isArabic) "كلمة مفتاحية جديدة..." else "New keyword...") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(SilaDimens.Space8))
            Button(
                onClick = {
                    if (newKeywordInput.isNotBlank()) {
                        onIntent(SettingsIntent.SetInterestTags(prefs.interestTags + newKeywordInput.trim()))
                        newKeywordInput = ""
                    }
                },
                enabled = newKeywordInput.isNotBlank()
            ) {
                Text(strings.addKeyword)
            }
        }
        if (prefs.interestTags.isNotEmpty()) {
            Spacer(modifier = Modifier.height(SilaDimens.Space8))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(SilaDimens.Space8),
                verticalArrangement = Arrangement.spacedBy(SilaDimens.Space4)
            ) {
                prefs.interestTags.forEach { tag ->
                    FilterChip(
                        selected = true,
                        onClick = { onIntent(SettingsIntent.SetInterestTags(prefs.interestTags - tag)) },
                        label = { Text("$tag ✕") }
                    )
                }
            }
        }
    }
}

/** مجموعة «نغمات التصنيفات»: صفوف النغمات لكل تصنيف + حوار المنتقي عند الطلب. */
@Composable
internal fun SettingsRingtonesSection(
    categoryRingtones: Map<Category, String>,
    pickerCategory: Category?,
    availableRingtones: List<RingtoneItem>,
    strings: SettingsStrings,
    isArabic: Boolean,
    onIntent: (SettingsIntent) -> Unit
) {
    SilaSettingsGroup(title = strings.sectionRingtones) {
        Text(
            text = strings.ringtonePriority,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = SilaDimens.Space16, vertical = SilaDimens.Space4)
        )
        listOf(
            Category.FAMILY to formatCategory(Category.FAMILY, isArabic),
            Category.FRIENDS to formatCategory(Category.FRIENDS, isArabic),
            Category.WORK to formatCategory(Category.WORK, isArabic),
            Category.SERVICES to formatCategory(Category.SERVICES, isArabic),
            Category.EMERGENCY to formatCategory(Category.EMERGENCY, isArabic)
        ).forEachIndexed { index, (cat, title) ->
            val uri = categoryRingtones[cat]
            SilaSettingsItem(
                iconEmoji = "🎵",
                title = title,
                value = if (uri != null) strings.customRingtone else strings.systemDefaultRingtone,
                onClick = { onIntent(SettingsIntent.OpenRingtonePicker(cat)) },
                showDivider = index < 4
            )
        }
    }
    pickerCategory?.let { category ->
        RingtonePickerDialog(
            strings = strings,
            isArabic = isArabic,
            category = category,
            availableRingtones = availableRingtones,
            onPick = { uri -> onIntent(SettingsIntent.SetCategoryRingtone(category, uri)) },
            onResetDefault = { onIntent(SettingsIntent.SetCategoryRingtone(category, null)) },
            onDismiss = { onIntent(SettingsIntent.DismissRingtonePicker) }
        )
    }
}

/** مجموعة «الخصوصية والنسخ الاحتياطي المشفر»: الخصوصية المحلية والتصدير والاستعادة والتقارير. */
@Composable
internal fun SettingsBackupSection(
    prefs: UserPreferences,
    strings: SettingsStrings,
    isArabic: Boolean,
    restoreBytesBuffer: ByteArray?,
    onIntent: (SettingsIntent) -> Unit
) {
    SilaSettingsGroup(title = strings.sectionBackup) {
        SilaSettingsItem(
            iconEmoji = "🛡️",
            title = strings.localPrivacy,
            value = strings.localPrivacyValue,
            onClick = {},
            showDivider = true
        )
        SilaSettingsItem(
            iconEmoji = "📤",
            title = strings.exportBackup,
            value = "AES-256-GCM",
            onClick = { onIntent(SettingsIntent.ShowExportDialog) },
            showDivider = true
        )
        SilaSettingsItem(
            iconEmoji = "📥",
            title = strings.restoreBackup,
            value = if (restoreBytesBuffer != null) {
                if (isArabic) "بيانات جاهزة (${restoreBytesBuffer.size} بايت)"
                else "Data ready (${restoreBytesBuffer.size} bytes)"
            } else {
                if (isArabic) "مطابقة المفاتيح" else "Key matching"
            },
            onClick = { onIntent(SettingsIntent.ShowImportDialog) },
            showDivider = true
        )
        SilaSettingsItem(
            iconEmoji = "📉",
            title = strings.crashReports,
            toggle = prefs.crashReportsEnabled,
            onToggle = { onIntent(SettingsIntent.SetCrashReportsEnabled(it)) },
            showDivider = true
        )
        SilaSettingsItem(
            iconEmoji = "🗑️",
            title = strings.deleteAccount,
            value = strings.deleteAccountValue,
            onClick = {}
        )
    }
}

/** مجموعة «حول صِلَة»: الإصدار والخصوصية والتقييم (بنود عرضية بلا نوايا في المرحلة الأولى). */
@Composable
internal fun SettingsAboutSection(
    strings: SettingsStrings
) {
    SilaSettingsGroup(title = strings.aboutSection) {
        SilaSettingsItem(
            iconEmoji = "ℹ️",
            title = strings.versionTitle,
            value = strings.versionValue,
            onClick = {},
            showDivider = true
        )
        SilaSettingsItem(
            iconEmoji = "📜",
            title = strings.privacyTerms,
            onClick = {},
            showDivider = true
        )
        SilaSettingsItem(
            iconEmoji = "⭐",
            title = strings.rateApp,
            onClick = {}
        )
    }
}


