package app.sila.feature.settings.domain

import app.sila.core.model.AppDigits
import app.sila.core.model.AppLanguage
import app.sila.core.model.AppThemeMode
import app.sila.core.model.Category
import app.sila.core.model.Contact
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

    /**
     * بحث مرشّحي الربط اليدوي لسجلات الاستعادة غير المطابقة (S1-15 — تذكرة #62):
     * قراءة من Room حصراً (SSOT) عبر بحث FTS/الأرقام؛ استعلام فارغ أو أقصر من حرفين
     * لا يعيد شيئاً (عرض كامل دفتر العناوين قائمة غير عملية للربط).
     */
    suspend fun searchLinkCandidates(query: String): List<Contact>

    /**
     * يطبّق إدخالاً غير مطابق على جهة اختارها المستخدم يدوياً (S1-15 — تذكرة #62):
     * التصنيف (إن وجد) وصلة الرحم (إن وجدت) تُطبَّق على جهة الهدف؛ الطبقة تُستنتَج
     * من العلاقة ذاتها (`Relation.layer`) والتكرار يبدأ `OCCASIONS` (محايد بلا جدولة
     * مؤكدة بعد الربط اليدوي — يعدّله المستخدم لاحقاً من شاشة الأرحام).
     * يعيد false عند غياب جهة الهدف (حُذفت)، أو إذا لم يكن في السجل تصنيف ولا صلة رحم.
     */
    suspend fun linkUnmatchedEntry(
        entry: UnmatchedBackupEntry,
        targetContactId: Long
    ): Boolean
}
