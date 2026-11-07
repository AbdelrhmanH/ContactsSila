package app.sila.feature.contacts.presentation.onboarding

import androidx.lifecycle.viewModelScope
import app.sila.core.datastore.UserPreferencesDataSource
import app.sila.di.createSilaSharedModule
import app.sila.feature.contacts.domain.onboarding.OnboardingStep
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * اختبارات توصيل حالة التهيئة الأولى فوق DataStore قرصي فعلي (Issue #34).
 *
 * العقد المقبول: وصول **أحدث حالة مدوَّنة** إلى المستهلك النشط ذي الصلة (إسقاط الوسطيات
 * لـ StateFlow قانوني؛ فقدان الحالة النهائية المطلوبة ليس كذلك).
 *
 * السبب الجذري المُصلَح: تدفق DataStore القرصي بارد، وكل مجموعة جديدة تقرأ الحالة بلا قفل —
 * إن تزامنت مع نافذة كتابة فتحصل على قديم مختوماً بالإصدار الجديد ويُسقط حارس الإصدارات الداخلي
 * تحديث الكتابة نهائياً لتلك المجموعة. الإصلاح: تخزين حار لمجموعة واحدة مملوكة داخل
 * SilaPreferencesDataSource — فلا طريق لأي مستهلك لإعادة بدء مجموعة `.data` منافسة.
 *
 * مزامنة المستلم العابر للخيوط: كل اختبار يُشعِر عبر CompletableDeferred من داخل المستلم
 * نفسه (لا فحص قائمة قبل انصراف مُجدولها — درس التشخيص: قائمة جامع على خيط آخر قد تتأخر
 * حلّياً بلا أي فقد فعلي).
 *
 * نموذج التنفيذ: runBlocking بزمن حقيقي — زمن runTest الافتراضي لا يحكم لا ممثّل DataStore
 * القرصي ولا منفّذات Room الحقيقية. Main حقيقي أحادي الخيط واحد لعالم الصنف كما في
 * OnboardingViewModelKoinTest (بلا resetMain — فكّ Main يصطاد استئنافات IO متأخرة).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelStateDeliveryTest {

    private val koin get() = KoinPlatformTools.defaultContext().get()

    companion object {
        /** Main حقيقي أحادي الخيط — مرآة خيط Main الإنتاجي، واحد لعالم الصنف كاملاً. */
        private val mainExecutor: java.util.concurrent.ExecutorService =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "state-delivery-test-main").apply { isDaemon = true }
            }
        private val mainDispatcher: ExecutorCoroutineDispatcher = mainExecutor.asCoroutineDispatcher()

        @BeforeClass
        @JvmStatic
        fun setUpClass() {
            Dispatchers.setMain(mainDispatcher)
        }

        @AfterClass
        @JvmStatic
        fun tearDownClass() {
            // تصريف استئنافات الإلغاء مع بقاء Main حيّاً — لا resetMain (نفس عقد OnboardingViewModelKoinTest).
            repeat(times = 3) {
                runCatching { mainExecutor.submit(Runnable {}).get() }
                Thread.sleep(50)
            }
        }

        /** مهلة انتظار الإصدار بعد الكتابة — زمن حقيقي كافٍ لممثّل DataStore القرصي على CI. */
        private const val POST_WRITE_TIMEOUT_MILLIS = 15_000L

        /** مهلة أول حالة/تدفئة — أول تشغيل بارد لـ JVM (بداية تهيئة Room + DataStore على القرص). */
        private const val FIRST_STATE_TIMEOUT_MILLIS = 60_000L
    }

    @AfterTest
    fun tearDown() {
        runCatching { koin.getOrNull<OnboardingViewModel>()?.viewModelScope?.cancel() }
        stopKoin()
    }

    /** مستلم حيّ لا يفصل الاشتراك يعبر كتابة نية فعلية — DataStore قرصي فعلي + مسار إنتاجي كامل عبر Koin. */
    @Test
    fun diskMode_activeSubscriberReceivesPostWriteEmission() = runBlocking {
        startDiskKoin()
        val viewModel = onboardingViewModel()

        val permissionObserved = CompletableDeferred<Unit>()
        val collector = launch {
            viewModel.state.collect {
                if (it.step == OnboardingStep.PERMISSION && !permissionObserved.isCompleted) {
                    permissionObserved.complete(Unit)
                }
            }
        }

        val firstState = awaitFirstRealState(viewModel)
        assertEquals(OnboardingStep.WELCOME, firstState.step, "Initial persisted state (WELCOME) must be delivered first.")

        viewModel.onIntent(OnboardingIntent.StartOnboarding)

        // توصيل القيمة إلى المستهلك النشط عبر الـ StateFlow نفسه (حتمي):
        val afterWrite = withTimeout(POST_WRITE_TIMEOUT_MILLIS) {
            viewModel.state.first { it.step == OnboardingStep.PERMISSION }
        }
        assertEquals(OnboardingStep.PERMISSION, afterWrite.step)
        // توصيل القيمة إلى المستلم الحيّ عبر إشارة من داخله (حتمي — لا فحص قائمة غير مصفّاة):
        withTimeout(POST_WRITE_TIMEOUT_MILLIS) { permissionObserved.await() }
        collector.cancel()
        viewModel.viewModelScope.cancel()
    }

    /** ضابط تحكم: نفس المتن فوق مصدر في الذاكرة (فرع _memoryState لا يمسه الإصلاح). */
    @Test
    fun memoryMode_control_activeSubscriberReceivesPostWriteEmission() = runBlocking {
        startKoin { modules(createSilaSharedModule()) }
        val viewModel = onboardingViewModel()

        val permissionObserved = CompletableDeferred<Unit>()
        val collector = launch {
            viewModel.state.collect {
                if (it.step == OnboardingStep.PERMISSION && !permissionObserved.isCompleted) {
                    permissionObserved.complete(Unit)
                }
            }
        }

        val firstState = awaitFirstRealState(viewModel)
        assertEquals(OnboardingStep.WELCOME, firstState.step)

        viewModel.onIntent(OnboardingIntent.StartOnboarding)

        withTimeout(POST_WRITE_TIMEOUT_MILLIS) {
            viewModel.state.first { it.step == OnboardingStep.PERMISSION }
        }
        withTimeout(POST_WRITE_TIMEOUT_MILLIS) { permissionObserved.await() }
        collector.cancel()
        viewModel.viewModelScope.cancel()
    }

    /**
     * اختبار انحدار السباق الفعلي (Issue #34): اشتراك جديد بعد استقرار الكتابة على القرص يرى
     * أحدث حالة مدوَّنة. قبل الإصلاح كان يفشل تقريباً 50-75% من التشغيلات: قراءة الحالة الأولى
     * لحقبة الاشتراك تتسابق مع نافذة الكتابة فتحصل على قديم مختوماً بالإصدار الجديد ويسقط حارس
     * الإصدارات تحديث الكتابة نهائياً.
     */
    @Test
    fun diskMode_subscriptionAfterWrite_seesLatestPersistedState() = runBlocking {
        startDiskKoin()
        val viewModel = onboardingViewModel()
        awaitFirstRealState(viewModel)

        viewModel.onIntent(OnboardingIntent.StartOnboarding)
        // الكتابة نفسها استقرت على القرص أولاً (إثبات منتج-المستوى أن edit وصل الملف):
        val persisted = withTimeout(POST_WRITE_TIMEOUT_MILLIS) {
            preferences().preferences.first { it.onboardingResumeStep == "PERMISSION" }
        }
        assertEquals("PERMISSION", persisted.onboardingResumeStep, "Disk write must land in the DataStore file.")

        val state = withTimeout(POST_WRITE_TIMEOUT_MILLIS) {
            viewModel.state.first { it.step == OnboardingStep.PERMISSION }
        }
        assertEquals(
            OnboardingStep.PERMISSION,
            state.step,
            "Subscriber arriving after the write must see the latest persisted state."
        )
        viewModel.viewModelScope.cancel()
    }

    /** نوايا متلاحقة: التقارب على أحدث حالة مدوَّنة (إسقاط الوسطيات قانوني، الاستقرار على الأحدث إلزامي). */
    @Test
    fun diskMode_rapidIntents_convergeToLatestPersistedStep() = runBlocking {
        startDiskKoin()
        val viewModel = onboardingViewModel()
        awaitFirstRealState(viewModel)

        // نوايا متتالية دون انتظار حالات وسيطة (نمط stepResume/finishOrganizing):
        viewModel.onIntent(OnboardingIntent.StartOnboarding) // WELCOME → PERMISSION
        viewModel.onIntent(OnboardingIntent.SkipPermission) // PERMISSION → GUEST_LOGIN

        val converged = withTimeout(POST_WRITE_TIMEOUT_MILLIS) {
            viewModel.state.first { it.step == OnboardingStep.GUEST_LOGIN }
        }
        assertEquals(
            OnboardingStep.GUEST_LOGIN,
            converged.step,
            "Rapid multi-write sequence must converge to the latest persisted step."
        )
        viewModel.viewModelScope.cancel()
    }

    /** المفصل البياناتي: مستلم حيّ على preferences يستلم إصدارات ثلاث كتابات متلاحقة (أحدثها إلزامياً). */
    @Test
    fun dataSeam_diskMode_continuousCollectorReceivesRapidSetterEmissions() = runBlocking {
        startDiskKoin()
        val dataSource = preferences()
        val finalState = CompletableDeferred<Unit>()
        val collector = launch {
            dataSource.preferences.collect {
                // completeStep يكتب الخطوة التالية: WELCOME→PERMISSION ثم PERMISSION→GUEST_LOGIN
                // ثم GUEST_LOGIN→TOP_FIVE — أحدث قيمة نهائية مدوَّنة:
                if (it.onboardingResumeStep == "TOP_FIVE" && !finalState.isCompleted) {
                    finalState.complete(Unit)
                }
            }
        }
        withTimeout(FIRST_STATE_TIMEOUT_MILLIS) { dataSource.preferences.first { true } }

        // ثلاث كتابات متلاحقة عبر المستودع (نفس مسار نوايا الـ VM):
        val repository = koin.get<app.sila.feature.contacts.domain.onboarding.OnboardingRepository>()
        repository.completeStep(OnboardingStep.WELCOME)
        repository.completeStep(OnboardingStep.PERMISSION)
        repository.completeStep(OnboardingStep.GUEST_LOGIN)

        withTimeout(POST_WRITE_TIMEOUT_MILLIS) { finalState.await() }
        assertEquals("TOP_FIVE", dataSource.preferences.first().onboardingResumeStep)
        collector.cancel()
    }

    /** قراءة باردة بعد الكتابة ترى أحدث قيمة مدوَّنة (عقد المفصل للقراءات الفردية — نمط stepResume). */
    @Test
    fun diskMode_freshReadAfterWrite_seesLatestPersistedState() = runBlocking {
        startDiskKoin()
        val dataSource = preferences()
        // تدفئة المجموعة المملوكة قبل الكتابة (مطابق للإنتاج: أول قراءة قبل أي نية مستخدم):
        withTimeout(FIRST_STATE_TIMEOUT_MILLIS) { dataSource.preferences.first { true } }

        val repository = koin.get<app.sila.feature.contacts.domain.onboarding.OnboardingRepository>()
        repository.completeStep(OnboardingStep.WELCOME)

        val persisted = withTimeout(POST_WRITE_TIMEOUT_MILLIS) {
            dataSource.preferences.first { it.onboardingResumeStep == "PERMISSION" }
        }
        assertEquals("PERMISSION", persisted.onboardingResumeStep)
    }

    private fun startDiskKoin() {
        val storageDir = Files.createTempDirectory("onboarding_state_delivery_")
        startKoin { modules(createSilaSharedModule(storageDir.toString())) }
    }

    private fun onboardingViewModel(): OnboardingViewModel = koin.get()

    private fun preferences(): UserPreferencesDataSource = koin.get()

    /** أول حالة حقيقية (isLoading=false) — مهلة سخية لأول تشغيل بارد لـ JVM على القرص. */
    private suspend fun awaitFirstRealState(viewModel: OnboardingViewModel) =
        withTimeoutOrNull(FIRST_STATE_TIMEOUT_MILLIS) { viewModel.state.first { !it.isLoading } }
            ?: error("First combined state never arrived within ${FIRST_STATE_TIMEOUT_MILLIS}ms")
}
