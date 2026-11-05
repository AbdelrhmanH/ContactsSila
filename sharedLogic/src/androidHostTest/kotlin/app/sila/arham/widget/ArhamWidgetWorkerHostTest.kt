package app.sila.arham.widget

import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import app.sila.di.createSilaSharedModule
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * اختبارات مضيف انعاش الودجت (T8-10 · D3 · Issue #29) فوق Robolectric وWorkManager
 * الاختباري وKoin الحقيقي: الـ worker مُشغِّل حصراً — نجاحه يعيد تركيب كل ودجت (قراءة
 * Room حديثة)، وغياب Koin (عملية مبكرة) يعيد الجدولة `retry` بلا فشل دائم، والجدولة
 * الفريدة (دوري 30 د + أحادي REPLACE) idempotent بلا تراكم.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArhamWidgetWorkerHostTest {

    private val app get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        startKoin {
            androidContext(app)
            modules(createSilaSharedModule())
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    private fun workManager(): WorkManager = WorkManager.getInstance(app)

    @Test
    fun `worker with koin succeeds and re-glances all widgets`() = runBlocking {
        val worker = TestListenableWorkerBuilder<ArhamWidgetRefreshWorker>(app).build()

        val result = worker.doWork()

        // بلا ودجت مُثبَّت في الشادو ⇒ updateAll عملية ساكنة ناجحة (لا PII ولا شبكة).
        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun `worker without koin retries instead of failing permanently`() {
        stopKoin() // محاكاة عملية مبكرة (قبل SilaApplication.onCreate)
        try {
            val worker = TestListenableWorkerBuilder<ArhamWidgetRefreshWorker>(app).build()

            val result = runBlocking { worker.doWork() }

            assertEquals(ListenableWorker.Result.retry(), result, "غياب Koin ⇒ إعادة جدولة مؤقتة")
        } finally {
            startKoin {
                androidContext(app)
                modules(createSilaSharedModule())
            }
        }
    }

    @Test
    fun `event-driven one-shot enqueues under unique REPLACE name`() {
        GlanceWidgetRefresher.enqueueOneShot(app)
        GlanceWidgetRefresher.enqueueOneShot(app) // دفعة ثانية — REPLACE تدمج لا تُراكم

        val infos = workManager().getWorkInfosForUniqueWork(GlanceWidgetRefresher.ONE_SHOT_WORK_NAME).get()
        assertEquals(1, infos.size, "الاسم الفريد ⇒ تشغيلة واحدة معلَّقة كحد أقصى")
    }

    @Test
    fun `periodic 30min schedule is idempotent via unique UPDATE name`() {
        GlanceWidgetRefresher.enqueuePeriodic(app)
        GlanceWidgetRefresher.enqueuePeriodic(app) // إعادة تسجيل عند بدء العملية — بلا تراكم

        val infos = workManager().getWorkInfosForUniqueWork(GlanceWidgetRefresher.PERIODIC_WORK_NAME).get()
        assertEquals(1, infos.size, "العمل الدوري الوحيد بالاسم الفريد (سياسة UPDATE)")
        assertTrue(
            infos.first().state == androidx.work.WorkInfo.State.ENQUEUED ||
                infos.first().state == androidx.work.WorkInfo.State.RUNNING,
            "العمل الدوري بانتظار التنفيذ أو يعمل",
        )
    }
}
