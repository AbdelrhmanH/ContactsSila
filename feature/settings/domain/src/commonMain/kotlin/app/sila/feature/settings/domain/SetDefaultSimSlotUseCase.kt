package app.sila.feature.settings.domain

/**
 * حالة استخدام أحادية المسؤولية: تعيين شريحة SIM الافتراضية للمكالمات (SRP).
 */
class SetDefaultSimSlotUseCase(
    private val repository: SettingsRepository
) {
    suspend operator fun invoke(slot: Int?) = repository.setDefaultSimSlot(slot)
}
