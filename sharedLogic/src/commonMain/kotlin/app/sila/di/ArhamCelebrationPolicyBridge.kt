package app.sila.di

import app.sila.core.datastore.UserPreferencesDataSource
import app.sila.feature.arham.domain.ArhamCelebrationPolicy
import kotlinx.coroutines.flow.first

/**
 * جسر سياسة الاحتفال (T8-06 · DIP — خطة §D.3-5): ينفّذ بوابة النطاق
 * [ArhamCelebrationPolicy] فوق مصدر التفضيلات. جذر التركيب هو المكان الوحيد
 * المسموح لمعرفة DataStore في سياق أرحام (سبق ArhamOccasionBridge) — لا منطق
 * أعمال هنا: الفحص والقرار في `CelebrateTransitionUseCase`.
 */
class ArhamCelebrationPolicyBridge(
    private val userPreferencesDataSource: UserPreferencesDataSource,
) : ArhamCelebrationPolicy {

    override suspend fun isConfettiEnabled(): Boolean =
        userPreferencesDataSource.preferences.first().confettiEnabled

    override suspend fun celebratedPeriodKey(contactId: Long): Long? =
        userPreferencesDataSource.preferences.first().celebratedArhamTransitions[contactId]

    override suspend fun markCelebrated(contactId: Long, periodKey: Long) {
        userPreferencesDataSource.markArhamTransitionCelebrated(contactId = contactId, periodKey = periodKey)
    }
}
