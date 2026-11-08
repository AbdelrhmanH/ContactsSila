package app.sila.feature.settings.domain

import app.sila.core.model.AppDigits
import app.sila.core.model.AppLanguage
import app.sila.core.model.AppThemeMode
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.AutomationType
import app.sila.core.model.Category
import app.sila.core.model.ContactSwipeAction
import app.sila.core.model.DashboardPeriod
import app.sila.core.model.DefaultTab
import app.sila.core.model.DisputeStatus
import app.sila.core.model.NameOrder
import app.sila.core.model.Relation
import kotlinx.serialization.Serializable

@Serializable
data class BackupCategoryEntry(
    val lookupKey: String,
    val displayName: String,
    val category: Category
)

@Serializable
data class BackupArhamEntry(
    val lookupKey: String,
    val relation: Relation,
    val frequency: ArhamFrequency,
    val layer: app.sila.core.model.KinshipLayer = relation.layer,
    val parentLookupKey: String? = null,
    val lastContactedAt: Long? = null,
    val pausedUntil: Long? = null,
    val dates: List<app.sila.core.model.ImportantDate> = emptyList()
)

@Serializable
data class BackupTagEntry(
    val lookupKey: String,
    val tags: List<String>
)

@Serializable
data class BackupPreferencesEntry(
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val language: AppLanguage = AppLanguage.ARABIC,
    val digits: AppDigits = AppDigits.ARABIC,
    val nameOrder: NameOrder = NameOrder.FIRST_LAST,
    val defaultTab: DefaultTab = DefaultTab.CONTACTS,
    val visibleAccounts: Set<String> = emptySet(),
    val defaultSimSlot: Int? = null,
    val arhamNotificationsEnabled: Boolean = true,
    val quietHoursStartHour: Int = 22,
    val quietHoursEndHour: Int = 8,
    val arhamNotificationHour: Int = 19,
    val arhamNotificationMinute: Int = 0,
    val arhamMaxPerDay: Int = 1,
    val arhamOccasionDayBeforeEnabled: Boolean = false,
    val dashboardPeriod: DashboardPeriod = DashboardPeriod.WEEK,
    val confettiEnabled: Boolean = true,
    val autoTrackingEnabled: Boolean = true,
    val defaultCity: String? = null,
    val searchRadiusKm: Int = 5,
    val quickRatingEnabled: Boolean = true,
    val autoUpdatePhonebookServices: Boolean = false,
    val smartFilterEnabled: Boolean = true,
    val duplicateScanEnabled: Boolean = true,
    val postCallCategorizeEnabled: Boolean = true,
    val crashReportsEnabled: Boolean = true,
    val interestTags: Set<String> = emptySet(),
    val swipeStartAction: ContactSwipeAction = ContactSwipeAction.CALL,
    val swipeEndAction: ContactSwipeAction = ContactSwipeAction.MESSAGE
)

@Serializable
data class BackupAutomationEntry(
    val contactLookupKey: String,
    val type: AutomationType,
    val messageText: String? = null,
    val recurrenceJson: String = ""
)

@Serializable
data class BackupDisputeEntry(
    val serviceCloudId: String,
    val description: String,
    val status: DisputeStatus = DisputeStatus.OPEN
)

/**
 * بنية ملف النسخ الاحتياطي المشفر (.sila.backup) طبقاً لـ (PRD §12 ومواصفة 07-ringtones-preferences.md).
 * 🔒 تستبعد جهات الاتصال وسجلات المكالمات لضمان الخصوصية وسرعة النقل.
 */
@Serializable
data class BackupPayload(
    val version: Int = 1,
    val exportedAt: String,
    val appVersion: String = "1.0.0",
    val categories: List<BackupCategoryEntry> = emptyList(),
    val arham: List<BackupArhamEntry> = emptyList(),
    val tags: List<BackupTagEntry> = emptyList(),
    val preferences: BackupPreferencesEntry? = null,
    val categoryRingtones: Map<Category, String> = emptyMap(),
    val automations: List<BackupAutomationEntry> = emptyList(),
    val disputes: List<BackupDisputeEntry> = emptyList()
)

data class BackupPreview(
    val version: Int,
    val exportedAt: String,
    val appVersion: String,
    val categoriesCount: Int,
    val arhamCount: Int,
    val tagsCount: Int,
    val hasPreferences: Boolean,
    val categoryRingtonesCount: Int,
    val automationsCount: Int,
    val disputesCount: Int
)

enum class RestoreMode {
    MERGE,
    REPLACE
}

data class UnmatchedBackupEntry(
    val lookupKey: String,
    val displayName: String,
    val category: Category?,
    val arhamRelation: Relation?
)

data class RestoreResult(
    val matchedCategories: Int,
    val unmatchedCategories: Int,
    val matchedArham: Int,
    val unmatchedArham: Int,
    val restoredPreferences: Boolean,
    val unmatchedEntries: List<UnmatchedBackupEntry>
)
