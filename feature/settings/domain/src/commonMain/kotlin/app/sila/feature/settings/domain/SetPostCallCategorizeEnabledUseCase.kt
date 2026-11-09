package app.sila.feature.settings.domain

/**
 * حالة استخدام أحادية المسؤولية: تشغيل/إيقاف تصنيف ما بعد المكالمة تلقائياً (SRP).
 */
class SetPostCallCategorizeEnabledUseCase(
    private val repository: SettingsRepository
) {
    suspend operator fun invoke(enabled: Boolean) = repository.setPostCallCategorizeEnabled(enabled)
}
