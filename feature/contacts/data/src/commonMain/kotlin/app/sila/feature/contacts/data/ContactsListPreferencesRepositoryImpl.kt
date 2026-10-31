package app.sila.feature.contacts.data

import app.sila.core.datastore.UserPreferencesDataSource
import app.sila.core.model.ContactSwipeAction
import app.sila.feature.contacts.domain.ContactsListPreferencesRepository
import app.sila.feature.contacts.domain.SwipeActionConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * تنفيذ تفضيلات قائمة جهات الاتصال فوق DataStore (Issue #24 — استبعاد الاقتراحات
 * + إعداد أفعال السحب). القراءة تفوض مصدر التفضيلات المشترك (SSOT) — بلا أي
 * مسار كتابة مباشر خارج [UserPreferencesDataSource].
 */
class ContactsListPreferencesRepositoryImpl(
    private val dataSource: UserPreferencesDataSource,
) : ContactsListPreferencesRepository {

    override val dismissedSuggestionIds: Flow<Set<String>> =
        dataSource.preferences.map { it.dismissedSuggestionIds }

    override val swipeActions: Flow<SwipeActionConfig> =
        dataSource.preferences.map { SwipeActionConfig(start = it.swipeStartAction, end = it.swipeEndAction) }

    override suspend fun dismissSuggestion(id: String) {
        dataSource.dismissSuggestion(id)
    }

    override suspend fun setSwipeActions(config: SwipeActionConfig) {
        dataSource.setSwipeActions(start = config.start, end = config.end)
    }
}
