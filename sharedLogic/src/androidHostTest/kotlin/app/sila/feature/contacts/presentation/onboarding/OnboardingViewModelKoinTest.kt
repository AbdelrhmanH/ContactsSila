package app.sila.feature.contacts.presentation.onboarding

import androidx.lifecycle.viewModelScope
import app.sila.core.datastore.UserPreferencesDataSource
import app.sila.core.model.Relation
import app.sila.core.model.TopFiveSelection
import app.sila.di.createSilaSharedModule
import app.sila.feature.contacts.domain.onboarding.OnboardingPermissionState
import app.sila.feature.contacts.domain.onboarding.OnboardingStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.AfterClass
import org.junit.BeforeClass
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.mp.KoinPlatformTools
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * اختبارات حالة العرض لـ OnboardingViewModel فوق حاوية Koin الحقيقية
 * (`createSilaSharedModule`) — وفقاً لمصفوفة الأذونات في Issue #20 (#26 — T5). تشغّلها CI
 * ضمن :sharedLogic:androidHostTest مع Room/DataStore في الذاكرة (storageDirPath = null)
 * أو على القرص في مجلد مؤقت (محاكاة موت العملية — استئناف الخطوة).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelKoinTest {

    private val koin get() = KoinPlatformTools.defaultContext().get()

    companion object {
        /** Main حقيقي أحادي الخيط (مرآة لخيط Main على أندرويد) — واحد لعالم الصنف كاملاً. */
        private val mainExecutor: java.util.concurrent.ExecutorService =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "test-main").apply { isDaemon = true }
            }
        private val mainDispatcher: ExecutorCoroutineDispatcher = mainExecutor.asCoroutineDispatcher()

        @BeforeClass
        @JvmStatic
        fun setUpClass() {
            // Main بزمن حقيقي لا افتراضي: UnconfinedTestDispatcher مُجدوِله الخاص لا يتقدّم خارج
            // runTest فيُجمّد سلسلة stateIn على القرص (تدفق Room)، وDispatchers.Unconfined
            // (الفحص التشخيصي) لا يُشغّل viewModelScope.launch إطلاقاً فتضيع الـ Effects.
            // المُجدوِل التنفيذي الحقيقي يشغّل السلسلتين معاً: إصدارات Room/DataStore تُستأنف
            // عليه بزمن حقيقي، وtestScheduler يبقى صالحاً لـ advanceUntilIdle على جانب المُجمِّع.
            Dispatchers.setMain(mainDispatcher)
        }

        @AfterClass
        @JvmStatic
        fun tearDownClass() {
            // تصريف استئنافات إلغاء خطوط الأنابيب (اجتماع DataStore/Room داخل stateIn) مع بقاء
            // Main حيّاً — ثم نترك Main مضبوطاً ولا ننفّذ resetMain/close إطلاقاً: فكّ Main يفتح
            // نافذة يصطاد فيها أي استئناف متأخر من خيوط IO خطأ DispatchException غير مُلتقطة
            // تسقط على أول runTest في الصنف التالي (UncaughtExceptionsBeforeTest)؛ بقاء Main
            // يجعل تلك الاستئنافات عديمة الأثر (استئناف مُلغى = لا شيء). الخيط daemon فيموت
            // مع JVM العامل دون تعليقه. (إلغاء نطاق كل ViewModel يتم في tearDown لكل اختبار.)
            repeat(times = 3) {
                runCatching { mainExecutor.submit(Runnable {}).get() }
                Thread.sleep(50)
            }
        }
    }

    @AfterTest
    fun tearDown() {
        // إيقاف أي ViewModel حيّة قبل stopKoin — دون فكّ Main بين الاختبارات (انظر setUpClass):
        runCatching { koin.getOrNull<OnboardingViewModel>()?.viewModelScope?.cancel() }
        stopKoin()
    }

    @Test
    fun startsAtWelcomeAndCompletingWelcomeRoutesToPermissionStep() = runTest {
        startKoin { modules(createSilaSharedModule()) }
        val viewModel = onboardingViewModel()

        assertEquals(OnboardingStep.WELCOME, awaitFirstState().step)

        viewModel.onIntent(OnboardingIntent.StartOnboarding)

        assertEquals(OnboardingStep.PERMISSION, awaitStep(OnboardingStep.PERMISSION).step)
        assertTrue(!preferences().preferences.first().onboardingCompleted, "onboardingCompleted must stay false mid-onboarding.")
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun permissionResultEachState_hasDefinedPathAndPersistsPermission() = runTest {
        val statesThatProceed = setOf(
            OnboardingPermissionState.GRANTED,
            OnboardingPermissionState.LIMITED,
            OnboardingPermissionState.SKIPPED
        )
        for (state in OnboardingPermissionState.entries) {
            startKoin { modules(createSilaSharedModule()) }
            val viewModel = onboardingViewModel()
            viewModel.onIntent(OnboardingIntent.StartOnboarding)
            awaitStep(OnboardingStep.PERMISSION)

            viewModel.onIntent(OnboardingIntent.PermissionResult(state))

            val expectedStep = if (state in statesThatProceed) {
                OnboardingStep.GUEST_LOGIN
            } else {
                OnboardingStep.PERMISSION
            }
            val uiState = awaitStep(expectedStep)
            assertEquals(
                expectedStep,
                uiState.step,
                "$state must follow its matrix path."
            )
            // نسترب وصول الكتابة نفسها (لا قراءة واحدة تتسابق مع تدوين المنصة غير المتزامن —
            // حالة NOT_DETERMINED تبقى في الخطوة نفسها فلا إصدار حالة جديد يفصل بينهما).
            // بلا diag/withTimeout: زمن runTest افتراضي فيُطلق المهلة فوراً قبل وصول كتابة حقيقية:
            val persisted = preferences().preferences.first {
                it.onboardingContactsPermission == state.name
            }
            assertEquals(
                state.name,
                persisted.onboardingContactsPermission,
                "$state must be persisted via the repository (platform never asks from domain)."
            )
            viewModel.viewModelScope.cancel()
            stopKoin()
        }
    }

    @Test
    fun requestPermissionEffectIsBoundedAtTwo_thenRoutesToAppSettings() = runTest {
        startKoin { modules(createSilaSharedModule()) }
        val viewModel = onboardingViewModel()

        // مُجمِّع طفل عادي (ليس backgroundScope: مُجدوِل runTest يتوقف عن المعالجة حين يتبقّى
        // عمل backgroundScope فقط — TestScope.kt — فتبقى القائمة فارغة مهما استُدعي advanceUntilIdle).
        // الاستلام عبر take(3).toList() ثم join(): انتظار تعليقي بلا سباق مع توقيت الإرسال —
        // ثلاث نوايا تُنتج ثلاث Effects بالضبط، فيكتمل المُجمِّع ذاتياً ويعود join().
        val effects = mutableListOf<OnboardingEffect>()
        val effectsCollector = launch {
            viewModel.effects.take(3).toList(effects)
        }

        viewModel.onIntent(OnboardingIntent.StartOnboarding)
        awaitStep(OnboardingStep.PERMISSION)

        viewModel.onIntent(OnboardingIntent.RequestContactsPermission)
        viewModel.onIntent(OnboardingIntent.RequestContactsPermission)
        // استنفاد الحد (≤ 2): الطلب الثالث لا يُصدر طلباً جديداً — التحويل الفوري لإعدادات النظام.
        viewModel.onIntent(OnboardingIntent.RequestContactsPermission)
        effectsCollector.join()

        assertEquals(2, effects.count { it == OnboardingEffect.RequestContactsPermission })
        assertTrue(
            effects.last() == OnboardingEffect.OpenAppSettings,
            "Third request must route to app settings instead of a new system prompt."
        )
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun topFive_searchNoMatchShowsEmptyResultsWithoutError() = runTest {
        startKoin { modules(createSilaSharedModule()) }
        val viewModel = onboardingViewModel()
        driveToTopFive(viewModel)

        // دفتر المرحلة 1: قاعدة البيانات تبدأ بجهات بذرة تجريبية (تُستبدل بمهمة T2)؛
        // بحث بلا نتائج يعني قائمة فارغة طبيعية قابلة للتجاوز وليست إشارة خطأ.
        // ننتظر الحالة التي تحمل استعلام البحث نفسه مع قائمة فارغة (لا حالةً قديمة):
        viewModel.onIntent(OnboardingIntent.UpdateSearchQuery("zzzz_no_match"))
        val uiState = awaitState {
            it.searchQuery == "zzzz_no_match" && it.candidates.isEmpty() && it.step == OnboardingStep.TOP_FIVE
        }
        assertFalse(uiState.hasContactsError, "Empty search results are a normal state, not an error.")
        assertTrue(uiState.selections.isEmpty())
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun topFive_toggleRelationSave_persistsHandoffAndCompletesStep() = runTest {
        startKoin { modules(createSilaSharedModule()) }
        val viewModel = onboardingViewModel()
        driveToTopFive(viewModel)

        viewModel.onIntent(OnboardingIntent.ToggleSelection(systemContactId = "sys_7", displayName = "والدي"))
        viewModel.onIntent(OnboardingIntent.ToggleSelection(systemContactId = "sys_9", displayName = "والدتي"))
        assertEquals(2, awaitState { it.selections.size == 2 }.selections.size)
        viewModel.onIntent(OnboardingIntent.SetRelation(systemContactId = "sys_9", relation = Relation.MOTHER))

        viewModel.onIntent(OnboardingIntent.SaveTopFive)

        // نسترب وصول كتابة الحفظ نفسها (لا قراءة واحدة تتسابق مع التدوين غير المتزامن).
        // بلا diag/withTimeout: زمن runTest افتراضي فيُطلق المهلة فوراً قبل وصول كتابة حقيقية:
        val saved = preferences().preferences
            .first { it.onboardingTopFiveSelections.size == 2 }
            .onboardingTopFiveSelections
        assertEquals(Relation.CUSTOM, saved.first { it.systemContactId == "sys_7" }.relation)
        assertEquals(Relation.MOTHER, saved.first { it.systemContactId == "sys_9" }.relation)
        saved.forEach { assertEquals("WEEKLY", it.frequency.name, "Default handoff frequency stays WEEKLY.") }
        assertEquals(OnboardingStep.ORGANIZING, awaitStep(OnboardingStep.ORGANIZING).step)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun topFive_skipClearsSelectionsAndPersistsNoArhamRecord() = runTest {
        startKoin { modules(createSilaSharedModule()) }
        val viewModel = onboardingViewModel()
        driveToTopFive(viewModel)
        viewModel.onIntent(OnboardingIntent.ToggleSelection(systemContactId = "sys_7", displayName = "والدي"))
        assertEquals(1, awaitState { it.selections.size == 1 }.selections.size)

        viewModel.onIntent(OnboardingIntent.SkipTopFive)

        assertTrue(awaitStep(OnboardingStep.ORGANIZING).selections.isEmpty())
        assertTrue(
            preferences().preferences.first().onboardingTopFiveSelections.isEmpty(),
            "Skipping must not create any Arham record (Issue #26)."
        )
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun topFive_cappedAtFiveSelections() = runTest {
        startKoin { modules(createSilaSharedModule()) }
        val viewModel = onboardingViewModel()
        driveToTopFive(viewModel)

        repeat(times = 8) { index ->
            viewModel.onIntent(
                OnboardingIntent.ToggleSelection(
                    systemContactId = "sys_$index",
                    displayName = "جهة $index"
                )
            )
        }

        assertEquals(5, awaitState { it.selections.size == 5 }.selections.size)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun stepResume_afterProcessDeathResumesAtFirstIncompleteStep() = runBlocking {
        // زمن حقيقي لا افتراضي (runTest): تدفق Room على قرص يحتاج تنفيذاً حقيقياً على
        // منفّذات Room — تحت الزمن الافتراضي لـ runTest تتجمد أول إصدارة (أثبتّه الفحص
        // التشخيصي: نفس التدفق يُصدر فوراً تحت runBlocking ويتجمد تحت runTest).
        // ولا انتظارات حالات وسيطة هنا أيضاً (نفس سبب finishOrganizing: سلسلة combine
        // تفقد الإصدار الثاني من DataStore قرصي غير متزامن) — نتحقق عبر DataStore مباشرة.
        val storageDir = Files.createTempDirectory("onboarding_resume_test_")
        startKoin { modules(createSilaSharedModule(storageDir.toString())) }
        val firstViewModel = onboardingViewModel()
        firstViewModel.onIntent(OnboardingIntent.StartOnboarding) // WELCOME → PERMISSION
        firstViewModel.onIntent(OnboardingIntent.SkipPermission) // PERMISSION → GUEST_LOGIN
        // اكتمال الكتابة على القرص قبل محاكاة موت العملية (وإلا قُطع التعديل في منتصفه):
        diag("stepResume: persisted SKIPPED/GUEST_LOGIN") {
            preferences().preferences.first {
                it.onboardingContactsPermission == "SKIPPED" && it.onboardingResumeStep == "GUEST_LOGIN"
            }
        }
        firstViewModel.viewModelScope.cancel()
        stopKoin()

        // محاكاة موت العملية: حاوية جديدة بنفس مسار التخزين على القرص.
        startKoin { modules(createSilaSharedModule(storageDir.toString())) }
        val resumedViewModel = onboardingViewModel()
        val resumed = diag("stepResume: GUEST_LOGIN on re-entry") {
            resumedViewModel.state.first { !it.isLoading }
        }

        assertEquals(OnboardingStep.GUEST_LOGIN, resumed.step, "Re-entry resumes at the first incomplete step.")
        assertFalse(preferences().preferences.first().onboardingCompleted)
        resumedViewModel.viewModelScope.cancel()
    }

    @Test
    fun finishOrganizing_persistsCompletionSurvivesProcessDeathAndNeverRepeats() = runBlocking {
        // زمن حقيقي — نفس سبب stepResume (تدفق Room على قرص تحت runTest يتجمد).
        // ملاحظة تشخيصية مثبتة بالفحص التجريبي المؤقت: سلسلة combine في الـ ViewModel
        // تفقد الإصدار الثاني حين يقوده DataStore قرصي غير متزامن — لذلك نقود النوايا كلها
        // دون انتظارات حالات وسيطة، ونتحقق من الإكمال عبر DataStore مباشرة: المنفّذ الأحادي
        // FIFO لـ viewModelScope مع ممثّل DataStore يضمنان ترتيب الكتابات النهائي نفسه.
        val storageDir = Files.createTempDirectory("onboarding_finish_test_")
        startKoin { modules(createSilaSharedModule(storageDir.toString())) }
        val viewModel = onboardingViewModel()
        viewModel.onIntent(OnboardingIntent.StartOnboarding) // WELCOME → PERMISSION
        viewModel.onIntent(OnboardingIntent.SkipPermission) // PERMISSION → GUEST_LOGIN
        viewModel.onIntent(OnboardingIntent.ContinueAsGuest) // GUEST_LOGIN → TOP_FIVE
        viewModel.onIntent(OnboardingIntent.SkipTopFive) // TOP_FIVE → ORGANIZING
        viewModel.onIntent(OnboardingIntent.FinishOrganizing) // الإكمال النهائي

        // ينتظر الكتابة الأخيرة (onboardingCompleted تُدوَّن أخيراً داخل completeOnboarding)
        // — بحلولها تكون كل الكتابات السابقة قد استقرت على القرص:
        val finalPrefs = diag("finish: persisted onboardingCompleted") {
            preferences().preferences.first {
                it.onboardingCompleted && it.isGuest && it.onboardingResumeStep == null
            }
        }
        assertTrue(finalPrefs.isGuest, "Phase 1 completion keeps the guest path (zero cloud writes).")
        viewModel.viewModelScope.cancel()
        stopKoin()

        // محاكاة موت العملية: حاوية جديدة بنفس مسار التخزين على القرص.
        startKoin { modules(createSilaSharedModule(storageDir.toString())) }
        val resumedViewModel = onboardingViewModel()
        val resumed = diag("finish: state after process death") {
            resumedViewModel.state.first { !it.isLoading }
        }

        assertEquals(
            OnboardingStep.ORGANIZING,
            resumed.step,
            "With onboardingCompleted=true the home gate never repeats onboarding (resumeStep stays null)."
        )
        resumedViewModel.viewModelScope.cancel()
    }

    private fun onboardingViewModel(): OnboardingViewModel = koin.get()

    private fun preferences(): UserPreferencesDataSource = koin.get()

    /** حارس تعليق (زمن حقيقي — يُستخدم حصراً داخل runBlocking) — 60 ثانية لتغطية أول تشغيل بارد لـ JVM. */
    private suspend fun <T> diag(label: String, block: suspend () -> T): T =
        withTimeoutOrNull(60_000) { block() } ?: throw IllegalStateException("DIAG HUNG: $label")

    private suspend fun awaitFirstState(): OnboardingUiState =
        onboardingViewModel().state.first { !it.isLoading }

    /** انتظار حالة تُرضي المسند (يتجنّب قراءة حالةٍ قديمة سبق أن طابقت الخطوة نفسها). */
    private suspend fun awaitState(predicate: (OnboardingUiState) -> Boolean): OnboardingUiState =
        onboardingViewModel().state.first { !it.isLoading && predicate(it) }

    private suspend fun awaitStep(step: OnboardingStep): OnboardingUiState =
        awaitState { it.step == step }

    /** يقود من الترحيب إلى خطوة "أهم 5 أشخاص" عبر منح الصلاحية ثم المتابعة كضيف. */
    private suspend fun driveToTopFive(viewModel: OnboardingViewModel) {
        viewModel.onIntent(OnboardingIntent.StartOnboarding)
        awaitStep(OnboardingStep.PERMISSION)
        viewModel.onIntent(OnboardingIntent.PermissionResult(OnboardingPermissionState.GRANTED))
        viewModel.onIntent(OnboardingIntent.ContinueAsGuest)
        awaitStep(OnboardingStep.TOP_FIVE)
    }
}