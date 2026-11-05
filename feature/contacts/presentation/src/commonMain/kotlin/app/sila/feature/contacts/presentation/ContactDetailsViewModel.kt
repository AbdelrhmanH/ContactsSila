package app.sila.feature.contacts.presentation

import androidx.lifecycle.viewModelScope
import app.sila.core.common.AppClock
import app.sila.core.common.AppResult
import app.sila.core.common.BaseMviViewModel
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.RecentInteraction
import app.sila.feature.contacts.domain.ConnectedApp
import app.sila.feature.contacts.domain.ConnectedAppsDetector
import app.sila.feature.contacts.domain.DeleteContactWithUndoUseCase
import app.sila.feature.contacts.domain.ObserveContactDetailsUseCase
import app.sila.feature.contacts.domain.RecordInAppCallUseCase
import app.sila.feature.contacts.domain.ToggleFavoriteUseCase
import app.sila.feature.contacts.domain.UpdateContactCategoryUseCase
import app.sila.feature.contacts.domain.VCardGenerator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * حالة شاشة تفاصيل جهة الاتصال (T6 · Issue #27 · docs/features/03-contact-details-edit.md §أ).
 *
 * **عقد مشترك (Shared contract):** تستهلكه T4 (ربط iOS) و T6 (الشاشة المشتركة) —
 * أي تغيير في حقوله تغيير عقد مشترك يتطلب مراجعة منسّقة.
 *
 * الحقول الفارغة لا تُعرض (معيار قبول) — العرض يفحص أنواع البيانات ذاتها، فلا تحمل
 * الحالة إلا ما هو موجود فعلاً (بلا حقول placeholder).
 */
data class ContactDetailsUiState(
    val isLoading: Boolean = true,
    /** `null` بعد انتهاء التحميل = الجهة غير موجودة (قبر/حُذفت) — العرض يعود للقائمة. */
    val contact: Contact? = null,
    /** «التفاعلات الأخيرة» — قراءة Room حصراً (بلا READ_CALL_LOG في مسار التفاصيل). */
    val recentInteractions: List<RecentInteraction> = emptyList(),
    /** التطبيقات المتصلة المثبتة فعلاً — أزرارها تظهر لها فقط (AC). */
    val connectedApps: List<ConnectedApp> = emptyList(),
    /** نافذة التراجع نشطة — شريط تراجع يظهر والحذف النهائي مؤجل (J-5). */
    val isUndoWindowActive: Boolean = false,
    /** هدف أرحام (الوتيرة) إن وُجد رابط — صف «أرحام» في التخطيط. */
    val arhamFrequency: ArhamFrequency? = null,
    /** أيام منذ آخر اتصال أرحام — `null` بلا اتصال/رابط (تُحسب لحظة البناء بساعة النطاق). */
    val arhamDaysSinceLastContact: Long? = null,
) {
    val isNotFound: Boolean get() = !isLoading && contact == null
}

/** نوايا شاشة التفاصيل (T6) — العرض يُرسل ولا يحمل منطق أعمال. */
sealed interface ContactDetailsIntent {
    data class ToggleFavorite(val contactId: Long, val isFavorite: Boolean) : ContactDetailsIntent
    data class ChangeCategory(val contactId: Long, val category: Category) : ContactDetailsIntent

    /** اتصال بالرقم الافتراضي (الأساسي ثم الوحيد) — ضغطة مطولة لاختيار رقم في العرض (AC). */
    data class Call(val contactId: Long) : ContactDetailsIntent
    data class CallNumber(val e164: String) : ContactDetailsIntent
    data class Message(val e164: String) : ContactDetailsIntent
    data class Email(val address: String) : ContactDetailsIntent
    data class OpenWebsite(val url: String) : ContactDetailsIntent
    data class OpenConnectedApp(val app: ConnectedApp, val e164: String) : ContactDetailsIntent

    data class Edit(val contactId: Long) : ContactDetailsIntent
    data class ShareVCard(val contactId: Long) : ContactDetailsIntent

    /** يبدأ نافذة التراجع 5 ثوانٍ — لا كتابة في أي طرف حتى انصرافها (J-5). */
    data object RequestDelete : ContactDetailsIntent

    /** تراجع خلال النافذة — لا يكتب شيئاً في أي طرف (J-5). */
    data object UndoDelete : ContactDetailsIntent

    /** زر الرجوع — يُرسل [ContactDetailsEffect.NavigateBack] للغلاف. */
    data object Back : ContactDetailsIntent

    /**
     * بدء رنين الطوارئ (T8-11 · docs/features/06 §102-103) — جهة [Category.EMERGENCY]
     * حصراً بعد حوار تحذيري. **مرورٌ رقيق فقط:** تنفيذ الاستخدام ملكُ غلاف المضيف
     * (الميزات لا تعتمد بعضها — AGENTS.md/ADR-0007)؛ النتيجة هادئة عبر Room SSOT.
     */
    data class EmergencyRing(val contactId: Long) : ContactDetailsIntent
}

/** آثار جانبية منصات لغلاف التفاصيل (تُجمَع في androidApp/T4) — بلا منطق أعمال. */
sealed interface ContactDetailsEffect {
    data class OpenDialer(val e164: String) : ContactDetailsEffect
    data class OpenSms(val e164: String) : ContactDetailsEffect
    data class OpenEmail(val address: String) : ContactDetailsEffect
    data class OpenUrl(val url: String) : ContactDetailsEffect

    /** فتح تطبيق متصل بمخططه — الرقم الكامل يُبنى في العرض بترقيم الجهة. */
    data class OpenExternalApp(val scheme: String, val e164: String) : ContactDetailsEffect

    data class NavigateToEdit(val contactId: Long) : ContactDetailsEffect
    data object NavigateBack : ContactDetailsEffect

    /** مشاركة vCard نصاً — الحقول المُدارة حصراً (خصوصية J-7/ADR-0012). */
    data class ShareVCard(val vcardText: String) : ContactDetailsEffect

    /** فشل الحذف النهائي بعد انصراف النافذة — عرض خطأ مُترجم. */
    data object ShowDeleteError : ContactDetailsEffect

    /**
     * طلب بدء رنين الطوارئ (T8-11) — [contactId] يُسلَّم للمضيف الذي يملك بوابة
     * `CALL_PHONE` وتنفيذ `StartEmergencyRingingUseCase` (فصل الميزات).
     */
    data class EmergencyRing(val contactId: Long) : ContactDetailsEffect
}

/**
 * ViewModel شاشة تفاصيل جهة الاتصال (T6 · Issue #27) — MVI/UDF (ARCHITECTURE.md §4).
 *
 * - الحالة تُبث تفاعلياً من كاش Room (SSOT) عبر [ObserveContactDetailsUseCase] —
 *   تعديلٌ خارجي (مزامنة) ينعكس فوراً بلا إعادة تحميل، والتفاعلات الأخيرة تُقرأ
 *   مع كل إصدار جهة (كاش E.164 — قراءة رخيصة).
 * - **الحذف (J-5):** [ContactDetailsIntent.RequestDelete] يبدأ عدّاً 5 ثوانٍ في هذا
 *   الـ VM (شريط تراجع). التراجع يُلغِي المؤقت بلا أي كتابة؛ الانصراف ينفّذ الحذف
 *   عبر [DeleteContactWithUndoUseCase] (منصة أولاً ثم قبر Room — ADR-0012). إزالة
 *   الشاشة أثناء النافذة تُلغيها بلا كتابة (تراجع ضمني — قرار J-5).
 * - التطبيقات المتصلة تُكتشف مرة عند فتح الشاشة (عملية نادرة — عقد [ConnectedAppsDetector]).
 */
class ContactDetailsViewModel(
    private val contactId: Long,
    private val observeDetails: ObserveContactDetailsUseCase,
    private val deleteWithUndo: DeleteContactWithUndoUseCase,
    private val toggleFavorite: ToggleFavoriteUseCase,
    private val changeCategory: UpdateContactCategoryUseCase,
    private val recordInAppCall: RecordInAppCallUseCase,
    private val connectedAppsDetector: ConnectedAppsDetector,
    private val appClock: AppClock,
) : BaseMviViewModel<ContactDetailsUiState, ContactDetailsIntent, ContactDetailsEffect>(
    ContactDetailsUiState()
) {

    private val undoWindowActive = MutableStateFlow(false)
    private val connectedAppsFlow = MutableStateFlow<List<ConnectedApp>>(emptyList())
    private var undoJob: Job? = null
    private var contactForUndo: Contact? = null

    init {
        // اكتشاف التطبيقات المتصلة مرة واحدة عند إنشاء الشاشة — فارغة = الأزرار الأساسية حصراً.
        viewModelScope.launch {
            connectedAppsFlow.value = runCatching { connectedAppsDetector.detect() }.getOrNull().orEmpty()
        }
    }

    override val state: StateFlow<ContactDetailsUiState> = combine(
        observeDetails(contactId),
        undoWindowActive,
        connectedAppsFlow,
    ) { contact, undoActive, apps ->
        val interactions: List<RecentInteraction> = if (contact == null) {
            emptyList()
        } else {
            runCatching { observeDetails.recentInteractions(contact) }.getOrNull().orEmpty()
        }
        ContactDetailsUiState(
            isLoading = false,
            contact = contact,
            recentInteractions = interactions,
            connectedApps = apps,
            isUndoWindowActive = undoActive,
            // صف «أرحام» — الوتيرة والهدف، وأيام آخر اتصال تُحسب لحظة الإصدار.
            arhamFrequency = contact?.arham?.frequency,
            arhamDaysSinceLastContact = contact?.arham?.lastContactedAt?.let { lastContactedAt ->
                ((appClock.nowEpochMillis() - lastContactedAt) / MILLIS_PER_DAY).coerceAtLeast(0)
            },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ContactDetailsUiState(),
    )

    override fun onIntent(intent: ContactDetailsIntent) {
        when (intent) {
            is ContactDetailsIntent.ToggleFavorite -> viewModelScope.launch {
                toggleFavorite(intent.contactId, intent.isFavorite)
            }
            is ContactDetailsIntent.ChangeCategory -> viewModelScope.launch {
                changeCategory(intent.contactId, intent.category)
            }
            is ContactDetailsIntent.Call -> resolveCall()
            is ContactDetailsIntent.CallNumber -> viewModelScope.launch {
                recordInAppCall(intent.e164)
                sendEffect(ContactDetailsEffect.OpenDialer(intent.e164))
            }
            is ContactDetailsIntent.Message -> sendEffect(ContactDetailsEffect.OpenSms(intent.e164))
            is ContactDetailsIntent.Email -> sendEffect(ContactDetailsEffect.OpenEmail(intent.address))
            is ContactDetailsIntent.OpenWebsite -> sendEffect(ContactDetailsEffect.OpenUrl(intent.url))
            is ContactDetailsIntent.OpenConnectedApp ->
                sendEffect(ContactDetailsEffect.OpenExternalApp(intent.app.scheme, intent.e164))
            is ContactDetailsIntent.Edit -> sendEffect(ContactDetailsEffect.NavigateToEdit(intent.contactId))
            is ContactDetailsIntent.ShareVCard -> latestContact()?.let { contact ->
                sendEffect(ContactDetailsEffect.ShareVCard(VCardGenerator.generate(contact)))
            }
            ContactDetailsIntent.RequestDelete -> startUndoWindow()
            ContactDetailsIntent.UndoDelete -> cancelUndoWindow()
            ContactDetailsIntent.Back -> sendEffect(ContactDetailsEffect.NavigateBack)
            is ContactDetailsIntent.EmergencyRing -> sendEffect(ContactDetailsEffect.EmergencyRing(intent.contactId))
        }
    }

    /** الرقم الافتراضي: الأساسي ثم الوحيد؛ بلا رقم أو تعددٌ بلا أساسي — لا فعل هنا
     *  (العرض يفتح منتقي الأرقام ويُرسل [ContactDetailsIntent.CallNumber] مباشرة). */
    private fun resolveCall() {
        val contact = latestContact() ?: return
        val phones = contact.phones
        val primary = phones.firstOrNull { it.isPrimary } ?: phones.singleOrNull()
        if (primary != null) {
            onIntent(ContactDetailsIntent.CallNumber(primary.e164))
        }
    }

    /** نافذة التراجع (J-5): عدّ 5 ثوانٍ في الـ VM؛ الانصراف ينفّذ الحذف النهائي حصراً. */
    private fun startUndoWindow() {
        val contact = latestContact() ?: return
        contactForUndo = contact
        undoJob?.cancel()
        undoWindowActive.value = true
        undoJob = viewModelScope.launch {
            delay(DeleteContactWithUndoUseCase.UNDO_WINDOW_MILLIS)
            val target = contactForUndo
            undoWindowActive.value = false
            contactForUndo = null
            if (target != null) {
                when (deleteWithUndo(target)) {
                    is AppResult.Success -> sendEffect(ContactDetailsEffect.NavigateBack)
                    is AppResult.Error -> sendEffect(ContactDetailsEffect.ShowDeleteError)
                }
            }
        }
    }

    /** التراجع: إلغاء المؤقت — لا كتابة في أي طرف (J-5). */
    private fun cancelUndoWindow() {
        undoJob?.cancel()
        undoJob = null
        contactForUndo = null
        undoWindowActive.value = false
    }

    private fun latestContact(): Contact? = state.value.contact

    /** تحرير صريح من مضيف iOS عند pop (لا `ViewModelStoreOwner` هناك) — نفس دلالات `onCleared` (J-5). */
    fun clear() {
        undoJob?.cancel()
    }

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L
    }

    override fun onCleared() {
        // خروج الشاشة أثناء النافذة بلا تراجع = إلغاء بلا كتابة (تراجع ضمني — J-5).
        clear()
        super.onCleared()
    }
}
