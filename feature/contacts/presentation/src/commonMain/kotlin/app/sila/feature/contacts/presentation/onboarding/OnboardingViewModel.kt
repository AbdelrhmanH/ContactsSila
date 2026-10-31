package app.sila.feature.contacts.presentation.onboarding

import androidx.lifecycle.viewModelScope
import app.sila.core.common.BaseMviViewModel
import app.sila.core.model.AppLanguage
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.Contact
import app.sila.core.model.Relation
import app.sila.core.model.TopFiveSelection
import app.sila.feature.contacts.domain.ObserveContactsUseCase
import app.sila.feature.contacts.domain.onboarding.CompleteOnboardingStepUseCase
import app.sila.feature.contacts.domain.onboarding.CompleteOnboardingUseCase
import app.sila.feature.contacts.domain.onboarding.ObserveOnboardingStateUseCase
import app.sila.feature.contacts.domain.onboarding.OnboardingPermissionState
import app.sila.feature.contacts.domain.onboarding.OnboardingStep
import app.sila.feature.contacts.domain.onboarding.SaveTopFiveSelectionUseCase
import app.sila.feature.contacts.domain.onboarding.SetOnboardingPermissionsResultUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val isLoading: Boolean = true,
    val hasContactsError: Boolean = false,
    val contactsPermission: OnboardingPermissionState = OnboardingPermissionState.NOT_DETERMINED,
    val isGuest: Boolean = true,
    val language: AppLanguage = AppLanguage.ARABIC,
    val searchQuery: String = "",
    val candidates: List<Contact> = emptyList(),
    val selections: List<TopFiveSelection> = emptyList()
)

sealed interface OnboardingIntent {
    /** اكتملت شرائح الترحيب ← الانتقال لخطوة شرح الصلاحية. */
    data object StartOnboarding : OnboardingIntent

    /** المستخدم فعّل زر المنح ← المنصة (shell) وحدها تنفّذ الطلب الفعلي عبر Effect. */
    data object RequestContactsPermission : OnboardingIntent

    /** المنصة أبلغت النتيجة النهائية (منح / رفض / رفض نهائي / iOS .limited). */
    data class PermissionResult(val state: OnboardingPermissionState) : OnboardingIntent

    /** رفض نهائي ← فتح إعدادات التطبيق (لا يُعاد الطلب تلقائياً أبداً). */
    data object OpenAppSettings : OnboardingIntent

    /** المتابعة بدون منح الصلاحية (سجل فارغ + اختصار للإعدادات). */
    data object SkipPermission : OnboardingIntent

    /** "تخطي الآن" — المتابعة كضيف (المرحلة 1: لا OTP ولا حساب) مع zero cloud writes. */
    data object ContinueAsGuest : OnboardingIntent

    data class UpdateSearchQuery(val query: String) : OnboardingIntent

    /** إعادة محاولة تحميل جهات الاتصال بعد فشل التحميل (حالة خطأ). */
    data object RetryContactsLoad : OnboardingIntent

    data class ToggleSelection(val systemContactId: String, val displayName: String) : OnboardingIntent

    data class SetRelation(val systemContactId: String, val relation: Relation) : OnboardingIntent

    data object SaveTopFive : OnboardingIntent

    data object SkipTopFive : OnboardingIntent

    /** انتهت شاشة "جارٍ التنظيم…" ← الإكمال النهائي ويُدوَّم onboardingCompleted. */
    data object FinishOrganizing : OnboardingIntent
}

sealed interface OnboardingEffect {
    /** طلب صلاحية جهات الاتصال — تنفّذه المنصة فقط، بحد ≤ 2 طلب في تهيئة واحدة (Issue #20). */
    data object RequestContactsPermission : OnboardingEffect

    data object OpenAppSettings : OnboardingEffect
}

/**
 * ViewModel ميزة التهيئة الأولى (:feature:contacts:presentation) — MVI + UDF.
 *
 * - الحالات مصدرها حصرياً OnboardingRepository (DataStore = Single Source of Truth).
 * - الواجهة تقرأ UiState وترسل Intents فقط؛ لا منطق أعمال في Compose/SwiftUI (CODING_GUIDELINES).
 * - لم "أهم 5 أشخاص": البحث يمر عبر ObserveContactsUseCase الحالي، وجدول جهات الاتصال
 *   فارغ في المرحلة 1 قبل مهمة T2 (المزامنة) — وهي حالة فارغة عادية قابلة للتخطي لا خطأ.
 * - الصلاحية لا تُطلب من هنا: المنصة تترجم حالتها إلى OnboardingPermissionState فقط،
 *   وحد طلبات الصلاحية في تهيئة واحدة (≤ 2) مضبوط أدناها كحد ابتياطي.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModel(
    private val observeOnboardingState: ObserveOnboardingStateUseCase,
    private val completeStep: CompleteOnboardingStepUseCase,
    private val setPermissionsResult: SetOnboardingPermissionsResultUseCase,
    private val saveTopFiveSelection: SaveTopFiveSelectionUseCase,
    private val finishOnboarding: CompleteOnboardingUseCase,
    private val observeContacts: ObserveContactsUseCase
) : BaseMviViewModel<OnboardingUiState, OnboardingIntent, OnboardingEffect>(OnboardingUiState()) {

    /** عدد طلبات الصلاحية المسموح بتمريرها للمنصة — الحد المسموح في مصفوفة الأذونات (≤ 2). */
    private var permissionRequestsCount = 0

    private val searchQueryFlow = MutableStateFlow("")

    /** مفتاح إعادة تشغيل تدفق البحث (يُستعمل عند إعادة المحاولة بعد خطأ تحميل). */
    private val contactsReloadKey = MutableStateFlow(0)

    /** اختيارات الجلسة — تبقى في الذاكرة حتى الضغط على "حفظ ومتابعة" (تخطي الخطوة لا يحفظها). */
    private val selectionsFlow = MutableStateFlow<List<TopFiveSelection>>(emptyList())

    /** (مرشحون، فشل تحميل): تدفق واحد يمنع السحب المزدوج من مستودع جهات الاتصال. */
    private val candidatesFlow = combine(searchQueryFlow, contactsReloadKey) { query, _ -> query }
        .flatMapLatest { query ->
            observeContacts(searchQuery = query)
                .map<List<Contact>, Pair<List<Contact>, Boolean>> { contacts -> contacts to false }
                .catch { emit(emptyList<Contact>() to true) }
        }

    /** عقد التسليم إلى ميزة أرحام (T8) — يدوَّم السجل عند الحفظ فقط (Push/Pull من T8). */
    override val state: StateFlow<OnboardingUiState> = combine(
        observeOnboardingState(),
        selectionsFlow,
        searchQueryFlow,
        candidatesFlow
    ) { persisted, selections, searchQuery, (candidates, hasError) ->
        OnboardingUiState(
            step = persisted.resumeStep ?: OnboardingStep.ORGANIZING,
            isLoading = false,
            hasContactsError = hasError,
            contactsPermission = persisted.contactsPermission,
            isGuest = persisted.isGuest,
            language = persisted.language,
            searchQuery = searchQuery,
            candidates = candidates,
            selections = selections
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = OnboardingUiState()
    )

    init {
        // استئناف الاختيارات المحفوظة سابقاً (عودة بعد انتهاء العملية/process death).
        viewModelScope.launch {
            val saved = observeOnboardingState().first().topFiveSelections
            if (saved.isNotEmpty() && selectionsFlow.value.isEmpty()) {
                selectionsFlow.value = saved
            }
        }
    }

    override fun onIntent(intent: OnboardingIntent) {
        when (intent) {
            OnboardingIntent.StartOnboarding -> viewModelScope.launch {
                completeStep(OnboardingStep.WELCOME)
            }

            OnboardingIntent.RequestContactsPermission ->
                if (permissionRequestsCount < MAX_PERMISSION_REQUESTS) {
                    permissionRequestsCount++
                    sendEffect(OnboardingEffect.RequestContactsPermission)
                } else {
                    // استنفاد الحد (≤ 2): التحويل الفوري لإعدادات النظام بدل طلب جديد.
                    sendEffect(OnboardingEffect.OpenAppSettings)
                }

            is OnboardingIntent.PermissionResult -> viewModelScope.launch {
                onPermissionResult(intent.state)
            }

            OnboardingIntent.OpenAppSettings -> sendEffect(OnboardingEffect.OpenAppSettings)

            OnboardingIntent.SkipPermission -> viewModelScope.launch {
                onPermissionResult(OnboardingPermissionState.SKIPPED)
            }

            OnboardingIntent.ContinueAsGuest -> viewModelScope.launch {
                completeStep(OnboardingStep.GUEST_LOGIN)
            }

            is OnboardingIntent.UpdateSearchQuery -> searchQueryFlow.value = intent.query

            OnboardingIntent.RetryContactsLoad -> contactsReloadKey.value += 1

            is OnboardingIntent.ToggleSelection ->
                toggleSelection(intent.systemContactId, intent.displayName)

            is OnboardingIntent.SetRelation -> setRelation(intent.systemContactId, intent.relation)

            OnboardingIntent.SaveTopFive -> viewModelScope.launch {
                // (عقد التسليم إلى ميزة أرحام T8) — يُدوَّم السجل في مستودع التهيئة هنا فقط.
                saveTopFiveSelection(selectionsFlow.value.take(MAX_TOP_FIVE_SELECTIONS))
                completeStep(OnboardingStep.TOP_FIVE)
            }

            OnboardingIntent.SkipTopFive -> viewModelScope.launch {
                // التخطي لا يُنشئ أي سجل أرحام ولا يهيّئ أي مصادر مشعّة.
                selectionsFlow.value = emptyList()
                completeStep(OnboardingStep.TOP_FIVE)
            }

            OnboardingIntent.FinishOrganizing -> viewModelScope.launch {
                finishOnboarding()
            }
        }
    }

    private suspend fun onPermissionResult(state: OnboardingPermissionState) {
        setPermissionsResult(state)
        when (state) {
            // المنح / المحدود / التخطي يقدّمان بالتهيئة إلى شاشة الضيف.
            OnboardingPermissionState.GRANTED,
            OnboardingPermissionState.LIMITED,
            OnboardingPermissionState.SKIPPED -> completeStep(OnboardingStep.PERMISSION)

            // الرفض (لمرة أو نهائي) يبقى في شاشة الشرح (إعادة محاولة صريحة / إعدادات).
            OnboardingPermissionState.DENIED,
            OnboardingPermissionState.DENIED_FOREVER,
            OnboardingPermissionState.NOT_DETERMINED -> Unit
        }
    }

    private fun toggleSelection(systemContactId: String, displayName: String) {
        val current = selectionsFlow.value
        when {
            current.any { it.systemContactId == systemContactId } ->
                selectionsFlow.value = current.filterNot { it.systemContactId == systemContactId }

            current.size < MAX_TOP_FIVE_SELECTIONS ->
                selectionsFlow.value = current + TopFiveSelection(
                    systemContactId = systemContactId,
                    displayName = displayName,
                    relation = Relation.CUSTOM,
                    frequency = ArhamFrequency.WEEKLY
                )
        }
    }

    private fun setRelation(systemContactId: String, relation: Relation) {
        selectionsFlow.value = selectionsFlow.value.map { selection ->
            if (selection.systemContactId == systemContactId) {
                selection.copy(relation = relation)
            } else {
                selection
            }
        }
    }

    private companion object {
        const val MAX_TOP_FIVE_SELECTIONS = 5
        const val MAX_PERMISSION_REQUESTS = 2
    }
}
