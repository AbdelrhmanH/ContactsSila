package app.sila.feature.settings.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.sila.core.designsystem.SilaDimens
import app.sila.core.designsystem.SilaSettingsGroup
import app.sila.core.designsystem.SilaSettingsItem
import app.sila.core.designsystem.SilaTheme
import app.sila.core.model.AppDigits
import app.sila.core.model.AppLanguage
import app.sila.core.model.AppThemeMode
import app.sila.core.model.Category
import app.sila.core.model.ContactSwipeAction
import app.sila.core.model.DashboardPeriod
import app.sila.core.model.DefaultTab
import app.sila.core.model.NameOrder
import app.sila.core.model.UserPreferences
import app.sila.feature.settings.domain.BackupPreview
import app.sila.feature.settings.domain.RestoreMode

/**
 * شاشة الإعدادات Stateful (CODING_GUIDELINES.md §2-3: XScreen stateful + XContent stateless).
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val isArabic = state.preferences.language == AppLanguage.ARABIC

    SettingsDialog(
        state = state,
        isArabic = isArabic,
        onIntent = viewModel::onIntent,
        onDismiss = onDismiss
    )
}

/**
 * حوار واجهة الإعدادات والتفضيلات الشامل (:feature:settings:presentation).
 */
@Composable
fun SettingsDialog(
    state: SettingsUiState,
    isArabic: Boolean,
    onIntent: (SettingsIntent) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(SilaDimens.SheetCornerRadius),
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = SilaDimens.Space16)
        ) {
            SettingsContent(
                state = state,
                isArabic = isArabic,
                onIntent = onIntent,
                onDismiss = onDismiss
            )
        }
    }
}

/**
 * المحتوى التخطيطي لشاشة الإعدادات Stateless — قابل للمعاينة والاختبار بنسبة 100%.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsContent(
    state: SettingsUiState,
    isArabic: Boolean,
    onIntent: (SettingsIntent) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val prefs = state.preferences
    val scrollState = rememberScrollState()

    var customKeywordInput by remember { mutableStateOf("") }
    var exportPasswordInput by remember { mutableStateOf("") }
    var restorePasswordInput by remember { mutableStateOf("") }
    var restoreMode by remember { mutableStateOf(RestoreMode.MERGE) }
    var restorePreferencesCheckbox by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(SilaDimens.Space16)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = SilaDimens.Space16),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isArabic) "الإعدادات والتفضيلات" else "Settings & Preferences",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            IconButton(onClick = onDismiss) {
                Text(text = "✕", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // 1) الحساب (Phase 2 Placeholder)
        SilaSettingsGroup(title = if (isArabic) "الحساب" else "Account") {
            SilaSettingsItem(
                iconEmoji = "👤",
                title = if (isArabic) "حساب صِلَة الشخصي" else "Sila Profile",
                value = if (isArabic) "قريباً (المرحلة 2)" else "Coming in Phase 2",
                onClick = {},
                showDivider = false
            )
        }

        // 2) المظهر واللغة
        SilaSettingsGroup(title = if (isArabic) "المظهر واللغة" else "Appearance & Language") {
            SilaSettingsItem(
                iconEmoji = "🌐",
                title = if (isArabic) "اللغة" else "Language",
                value = if (isArabic) "العربية" else "English",
                onClick = {
                    val next = if (isArabic) AppLanguage.ENGLISH else AppLanguage.ARABIC
                    onIntent(SettingsIntent.SetLanguage(next))
                },
                showDivider = true
            )
            SilaSettingsItem(
                iconEmoji = "🎨",
                title = if (isArabic) "الوضع الداكن" else "Dark Theme",
                toggle = prefs.themeMode == AppThemeMode.DARK,
                onToggle = { enabled ->
                    onIntent(SettingsIntent.SetThemeMode(if (enabled) AppThemeMode.DARK else AppThemeMode.LIGHT))
                },
                showDivider = true
            )
            SilaSettingsItem(
                iconEmoji = "🔢",
                title = if (isArabic) "شكل الأرقام" else "Number Digits",
                value = if (prefs.digits == AppDigits.HINDI) (if (isArabic) "١٢٣ (هندية)" else "١٢٣ (Hindi)") else (if (isArabic) "123 (عربية)" else "123 (Arabic)"),
                onClick = {
                    val next = if (prefs.digits == AppDigits.ARABIC) AppDigits.HINDI else AppDigits.ARABIC
                    onIntent(SettingsIntent.SetDigits(next))
                }
            )
        }

        // 3) عام
        SilaSettingsGroup(title = if (isArabic) "عام" else "General") {
            SilaSettingsItem(
                iconEmoji = "📌",
                title = if (isArabic) "الشاشة الافتراضية" else "Default Tab",
                value = when (prefs.defaultTab) {
                    DefaultTab.CONTACTS -> if (isArabic) "جهات الاتصال" else "Contacts"
                    DefaultTab.SERVICES -> if (isArabic) "الخدمات" else "Services"
                    DefaultTab.ARHAM -> if (isArabic) "أرحام" else "Arham"
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
                iconEmoji = "🔤",
                title = if (isArabic) "ترتيب الأسماء" else "Name Order",
                value = if (prefs.nameOrder == NameOrder.FIRST_LAST) (if (isArabic) "الاسم الأول أولاً" else "First name first") else (if (isArabic) "اسم العائلة أولاً" else "Last name first"),
                onClick = {
                    val next = if (prefs.nameOrder == NameOrder.FIRST_LAST) NameOrder.LAST_FIRST else NameOrder.FIRST_LAST
                    onIntent(SettingsIntent.SetNameOrder(next))
                },
                showDivider = true
            )
            SilaSettingsItem(
                iconEmoji = "↔️",
                title = if (isArabic) "إجراءات السحب" else "Swipe Actions",
                value = if (isArabic) "يمين: اتصال / يسار: رسالة" else "Start: Call / End: Message",
                onClick = {
                    val nextStart = when (prefs.swipeStartAction) {
                        ContactSwipeAction.CALL -> ContactSwipeAction.MESSAGE
                        ContactSwipeAction.MESSAGE -> ContactSwipeAction.NONE
                        ContactSwipeAction.NONE -> ContactSwipeAction.CALL
                    }
                    onIntent(SettingsIntent.SetSwipeActions(nextStart, prefs.swipeEndAction))
                }
            )
        }

        // 4) صلة الرحم (أرحام)
        SilaSettingsGroup(title = if (isArabic) "صلة الرحم (أرحام)" else "Kinship (Arham)") {
            SilaSettingsItem(
                iconEmoji = "🔔",
                title = if (isArabic) "إشعارات التذكير" else "Reminders",
                toggle = prefs.arhamNotificationsEnabled,
                onToggle = { onIntent(SettingsIntent.SetArhamNotifications(it)) },
                showDivider = true
            )
            SilaSettingsItem(
                iconEmoji = "⏰",
                title = if (isArabic) "الوقت المفضل للتذكير" else "Preferred Reminder Time",
                value = "${prefs.arhamNotificationHour.toString().padStart(2, '0')}:${prefs.arhamNotificationMinute.toString().padStart(2, '0')}",
                onClick = {
                    val nextHour = (prefs.arhamNotificationHour + 1) % 24
                    onIntent(SettingsIntent.SetArhamNotificationTime(nextHour, prefs.arhamNotificationMinute))
                },
                showDivider = true
            )
            SilaSettingsItem(
                iconEmoji = "🌙",
                title = if (isArabic) "أوقات الهدوء (عابرة لمنتصف الليل)" else "Quiet Hours (Cross-midnight)",
                value = "${prefs.quietHoursStartHour.toString().padStart(2, '0')}:00 - ${prefs.quietHoursEndHour.toString().padStart(2, '0')}:00",
                onClick = {
                    onIntent(SettingsIntent.SetQuietHours(22, 8))
                },
                showDivider = true
            )
            SilaSettingsItem(
                iconEmoji = "📊",
                title = if (isArabic) "فترة اللوحة الإحصائية" else "Dashboard Period",
                value = if (prefs.dashboardPeriod == DashboardPeriod.WEEK) (if (isArabic) "أسبوعياً" else "Weekly") else (if (isArabic) "شهرياً" else "Monthly"),
                onClick = {
                    val next = if (prefs.dashboardPeriod == DashboardPeriod.WEEK) DashboardPeriod.MONTH else DashboardPeriod.WEEK
                    onIntent(SettingsIntent.SetDashboardPeriod(next))
                },
                showDivider = true
            )
            SilaSettingsItem(
                iconEmoji = "🎉",
                title = if (isArabic) "احتفال الكونفيتي عند الصلة" else "Celebration Confetti",
                toggle = prefs.confettiEnabled,
                onToggle = { onIntent(SettingsIntent.SetConfettiEnabled(it)) },
                showDivider = true
            )
            SilaSettingsItem(
                iconEmoji = "📞",
                title = if (isArabic) "التتبع التلقائي للمكالمات" else "Auto Call Tracking",
                toggle = prefs.autoTrackingEnabled,
                onToggle = { onIntent(SettingsIntent.SetAutoTrackingEnabled(it)) }
            )
        }

        // 5) التنظيم والفلترة الذكية
        SilaSettingsGroup(title = if (isArabic) "التنظيم والفلترة الذكية" else "Organization & Filter") {
            SilaSettingsItem(
                iconEmoji = "✨",
                title = if (isArabic) "الفلترة الذكية للأرقام المؤقتة" else "Smart Filter for Temporary Numbers",
                toggle = prefs.smartFilterEnabled,
                onToggle = { onIntent(SettingsIntent.SetSmartFilter(it)) },
                showDivider = true
            )
            SilaSettingsItem(
                iconEmoji = "🔍",
                title = if (isArabic) "الفحص الدوري للأرقام المكررة" else "Periodic Duplicate Scan",
                toggle = prefs.duplicateScanEnabled,
                onToggle = { onIntent(SettingsIntent.SetDuplicateScanEnabled(it)) },
                showDivider = true
            )
            // النمط 2 — إدخال → رقاقات للكلمات المفتاحية المخصصة
            Column(modifier = Modifier.padding(horizontal = SilaDimens.Space16, vertical = SilaDimens.Space8)) {
                Text(
                    text = if (isArabic) "الكلمات المفتاحية المخصصة (إدخال ← رقاقات)" else "Custom Filter Keywords",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(SilaDimens.Space8))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = customKeywordInput,
                        onValueChange = { customKeywordInput = it },
                        placeholder = { Text(if (isArabic) "أضف كلمة (مثل: دليفري)..." else "Add keyword...") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.width(SilaDimens.Space8))
                    Button(
                        onClick = {
                            if (customKeywordInput.isNotBlank()) {
                                onIntent(SettingsIntent.SetInterestTags(prefs.interestTags + customKeywordInput.trim()))
                                customKeywordInput = ""
                            }
                        }
                    ) {
                        Text(if (isArabic) "إضافة" else "Add")
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
                                onClick = {
                                    onIntent(SettingsIntent.SetInterestTags(prefs.interestTags - tag))
                                },
                                label = { Text("$tag ✕") }
                            )
                        }
                    }
                }
            }
        }

        // 6) النغمات المخصصة
        SilaSettingsGroup(title = if (isArabic) "نغمات التصنيفات" else "Category Ringtones") {
            Text(
                text = if (isArabic) "الأولوية: نغمة الشخص > نغمة التصنيف > نغمة النظام" else "Priority: Contact Ringtone > Category Ringtone > System Default",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = SilaDimens.Space16, vertical = SilaDimens.Space4)
            )
            listOf(
                Category.FAMILY to (if (isArabic) "العائلة 👨‍👩‍👧‍👦" else "Family"),
                Category.FRIENDS to (if (isArabic) "الأصدقاء 🤝" else "Friends"),
                Category.WORK to (if (isArabic) "العمل 💼" else "Work"),
                Category.SERVICES to (if (isArabic) "الخدمات 🛠️" else "Services"),
                Category.EMERGENCY to (if (isArabic) "الطوارئ 🚨" else "Emergency")
            ).forEachIndexed { index, (cat, title) ->
                val uri = state.categoryRingtones[cat]
                SilaSettingsItem(
                    iconEmoji = "🎵",
                    title = title,
                    value = if (uri != null) (if (isArabic) "مخصصة ✓" else "Custom ✓") else (if (isArabic) "نغمة النظام" else "System Default"),
                    onClick = {
                        onIntent(SettingsIntent.OpenRingtonePicker(cat))
                    },
                    showDivider = index < 4
                )
            }
        }

        // 7) الخصوصية والنسخ الاحتياطي
        SilaSettingsGroup(title = if (isArabic) "الخصوصية والنسخ الاحتياطي" else "Privacy & Backup") {
            SilaSettingsItem(
                iconEmoji = "🛡️",
                title = if (isArabic) "ماذا نعرف عنك؟ (الخصوصية المحلية)" else "Local-first Privacy",
                value = if (isArabic) "0 بيانات مرفوعة" else "0 data uploaded",
                onClick = {},
                showDivider = true
            )
            SilaSettingsItem(
                iconEmoji = "📤",
                title = if (isArabic) "تصدير نسخة مشفرة (.sila.backup)" else "Export Encrypted Backup",
                value = if (isArabic) "AES-256-GCM" else "AES-256-GCM",
                onClick = { onIntent(SettingsIntent.ShowExportDialog) },
                showDivider = true
            )
            SilaSettingsItem(
                iconEmoji = "📥",
                title = if (isArabic) "استعادة نسخة احتياطية" else "Restore Backup",
                value = if (isArabic) "مطابقة المفاتيح" else "Key matching",
                onClick = { onIntent(SettingsIntent.ShowImportDialog) },
                showDivider = true
            )
            SilaSettingsItem(
                iconEmoji = "📉",
                title = if (isArabic) "إرسال تقارير الأعطال مجهولة المصدر" else "Crash Reports",
                toggle = prefs.crashReportsEnabled,
                onToggle = { onIntent(SettingsIntent.SetCrashReportsEnabled(it)) },
                showDivider = true
            )
            SilaSettingsItem(
                iconEmoji = "🗑️",
                title = if (isArabic) "حذف الحساب والبيانات السحابية" else "Delete Account",
                value = if (isArabic) "مرحلة 2 (وضع الضيف حالياً)" else "Phase 2 (Guest Mode)",
                onClick = {}
            )
        }

        // 8) حول صِلَة
        SilaSettingsGroup(title = if (isArabic) "حول صِلَة" else "About Sila") {
            SilaSettingsItem(
                iconEmoji = "ℹ️",
                title = if (isArabic) "الإصدار" else "Version",
                value = "1.0.0 (Phase 1 MVP)",
                onClick = {},
                showDivider = true
            )
            SilaSettingsItem(
                iconEmoji = "📜",
                title = if (isArabic) "سياسة الخصوصية والشروط" else "Privacy Policy & Terms",
                onClick = {},
                showDivider = true
            )
            SilaSettingsItem(
                iconEmoji = "⭐",
                title = if (isArabic) "تقييم التطبيق وتواصل معنا" else "Rate App & Feedback",
                onClick = {}
            )
        }

        Spacer(modifier = Modifier.height(SilaDimens.Space16))
        OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isArabic) "إغلاق الإعدادات" else "Close Settings")
        }
    }

    // ===== الحوارات المنبثقة =====

    // حوار إدخال كلمة مرور التصدير
    if (state.showExportPasswordDialog) {
        AlertDialog(
            onDismissRequest = { onIntent(SettingsIntent.DismissExportDialog) },
            title = { Text(if (isArabic) "تصدير نسخة احتياطية مشفرة" else "Export Encrypted Backup") },
            text = {
                Column {
                    Text(
                        if (isArabic)
                            "سيتم تشفير بيانات التصنيفات وصلة الرحم والوسوم والتفضيلات بـ AES-256-GCM. أدخل كلمة مرور قوية لحماية ملفك:"
                        else
                            "Categories, kinship, tags, and preferences will be encrypted with AES-256-GCM. Enter a password to protect your file:"
                    )
                    Spacer(modifier = Modifier.height(SilaDimens.Space8))
                    OutlinedTextField(
                        value = exportPasswordInput,
                        onValueChange = { exportPasswordInput = it },
                        label = { Text(if (isArabic) "كلمة المرور" else "Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (state.backupErrorMessage != null) {
                        Spacer(modifier = Modifier.height(SilaDimens.Space4))
                        Text(text = state.backupErrorMessage, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (exportPasswordInput.isNotBlank()) {
                            onIntent(SettingsIntent.ExportBackup(exportPasswordInput))
                        }
                    },
                    enabled = exportPasswordInput.isNotBlank() && !state.isExportingBackup
                ) {
                    Text(if (state.isExportingBackup) (if (isArabic) "جاري التشفير..." else "Encrypting...") else (if (isArabic) "تصدير" else "Export"))
                }
            },
            dismissButton = {
                TextButton(onClick = { onIntent(SettingsIntent.DismissExportDialog) }) {
                    Text(if (isArabic) "إلغاء" else "Cancel")
                }
            }
        )
    }

    // حوار استيراد النسخة الاحتياطية وإدخال كلمة المرور
    if (state.showImportPasswordDialog) {
        AlertDialog(
            onDismissRequest = { onIntent(SettingsIntent.DismissImportDialog) },
            title = { Text(if (isArabic) "استعادة نسخة احتياطية" else "Restore Backup") },
            text = {
                Column {
                    Text(
                        if (isArabic)
                            "أدخل كلمة المرور التي تم استخدامها عند تشفير الملف لمعاينة واستعادة البيانات:"
                        else
                            "Enter the password used when encrypting the backup file to preview and restore:"
                    )
                    Spacer(modifier = Modifier.height(SilaDimens.Space8))
                    OutlinedTextField(
                        value = restorePasswordInput,
                        onValueChange = { restorePasswordInput = it },
                        label = { Text(if (isArabic) "كلمة المرور" else "Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (state.backupErrorMessage != null) {
                        Spacer(modifier = Modifier.height(SilaDimens.Space4))
                        Text(text = state.backupErrorMessage, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (restorePasswordInput.isNotBlank()) {
                            // محاكاة أو تشغيل المعاينة
                            onIntent(SettingsIntent.PreviewBackupFile(ByteArray(0), restorePasswordInput))
                        }
                    },
                    enabled = restorePasswordInput.isNotBlank() && !state.isImportingBackup
                ) {
                    Text(if (state.isImportingBackup) (if (isArabic) "جاري الفحص..." else "Verifying...") else (if (isArabic) "معاينة النسخة" else "Preview Backup"))
                }
            },
            dismissButton = {
                TextButton(onClick = { onIntent(SettingsIntent.DismissImportDialog) }) {
                    Text(if (isArabic) "إلغاء" else "Cancel")
                }
            }
        )
    }

    // حوار معاينة النسخة الاحتياطية وخيارات الدمج/الاستبدال
    state.backupPreview?.let { preview ->
        if (state.showRestorePreviewDialog) {
            AlertDialog(
                onDismissRequest = { onIntent(SettingsIntent.DismissRestorePreviewDialog) },
                title = { Text(if (isArabic) "معاينة النسخة الاحتياطية" else "Backup Preview") },
                text = {
                    Column {
                        Text(
                            text = if (isArabic)
                                "تم التحقق وفك التشفير بنجاح!\n• عدد التصنيفات: ${preview.categoriesCount}\n• بيانات أرحام: ${preview.arhamCount}\n• الوسوم المخصصة: ${preview.tagsCount}\n• نغمات التصنيفات: ${preview.categoryRingtonesCount}"
                            else
                                "Decryption verified successfully!\n• Categories: ${preview.categoriesCount}\n• Kinship entries: ${preview.arhamCount}\n• Tags: ${preview.tagsCount}\n• Ringtones: ${preview.categoryRingtonesCount}"
                        )
                        Spacer(modifier = Modifier.height(SilaDimens.Space12))
                        Text(
                            text = if (isArabic) "طريقة الاستعادة:" else "Restore Mode:",
                            fontWeight = FontWeight.Bold
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = restoreMode == RestoreMode.MERGE,
                                onClick = { restoreMode = RestoreMode.MERGE }
                            )
                            Text(if (isArabic) "دمج مع البيانات الحالية" else "Merge with existing data")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = restoreMode == RestoreMode.REPLACE,
                                onClick = { restoreMode = RestoreMode.REPLACE }
                            )
                            Text(if (isArabic) "استبدال بالكامل" else "Replace existing data")
                        }
                        Spacer(modifier = Modifier.height(SilaDimens.Space8))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = restorePreferencesCheckbox,
                                onCheckedChange = { restorePreferencesCheckbox = it }
                            )
                            Text(if (isArabic) "استعادة تفضيلات التطبيق أيضاً" else "Also restore app preferences")
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            onIntent(
                                SettingsIntent.ConfirmRestore(
                                    cipherBytes = ByteArray(0),
                                    password = restorePasswordInput,
                                    mode = restoreMode,
                                    restorePreferences = restorePreferencesCheckbox
                                )
                            )
                        }
                    ) {
                        Text(if (isArabic) "بدء الاستعادة" else "Confirm Restore")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onIntent(SettingsIntent.DismissRestorePreviewDialog) }) {
                        Text(if (isArabic) "إلغاء" else "Cancel")
                    }
                }
            )
        }
    }

    // حوار نغمات iOS التوجيهي (Guidance Dialog)
    if (state.showRingtoneGuidanceDialog) {
        AlertDialog(
            onDismissRequest = { onIntent(SettingsIntent.DismissRingtoneGuidance) },
            title = { Text(if (isArabic) "النغمات المخصصة على iOS" else "Custom Ringtones on iOS") },
            text = {
                Text(
                    if (isArabic)
                        "نظام iOS لا يسمح لأي تطبيق بتعيين نغمات الرنين برمجياً في دفتر الهاتف.\n\nيمكنك تعيين نغمة رنين خاصة لأي شخص مباشرة عبر فتح جهة الاتصال في تطبيق جهات اتصال النظام واختيار 'نغمة الرنين'."
                    else
                        "iOS does not permit third-party apps to change ringtones programmatically.\n\nYou can assign a custom ringtone directly by opening the contact in the native iOS Contacts app and choosing 'Ringtone'."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onIntent(SettingsIntent.DismissRingtoneGuidance)
                    }
                ) {
                    Text(if (isArabic) "حسناً، فهمت" else "Got it")
                }
            }
        )
    }

    // حوار اختيار النغمة للتصنيف
    state.showRingtonePickerCategory?.let { category ->
        AlertDialog(
            onDismissRequest = { onIntent(SettingsIntent.DismissRingtonePicker) },
            title = {
                Text(
                    if (isArabic) "اختر نغمة لتصنيف ${category.name}" else "Choose Ringtone for ${category.name}"
                )
            },
            text = {
                Column {
                    Text(
                        if (isArabic)
                            "سيتم تطبيق هذه النغمة تلقائياً على جميع جهات الاتصال في هذا التصنيف التي ليس لها نغمة فردية."
                        else
                            "This ringtone will be applied to all contacts in this category that do not have an individual ringtone."
                    )
                    Spacer(modifier = Modifier.height(SilaDimens.Space8))
                    TextButton(
                        onClick = {
                            onIntent(SettingsIntent.SetCategoryRingtone(category, "content://media/chime.ogg"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isArabic) "نغمة صِلَة العائلية (Chime)" else "Sila Family Chime")
                    }
                    TextButton(
                        onClick = {
                            onIntent(SettingsIntent.SetCategoryRingtone(category, null))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isArabic) "إعادة تعيين لنغمة النظام الافتراضية" else "Reset to System Default")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onIntent(SettingsIntent.DismissRingtonePicker) }) {
                    Text(if (isArabic) "إغلاق" else "Close")
                }
            }
        )
    }
}
