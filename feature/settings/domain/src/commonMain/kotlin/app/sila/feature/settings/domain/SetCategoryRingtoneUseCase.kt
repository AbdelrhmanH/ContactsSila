package app.sila.feature.settings.domain

import app.sila.core.model.Category

/**
 * يحدد نغمة تصنيف معينة ويطبقها على جميع الأعضاء الذين ليس لديهم نغمة فردية (PRD §11).
 */
class SetCategoryRingtoneUseCase(
    private val repository: SettingsRepository,
    private val platformRingtoneSource: PlatformRingtoneSource
) {
    suspend operator fun invoke(category: Category, ringtoneUri: String?) {
        repository.setCategoryRingtone(category, ringtoneUri)
        platformRingtoneSource.applyCategoryRingtone(category, ringtoneUri)
    }
}
