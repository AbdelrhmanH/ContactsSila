package app.sila.feature.settings.domain

import app.sila.core.model.AppDigits
import app.sila.core.model.AppLanguage
import app.sila.core.model.AppThemeMode
import app.sila.core.model.ContactSwipeAction
import app.sila.core.model.DashboardPeriod
import app.sila.core.model.DefaultTab
import app.sila.core.model.NameOrder
import app.sila.core.model.UserPreferences
import kotlinx.coroutines.flow.Flow

/**
 * بوابة نطاق ضيقة لكل عمليات تفضيلات الإعدادات (تذكرة #66 · ADR-0014) — الواجهة الوحيدة
 * التي يعتمدها [app.sila.feature.settings.presentation.SettingsViewModel] للتفضيلات:
 * مراقبة القراءة عبر [ObserveUserPreferencesUseCase] وجميع الكتابات عبر حالات استخدام
 * أحادية المسؤولية (نمط `Set*UseCase`) — **لا مستودع مباشرة من الـ ViewModel**، و**لا
 * مسار مزدوج** (`useCase ?: repository`) — المسار واحد حصراً وهو الـ UseCase.
 */
interface SettingsPreferencesPort {
    /** تدفق التفضيلات الحية من [ObserveUserPreferencesUseCase]. */
    fun observe(): Flow<UserPreferences>

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
    suspend fun setDuplicateScanEnabled(enabled: Boolean)
    suspend fun setPostCallCategorizeEnabled(enabled: Boolean)
    suspend fun setCrashReportsEnabled(enabled: Boolean)
    suspend fun setSmartFilterEnabled(enabled: Boolean)
    suspend fun setDefaultCity(city: String?)
    suspend fun setSearchRadiusKm(radiusKm: Int)
    suspend fun setQuickRatingEnabled(enabled: Boolean)
    suspend fun setAutoUpdatePhonebookServices(enabled: Boolean)
    suspend fun setInterestTags(tags: Set<String>)
    suspend fun setSwipeActions(start: ContactSwipeAction, end: ContactSwipeAction)
}

/**
 * تنفيذ البوابة فوق ميزة Settings نفسها: يجمّع حالات استخدام الكتابة الفردية (نمط
 * `Set*UseCase`) فوق [SettingsRepository] — نقطة التركيب الوحيدة لعدّاتها (ADR-0014 §2)،
 * فكُلّ مسار كتابة يمر عبر UseCase حقيقية لا عبر المستودع مباشرة.
 */
class SettingsPreferencesPortImpl(
    repository: SettingsRepository
) : SettingsPreferencesPort {
    private val observePreferencesUseCase = ObserveUserPreferencesUseCase(repository)
    private val setThemeModeUseCase = SetThemeModeUseCase(repository)
    private val setLanguageUseCase = SetLanguageUseCase(repository)
    private val setDigitsUseCase = SetDigitsUseCase(repository)
    private val setNameOrderUseCase = SetNameOrderUseCase(repository)
    private val setDefaultTabUseCase = SetDefaultTabUseCase(repository)
    private val setVisibleAccountsUseCase = SetVisibleAccountsUseCase(repository)
    private val setDefaultSimSlotUseCase = SetDefaultSimSlotUseCase(repository)
    private val setArhamNotificationsUseCase = SetArhamNotificationsUseCase(repository)
    private val setArhamNotificationTimeUseCase = SetArhamNotificationTimeUseCase(repository)
    private val setArhamMaxPerDayUseCase = SetArhamMaxPerDayUseCase(repository)
    private val setQuietHoursUseCase = SetQuietHoursUseCase(repository)
    private val setDashboardPeriodUseCase = SetDashboardPeriodUseCase(repository)
    private val setConfettiEnabledUseCase = SetConfettiEnabledUseCase(repository)
    private val setAutoTrackingEnabledUseCase = SetAutoTrackingEnabledUseCase(repository)
    private val setDuplicateScanUseCase = SetDuplicateScanUseCase(repository)
    private val setPostCallCategorizeUseCase = SetPostCallCategorizeEnabledUseCase(repository)
    private val setCrashReportsUseCase = SetCrashReportsUseCase(repository)
    private val setSmartFilterUseCase = SetSmartFilterUseCase(repository)
    private val setDefaultCityUseCase = SetDefaultCityUseCase(repository)
    private val setSearchRadiusKmUseCase = SetSearchRadiusKmUseCase(repository)
    private val setQuickRatingUseCase = SetQuickRatingEnabledUseCase(repository)
    private val setAutoUpdateServicesUseCase = SetAutoUpdatePhonebookServicesUseCase(repository)
    private val setInterestTagsUseCase = SetInterestTagsUseCase(repository)
    private val setSwipeActionsUseCase = SetSwipeActionsUseCase(repository)

    override fun observe(): Flow<UserPreferences> = observePreferencesUseCase()

    override suspend fun setThemeMode(mode: AppThemeMode) = setThemeModeUseCase(mode)
    override suspend fun setLanguage(language: AppLanguage) = setLanguageUseCase(language)
    override suspend fun setDigits(digits: AppDigits) = setDigitsUseCase(digits)
    override suspend fun setNameOrder(order: NameOrder) = setNameOrderUseCase(order)
    override suspend fun setDefaultTab(tab: DefaultTab) = setDefaultTabUseCase(tab)
    override suspend fun setVisibleAccounts(accounts: Set<String>) = setVisibleAccountsUseCase(accounts)
    override suspend fun setDefaultSimSlot(slot: Int?) = setDefaultSimSlotUseCase(slot)
    override suspend fun setArhamNotificationsEnabled(enabled: Boolean) = setArhamNotificationsUseCase(enabled)
    override suspend fun setArhamNotificationTime(hour: Int, minute: Int) = setArhamNotificationTimeUseCase(hour, minute)
    override suspend fun setArhamMaxPerDay(max: Int) = setArhamMaxPerDayUseCase(max)
    override suspend fun setQuietHours(startHour: Int, endHour: Int) = setQuietHoursUseCase(startHour, endHour)
    override suspend fun setDashboardPeriod(period: DashboardPeriod) = setDashboardPeriodUseCase(period)
    override suspend fun setConfettiEnabled(enabled: Boolean) = setConfettiEnabledUseCase(enabled)
    override suspend fun setAutoTrackingEnabled(enabled: Boolean) = setAutoTrackingEnabledUseCase(enabled)
    override suspend fun setDuplicateScanEnabled(enabled: Boolean) = setDuplicateScanUseCase(enabled)
    override suspend fun setPostCallCategorizeEnabled(enabled: Boolean) = setPostCallCategorizeUseCase(enabled)
    override suspend fun setCrashReportsEnabled(enabled: Boolean) = setCrashReportsUseCase(enabled)
    override suspend fun setSmartFilterEnabled(enabled: Boolean) = setSmartFilterUseCase(enabled)
    override suspend fun setDefaultCity(city: String?) = setDefaultCityUseCase(city)
    override suspend fun setSearchRadiusKm(radiusKm: Int) = setSearchRadiusKmUseCase(radiusKm)
    override suspend fun setQuickRatingEnabled(enabled: Boolean) = setQuickRatingUseCase(enabled)
    override suspend fun setAutoUpdatePhonebookServices(enabled: Boolean) = setAutoUpdateServicesUseCase(enabled)
    override suspend fun setInterestTags(tags: Set<String>) = setInterestTagsUseCase(tags)
    override suspend fun setSwipeActions(start: ContactSwipeAction, end: ContactSwipeAction) =
        setSwipeActionsUseCase(start, end)
}
