package app.sila.feature.settings.domain

import app.sila.core.model.AppDigits
import app.sila.core.model.AppLanguage
import app.sila.core.model.AppThemeMode
import app.sila.core.model.Category
import app.sila.core.model.ContactSwipeAction
import app.sila.core.model.DashboardPeriod
import app.sila.core.model.DefaultTab
import app.sila.core.model.NameOrder
import app.sila.core.model.UserPreferences
import kotlinx.coroutines.flow.Flow

/**
 * عقد مستودع الإعدادات والتفضيلات (طبقة Domain داخل :feature:settings:domain — DIP).
 */
interface SettingsRepository {
    val preferences: Flow<UserPreferences>
    suspend fun setThemeMode(mode: AppThemeMode)
    suspend fun setLanguage(language: AppLanguage)
    suspend fun setDigits(digits: AppDigits)
    suspend fun setNameOrder(order: NameOrder)
    suspend fun setDefaultTab(tab: DefaultTab)
    suspend fun setVisibleAccounts(accounts: Set<String>)
    suspend fun setDefaultSimSlot(slot: Int?)
    suspend fun setArhamNotificationsEnabled(enabled: Boolean)
    suspend fun setArhamNotificationTime(hour: Int, minute: Int)
    suspend fun setArhamMaxPerDay(max: Int)
    suspend fun setQuietHours(startHour: Int, endHour: Int)
    suspend fun setDashboardPeriod(period: DashboardPeriod)
    suspend fun setConfettiEnabled(enabled: Boolean)
    suspend fun setAutoTrackingEnabled(enabled: Boolean)
    suspend fun setDefaultCity(city: String?)
    suspend fun setSearchRadiusKm(radiusKm: Int)
    suspend fun setQuickRatingEnabled(enabled: Boolean)
    suspend fun setAutoUpdatePhonebookServices(enabled: Boolean)
    suspend fun setSmartFilterEnabled(enabled: Boolean)
    suspend fun setDuplicateScanEnabled(enabled: Boolean)
    suspend fun setPostCallCategorizeEnabled(enabled: Boolean)
    suspend fun setCrashReportsEnabled(enabled: Boolean)
    suspend fun setInterestTags(tags: Set<String>)
    suspend fun setSwipeActions(start: ContactSwipeAction, end: ContactSwipeAction)
    suspend fun setCategoryRingtone(category: Category, uri: String?)
    suspend fun setCategoryRingtones(ringtones: Map<Category, String>)

    /**
     * يجمع بيانات التطبيق المسموح بنسخها احتياطياً (التصنيفات، أرحام، الوسوم، التفضيلات، الأتمتة، النزاعات)
     * مع استبعاد جهات الاتصال وسجلات المكالمات كلياً.
     */
    suspend fun getBackupData(): BackupPayload

    /**
     * يستعيد بيانات النسخة الاحتياطية مع مطابقة جهات الاتصال عبر lookupKey
     * وتطبيق وضع الدمج (MERGE) أو الاستبدال (REPLACE)، وسؤال المستخدم حول استعادة التفضيلات.
     */
    suspend fun restoreBackupData(
        payload: BackupPayload,
        mode: RestoreMode,
        restorePreferences: Boolean
    ): RestoreResult
}
