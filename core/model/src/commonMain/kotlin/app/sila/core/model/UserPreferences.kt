package app.sila.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class AppThemeMode {
    LIGHT,
    DARK,
    SYSTEM
}

@Serializable
enum class AppLanguage(val code: String, val isRtl: Boolean) {
    ARABIC("ar", true),
    ENGLISH("en", false)
}

@Serializable
enum class DefaultTab {
    CONTACTS,
    SERVICES,
    ARHAM
}

@Serializable
data class UserPreferences(
    val themeMode: AppThemeMode = AppThemeMode.LIGHT,
    val language: AppLanguage = AppLanguage.ARABIC,
    val defaultTab: DefaultTab = DefaultTab.CONTACTS,
    val arhamNotificationsEnabled: Boolean = true,
    val quietHoursStartHour: Int = 23,
    val quietHoursEndHour: Int = 8,
    val confettiEnabled: Boolean = true,
    val searchRadiusKm: Int = 5,
    val autoUpdatePhonebookServices: Boolean = false,
    val smartFilterEnabled: Boolean = true,
    val onboardingCompleted: Boolean = false
)
