package app.sila.feature.settings.domain

import app.sila.core.model.AppLanguage

/**
 * حالة استخدام أحادية المسؤولية: تغيير لغة التطبيق (SRP).
 */
class SetLanguageUseCase(
    private val repository: SettingsRepository
) {
    suspend operator fun invoke(language: AppLanguage) = repository.setLanguage(language)
}
