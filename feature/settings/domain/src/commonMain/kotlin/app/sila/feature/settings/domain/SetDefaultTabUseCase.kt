package app.sila.feature.settings.domain

import app.sila.core.model.DefaultTab

/**
 * حالة استخدام أحادية المسؤولية: تعيين التبويب الافتراضي عند فتح التطبيق (SRP).
 */
class SetDefaultTabUseCase(
    private val repository: SettingsRepository
) {
    suspend operator fun invoke(tab: DefaultTab) = repository.setDefaultTab(tab)
}
