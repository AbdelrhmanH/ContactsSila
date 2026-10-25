package app.sila.feature.settings.domain

import app.sila.core.model.UserPreferences
import kotlinx.coroutines.flow.Flow

/**
 * حالة استخدام أحادية المسؤولية: مراقبة تفضيلات المستخدم التفاعلية (SRP).
 */
class ObserveUserPreferencesUseCase(
    private val repository: SettingsRepository
) {
    operator fun invoke(): Flow<UserPreferences> = repository.preferences
}
