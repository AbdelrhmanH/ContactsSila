package app.sila.feature.settings.domain

/**
 * حالة استخدام أحادية المسؤولية: تشغيل/إيقاف التأثير الترحيبي (Confetti) بعد المكالمات (SRP).
 */
class SetConfettiEnabledUseCase(
    private val repository: SettingsRepository
) {
    suspend operator fun invoke(enabled: Boolean) = repository.setConfettiEnabled(enabled)
}
