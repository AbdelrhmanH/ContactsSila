package app.sila.feature.settings.presentation

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import app.sila.feature.settings.domain.RestoreMode

/**
 * شاشة الإعدادات والتفضيلات لـ ContactsSila (T9 — Issue #30).
 * تطبق مبادئ Clean Architecture و Offline-First:
 * - تقرأ الحالة عبر collectAsStateWithLifecycle
 * - تستمع للآثار الجانبية (Effects) عبر LaunchedEffect وتظهر رسائل للمستخدم
 * - تعتمد على مركزية النصوص المترجمة (SettingsStrings) لمنع النصوص الثابتة
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isArabic = state.preferences.language == AppLanguage.ARABIC
    val strings = remember(isArabic) { if (isArabic) SettingsStrings.arabic() else SettingsStrings.english() }
    val snackbarHostState = remember { SnackbarHostState() }

    var lastExportedBytes by remember { mutableStateOf<ByteArray?>(null) }
    var restoreBytesBuffer by remember { mutableStateOf<ByteArray?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is SettingsEffect.BackupExported -> {
                    lastExportedBytes = effect.bytes
                    restoreBytesBuffer = effect.bytes
                    snackbarHostState.showSnackbar(
                        if (isArabic) "تم تصدير النسخة الاحتياطية بنجاح (${effect.bytes.size} بايت)"
                        else "Backup exported successfully (${effect.bytes.size} bytes)"
                    )
                }
                is SettingsEffect.BackupRestored -> {
                    snackbarHostState.showSnackbar(
                        if (isArabic) "تمت استعادة النسخة بنجاح (${effect.result.matchedCategories} تصنيف، ${effect.result.matchedArham} صلة رحم)"
                        else "Backup restored successfully (${effect.result.matchedCategories} categories, ${effect.result.matchedArham} kinship ties)"
                    )
                }
                is SettingsEffect.ShowToast -> {
                    snackbarHostState.showSnackbar(effect.message)
                }
                is SettingsEffect.OpenIosContactsApp -> Unit
            }
        }
    }

    SettingsDialog(
        state = state,
        strings = strings,
        isArabic = isArabic,
        onIntent = viewModel::onIntent,
        onDismiss = onDismiss,
        snackbarHostState = snackbarHostState,
        lastExportedBytes = lastExportedBytes,
        restoreBytesBuffer = restoreBytesBuffer,
        onUpdateRestoreBytes = { restoreBytesBuffer = it }
    )
}

@Composable
fun SettingsDialog(
    state: SettingsUiState,
    strings: SettingsStrings,
    isArabic: Boolean,
    onIntent: (SettingsIntent) -> Unit,
    onDismiss: () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    lastExportedBytes: ByteArray? = null,
    restoreBytesBuffer: ByteArray? = null,
    onUpdateRestoreBytes: (ByteArray?) -> Unit = {}
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = SilaDimens.Space16),
            shape = RoundedCornerShape(SilaDimens.SettingsGroupCornerRadius),
            color = MaterialTheme.colorScheme.background,
            tonalElevation = 4.dp
        ) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    Surface(
                        tonalElevation = 2.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(SilaDimens.Space16),
                            contentAlignment = Alignment.Center
                        ) {
                            Button(
                                onClick = onDismiss,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(strings.close)
                            }
                        }
                    }
                }
            ) { paddingValues ->
                SettingsContent(
                    state = state,
                    strings = strings,
                    isArabic = isArabic,
                    onIntent = onIntent,
                    onDismiss = onDismiss,
                    lastExportedBytes = lastExportedBytes,
                    restoreBytesBuffer = restoreBytesBuffer,
                    onUpdateRestoreBytes = onUpdateRestoreBytes,
                    modifier = Modifier.padding(paddingValues)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsContent(
    state: SettingsUiState,
    strings: SettingsStrings,
    isArabic: Boolean,
    onIntent: (SettingsIntent) -> Unit,
    onDismiss: () -> Unit,
    lastExportedBytes: ByteArray? = null,
    restoreBytesBuffer: ByteArray? = null,
    onUpdateRestoreBytes: (ByteArray?) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val prefs = state.preferences

    var exportPasswordInput by remember { mutableStateOf("") }
    var restorePasswordInput by remember { mutableStateOf("") }
    var restoreMode by remember { mutableStateOf(RestoreMode.MERGE) }
    var restorePreferencesCheckbox by remember { mutableStateOf(false) }
    var newKeywordInput by remember { mutableStateOf("") }
    var showQuietHoursDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(SilaDimens.Space16),
        verticalArrangement = Arrangement.spacedBy(SilaDimens.Space16)
    ) {
        Text(
            text = strings.title,
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground
        )

        // 1) المظهر واللغة
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
                    val next = when (prefs.themeMode) {
                        AppThemeMode.SYSTEM -> AppThemeMode.LIGHT
                        AppThemeMode.LIGHT -> AppThemeMode.DARK
                        AppThemeMode.DARK -> AppThemeMode.SYSTEM
                    }
                    onIntent(SettingsIntent.SetThemeMode(next))
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

        // 2) السلوك والتنقل
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
                    // تبديل دوري لأفعال السحب
                    val nextStart = when (prefs.swipeStartAction) {
                        ContactSwipeAction.CALL -> ContactSwipeAction.MESSAGE
                        ContactSwipeAction.MESSAGE -> ContactSwipeAction.NONE
                        ContactSwipeAction.NONE -> ContactSwipeAction.CALL
                    }
                    onIntent(SettingsIntent.SetSwipeActions(nextStart, prefs.swipeEndAction))
                },
                showDivider = true
            )
            SilaSettingsItem(
                iconEmoji = "📶",
                title = strings.simSlot,
                value = prefs.defaultSimSlot?.let { "SIM $it" } ?: (if (isArabic) "السؤال دائماً" else "Always Ask"),
                onClick = {
                    val next = when (prefs.defaultSimSlot) {
                        null -> 1
                        1 -> 2
                        else -> null
                    }
                    onIntent(SettingsIntent.SetDefaultSimSlot(next))
                }
            )
        }

        // 3) إعدادات صلة الرحم
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
                    value = "${prefs.arhamNotificationHour.toString().padStart(2, '0')}:${prefs.arhamNotificationMinute.toString().padStart(2, '0')}",
                    onClick = {
                        val nextHour = (prefs.arhamNotificationHour + 1) % 24
                        onIntent(SettingsIntent.SetArhamNotificationTime(nextHour, 0))
                    },
                    showDivider = true
                )
                SilaSettingsItem(
                    iconEmoji = "📊",
                    title = strings.arhamMaxPerDay,
                    value = "${prefs.arhamMaxPerDay}",
                    onClick = {
                        val next = if (prefs.arhamMaxPerDay >= 5) 1 else prefs.arhamMaxPerDay + 1
                        onIntent(SettingsIntent.SetArhamMaxPerDay(next))
                    },
                    showDivider = true
                )
                SilaSettingsItem(
                    iconEmoji = "🌙",
                    title = strings.quietHours,
                    value = "${prefs.quietHoursStartHour.toString().padStart(2, '0')}:00 - ${prefs.quietHoursEndHour.toString().padStart(2, '0')}:00",
                    onClick = { showQuietHoursDialog = true }
                )
            }
        }

        // 4) التنظيم والفلترة الذكية
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

        // 5) النغمات المخصصة
        SilaSettingsGroup(title = strings.sectionRingtones) {
            Text(
                text = strings.ringtonePriority,
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
                    value = if (uri != null) strings.customRingtone else strings.systemDefaultRingtone,
                    onClick = {
                        onIntent(SettingsIntent.OpenRingtonePicker(cat))
                    },
                    showDivider = index < 4
                )
            }
        }

        // 6) الخصوصية والنسخ الاحتياطي
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
                value = if (restoreBytesBuffer != null) (if (isArabic) "بيانات جاهزة (${restoreBytesBuffer.size} بايت)" else "Data ready (${restoreBytesBuffer.size} bytes)") else (if (isArabic) "مطابقة المفاتيح" else "Key matching"),
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

        // 7) حول صِلَة
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

    // ===== الحوارات المنبثقة =====

    // حوار الساعات الهادئة
    if (showQuietHoursDialog) {
        var startH by remember { mutableStateOf(prefs.quietHoursStartHour.toFloat()) }
        var endH by remember { mutableStateOf(prefs.quietHoursEndHour.toFloat()) }

        AlertDialog(
            onDismissRequest = { showQuietHoursDialog = false },
            title = { Text(strings.quietHoursPickTitle) },
            text = {
                Column {
                    Text("${strings.quietHoursStart}: ${startH.toInt().toString().padStart(2, '0')}:00")
                    Slider(
                        value = startH,
                        onValueChange = { startH = it },
                        valueRange = 0f..23f,
                        steps = 22
                    )
                    Spacer(modifier = Modifier.height(SilaDimens.Space8))
                    Text("${strings.quietHoursEnd}: ${endH.toInt().toString().padStart(2, '0')}:00")
                    Slider(
                        value = endH,
                        onValueChange = { endH = it },
                        valueRange = 0f..23f,
                        steps = 22
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    onIntent(SettingsIntent.SetQuietHours(startH.toInt(), endH.toInt()))
                    showQuietHoursDialog = false
                }) {
                    Text(strings.save)
                }
            },
            dismissButton = {
                TextButton(onClick = { showQuietHoursDialog = false }) {
                    Text(strings.cancel)
                }
            }
        )
    }

    // حوار تصدير النسخة الاحتياطية المشفرة
    if (state.showExportPasswordDialog) {
        AlertDialog(
            onDismissRequest = { onIntent(SettingsIntent.DismissExportDialog) },
            title = { Text(strings.exportDialogTitle) },
            text = {
                Column {
                    Text(strings.exportDialogDesc)
                    Spacer(modifier = Modifier.height(SilaDimens.Space8))
                    OutlinedTextField(
                        value = exportPasswordInput,
                        onValueChange = { exportPasswordInput = it },
                        label = { Text(strings.passwordLabel) },
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
                    Text(if (state.isExportingBackup) strings.encrypting else strings.exportButton)
                }
            },
            dismissButton = {
                TextButton(onClick = { onIntent(SettingsIntent.DismissExportDialog) }) {
                    Text(strings.cancel)
                }
            }
        )
    }

    // حوار استيراد النسخة الاحتياطية ومعاينتها
    if (state.showImportPasswordDialog) {
        AlertDialog(
            onDismissRequest = { onIntent(SettingsIntent.DismissImportDialog) },
            title = { Text(strings.restoreDialogTitle) },
            text = {
                Column {
                    Text(strings.restoreDialogDesc)
                    Spacer(modifier = Modifier.height(SilaDimens.Space8))
                    if (restoreBytesBuffer != null) {
                        Text(
                            text = if (isArabic) "✓ تم تحميل بيانات النسخة (${restoreBytesBuffer.size} بايت)" else "✓ Backup data loaded (${restoreBytesBuffer.size} bytes)",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(SilaDimens.Space4))
                    } else {
                        Text(
                            text = if (isArabic) "قم بتصدير نسخة أولاً أو اختيار ملف احتياطي" else "Export a backup first or load a backup file",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(SilaDimens.Space4))
                    }
                    OutlinedTextField(
                        value = restorePasswordInput,
                        onValueChange = { restorePasswordInput = it },
                        label = { Text(strings.passwordLabel) },
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
                        val bytesToPreview = restoreBytesBuffer ?: lastExportedBytes
                        if (restorePasswordInput.isNotBlank() && bytesToPreview != null) {
                            onIntent(SettingsIntent.PreviewBackupFile(bytesToPreview, restorePasswordInput))
                        }
                    },
                    enabled = restorePasswordInput.isNotBlank() && (restoreBytesBuffer != null || lastExportedBytes != null) && !state.isImportingBackup
                ) {
                    Text(if (state.isImportingBackup) strings.verifying else strings.previewButton)
                }
            },
            dismissButton = {
                TextButton(onClick = { onIntent(SettingsIntent.DismissImportDialog) }) {
                    Text(strings.cancel)
                }
            }
        )
    }

    // حوار معاينة النسخة الاحتياطية وتأكيد الاستعادة
    state.backupPreview?.let { preview ->
        if (state.showRestorePreviewDialog) {
            AlertDialog(
                onDismissRequest = { onIntent(SettingsIntent.DismissRestorePreviewDialog) },
                title = { Text(strings.previewDialogTitle) },
                text = {
                    Column {
                        Text(
                            text = if (isArabic)
                                "تم التحقق وفك التشفير بنجاح!\n• عدد التصنيفات: ${preview.categoriesCount}\n• بيانات أرحام: ${preview.arhamCount}\n• الوسوم: ${preview.tagsCount}\n• نغمات التصنيفات: ${preview.categoryRingtonesCount}"
                            else
                                "Decryption verified successfully!\n• Categories: ${preview.categoriesCount}\n• Kinship ties: ${preview.arhamCount}\n• Tags: ${preview.tagsCount}\n• Ringtones: ${preview.categoryRingtonesCount}"
                        )
                        Spacer(modifier = Modifier.height(SilaDimens.Space12))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(SilaDimens.Space12))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { restoreMode = RestoreMode.MERGE }
                        ) {
                            RadioButton(
                                selected = restoreMode == RestoreMode.MERGE,
                                onClick = { restoreMode = RestoreMode.MERGE }
                            )
                            Spacer(modifier = Modifier.width(SilaDimens.Space8))
                            Column {
                                Text(strings.restoreModeMerge, fontWeight = FontWeight.Bold)
                                Text(strings.restoreModeMergeDesc, style = MaterialTheme.typography.bodySmall)
                            }
                        }

                        Spacer(modifier = Modifier.height(SilaDimens.Space8))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { restoreMode = RestoreMode.REPLACE }
                        ) {
                            RadioButton(
                                selected = restoreMode == RestoreMode.REPLACE,
                                onClick = { restoreMode = RestoreMode.REPLACE }
                            )
                            Spacer(modifier = Modifier.width(SilaDimens.Space8))
                            Column {
                                Text(strings.restoreModeReplace, fontWeight = FontWeight.Bold)
                                Text(strings.restoreModeReplaceDesc, style = MaterialTheme.typography.bodySmall)
                            }
                        }

                        Spacer(modifier = Modifier.height(SilaDimens.Space12))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { restorePreferencesCheckbox = !restorePreferencesCheckbox }
                        ) {
                            Checkbox(
                                checked = restorePreferencesCheckbox,
                                onCheckedChange = { restorePreferencesCheckbox = it }
                            )
                            Spacer(modifier = Modifier.width(SilaDimens.Space8))
                            Text(strings.restorePrefsCheckbox)
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val bytesToRestore = restoreBytesBuffer ?: lastExportedBytes
                            if (bytesToRestore != null) {
                                onIntent(
                                    SettingsIntent.ConfirmRestore(
                                        cipherBytes = bytesToRestore,
                                        password = restorePasswordInput,
                                        mode = restoreMode,
                                        restorePreferences = restorePreferencesCheckbox
                                    )
                                )
                            }
                        },
                        enabled = (restoreBytesBuffer != null || lastExportedBytes != null) && !state.isImportingBackup
                    ) {
                        Text(strings.startRestore)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onIntent(SettingsIntent.DismissRestorePreviewDialog) }) {
                        Text(strings.cancel)
                    }
                }
            )
        }
    }

    // حوار اختيار النغمة
    state.showRingtonePickerCategory?.let { category ->
        AlertDialog(
            onDismissRequest = { onIntent(SettingsIntent.DismissRingtonePicker) },
            title = {
                Text("${strings.chooseRingtone}: ${formatCategory(category, isArabic)}")
            },
            text = {
                Column {
                    Text(strings.ringtoneExplain)
                    Spacer(modifier = Modifier.height(SilaDimens.Space8))

                    if (state.availableRingtones.isNotEmpty()) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                        ) {
                            items(state.availableRingtones) { ringtoneItem ->
                                TextButton(
                                    onClick = {
                                        onIntent(SettingsIntent.SetCategoryRingtone(category, ringtoneItem.uri))
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(ringtoneItem.title)
                                }
                            }
                        }
                    } else {
                        TextButton(
                            onClick = {
                                onIntent(SettingsIntent.SetCategoryRingtone(category, "content://media/chime.ogg"))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (isArabic) "نغمة صِلَة العائلية" else "Sila Chime")
                        }
                    }

                    HorizontalDivider()
                    TextButton(
                        onClick = {
                            onIntent(SettingsIntent.SetCategoryRingtone(category, null))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(strings.resetDefault)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onIntent(SettingsIntent.DismissRingtonePicker) }) {
                    Text(strings.close)
                }
            }
        )
    }
}

private fun formatSwipeAction(action: ContactSwipeAction, strings: SettingsStrings): String = when (action) {
    ContactSwipeAction.CALL -> strings.swipeCall
    ContactSwipeAction.MESSAGE -> strings.swipeMessage
    ContactSwipeAction.NONE -> strings.swipeNone
}

data class SettingsStrings(
    val title: String,
    val close: String,
    val sectionAppearance: String,
    val theme: String,
    val themeLight: String,
    val themeDark: String,
    val themeSystem: String,
    val language: String,
    val digits: String,
    val digitsArabic: String,
    val digitsHindi: String,
    val nameOrder: String,
    val nameOrderFirstLast: String,
    val nameOrderLastFirst: String,
    val sectionGeneral: String,
    val defaultTab: String,
    val tabContacts: String,
    val tabServices: String,
    val tabArham: String,
    val swipeStart: String,
    val swipeEnd: String,
    val swipeCall: String,
    val swipeMessage: String,
    val swipeNone: String,
    val simSlot: String,
    val sectionArham: String,
    val arhamNotifications: String,
    val arhamTime: String,
    val arhamMaxPerDay: String,
    val quietHours: String,
    val quietHoursPickTitle: String,
    val quietHoursStart: String,
    val quietHoursEnd: String,
    val save: String,
    val cancel: String,
    val sectionOrganization: String,
    val smartFilter: String,
    val duplicateScan: String,
    val customKeywords: String,
    val addKeyword: String,
    val sectionRingtones: String,
    val ringtonePriority: String,
    val customRingtone: String,
    val systemDefaultRingtone: String,
    val chooseRingtone: String,
    val ringtoneExplain: String,
    val resetDefault: String,
    val sectionBackup: String,
    val localPrivacy: String,
    val localPrivacyValue: String,
    val exportBackup: String,
    val restoreBackup: String,
    val crashReports: String,
    val deleteAccount: String,
    val deleteAccountValue: String,
    val exportDialogTitle: String,
    val exportDialogDesc: String,
    val passwordLabel: String,
    val exportButton: String,
    val encrypting: String,
    val restoreDialogTitle: String,
    val restoreDialogDesc: String,
    val previewButton: String,
    val verifying: String,
    val previewDialogTitle: String,
    val restoreModeMerge: String,
    val restoreModeMergeDesc: String,
    val restoreModeReplace: String,
    val restoreModeReplaceDesc: String,
    val restorePrefsCheckbox: String,
    val startRestore: String,
    val aboutSection: String,
    val versionTitle: String,
    val versionValue: String,
    val privacyTerms: String,
    val rateApp: String
) {
    companion object {
        fun arabic(): SettingsStrings = SettingsStrings(
            title = "الإعدادات والتفضيلات",
            close = "إغلاق الإعدادات",
            sectionAppearance = "المظهر واللغة",
            theme = "السمة المظهرية",
            themeLight = "فاتح ☀️",
            themeDark = "داكن 🌙",
            themeSystem = "تلقائي (حسب النظام) ⚙️",
            language = "لغة التطبيق",
            digits = "كتابة الأرقام",
            digitsArabic = "أرقام عربية غربية (1 2 3)",
            digitsHindi = "أرقام عربية مشرقية (١ ٢ ٣)",
            nameOrder = "ترتيب عرض الأسماء",
            nameOrderFirstLast = "الاسم الأول أولاً",
            nameOrderLastFirst = "اسم العائلة أولاً",
            sectionGeneral = "عام وسلوك التطبيق",
            defaultTab = "التبويب الافتراضي عند الفتح",
            tabContacts = "جهات الاتصال 👥",
            tabServices = "دليل الخدمات 🛠️",
            tabArham = "أرحام ❤️",
            swipeStart = "سحب البداية",
            swipeEnd = "سحب النهاية",
            swipeCall = "اتصال 📞",
            swipeMessage = "رسالة 💬",
            swipeNone = "معطل ✕",
            simSlot = "شريحة الاتصال المفضلة",
            sectionArham = "صلة الرحم (أرحام)",
            arhamNotifications = "تفعيل تنبيهات صلة الرحم",
            arhamTime = "وقت التذكير اليومي",
            arhamMaxPerDay = "الحد الأقصى للتنبيهات يومياً",
            quietHours = "الساعات الهادئة (حظر التنبيهات)",
            quietHoursPickTitle = "تحديد الساعات الهادئة",
            quietHoursStart = "بداية الهدوء",
            quietHoursEnd = "نهاية الهدوء",
            save = "حفظ",
            cancel = "إلغاء",
            sectionOrganization = "التنظيم والذكاء الاصطناعي المحلي",
            smartFilter = "الفلترة الذكية التلقائية",
            duplicateScan = "فحص التكرارات بالخلفية عند التشغيل",
            customKeywords = "الكلمات المفتاحية المخصصة للتصنيف",
            addKeyword = "إضافة",
            sectionRingtones = "نغمات التصنيفات",
            ringtonePriority = "الأولوية: نغمة الشخص > نغمة التصنيف > نغمة النظام",
            customRingtone = "مخصصة ✓",
            systemDefaultRingtone = "نغمة النظام الافتراضية",
            chooseRingtone = "اختر نغمة",
            ringtoneExplain = "سيتم تطبيق هذه النغمة تلقائياً على جهات الاتصال في هذا التصنيف التي ليس لها نغمة فردية.",
            resetDefault = "إعادة تعيين لنغمة النظام الافتراضية",
            sectionBackup = "الخصوصية والنسخ الاحتياطي المشفر",
            localPrivacy = "ماذا نعرف عنك؟ (الخصوصية المحلية)",
            localPrivacyValue = "0 بيانات مرفوعة (محلي 100%)",
            exportBackup = "تصدير نسخة مشفرة (.sila.backup)",
            restoreBackup = "استعادة نسخة احتياطية",
            crashReports = "إرسال تقارير الأعطال مجهولة المصدر",
            deleteAccount = "حذف البيانات والبدء من جديد",
            deleteAccountValue = "محلي فقط (لا سحابة)",
            exportDialogTitle = "تصدير نسخة احتياطية مشفرة",
            exportDialogDesc = "سيتم تشفير التصنيفات وبيانات أرحام والوسوم والتفضيلات بـ AES-256-GCM. أدخل كلمة مرور قوية:",
            passwordLabel = "كلمة المرور",
            exportButton = "تصدير مشفر",
            encrypting = "جاري التشفير...",
            restoreDialogTitle = "استعادة نسخة احتياطية",
            restoreDialogDesc = "أدخل كلمة المرور لمعاينة وفك تشفير النسخة:",
            previewButton = "معاينة النسخة",
            verifying = "جاري الفحص...",
            previewDialogTitle = "معاينة النسخة الاحتياطية",
            restoreModeMerge = "دمج مع البيانات الحالية",
            restoreModeMergeDesc = "تحديث التصنيفات وصلة الرحم والوسوم دون حذف القائم",
            restoreModeReplace = "استبدال البيانات الحالية",
            restoreModeReplaceDesc = "إعادة تعيين التصنيفات والوسوم القديمة وتطبيق محتوى النسخة",
            restorePrefsCheckbox = "استعادة تفضيلات وإعدادات التطبيق أيضاً",
            startRestore = "بدء الاستعادة",
            aboutSection = "حول صِلَة",
            versionTitle = "رقم الإصدار",
            versionValue = "1.0.0 (Phase 1 Local MVP)",
            privacyTerms = "سياسة الخصوصية والشروط",
            rateApp = "تقييم التطبيق والدعم"
        )

        fun english(): SettingsStrings = SettingsStrings(
            title = "Settings & Preferences",
            close = "Close Settings",
            sectionAppearance = "Appearance & Language",
            theme = "Theme Mode",
            themeLight = "Light ☀️",
            themeDark = "Dark 🌙",
            themeSystem = "System Default ⚙️",
            language = "Language",
            digits = "Digits Format",
            digitsArabic = "Western Arabic (1 2 3)",
            digitsHindi = "Eastern Arabic (١ ٢ ٣)",
            nameOrder = "Name Display Order",
            nameOrderFirstLast = "First Name, Last Name",
            nameOrderLastFirst = "Last Name, First Name",
            sectionGeneral = "General & Behavior",
            defaultTab = "Default Startup Tab",
            tabContacts = "Contacts 👥",
            tabServices = "Services 🛠️",
            tabArham = "Arham ❤️",
            swipeStart = "Start Swipe",
            swipeEnd = "End Swipe",
            swipeCall = "Call 📞",
            swipeMessage = "Message 💬",
            swipeNone = "Disabled ✕",
            simSlot = "Default SIM Slot",
            sectionArham = "Kinship (Arham)",
            arhamNotifications = "Kinship Reminders",
            arhamTime = "Daily Reminder Time",
            arhamMaxPerDay = "Max Reminders Per Day",
            quietHours = "Quiet Hours (Do Not Disturb)",
            quietHoursPickTitle = "Set Quiet Hours",
            quietHoursStart = "Quiet Start",
            quietHoursEnd = "Quiet End",
            save = "Save",
            cancel = "Cancel",
            sectionOrganization = "Organization & Local AI",
            smartFilter = "Smart Filter",
            duplicateScan = "Startup Duplicate Scan",
            customKeywords = "Custom Category Keywords",
            addKeyword = "Add",
            sectionRingtones = "Category Ringtones",
            ringtonePriority = "Priority: Contact Ringtone > Category Ringtone > System Default",
            customRingtone = "Custom ✓",
            systemDefaultRingtone = "System Default",
            chooseRingtone = "Select Ringtone",
            ringtoneExplain = "This ringtone will be assigned to category contacts without individual custom ringtones.",
            resetDefault = "Reset to System Default",
            sectionBackup = "Privacy & Encrypted Backup",
            localPrivacy = "What do we know about you?",
            localPrivacyValue = "0 data uploaded (100% offline)",
            exportBackup = "Export Encrypted Backup (.sila.backup)",
            restoreBackup = "Restore Backup",
            crashReports = "Anonymous Crash Reports",
            deleteAccount = "Reset Data & Start Fresh",
            deleteAccountValue = "Local only (No Cloud)",
            exportDialogTitle = "Export Encrypted Backup",
            exportDialogDesc = "Categories, kinship, tags, and preferences will be encrypted with AES-256-GCM. Enter a strong password:",
            passwordLabel = "Password",
            exportButton = "Export Encrypted",
            encrypting = "Encrypting...",
            restoreDialogTitle = "Restore Backup",
            restoreDialogDesc = "Enter the password to preview and decrypt the backup:",
            previewButton = "Preview Backup",
            verifying = "Verifying...",
            previewDialogTitle = "Backup Preview",
            restoreModeMerge = "Merge with current data",
            restoreModeMergeDesc = "Update categories, kinship, and tags without deleting existing ones",
            restoreModeReplace = "Replace current data",
            restoreModeReplaceDesc = "Reset previous categories and tags, applying the backup payload",
            restorePrefsCheckbox = "Also restore app preferences and settings",
            startRestore = "Start Restore",
            aboutSection = "About Sila",
            versionTitle = "Version",
            versionValue = "1.0.0 (Phase 1 Local MVP)",
            privacyTerms = "Privacy Policy & Terms",
            rateApp = "Rate App & Feedback"
        )
    }
}

private fun formatCategory(category: Category, isArabic: Boolean): String = when (category) {
    Category.FAMILY -> if (isArabic) "العائلة 👨‍👩‍👧‍👦" else "Family"
    Category.FRIENDS -> if (isArabic) "الأصدقاء 🤝" else "Friends"
    Category.WORK -> if (isArabic) "العمل 💼" else "Work"
    Category.SERVICES -> if (isArabic) "الخدمات 🛠️" else "Services"
    Category.EMERGENCY -> if (isArabic) "الطوارئ 🚨" else "Emergency"
    Category.UNCATEGORIZED -> if (isArabic) "غير مصنف" else "Uncategorized"
}
