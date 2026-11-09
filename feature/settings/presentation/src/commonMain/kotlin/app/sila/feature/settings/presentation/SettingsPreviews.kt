package app.sila.feature.settings.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import app.sila.core.designsystem.SilaTheme
import app.sila.core.model.Category
import app.sila.core.model.UserPreferences
import app.sila.feature.settings.domain.BackupPreview
import app.sila.feature.settings.domain.RingtoneItem
import androidx.compose.ui.tooling.preview.Preview

/**
 * معاينات شاشة الإعدادات متعددة الحالات (S2-17 — تذكرة #67):
 * كل قسم سطحي في حالتَيه اللغويتين (عربي فاتح / إنجليزي داكن)، الجسد الكامل عبر
 * [SettingsContent] في حالتي الخط 200% وحالة الوضع الداكن الإنجليزية، وجسدا حوار
 * للتصدير ومعاينة الاستعادة — وفق CODING_GUIDELINES: RTL أولاً وأحجام خطوط كبيرة.
 *
 * عدد دوال الملف = 19 (أقل من سقف TooManyFunctions = 20): حالات المعاينة خصائص
 * ثابتة لا تُحتسب دوالاً.
 */

/** حالة معاينة مشتركة: تفضيلات افتراضية معرّبة ونغمات متاحة لكل التصنيفات. */
private val PreviewState: SettingsUiState = SettingsUiState(
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

/** غلاف المعاينات: [SilaTheme] مع اتجاه اللغة اختياري وتكبير الخط عبر [LocalDensity]. */
@Composable
private fun SettingsPreviewShell(
    darkTheme: Boolean,
    isRtl: Boolean,
    fontScale: Float = 1f,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalDensity provides LocalDensity.current.copy(fontScale = fontScale)
    ) {
        SilaTheme(darkTheme = darkTheme, isRtl = isRtl) {
            content()
        }
    }
}

// ===== الأقسام السبعة: عربي فاتح / إنجليزي داكن =====

@Preview(showBackground = true)
@Composable
private fun AppearanceSectionArabicLightPreview() {
    SettingsPreviewShell(darkTheme = false, isRtl = true) {
        SettingsAppearanceSection(PreviewState.preferences, SettingsStrings.arabic(), {})
    }
}

@Preview(showBackground = true)
@Composable
private fun AppearanceSectionEnglishDarkPreview() {
    SettingsPreviewShell(darkTheme = true, isRtl = false) {
        SettingsAppearanceSection(PreviewState.preferences, SettingsStrings.english(), {})
    }
}

@Preview(showBackground = true)
@Composable
private fun GeneralSectionArabicLightPreview() {
    SettingsPreviewShell(darkTheme = false, isRtl = true) {
        SettingsGeneralSection(PreviewState.preferences, SettingsStrings.arabic(), true, {})
    }
}

@Preview(showBackground = true)
@Composable
private fun GeneralSectionEnglishDarkPreview() {
    SettingsPreviewShell(darkTheme = true, isRtl = false) {
        SettingsGeneralSection(PreviewState.preferences, SettingsStrings.english(), false, {})
    }
}

@Preview(showBackground = true)
@Composable
private fun ArhamSectionArabicLightPreview() {
    SettingsPreviewShell(darkTheme = false, isRtl = true) {
        SettingsArhamSection(PreviewState.preferences, SettingsStrings.arabic(), {})
    }
}

@Preview(showBackground = true)
@Composable
private fun ArhamSectionEnglishDarkPreview() {
    SettingsPreviewShell(darkTheme = true, isRtl = false) {
        SettingsArhamSection(PreviewState.preferences, SettingsStrings.english(), {})
    }
}

@Preview(showBackground = true)
@Composable
private fun OrganizationSectionArabicLightPreview() {
    SettingsPreviewShell(darkTheme = false, isRtl = true) {
        SettingsOrganizationSection(PreviewState.preferences, SettingsStrings.arabic(), true, {})
    }
}

@Preview(showBackground = true)
@Composable
private fun OrganizationSectionEnglishDarkPreview() {
    SettingsPreviewShell(darkTheme = true, isRtl = false) {
        SettingsOrganizationSection(PreviewState.preferences, SettingsStrings.english(), false, {})
    }
}

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
private fun RingtonesSectionEnglishDarkPreview() {
    SettingsPreviewShell(darkTheme = true, isRtl = false) {
        SettingsRingtonesSection(
            categoryRingtones = PreviewState.categoryRingtones,
            pickerCategory = null,
            availableRingtones = PreviewState.availableRingtones,
            strings = SettingsStrings.english(),
            isArabic = false,
            onIntent = {}
        )
    }
}

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
private fun BackupSectionEnglishDarkPreview() {
    SettingsPreviewShell(darkTheme = true, isRtl = false) {
        SettingsBackupSection(
            prefs = PreviewState.preferences,
            strings = SettingsStrings.english(),
            isArabic = false,
            restoreBytesBuffer = byteArrayOf(0x53),
            onIntent = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AboutSectionArabicLightPreview() {
    SettingsPreviewShell(darkTheme = false, isRtl = true) {
        SettingsAboutSection(SettingsStrings.arabic())
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
            state = PreviewState,
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
