package app.sila.feature.contacts.domain

import app.sila.core.model.ContactSwipeAction
import kotlinx.coroutines.flow.Flow

/**
 * تفضيلات قائمة جهات الاتصال (Issue #24): استبعاد بطاقات الاقتراح + إعداد أفعال السحب.
 * عقد نطاق (DIP) — التنفيذ في `:feature:contacts:data` فوق DataStore.
 */
interface ContactsListPreferencesRepository {

    /** معرّفات بطاقات الاقتراح المُستبعدة نهائياً. */
    val dismissedSuggestionIds: Flow<Set<String>>

    /** استبعاد بطاقة نهائياً (لا تعود لنفس العناصر). */
    suspend fun dismissSuggestion(id: String)

    /** أفعال السحب الحالية (بداية/نهاية منطقيتان). */
    val swipeActions: Flow<SwipeActionConfig>

    /** ضبط أفعال السحب. */
    suspend fun setSwipeActions(config: SwipeActionConfig)
}

/** إعداد أفعال السحب (يمين/يسار منطقياً — ينعكس في RTL تلقائياً). */
data class SwipeActionConfig(
    val start: ContactSwipeAction,
    val end: ContactSwipeAction
)
