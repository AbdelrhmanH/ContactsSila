package app.sila.feature.arham.presentation

import androidx.lifecycle.viewModelScope
import app.sila.core.common.AppClock
import app.sila.core.common.BaseMviViewModel
import app.sila.core.common.onSuccess
import app.sila.core.model.ArhamDashboardStats
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.ArhamItem
import app.sila.core.model.ArhamPeriod
import app.sila.core.model.ArhamStatus
import app.sila.core.model.CallEvent
import app.sila.core.model.Contact
import app.sila.core.model.EmergencyRingingSession
import app.sila.core.model.KinshipLayer
import app.sila.core.model.Relation
import app.sila.feature.arham.domain.ArhamFamilyTree
import app.sila.feature.arham.domain.ArhamOccasion
import app.sila.feature.arham.domain.BuildArhamFamilyTreeUseCase
import app.sila.feature.arham.domain.CelebrateTransitionUseCase
import app.sila.feature.arham.domain.ConfirmationAnswer
import app.sila.feature.arham.domain.EmergencySessionRepository
import app.sila.feature.arham.domain.GetArhamDashboardStatsUseCase
import app.sila.feature.arham.domain.GetArhamPriorityListUseCase
import app.sila.feature.arham.domain.ObserveArhamContactsUseCase
import app.sila.feature.arham.domain.ObservePendingConfirmationsUseCase
import app.sila.feature.arham.domain.ObserveUpcomingOccasionsUseCase
import app.sila.feature.arham.domain.RecordDialAttemptUseCase
import app.sila.feature.arham.domain.ResolveConfirmationUseCase
import app.sila.feature.arham.domain.StopEmergencyRingingUseCase
import app.sila.feature.arham.domain.UpdateKinshipUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** حالة موافقة فحص المكالمات على سطح الواجهة (AC-4). */
enum class ScreeningConsentUi {
    /** المنصة لا تنطبق (iOS / قبل API 29) — البطاقة مخفية كلياً. */
    HIDDEN,

    /** يلزم موافقة `ROLE_CALL_SCREENING` — بطاقة الشرح تظهر. */
    NEEDED,

    /** الموافقة محمولة — لا بطاقة. */
    GRANTED,
}

/**
 * محرّر القرابة المفتوح (T8-11 · AC-1) — `null` والورقة مغلقة. `preselectedRelation`
 * يأتي من «+» صف التوليدة (نقل قائم إلى تلك الطبقة) ويبقى `null` في التحرير الحر.
 */
data class KinshipEditorUi(
    val contactId: Long,
    val contactName: String,
    val currentRelation: Relation?,
    val currentFrequency: ArhamFrequency?,
    val currentParentContactId: Long?,
    val proposedFrequency: ArhamFrequency?,
    val preselectedRelation: Relation? = null,
    val restrictToLayer: KinshipLayer? = null,
)

/** ورقة عقدة الشجرة المفتوحة (AC-13) — الهوية حصراً؛ الرقم يُحلّ عبر الكاش. */
data class TreeNodeSheetUi(
    val contactId: Long,
    val displayName: String,
    val relation: Relation,
    val status: ArhamStatus,
    val phone: String?,
)

/** منتقي «+» صف التوليدة (AC-13: عقدة فارغة) — أقارب أرحام خارج تلك الطبقة. */
data class GenerationAddPickerUi(
    val layer: KinshipLayer,
    val candidates: List<Pair<Long, String>>, // contactId to displayName
)

data class ArhamUiState(
    val period: ArhamPeriod = ArhamPeriod.WEEK,
    val items: List<ArhamItem> = emptyList(),
    val stats: ArhamDashboardStats = ArhamDashboardStats(),
    val occasions: List<OccasionUi> = emptyList(),
    val showTree: Boolean = false,
    val tree: ArhamFamilyTree? = null,
    val pendingConfirmations: List<CallEvent> = emptyList(),
    val showConfirmations: Boolean = false,
    val kinshipEditor: KinshipEditorUi? = null,
    val treeNodeSheet: TreeNodeSheetUi? = null,
    val generationAddPicker: GenerationAddPickerUi? = null,
    val emergencySession: EmergencyRingingSession? = null,
    val screeningConsent: ScreeningConsentUi = ScreeningConsentUi.HIDDEN,
)

sealed interface ArhamIntent {
    data class ChangePeriod(val period: ArhamPeriod) : ArhamIntent
    data class RecordCall(val contactId: Long, val phone: String? = null) : ArhamIntent
    data object ToggleTree : ArhamIntent
    data object ToggleConfirmations : ArhamIntent
    data class ResolveConfirmation(val eventId: String, val answer: ConfirmationAnswer) : ArhamIntent
    data class OpenKinshipEditor(
        val contactId: Long,
        val preselectedRelation: Relation? = null,
        val restrictToLayer: KinshipLayer? = null,
    ) : ArhamIntent

    data object DismissKinshipEditor : ArhamIntent
    data class SaveKinship(
        val contactId: Long,
        val relation: Relation,
        val frequency: ArhamFrequency?,
        val parentContactId: Long?,
        val pauseForDays: Int? = null,
    ) : ArhamIntent

    data class OpenTreeNodeSheet(val contactId: Long) : ArhamIntent
    data object DismissTreeNodeSheet : ArhamIntent
    data class OpenGenerationAdd(val layer: KinshipLayer) : ArhamIntent
    data object DismissGenerationAddPicker : ArhamIntent
    data object StopEmergency : ArhamIntent
    data object RequestScreeningConsent : ArhamIntent
    data object RefreshScreeningConsent : ArhamIntent

    /** تجاهل بطاقة الموافقة لهذه الجلسة — لا كتابة تفضيلات (المنصة تسأل لاحقاً). */
    data object DismissScreeningConsent : ArhamIntent
}

sealed interface ArhamEffect {
    data class Dial(val phone: String) : ArhamEffect
    data object ShowCelebrationConfetti : ArhamEffect

    /** أطلق حوار `ROLE_CALL_SCREENING` من المنصة (AC-4) — يُعاد التقييم بعدها. */
    data object RequestScreeningRole : ArhamEffect
}

/**
 * ViewModel ميزة أرحام — يطبق مبدأ المسؤولية الواحدة وعكس التبعية (SRP & DIP)
 * ويحول التدفقات التفاعلية (Flow) إلى StateFlow عبر stateIn(WhileSubscribed(5_000)) (CODING_GUIDELINES.md §1, §8).
 *
 * T8-06 (خطة §D.3-4/§D.3-5 · Issue #29): زر الاتصال يسجّل **محاولة** `IN_APP` عبر
 * آلة الحالات حصراً (AC-3)، والاحتفال مُقرَّر عبر [CelebrateTransitionUseCase].
 *
 * T8-11 (إعادة بناء العرض · AC-1/AC-2/AC-7/AC-13 · Issue #29): يضيف شجرة العائلة
 * (رسمٌ من النموذج الصافي)، وورقة التأكيد (قراءة Room حصراً — الحسم عبر use case)،
 * وورقة القرابة (الافتراضيات الموثقة AC-1)، وبطاقة المناسبات (O-5)، ولافتة إيقاف
 * الطوارئ (قراءة Room SSOT — الإيقاف عبر use case)، وموافقة الفحص (AC-4).
 * بلا منطق أعمال هنا: كل قرار في النطاق، والكتابة عبر use cases حصراً.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArhamViewModel(
    private val getPriorityList: GetArhamPriorityListUseCase,
    private val getDashboardStats: GetArhamDashboardStatsUseCase,
    private val recordDialAttempt: RecordDialAttemptUseCase,
    private val celebrateTransition: CelebrateTransitionUseCase,
    private val buildTree: BuildArhamFamilyTreeUseCase,
    private val observePendingConfirmations: ObservePendingConfirmationsUseCase,
    private val resolveConfirmation: ResolveConfirmationUseCase,
    private val observeOccasions: ObserveUpcomingOccasionsUseCase,
    private val observeContacts: ObserveArhamContactsUseCase,
    private val updateKinship: UpdateKinshipUseCase,
    private val stopEmergency: StopEmergencyRingingUseCase,
    private val emergencySessionRepository: EmergencySessionRepository,
    private val screeningConsentSource: ArhamScreeningConsentSource,
    private val clock: AppClock,
) : BaseMviViewModel<ArhamUiState, ArhamIntent, ArhamEffect>(ArhamUiState()) {

    private val periodFlow = MutableStateFlow(ArhamPeriod.WEEK)
    private val showTreeFlow = MutableStateFlow(false)
    private val showConfirmationsFlow = MutableStateFlow(false)
    private val kinshipEditorFlow = MutableStateFlow<KinshipEditorUi?>(null)
    private val treeNodeSheetFlow = MutableStateFlow<TreeNodeSheetUi?>(null)
    private val generationAddPickerFlow = MutableStateFlow<GenerationAddPickerUi?>(null)
    private val screeningConsentFlow = MutableStateFlow(ScreeningConsentUi.HIDDEN)

    /** كاش الأقارب لحلّ أرقام الاتصال (ورقة عقدة الشجرة) — قراءة Room حصراً. */
    private var contactsCache: List<Contact> = emptyList()

    /** كاش الشجرة لحلّ حالة العقدة عند فتح ورقتها (مشتق لحظة البناء — لا إعادة حساب). */
    private var treeCache: ArhamFamilyTree? = null

    /** خريطة حالة كل جهة في آخر انبعاث — `null` قبل أول انبعاث (يبذّر بلا احتفال). */
    private var previousStatuses: Map<Long, ArhamStatus>? = null

    init {
        getPriorityList()
            .onEach { items -> detectCelebration(items) }
            .launchIn(viewModelScope)
        observeContacts()
            .onEach { contactsCache = it }
            .launchIn(viewModelScope)
        buildTree()
            .onEach { treeCache = it }
            .launchIn(viewModelScope)
        refreshScreeningConsent()
    }

    /**
     * تدفق البيانات المشترك — يُجمع مرة واحدة من المستودع (Room SSOT). أعلامُ الواجهة
     * (فتح أوراق/تبديل تبويب) **لا تعيد تشغيله**: تعديل ريفيو T8-11 P2-1 — البيانات
     * خارج `flatMapLatest` حتى لا يعيد كل تبديلٍ للعلم قراءةَ المستودع. الوتيرة وحدها
     * تعيد اشتقاق الإحصاء عبر `flatMapLatest` على `periodFlow`.
     */
    private val dataFlow: Flow<Pair<Quadruple, List<Contact>>> = combine(
        getPriorityList(),
        periodFlow.flatMapLatest { period -> getDashboardStats(period) },
        observeOccasions(),
        buildTree(),
        observePendingConfirmations(),
    ) { items, stats, occasions, tree, pending ->
        Quadruple(items, stats, occasions, tree, pending)
    }.combine(observeContacts()) { data, contacts ->
        data to contacts
    }

    /** أعلام واجهة محلية (أوراق/مختارات/تباينات) — بلا دلالة خارج النطاق. */
    private val uiFlagsFlow: Flow<LocalFlags> = combine(
        showTreeFlow,
        showConfirmationsFlow,
        kinshipEditorFlow,
        treeNodeSheetFlow,
        generationAddPickerFlow,
    ) { showTree, showConfirmations, editor, nodeSheet, picker ->
        LocalFlags(showTree, showConfirmations, editor, nodeSheet, picker, ScreeningConsentUi.HIDDEN)
    }.combine(screeningConsentFlow) { flags, consent ->
        flags.copy(consent = consent)
    }

    override val state: StateFlow<ArhamUiState> = combine(
        dataFlow,
        uiFlagsFlow,
        periodFlow,
        emergencySessionRepository.observeInProgressSession(),
    ) { dataPair, flags, period, session ->
        val (data, contacts) = dataPair
        ArhamUiState(
            period = period,
            items = data.items,
            stats = data.stats,
            // ضمّ القرابة من كاش الأقارب — المناسبة الخام بلا قرابة (O-8)، والنص
            // المترجم يُبنى في الشاشة (الـ VM لا يلمس موارد الترجمة).
            occasions = data.occasions.map { raw ->
                OccasionUi(
                    contactId = raw.contactId,
                    displayName = raw.displayName,
                    relation = contacts.firstOrNull { it.id == raw.contactId }?.arham?.relation,
                    daysUntil = raw.daysUntil,
                )
            },
            showTree = flags.showTree,
            tree = data.tree,
            pendingConfirmations = data.pending,
            showConfirmations = flags.showConfirmations,
            kinshipEditor = flags.editor,
            treeNodeSheet = flags.nodeSheet,
            generationAddPicker = flags.picker,
            emergencySession = session,
            screeningConsent = flags.consent,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ArhamUiState(),
    )

    /** حزم الدمج الداخلي — بلا دلالة خارج النطاق (تُفكَّك فوراً في الدمج النهائي). */
    private data class LocalFlags(
        val showTree: Boolean,
        val showConfirmations: Boolean,
        val editor: KinshipEditorUi?,
        val nodeSheet: TreeNodeSheetUi?,
        val picker: GenerationAddPickerUi?,
        val consent: ScreeningConsentUi,
    )

    private data class Quadruple(
        val items: List<ArhamItem>,
        val stats: ArhamDashboardStats,
        val occasions: List<ArhamOccasion>,
        val tree: ArhamFamilyTree,
        val pending: List<CallEvent>,
    )

    override fun onIntent(intent: ArhamIntent) {
        when (intent) {
            is ArhamIntent.ChangePeriod -> periodFlow.value = intent.period
            is ArhamIntent.RecordCall -> handleRecordCall(intent)
            ArhamIntent.ToggleTree -> showTreeFlow.value = !showTreeFlow.value
            ArhamIntent.ToggleConfirmations -> showConfirmationsFlow.value = !showConfirmationsFlow.value
            is ArhamIntent.ResolveConfirmation -> handleResolveConfirmation(intent)
            is ArhamIntent.OpenKinshipEditor -> openKinshipEditor(intent)
            ArhamIntent.DismissKinshipEditor -> kinshipEditorFlow.value = null
            is ArhamIntent.SaveKinship -> handleSaveKinship(intent)
            is ArhamIntent.OpenTreeNodeSheet -> openTreeNodeSheet(intent.contactId)
            ArhamIntent.DismissTreeNodeSheet -> treeNodeSheetFlow.value = null
            is ArhamIntent.OpenGenerationAdd -> openGenerationAdd(intent.layer)
            ArhamIntent.DismissGenerationAddPicker -> generationAddPickerFlow.value = null
            ArhamIntent.StopEmergency -> viewModelScope.launch { stopEmergency(sessionId = null) }
            ArhamIntent.RequestScreeningConsent -> sendEffect(ArhamEffect.RequestScreeningRole)
            ArhamIntent.RefreshScreeningConsent -> refreshScreeningConsent()
            ArhamIntent.DismissScreeningConsent -> screeningConsentFlow.value = ScreeningConsentUi.HIDDEN
        }
    }

    /**
     * تسجيل محاولة `IN_APP` عبر آلة الحالات حصراً (AC-3) — الرقم من الطلب إن جاء
     * من صف القائمة، وإلا يُحلّ من كاش الأقارب (ورقة عقدة الشجرة). بلا رقم
     * (جهة بلا هواتف) تُهمل هادئاً — لا اكتمال مُختلق ولا انهيار (§O.5).
     */
    private fun handleRecordCall(intent: ArhamIntent.RecordCall) {
        val phone = intent.phone
            ?: contactsCache.firstOrNull { it.id == intent.contactId }
                ?.phones?.firstOrNull { it.isPrimary }?.e164
            ?: contactsCache.firstOrNull { it.id == intent.contactId }
                ?.phones?.firstOrNull()?.e164
            ?: return
        viewModelScope.launch {
            recordDialAttempt(
                contactId = intent.contactId,
                remoteNumber = phone,
                timestampMillis = clock.nowEpochMillis(),
            )
            sendEffect(ArhamEffect.Dial(phone))
        }
    }

    /** حسم التأكيد عبر use case — الفشل (محسوم سلفاً/مفقود) عملية ساكنة؛ التدفق ينبض بالحقيقة. */
    private fun handleResolveConfirmation(intent: ArhamIntent.ResolveConfirmation) {
        viewModelScope.launch {
            resolveConfirmation(eventId = intent.eventId, answer = intent.answer)
        }
    }

    private fun openKinshipEditor(intent: ArhamIntent.OpenKinshipEditor) {
        val contact = contactsCache.firstOrNull { it.id == intent.contactId } ?: return
        val arham = contact.arham
        // الاقتراح: للقرابة الفعّالة (المُسبقة من «+» أو الحالية) — يُعرض فقط حين
        // لا توجد وتيرة محفوظة أو أن النقل إلى طبقة جديدة جارٍ (AC-1: اقتراح لا إجبار).
        val effectiveRelation = intent.preselectedRelation ?: arham?.relation
        kinshipEditorFlow.value = KinshipEditorUi(
            contactId = contact.id,
            contactName = contact.name.displayName,
            currentRelation = arham?.relation,
            currentFrequency = arham?.frequency,
            currentParentContactId = arham?.parentContactId,
            proposedFrequency = effectiveRelation
                ?.let(UpdateKinshipUseCase::defaultFrequencyFor)
                ?.takeIf { arham?.frequency == null || intent.preselectedRelation != null },
            preselectedRelation = intent.preselectedRelation,
            restrictToLayer = intent.restrictToLayer,
        )
    }

    /**
     * حفظ القرابة عبر use case (AC-1): الوتيرة إلزامية — `null` (بلا اقتراح/بلا
     * اختيار) يُهمل الطلب هادئاً والورقة تبقى مفتوحة. الإيقاف المؤقت الخيار
     * الموثق الوحيد: 30 يوماً من الآن.
     */
    private fun handleSaveKinship(intent: ArhamIntent.SaveKinship) {
        val frequency = intent.frequency ?: return
        viewModelScope.launch {
            updateKinship(
                contactId = intent.contactId,
                relation = intent.relation,
                frequency = frequency,
                parentContactId = intent.parentContactId,
                pausedUntilMillis = intent.pauseForDays?.let { days ->
                    clock.nowEpochMillis() + days * 24L * 60 * 60 * 1000
                },
            ).onSuccess { kinshipEditorFlow.value = null }
        }
    }

    private fun openTreeNodeSheet(contactId: Long) {
        val contact = contactsCache.firstOrNull { it.id == contactId } ?: return
        val arham = contact.arham ?: return
        val node = treeCache?.generations?.flatMap { it.nodes }?.firstOrNull { it.contactId == contactId }
            ?: return
        treeNodeSheetFlow.value = TreeNodeSheetUi(
            contactId = contact.id,
            displayName = contact.name.displayName,
            relation = arham.relation,
            status = node.status,
            phone = contact.phones.firstOrNull { it.isPrimary }?.e164
                ?: contact.phones.firstOrNull()?.e164,
        )
    }

    private fun openGenerationAdd(layer: KinshipLayer) {
        val candidates = contactsCache
            .filter { it.arham != null && it.arham?.relation?.layer != layer }
            .map { it.id to it.name.displayName }
        generationAddPickerFlow.value = GenerationAddPickerUi(layer = layer, candidates = candidates)
    }

    /** إعادة تقييم موافقة الفحص من المصدر المنصوي — `null` ⇒ إخفاء (§O.5). */
    private fun refreshScreeningConsent() {
        screeningConsentFlow.value = when (val held = screeningConsentSource.isRoleHeld()) {
            null -> ScreeningConsentUi.HIDDEN
            true -> ScreeningConsentUi.GRANTED
            false -> ScreeningConsentUi.NEEDED
        }
    }

    /**
     * كاشف الانتقال أحمر←أخضر على انبعاثات القائمة في الذاكرة: الجهة الجديدة بلا
     * حالة سابقة لا تُحتفَل، والقرار للسياسة (كونفيتي مفعَّل + مرة واحدة في فترة
     * الأسبوع لكل جهة). أثر واحد كحدّ أقصى لكل انبعاث.
     */
    private fun detectCelebration(items: List<ArhamItem>) {
        val currentStatuses = items.associate { it.contactId to it.status }
        val previous = previousStatuses
        previousStatuses = currentStatuses
        if (previous == null) return
        viewModelScope.launch {
            for (item in items) {
                val before = previous[item.contactId] ?: continue
                if (before != item.status &&
                    celebrateTransition(item.contactId, previousStatus = before, currentStatus = item.status)
                ) {
                    sendEffect(ArhamEffect.ShowCelebrationConfetti)
                    return@launch
                }
            }
        }
    }
}
