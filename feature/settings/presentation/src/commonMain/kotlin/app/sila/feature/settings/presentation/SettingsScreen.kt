package app.sila.feature.settings.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import app.sila.core.designsystem.SilaDimens
import app.sila.core.designsystem.SilaSettingsGroup
import app.sila.core.designsystem.SilaSettingsItem
import app.sila.core.model.AppLanguage
import app.sila.core.model.AppThemeMode
import app.sila.core.model.UserPreferences

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
        preferences = state.preferences,
        isArabic = isArabic,
        onToggleLanguage = {
            val next = if (isArabic) AppLanguage.ENGLISH else AppLanguage.ARABIC
            viewModel.onIntent(SettingsIntent.SetLanguage(next))
        },
        onChangeThemeMode = { viewModel.onIntent(SettingsIntent.SetThemeMode(it)) },
        onToggleArhamNotifications = { viewModel.onIntent(SettingsIntent.SetArhamNotifications(it)) },
        onToggleSmartFilter = { viewModel.onIntent(SettingsIntent.SetSmartFilter(it)) },
        onDismiss = onDismiss
    )
}

/**
 * واجهة ميزة الإعدادات والتفضيلات Stateless (:feature:settings:presentation).
 */
@Composable
fun SettingsDialog(
    preferences: UserPreferences,
    isArabic: Boolean,
    onToggleLanguage: () -> Unit,
    onChangeThemeMode: (AppThemeMode) -> Unit,
    onToggleArhamNotifications: (Boolean) -> Unit,
    onToggleSmartFilter: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(SilaDimens.SheetCornerRadius),
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxWidth().padding(SilaDimens.Space16)
        ) {
            Column(modifier = Modifier.padding(SilaDimens.Space16)) {
                Text(
                    text = if (isArabic) "الإعدادات والتفضيلات" else "Settings & Preferences",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = SilaDimens.Space16)
                )

                SilaSettingsGroup(title = if (isArabic) "المظهر واللغة" else "Appearance & Language") {
                    SilaSettingsItem(
                        iconEmoji = "🌐",
                        title = if (isArabic) "اللغة" else "Language",
                        value = if (isArabic) "العربية" else "English",
                        onClick = onToggleLanguage,
                        showDivider = true
                    )
                    SilaSettingsItem(
                        iconEmoji = "🎨",
                        title = if (isArabic) "الوضع الداكن" else "Dark Theme",
                        toggle = preferences.themeMode == AppThemeMode.DARK,
                        onToggle = { enabled ->
                            onChangeThemeMode(if (enabled) AppThemeMode.DARK else AppThemeMode.LIGHT)
                        }
                    )
                }

                SilaSettingsGroup(title = if (isArabic) "أرحام والتنظيم" else "Arham & Organization") {
                    SilaSettingsItem(
                        iconEmoji = "🔔",
                        title = if (isArabic) "تذكيرات صلة الرحم" else "Arham Reminders",
                        toggle = preferences.arhamNotificationsEnabled,
                        onToggle = onToggleArhamNotifications,
                        showDivider = true
                    )
                    SilaSettingsItem(
                        iconEmoji = "✨",
                        title = if (isArabic) "الفلترة الذكية للأرقام" else "Smart Contacts Filter",
                        toggle = preferences.smartFilterEnabled,
                        onToggle = onToggleSmartFilter
                    )
                }

                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isArabic) "إغلاق" else "Close")
                }
            }
        }
    }
}
