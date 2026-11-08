package app.sila.feature.contacts.domain

import app.sila.core.common.AppDispatchers
import app.sila.core.common.AppResult
import app.sila.core.common.DataError
import app.sila.core.model.Category
import app.sila.core.model.Contact
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn

/**
 * مرشّحات الفلترة الذكية للمراجعة (T7 · Issue #28 — spec 04 §ب؛ agy F6): جهات
 * UNCATEGORIZED **غير المستثناة** (`excludeFromSmartFilter` — «لا تسألني مجدداً»)،
 * **تحت بوابة `smartFilterEnabled`** (قبول إعداد المستخدم — بلا تقييم حين تُطفأ)،
 * وعدّاد البطاقة (S11) يعدّ مطابقات القواعد حصراً لا كِل UNCATEGORIZED — وإلا فتحت
 * البطاقة شاشة فارغة.
 *
 * التغذية الراجعة المحلية (`smart_filter_feedback`): التوكن المرفوض مسيطراً
 * ([SmartFilterWeight.isRejectedDominant]) يُنزل ثقة اقتراحه درجةً — فلا يُحدد
 * مسبقاً في المراجعة (عقد «يقلل وزنه» في docs/features/04).
 *
 * الترتيب حتمي: المجموعات بترتيب أولوية العرض (عائلة، طوارئ، عمل، خدمات، ثم البقية)،
 * والبنود داخلها ثقةً تنازلياً ثم الاسم.
 */
class ObserveSmartFilterSuggestionsUseCase(
    private val repository: ContactsRepository,
    private val listPreferences: ContactsListPreferencesRepository,
    private val mergeStore: ContactsMergeStore,
    private val dispatchers: AppDispatchers,
    private val engine: SmartFilterRuleEngine = SmartFilterRuleEngine(),
) {

    operator fun invoke(): Flow<List<SmartFilterCategoryGroup>> = combine(
        repository.observeContacts(),
        listPreferences.smartFilterEnabled,
        mergeStore.observeFeedback(),
    ) { contacts, enabled, feedback ->
        if (!enabled) return@combine emptyList()
        buildGroups(contacts, feedback.associateBy { it.token to it.category })
    }
        .flowOn(dispatchers.default) // تقييم القواعد على دفعات كبيرة خارج Main (عرف الأداء)
        .distinctUntilChanged()

    private fun buildGroups(
        contacts: List<Contact>,
        feedbackByKey: Map<Pair<String, Category>, SmartFilterWeight>,
    ): List<SmartFilterCategoryGroup> {
        val items = contacts.asSequence()
            .filter { it.category == Category.UNCATEGORIZED && !it.excludeFromSmartFilter }
            .mapNotNull { contact -> engine.evaluate(contact, feedbackByKey) }
            .toList()

        return items
            .groupBy { it.category }
            .mapTo(mutableListOf()) { (category, groupItems) ->
                SmartFilterCategoryGroup(
                    category = category,
                    items = groupItems.sortedWith(
                        compareBy({ it.confidence }, { it.displayName }, { it.contactId }),
                    ),
                )
            }
            .sortedBy { group -> DISPLAY_ORDER.indexOf(group.category).let { if (it < 0) Int.MAX_VALUE else it } }
    }

    private companion object {
        val DISPLAY_ORDER = listOf(Category.FAMILY, Category.EMERGENCY, Category.WORK, Category.SERVICES)
    }
}

/**
 * تطبيق اقتراحات الفلترة الذكية بعد التأكيد الصريح (T7 · spec 04 §ب): كتابة
 * التصنيفات المحددة حصراً (تحديث جماعي بالتصنيف) + تسجيل قبول التغذية الراجعة لكل
 * (token, category) — التعلّم المحلي لا يكتب في المنصة ولا يرفع شيئاً. الفشل أثر
 * صادق [AppResult.Error] — لا نجاح زائف.
 */
class ApplySmartFilterSuggestionsUseCase(
    private val repository: ContactsRepository,
    private val mergeStore: ContactsMergeStore,
) {

    suspend operator fun invoke(items: List<SmartFilterItem>): AppResult<Unit> = try {
        if (items.isNotEmpty()) {
            items.groupBy { it.category }.forEach { (category, groupItems) ->
                repository.updateCategories(groupItems.map { it.contactId }, category)
            }
            items.forEach { item ->
                mergeStore.recordFeedback(token = item.matchedToken, category = item.category, accepted = true)
            }
        }
        AppResult.Success(Unit)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        AppResult.Error(
            DataError.Unknown(
                causeMessage = failure::class.simpleName + ": " + (failure.message ?: "no message"),
            ),
        )
    }
}

/** تسجيل تغذية راجعة سلبية («ليست هذه الفئة») — وزن التوكن ينخفض في التقييمات القادمة. */
class RecordSmartFilterFeedbackUseCase(
    private val mergeStore: ContactsMergeStore,
) {

    suspend operator fun invoke(item: SmartFilterItem, accepted: Boolean) {
        mergeStore.recordFeedback(token = item.matchedToken, category = item.category, accepted = accepted)
    }
}

/**
 * «لا تسألني مجدداً» لجهة واحدة (T7 · Issue #28 — آلية المواصفة نفسها): يكتب
 * `excludeFromSmartFilter = true` في Room (عمود صِلَة خالص — لا يمس المنصة).
 * يعود `true` إن كُتب، و`false` إن غابت الجهة.
 */
class SetExcludeFromSmartFilterUseCase(
    private val repository: ContactsRepository,
) {

    suspend operator fun invoke(contactId: Long): Boolean {
        val contact = repository.getContactById(contactId) ?: return false
        if (contact.excludeFromSmartFilter) return true
        repository.upsertContact(contact.copy(excludeFromSmartFilter = true))
        return true
    }
}
