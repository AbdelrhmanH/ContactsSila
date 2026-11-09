package app.sila.feature.settings.domain

/**
 * حالة استخدام أحادية المسؤولية: تشغيل/إيقاف التقييم السريع بعد المكالمات (SRP).
 */
class SetQuickRatingEnabledUseCase(
    private val repository: SettingsRepository
) {
    suspend operator fun invoke(enabled: Boolean) = repository.setQuickRatingEnabled(enabled)
}
