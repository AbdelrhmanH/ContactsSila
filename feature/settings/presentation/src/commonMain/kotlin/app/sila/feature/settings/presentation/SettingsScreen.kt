package app.sila.feature.settings.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sila.core.designsystem.SilaDimens
import app.sila.core.model.AppLanguage

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
    onDismiss: () -> Unit,
    /** حفظ بايتات التصدير في ملف يختاره المستخدم (مضيف Android/SAF) — null يعني بلا حفظ متاح على هذه المنصة. */
    onPersistBackup: ((ByteArray) -> Unit)? = null,
    /** طلب اختيار ملف نسخة للاستيراد من الجهاز — null يعني بلا منتقي ملف على هذه المنصة. */
    onPickBackupFile: (() -> Unit)? = null
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
                    onPersistBackup?.invoke(effect.bytes)
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
        onUpdateRestoreBytes = { restoreBytesBuffer = it },
        onPersistBackup = onPersistBackup,
        onPickBackupFile = onPickBackupFile
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
    onUpdateRestoreBytes: (ByteArray?) -> Unit = {},
    /** حفظ بايتات التصدير في ملف يختاره المستخدم (مضيف Android/SAF) — يُمرَّر من SettingsScreen. */
    onPersistBackup: ((ByteArray) -> Unit)? = null,
    /** طلب اختيار ملف نسخة للاستيراد من الجهاز — يُمرَّر من SettingsScreen. */
    onPickBackupFile: (() -> Unit)? = null
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
                    onPersistBackup = onPersistBackup,
                    onPickBackupFile = onPickBackupFile,
                    modifier = Modifier.padding(paddingValues)
                )
            }
        }
    }
}

/**
 * جسد محتوى الإعدادات بعد التفكيك (S2-17 — تذكرة #67): تركيب رفيع يركِّب الأقسام
 * السبعة من [SettingsSections] ومضيفَي الحوارات من [SettingsDialogs] بلا أي منطق
 * أعمال — يقرأ [SettingsUiState] ويرسل [SettingsIntent] فقط.
 *
 * ملاحظة توافق نزيهة: التركيب محصور الاستخدام داخلياً في وحدة الميزة كلها، ومع
 * ذلك أبقينا التوقيع العام بمعاملاته السابقة كما هو (منها `onUpdateRestoreBytes`
 * الميت الخدمة سلفاً — لم يكن يُستدعَى في أي جسد حوار في الأصل) حتى يبقى هذا
 * التغيير بلا مساس بتوقيع الميزة العامة ليُراجَع التجزئة وحدها، وإزالة المعامل
 * الميت مهمة صيانة قادمة منفصلة.
 */
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
    onPersistBackup: ((ByteArray) -> Unit)? = null,
    onPickBackupFile: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val prefs = state.preferences

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

        SettingsAppearanceSection(prefs, strings, onIntent)
        SettingsGeneralSection(prefs, strings, isArabic, onIntent)
        SettingsArhamSection(prefs, strings, onIntent)
        SettingsOrganizationSection(prefs, strings, isArabic, onIntent)
        SettingsRingtonesSection(
            categoryRingtones = state.categoryRingtones,
            pickerCategory = state.showRingtonePickerCategory,
            availableRingtones = state.availableRingtones,
            strings = strings,
            isArabic = isArabic,
            onIntent = onIntent
        )
        SettingsBackupSection(prefs, strings, isArabic, restoreBytesBuffer, onIntent)
        SettingsAboutSection(strings)
    }

    // ===== الحوارات المنبثقة =====
    // المضيفان يُركَّبان دائماً (بلا قِفلة if) كما في الأصل: مسودات كلمات السر ووضع
    // الاستعادة تبقى حية عبر إغلاق الحوار وإعادة فتحه.
    SettingsRestorePasswordDialogs(
        state = state,
        strings = strings,
        isArabic = isArabic,
        onIntent = onIntent,
        lastExportedBytes = lastExportedBytes,
        restoreBytesBuffer = restoreBytesBuffer,
        onPickBackupFile = onPickBackupFile
    )
    SettingsRestoreOutcomeDialogs(
        state = state,
        strings = strings,
        isArabic = isArabic,
        onIntent = onIntent
    )
}
