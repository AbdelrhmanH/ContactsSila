package app.sila.feature.settings.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

/**
 * معاينات الأقسام الأربعة الأولى بأكمل مصفوفة الحالات (S2-17 — تذكرة #67):
 * لكل قسم أربعة أحوال — عربي-فاتح / عربي-داكن / إنجليزي-فاتح / إنجليزي-داكن —
 * وفق CODING_GUIDELINES: RTL أولاً + Dark mode + أحجام خطوط كبيرة.
 *
 * 16 دالة (أقل من سقف TooManyFunctions = 20). الغلاف وأثبتات الحالة في
 * [SettingsContentDialogsPreviews].
 */

// ===== المظهر واللغة × 4 =====

@Preview(showBackground = true)
@Composable
private fun AppearanceSectionArabicLightPreview() {
    SettingsPreviewShell(darkTheme = false, isRtl = true) {
        SettingsAppearanceSection(PreviewState.preferences, SettingsStrings.arabic(), {})
    }
}

@Preview(showBackground = true)
@Composable
private fun AppearanceSectionArabicDarkPreview() {
    SettingsPreviewShell(darkTheme = true, isRtl = true) {
        SettingsAppearanceSection(PreviewState.preferences, SettingsStrings.arabic(), {})
    }
}

@Preview(showBackground = true)
@Composable
private fun AppearanceSectionEnglishLightPreview() {
    SettingsPreviewShell(darkTheme = false, isRtl = false) {
        SettingsAppearanceSection(PreviewStateEnglish.preferences, SettingsStrings.english(), {})
    }
}

@Preview(showBackground = true)
@Composable
private fun AppearanceSectionEnglishDarkPreview() {
    SettingsPreviewShell(darkTheme = true, isRtl = false) {
        SettingsAppearanceSection(PreviewStateEnglish.preferences, SettingsStrings.english(), {})
    }
}

// ===== عام وسلوك التطبيق × 4 =====

@Preview(showBackground = true)
@Composable
private fun GeneralSectionArabicLightPreview() {
    SettingsPreviewShell(darkTheme = false, isRtl = true) {
        SettingsGeneralSection(PreviewState.preferences, SettingsStrings.arabic(), true, {})
    }
}

@Preview(showBackground = true)
@Composable
private fun GeneralSectionArabicDarkPreview() {
    SettingsPreviewShell(darkTheme = true, isRtl = true) {
        SettingsGeneralSection(PreviewState.preferences, SettingsStrings.arabic(), true, {})
    }
}

@Preview(showBackground = true)
@Composable
private fun GeneralSectionEnglishLightPreview() {
    SettingsPreviewShell(darkTheme = false, isRtl = false) {
        SettingsGeneralSection(PreviewStateEnglish.preferences, SettingsStrings.english(), false, {})
    }
}

@Preview(showBackground = true)
@Composable
private fun GeneralSectionEnglishDarkPreview() {
    SettingsPreviewShell(darkTheme = true, isRtl = false) {
        SettingsGeneralSection(PreviewStateEnglish.preferences, SettingsStrings.english(), false, {})
    }
}

// ===== صلة الرحم (أرحام) × 4 =====

@Preview(showBackground = true)
@Composable
private fun ArhamSectionArabicLightPreview() {
    SettingsPreviewShell(darkTheme = false, isRtl = true) {
        SettingsArhamSection(PreviewState.preferences, SettingsStrings.arabic(), {})
    }
}

@Preview(showBackground = true)
@Composable
private fun ArhamSectionArabicDarkPreview() {
    SettingsPreviewShell(darkTheme = true, isRtl = true) {
        SettingsArhamSection(PreviewState.preferences, SettingsStrings.arabic(), {})
    }
}

@Preview(showBackground = true)
@Composable
private fun ArhamSectionEnglishLightPreview() {
    SettingsPreviewShell(darkTheme = false, isRtl = false) {
        SettingsArhamSection(PreviewStateEnglish.preferences, SettingsStrings.english(), {})
    }
}

@Preview(showBackground = true)
@Composable
private fun ArhamSectionEnglishDarkPreview() {
    SettingsPreviewShell(darkTheme = true, isRtl = false) {
        SettingsArhamSection(PreviewStateEnglish.preferences, SettingsStrings.english(), {})
    }
}

// ===== التنظيم والذكاء الاصطناعي المحلي × 4 =====

@Preview(showBackground = true)
@Composable
private fun OrganizationSectionArabicLightPreview() {
    SettingsPreviewShell(darkTheme = false, isRtl = true) {
        SettingsOrganizationSection(PreviewState.preferences, SettingsStrings.arabic(), true, {})
    }
}

@Preview(showBackground = true)
@Composable
private fun OrganizationSectionArabicDarkPreview() {
    SettingsPreviewShell(darkTheme = true, isRtl = true) {
        SettingsOrganizationSection(PreviewState.preferences, SettingsStrings.arabic(), true, {})
    }
}

@Preview(showBackground = true)
@Composable
private fun OrganizationSectionEnglishLightPreview() {
    SettingsPreviewShell(darkTheme = false, isRtl = false) {
        SettingsOrganizationSection(PreviewStateEnglish.preferences, SettingsStrings.english(), false, {})
    }
}

@Preview(showBackground = true)
@Composable
private fun OrganizationSectionEnglishDarkPreview() {
    SettingsPreviewShell(darkTheme = true, isRtl = false) {
        SettingsOrganizationSection(PreviewStateEnglish.preferences, SettingsStrings.english(), false, {})
    }
}
