package app.sila.feature.settings.domain

import app.sila.core.model.Category
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * يراقب خريطة نغمات التصنيفات المسجلة في تفضيلات المستخدم (PRD §11).
 */
class ObserveCategoryRingtonesUseCase(
    private val repository: SettingsRepository
) {
    operator fun invoke(): Flow<Map<Category, String>> =
        repository.preferences.map { it.categoryRingtones }
}
