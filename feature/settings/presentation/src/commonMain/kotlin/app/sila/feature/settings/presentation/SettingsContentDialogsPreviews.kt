package app.sila.feature.settings.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import app.sila.core.designsystem.SilaTheme
import app.sila.core.model.AppLanguage
import app.sila.core.model.Category
import app.sila.core.model.UserPreferences
import app.sila.feature.settings.domain.BackupPreview
import app.sila.feature.settings.domain.RingtoneItem

/**
 * معاينات الأقسام الثلاثة الأخيرة والجسد الكامل والحوارات بأكمل مصفوفة الحالات
 * (S2-17 — تذكرة #67): لكل قسم أربعة أحوال — عربي-فاتح / عربي-داكن /
 * إنجليزي-فاتح / إنجليزي-داكن — وفق CODING_GUIDELINES: RTL أولاً + Dark mode +
 * أحجام خطوط كبيرة، مع جسدين كاملين وجسدي حوار.
 *
 * الغلاف [SettingsPreviewShell] وثابتات الحالة الداخلية معرَّفة هنا ليستخدمهما
 * [SettingsSectionPreviews] كذلك. عدد دوال الملف = 17 (أقل من سقف
 * TooManyFunctions = 20)؛ حالات المعاينة خصائص ثابتة لا تُحتسب دوالاً.
 */

// ===== ثابتات الحالة المشتركة (internal لأن الملف المجاور يستخدم شيئين منها) =====

/** حالة معاينة مشتركة: تفضيلات افتراضية معرّبة ونغمات متاحة لكل التصنيفات. */
internal val PreviewState: SettingsUiState = SettingsUiState(
    preferences = UserPreferences(),
    categoryRingtones = mapOf(
        Category.FAMILY to "content://media/sila/family",
        Category.FRIENDS to "content://media/sila/friends"
    ),
    availableRingtones = listOf(
        RingtoneItem("نغمة النظام الأولى", "content://media/ringtone/1"),
        RingtoneItem("نغمة النظام الثانية", "content://media/ringtone/2"),
        RingtoneItem("نغمة النظام الثالثة", "content://media/ringtone/3")
    )
)

/** النسخة الإنجليزية للحالة: اللغة فقط تُبدَّل لتظهر صف «اللغة = الإنجليزية» في المظهر. */
internal val PreviewStateEnglish: SettingsUiState = PreviewState.copy(
    preferences = PreviewState.preferences.copy(language = AppLanguage.ENGLISH)
)

/** معاينة مُعوَّضة لفحص النسخة في حوار التأكيد (أعداد ثابتة للعرض، خارجة عن أي بيانات حقيقية). */
private val PreviewBackup: BackupPreview = BackupPreview(
    version = 1,
    exportedAt = "2026-01-01T09:00:00Z",
    appVersion = "1.0.0",
    categoriesCount = 6,
    arhamCount = 14,
    tagsCount = 4,
    hasPreferences = true,
    categoryRingtonesCount = 2,
    automationsCount = 1,
    disputesCount = 0
)

/** حالات معاينة الحوارات: حوار تصدير ظاهر ومعاينة نسخة مشفرة تجاوز فحصها. */
private val PreviewExportDialogState: SettingsUiState = PreviewState.copy(showExportPasswordDialog = true)
private val PreviewRestoreDialogState: SettingsUiState = PreviewState.copy(
    showRestorePreviewDialog = true,
    backupPreview = PreviewBackup
)

/**
 * غلاف المعاينات المشترك: [SilaTheme] مع اتجاه اللغة اختياري وتكبير الخط عبر
 * [LocalDensity] بإعادة بناء قيمه صراحةً (واجهة Density بلا `copy`).
 */
@Composable
internal fun SettingsPreviewShell(
    darkTheme: Boolean,
    isRtl: Boolean,
    fontScale: Float = 1f,
    content: @Composable () -> Unit
) {
    val current = LocalDensity.current
    val adjusted = Density(density = current.density, fontScale = current.fontScale * fontScale)
    CompositionLocalProvider(LocalDensity provides adjusted) {
        SilaTheme(darkTheme = darkTheme, isRtl = isRtl) {
            content()
        }
    }
}

// ===== النغمات × 4 =====

@Preview(showBackground = true)
@Composable
private fun RingtonesSectionArabicLightPreview() {
    SettingsPreviewShell(darkTheme = false, isRtl = true) {
        SettingsRingtonesSection(
            categoryRingtones = PreviewState.categoryRingtones,
            pickerCategory = null,
            availableRingtones = PreviewState.availableRingtones,
            strings = SettingsStrings.arabic(),
            isArabic = true,
            onIntent = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RingtonesSectionArabicDarkPreview() {
    SettingsPreviewShell(darkTheme = true, isRtl = true) {
        SettingsRingtonesSection(
            categoryRingtones = PreviewState.categoryRingtones,
            pickerCategory = null,
            availableRingtones = PreviewState.availableRingtones,
            strings = SettingsStrings.arabic(),
            isArabic = true,
            onIntent = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RingtonesSectionEnglishLightPreview() {
    SettingsPreviewShell(darkTheme = false, isRtl = false) {
        SettingsRingtonesSection(
            categoryRingtones = PreviewStateEnglish.categoryRingtones,
            pickerCategory = null,
            availableRingtones = PreviewStateEnglish.availableRingtones,
            strings = SettingsStrings.english(),
            isArabic = false,
            onIntent = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RingtonesSectionEnglishDarkPreview() {
    SettingsPreviewShell(darkTheme = true, isRtl = false) {
        SettingsRingtonesSection(
            categoryRingtones = PreviewStateEnglish.categoryRingtones,
            pickerCategory = null,
            availableRingtones = PreviewStateEnglish.availableRingtones,
            strings = SettingsStrings.english(),
            isArabic = false,
            onIntent = {}
        )
    }
}

// ===== النسخ الاحتياطي × 4 =====

@Preview(showBackground = true)
@Composable
private fun BackupSectionArabicLightPreview() {
    SettingsPreviewShell(darkTheme = false, isRtl = true) {
        SettingsBackupSection(
            prefs = PreviewState.preferences,
            strings = SettingsStrings.arabic(),
            isArabic = true,
            restoreBytesBuffer = null,
            onIntent = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun BackupSectionArabicDarkPreview() {
    SettingsPreviewShell(darkTheme = true, isRtl = true) {
        SettingsBackupSection(
            prefs = PreviewState.preferences,
            strings = SettingsStrings.arabic(),
            isArabic = true,
            restoreBytesBuffer = null,
            onIntent = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun BackupSectionEnglishLightPreview() {
    SettingsPreviewShell(darkTheme = false, isRtl = false) {
        SettingsBackupSection(
            prefs = PreviewStateEnglish.preferences,
            strings = SettingsStrings.english(),
            isArabic = false,
            restoreBytesBuffer = byteArrayOf(0x53),
            onIntent = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun BackupSectionEnglishDarkPreview() {
    SettingsPreviewShell(darkTheme = true, isRtl = false) {
        SettingsBackupSection(
            prefs = PreviewStateEnglish.preferences,
            strings = SettingsStrings.english(),
            isArabic = false,
            restoreBytesBuffer = byteArrayOf(0x53),
            onIntent = {}
        )
    }
}

// ===== حول صِلَة × 4 =====

@Preview(showBackground = true)
@Composable
private fun AboutSectionArabicLightPreview() {
    SettingsPreviewShell(darkTheme = false, isRtl = true) {
        SettingsAboutSection(SettingsStrings.arabic())
    }
}

@Preview(showBackground = true)
@Composable
private fun AboutSectionArabicDarkPreview() {
    SettingsPreviewShell(darkTheme = true, isRtl = true) {
        SettingsAboutSection(SettingsStrings.arabic())
    }
}

@Preview(showBackground = true)
@Composable
private fun AboutSectionEnglishLightPreview() {
    SettingsPreviewShell(darkTheme = false, isRtl = false) {
        SettingsAboutSection(SettingsStrings.english())
    }
}

@Preview(showBackground = true)
@Composable
private fun AboutSectionEnglishDarkPreview() {
    SettingsPreviewShell(darkTheme = true, isRtl = false) {
        SettingsAboutSection(SettingsStrings.english())
    }
}

// ===== الجسد الكامل: خط 200% (عربي فاتح) وعادي (إنجليزي داكن) =====

@Preview(showBackground = true)
@Composable
private fun FullContentArabicLightLargeFontPreview() {
    SettingsPreviewShell(darkTheme = false, isRtl = true, fontScale = 2f) {
        SettingsContent(
            state = PreviewState,
            strings = SettingsStrings.arabic(),
            isArabic = true,
            onIntent = {},
            onDismiss = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun FullContentEnglishDarkPreview() {
    SettingsPreviewShell(darkTheme = true, isRtl = false) {
        SettingsContent(
            state = PreviewStateEnglish,
            strings = SettingsStrings.english(),
            isArabic = false,
            onIntent = {},
            onDismiss = {}
        )
    }
}

// ===== جسدا حوار: التصدير (إنجليزي داكن) ومعاينة الاستعادة (عربي فاتح) =====

@Preview(showBackground = true)
@Composable
private fun ExportPasswordDialogEnglishDarkPreview() {
    SettingsPreviewShell(darkTheme = true, isRtl = false) {
        SettingsRestorePasswordDialogs(
            state = PreviewExportDialogState,
            strings = SettingsStrings.english(),
            isArabic = false,
            onIntent = {},
            lastExportedBytes = null,
            restoreBytesBuffer = byteArrayOf(0x53),
            onPickBackupFile = null
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RestorePreviewDialogArabicLightPreview() {
    SettingsPreviewShell(darkTheme = false, isRtl = true) {
        SettingsRestorePasswordDialogs(
            state = PreviewRestoreDialogState,
            strings = SettingsStrings.arabic(),
            isArabic = true,
            onIntent = {},
            lastExportedBytes = null,
            restoreBytesBuffer = byteArrayOf(0x53),
            onPickBackupFile = null
        )
    }
}
