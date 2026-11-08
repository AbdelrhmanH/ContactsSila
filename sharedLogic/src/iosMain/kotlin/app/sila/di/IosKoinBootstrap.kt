package app.sila.di

import app.sila.arham.notification.IosArhamNotificationScheduler
import app.sila.arham.notification.IosArhamNotificationCenterRegistering
import app.sila.arham.notification.IosArhamReminderDispatch
import app.sila.arham.notification.iosNotificationCenterIfPresent
import app.sila.arham.observer.ArhamCallStateObserver
import app.sila.arham.widget.IosArhamWidgetSnapshotPublisher
import app.sila.core.database.dao.ArhamDataChangeListener
import app.sila.core.database.dao.LocalSilaDatabase
import app.sila.core.datastore.SilaPreferencesDataSource
import app.sila.core.datastore.UserPreferencesDataSource
import app.sila.feature.contacts.data.ContactsSyncEngine
import app.sila.feature.contacts.presentation.ContactDetailsViewModel
import app.sila.feature.contacts.presentation.ContactEditViewModel
import app.sila.feature.contacts.presentation.ContactsViewModel
import app.sila.feature.contacts.presentation.duplicates.DuplicatesReviewViewModel
import app.sila.feature.contacts.presentation.duplicates.SmartFilterReviewViewModel
import app.sila.feature.contacts.presentation.onboarding.OnboardingViewModel
import app.sila.feature.arham.domain.PlanArhamDailyNotificationUseCase
import app.sila.feature.arham.domain.ReconcileInterruptedSessionUseCase
import app.sila.feature.arham.presentation.ArhamViewModel
import app.sila.feature.main.MainViewModel
import app.sila.maintenance.ArhamMaterializationCoordinator
import app.sila.maintenance.MergeSweepCoordinator
import app.sila.maintenance.RetentionCleanupCoordinator
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.Volatile
import org.koin.core.Koin
import org.koin.core.context.stopKoin
import org.koin.core.parameter.parametersOf
import org.koin.mp.KoinPlatformTools
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/**
 * مهيئ حاوية Koin لمنصة iOS (Issue #5 & Issue #9).
 *
 * يلتزم باستخدام مجلد `NSApplicationSupportDirectory` (`Application Support/Sila`)
 * لتخزين قاعدة بيانات Room KMP (`sila_local.db`) وملف تفضيلات DataStore
 * (`sila_user_preferences.preferences_pb`) على القرص، مع منع السقوط الصامت إلى `:memory:`.
 */
object IosKoinBootstrap {

    private const val APP_SUPPORT_SUBDIRECTORY = "Sila"

    /**
     * نطاق كائن محفوظ لمهام محرك المزامنة طويلة العمر على Native — كائن `object`
     * لا يُجمع مهما طال عمر المراقبة (إلا بتفكيك العملية نفسها). في الإنتاج يبقى
     * نطاق عمر العملية بالتصميم، لكنه الآن قابل للتصريف: [drainEngineScopeBeforeContainerClose]
     * يلغيه وينتظر اكتمال كل أبنائه **قبل** أي إغلاق حاوية (`stopKoin`) ثم يعيد
     * إنشاءه طازجاً — إغلاق تجمع اتصالات SQLite تحت عملٍ حيّ (عبر `onClose` في
     * [KoinModule]) كان السبب الجذري لانهيار T8+DEF-01 المدمج (run 42293935880).
     */
    @Volatile
    private var engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** سقف مهلة تصريف النطاق قبل أي إغلاق حاوية (ملي ثانية) — كل أبناء النطاق
     * تعاونيون (نقاط تعليق فقط)، فتجاوز المهلة يعني خللاً مرضياً يستحق الفشل الصاخب. */
    private const val ENGINE_SCOPE_DRAIN_TIMEOUT_MILLIS = 5_000L

    /** F-02: علم تشغيل واحد لصيانة الاستبقاء لكل عملية (idempotent — كما start أعلاه). */
    @Volatile
    private var cleanupLaunched = false

    /** T8-12 (D2 §8): علم إعادة جدولة واحدة للتذكير اليومي لكل عملية (idempotent). */
    @Volatile
    private var reminderRescheduled = false

    /** T8-12 (AC-6): علم بدء مراقب المكالمات لكل عملية — ضمن حياة التطبيق فقط. */
    @Volatile
    private var callObserverStarted = false

    /** T8-12: علم تقييد مندوب مركز الإشعارات لكل عملية (التعيين idempotent). */
    @Volatile
    private var notificationDelegateRegistered = false

    /** T8-13 (D6): علم تسجيل جسر نشر لقطة الودجت على قاعدة البيانات (مرآة أندرويد، idempotent). */
    @Volatile
    private var widgetBridgeRegistered = false

    /** T8-13 (§N.5): علم مُوافَة جلسات الطوارئ المقطوعة عند بدء العملية (مرآة أندرويد، idempotent). */
    @Volatile
    private var emergencyReconciled = false

    /** T8-15 (F-1): علم تجسيد واحد لـ«أهم 5» لكل عملية (مرآة أندرويد، idempotent). */
    @Volatile
    private var materializationLaunched = false

    /**
     * المحرك الذي أطلقه [ensureStarted] على الحاوية الحالية — يُلتقط هنا كي يصرفه
     * [drainEngineScopeBeforeContainerClose] بلا بحث Koin متأخر (بلا إنشاء حثيث
     * لـ `CNContactStore` على حاوية غريبة، وبلا فشل تعريف على حاوية دنيا).
     */
    @Volatile
    private var activeEngine: ContactsSyncEngine? = null

    /** مراقب حالة المكالمات الذي أطلقه [startCallStateObserver] على الحاوية الحالية —
     * يوقفه التصريف قبل أي إغلاق (خروج المندوب ObjC متزامن — لا انتظار لازم). */
    @Volatile
    private var activeCallObserver: ArhamCallStateObserver? = null

    /** مجدول التذكير الذي التقطه [rescheduleDailyReminder] على خيط المستدعي — التصريف
     * يلغي نطاقه المملوك وينتظر (أعمالُ الجدولة لم تعد نطاقات منفصلة بلا مالك). */
    @Volatile
    private var activeArhamScheduler: IosArhamNotificationScheduler? = null

    /** غلاف مندوب الإشعارات المقيد في [registerNotificationCenterDelegate] — التصريف
     * يلغي نطاق أفعال الإزاحة فيه وينتظر؛ تسجيل المندوب لدى النظام يستبدل ذاتياً عند
     * إعادة التقييد على الحاوية التالية. */
    @Volatile
    private var activeArhamRegistrar: IosArhamNotificationCenterRegistering? = null

    /** ثلاثية جسر الودجت الحالية (الناشر + قاعدة الحاوية + المستمع) — التصريف يزيل
     * المستمع من القاعدة القديمة أولاً (وقف إنتاج الأحداث) ثم يغلق الناشر وينتظر. */
    @Volatile
    private var activeWidgetPublisher: IosArhamWidgetSnapshotPublisher? = null

    @Volatile
    private var activeWidgetDb: LocalSilaDatabase? = null

    @Volatile
    private var activeWidgetListener: ArhamDataChangeListener? = null

    /**
     * للاختبارات حصراً (نفس مبدئ خطافات `LocalSilaDatabase` الداخلية): يعيد علم
     * الإطلاق الواحد [cleanupLaunched] إلى حالة ما قبل الإطلاق، ليستطيع اختبار مسار
     * الإنتاج التحقق من نشر التقرير على حاوية الاختبار الجديدة — إذ يستهلك أي اختبار
     * سابق العلم على حاوته بينما `stopKoin` بين الاختبارات يُنشئ حاوية أخرى.
     * لا يُستدعى من كود الإنتاج إطلاقاً.
     */
    internal fun resetCleanupLaunchForTest() {
        cleanupLaunched = false
    }

    /** بصمة علم الإطلاق الواحد للاختبارات حصراً (مرآة [resetCleanupLaunchForTest]). */
    internal fun isCleanupLaunchedForTest(): Boolean = cleanupLaunched

    /** بصمة أعلام أرحام للاختبارات حصراً — true فقط بعد تصريفٍ صفّرها كلها (النمط المرجعي). */
    internal fun areArhamLifecycleFlagsResetForTest(): Boolean =
        !reminderRescheduled && !callObserverStarted && !notificationDelegateRegistered &&
            !widgetBridgeRegistered && !emergencyReconciled && !materializationLaunched

    /**
     * تصريف عمل النطاق المحفوظ **قبل** إغلاق أي حاوية Koin — الحاجز الذي يمنع
     * `stopKoin()` من إغلاق تجمع اتصالات SQLite تحت عملٍ حيّ. الترتيب إلزامي:
     * 1) إيقاف عمل المحرك (`ContactsSyncEngine.stop` المنتظرة تفكك جامعَيها
     * وكوروتين المشاركة حتى `awaitClose` الأخير في مصدر المنصة)، 2) إلغاء
     * [engineScope] والانتظار حتى اكتمال كل أبنائه (المنسّقون)، 3) تصفير أعلام
     * الإطلاق الواحد بعد اكتمال دورة الحياة بالكامل، 4) إعادة إنشاء النطاق طازجاً
     * ليستلمه الاختبار/المسار التالي. لا تُستدعى `stopKoin()` في هذه الدالة —
     * تُستدعى من المستدعي **بعد** عودتها فقط.
     *
     * التغطية بعد دمج T8: كل ما يُطلقه [ensureStarted] — دورة حياة المحرك
     * (كوروتين المشاركة + جامعا الصلاحيات/الإشارات)، صيانة الاستبقاء، تجسيد «أهم 5»،
     * إعادة جدولة التذكير، موافة الطوارئ، ومراقب المكالمات — في [engineScope]،
     * **وأضف إلى ذلك** نطاقات أرحام المملوكة: مجدول التذكير ونطاق أفعال مندوب
     * الإشعارات ([drainForShutdown] لكلٍّ)، وجسر الودجت (إزالة المستمع من القاعدة
     * القديمة ثم [IosArhamWidgetSnapshotPublisher.close]). **خارج التغطية عمداً:**
     * نطاقات `viewModelScope` لـ ViewModels حاوية Koin (مالكها من استدعى الـ getters
     * — يلغيها من حلّها في اختباره بنمط `ContactsViewModelKoinTest` المستقر —
     * مطبَّقة في اختبارات T8)، ونطاق متحكم الرنين المقاطَع (حاوية-النطاق بلا أي
     * مسار اختبار عبر الحاويات؛ كل إطلاقاته بعزل runCatching وتوافى جلساته عند
     * الإقلاع التالي — مقبول بالتصميم)، ونطاق الناشر الخامل في `KoinWiringSmokeTest`
     * (قناة فارغة بلا مستمع — لا لمس لأي تابع).
     *
     * @param strictTimeout الوضع الصارم (الاختبارات): تجاوز المهلة يفشل loudly
     *   ولا يسمح بإغلاق الحاوية تحت عمل حيّ. في مسار الإنتاج الشاذ (تبديل حاوية
     *   في [ensureStarted]) يُسجَّل التجاوز ويُكمل بأفضل جهد — لا يُعطِّل الإقلاع.
     */
    @Suppress("TooGenericExceptionCaught") // العزل الضيق المُعلَّل أدناه — بنمط runGuarded في المحرك
    private fun drainEngineScopeBeforeContainerClose(strictTimeout: Boolean) {
        val scopeJob = engineScope.coroutineContext[Job]
        try {
            runBlocking {
                withTimeout(ENGINE_SCOPE_DRAIN_TIMEOUT_MILLIS) {
                    // خطوة اختيارية بمعزل ضيق: فشل إيقاف المحرك (حاوية غريبة/تعريف
                    // مكسور) لا يجوز أن يتخطى الثابتَ الأكبر — إلغاء النطاق
                    // والانتظار لكل أبنائه. إعادة رمي الإلغاء إلزامية كي لا يبتلع
                    // هذا العزل إلغاء withTimeout نفسه.
                    // خطوة 0 — وقف مراقب المكالمات (خروج المندوب متزامن — نظافة، ليست صحة)
                    drainStep("call-observer stop") { activeCallObserver?.stop() }
                    // خطوة 1 — جدولة التذكير: إلغاء النطاق المملوك والانتظار (بلا لمس
                    // للإشعار المسلَّم للنظام — الزنّاد اليومي يعاد تخطيطه عند كل إقلاع)
                    drainStep("reminder-scheduler drain") { activeArhamScheduler?.drainForShutdown() }
                    // خطوة 2 — نطاق أفعال مندوب الإشعارات: إلغاء وانتظار أي «ذكّرني غداً»
                    drainStep("notification-delegate drain") { activeArhamRegistrar?.drainForShutdown() }
                    // خطوة 3 — وقف إنتاج أحداث الودجت من القاعدة القديمة (إزالة المستمع
                    // ذرّية قبل إغلاق قناة الناشر — أحدث الأحداث فقط قد تصل وكلها آمنة)
                    drainStep("widget-listener removal") {
                        val listener = activeWidgetListener
                        val db = activeWidgetDb
                        if (listener != null && db != null) db.removeArhamDataChangeListener(listener)
                    }
                    // خطوة 4 — إغلاق الناشر: قناة تغلق (يكمل النشر المعلق نفسه) ثم انضمام
                    drainStep("widget-publisher close") { activeWidgetPublisher?.close() }
                    // خطوة 5 — إيقاف المحرك (النمط المرجعي من PR #46)
                    drainStep("engine stop") { activeEngine?.stop() }
                    // خطوة 6 — إلغاء نطاق الكائن والانتظار لكل أبنائه (المنسّقون والموافة
                    // وإعادة الجدولة ومراقبة المكالمات والاستبقاء)
                    scopeJob?.cancelAndJoin()
                }
            }
        } catch (e: TimeoutCancellationException) {
            // طلب الإلغاء قبل أي فشل/تسجيل — لا يبقى أبناء النطاق بلا إلغاء إطلاقاً.
            scopeJob?.cancel()
            if (strictTimeout) {
                error(
                    "IosKoinBootstrap engine-scope drain timed out after " +
                        "$ENGINE_SCOPE_DRAIN_TIMEOUT_MILLIS ms; refusing to close a container under live work."
                )
            }
            // أفضل جهد موثق: النطاق القديم يُترك من غير انضمام عند تجاوز المهلة
            // (طلِب إلغاؤه أعلاه)، والتسجيل loud — لا إغلاق صامت تحت عمل حيّ.
            println(
                "[IosKoinBootstrap] engine-scope drain timed out after " +
                    "$ENGINE_SCOPE_DRAIN_TIMEOUT_MILLIS ms; proceeding with container replacement (best-effort)."
            )
        }
        // الأعلام لا تُصفَّر إلا بعد اكتمال دورة الحياة بالكامل، والنطاق الطازج يُبنى
        // بعد الإلغاء-والانضمام لا قبلهما (وإلا تُركت مهمة قديمة حية بلا مرجع).
        // أعلام T8 لا تُصفَّر إلا بعد اكتمال دورة الحياة بالكامل — وإلا يُسكَت أرحام
        // لبقية عمر العملية بعد أي استبدال حاوية (فجوة الاتحاد النصي للدمج).
        resetCleanupLaunchForTest()
        resetArhamLifecycleFlagsForTest()
        activeEngine = null
        activeCallObserver = null
        activeArhamScheduler = null
        activeArhamRegistrar = null
        activeWidgetPublisher = null
        activeWidgetDb = null
        activeWidgetListener = null
        engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    /**
     * عزل ضيق لخطوة تصريف واحدة: فشلها لا يتخطى الثابت الأكبر (اكتمال التفكيك
     * كله) — يسجَّل بنوعه ويُكمل، بإعادة رمي الإلغاء إلزامياً كي لا يبتلع العزلُ
     * إلغاء withTimeout نفسه (نمط PR #46 المرجعي لعازل المحرك).
     */
    private inline fun drainStep(name: String, step: () -> Unit) {
        try {
            step()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("[IosKoinBootstrap] " + name + " skipped during drain: " + e::class.simpleName)
        }
    }

    /**
     * للاختبارات حصراً (نفس مبدئ خطافات [resetCleanupLaunchForTest] الداخلية):
     * تصريف [engineScope] وانتظار كل أبنائه قبل استدعاء `stopKoin()` في التفكيك —
     * المرآة الإلزامية لترتيب «إيقاف العمل ثم إغلاق قاعدة البيانات». لا يُستدعى من
     * كود الإنتاج إطلاقاً.
     */
    internal fun shutdownForTest() {
        drainEngineScopeBeforeContainerClose(strictTimeout = true)
    }

    /**
     * للاختبارات حصراً (مرآة [resetCleanupLaunchForTest]): يعيد أعلام دورة حياة أرحام
     * الثلاثة (T8-12) إلى ما قبل الإطلاق ليبقى اختبار المهيئ مستقلاً عن ترتيب تنفيذه —
     * لا يُستدعى من كود الإنتاج إطلاقاً.
     */
    internal fun resetArhamLifecycleFlagsForTest() {
        reminderRescheduled = false
        callObserverStarted = false
        notificationDelegateRegistered = false
        widgetBridgeRegistered = false
        emergencyReconciled = false
        materializationLaunched = false
    }

    /**
     * للاختبارات حصراً (مرآة [resetCleanupLaunchForTest]): يعيد علم تجسيد «أهم 5»
     * (T8-15 · F-1) إلى ما قبل الإطلاق ليستقل اختبار مسار الإنتاج عن ترتيب التنفيذ.
     * لا يُستدعى من كود الإنتاج إطلاقاً.
     */
    internal fun resetMaterializationForTest() {
        materializationLaunched = false
    }

    /**
     * F-02: تشغيل صيانة الاستبقاء (تنظيف القبور >30 يوماً والمكالمات >365 يوماً) مرة واحدة
     * لكل عملية على نطاق عمر التطبيق — من كلا مساري [ensureStarted]، والتنفيذ خارج الخيط
     * الرئيسي (Dispatchers.io في الـ DAO) والإخفاق يُسجَّل بلا PII ولا يسقط التطبيق.
     */
    private fun startRetentionCleanup(koin: Koin) {
        if (cleanupLaunched) return
        cleanupLaunched = true
        koin.get<RetentionCleanupCoordinator>().launchIn(engineScope)
        koin.get<MergeSweepCoordinator>().launchIn(engineScope)
    }

    /**
     * T8-15 (F-1 — مرآة SilaApplication): تجسيد «أهم 5 أشخاص» عند إقلاع العملية —
     * مرة واحدة لكل عملية على نطاق عمر التطبيق، والإخفاق يُبتلع داخل المشغِّل
     * (تسجيلٌ بنوعٍ حصراً — بلا PII) فيبقى الخاد غير مرفوع (§F.10) وتُستكمل
     * العملية عند الإقلاع التالي.
     */
    private fun startTopFiveMaterialization(koin: Koin) {
        if (materializationLaunched) return
        materializationLaunched = true
        koin.get<ArhamMaterializationCoordinator>().launchIn(engineScope)
    }

    /**
     * T8-12 (D2 §8 — مرآة SilaApplication): إعادة جدولة التذكير اليومي عند كل تشغيل
     * عملية — الميزة معطّلة؟ إلغاء الطلب المعلّق (فارق iOS الموثّق)؛ وإلا خطةٌ جديدة
     * من `now` (الموعد المفقود أثناء الغياب يُحسب من جديد). idempotent بمرآة الأعلام.
     */
    private fun rescheduleDailyReminder(koin: Koin) {
        if (reminderRescheduled) return
        reminderRescheduled = true
        // حل التبعيات على خيط المستدعي (نمط activeEngine المرجعي): فشل الحل فوري
        // عند الإقلاع، والمرجع الملتقط هو ما يصرفه التصريف لاحقاً — لا koin.get
        // متأخر يضرب حاوية مغلقة (فجوة ClosedScopeException المدمجة).
        val scheduler = koin.get<IosArhamNotificationScheduler>()
        val preferences = koin.get<UserPreferencesDataSource>()
        val planner = koin.get<PlanArhamDailyNotificationUseCase>()
        activeArhamScheduler = scheduler
        engineScope.launch {
            runCatching {
                IosArhamReminderDispatch.rescheduleFromNow(
                    preferences = preferences,
                    planner = planner,
                    scheduler = scheduler,
                )
            }.onFailure {
                if (it is CancellationException) throw it // إلغاء التصريف ليس إخفاقاً
                println("IosArhamReminderDispatch failed: ${it::class.simpleName}")
            }
        }
    }

    /**
     * T8-12 (AC-6 — مرآة SilaApplication): مراقب حالة المكالمات ضمن حياة التطبيق فقط —
     * حالته بلا هوية (`CXCall` لا يعرض رقماً) والربط عبر النطاق المشترك. بدايته
     * idempotent، والإخفاق يُسجَّل بنوعٍ حصراً — بلا انهيار.
     */
    private fun startCallStateObserver(koin: Koin) {
        if (callObserverStarted) return
        callObserverStarted = true
        val observer = koin.get<ArhamCallStateObserver>()
        activeCallObserver = observer
        observer.start(engineScope)
    }

    /**
     * T8-12: تقييد مندوب مركز الإشعارات + تسجيل فئة الأفعال — قبل أول تفاعل مع
     * الإشعارات (استدعاء المهيئ يسبق جسم الواجهة — iOSApp.init).
     */
    private fun registerNotificationCenterDelegate(koin: Koin) {
        if (notificationDelegateRegistered) return
        notificationDelegateRegistered = true
        // الواجهة الصرفة هي مفتاح Koin (لا KClass لأنواع ObjC الفرعية — سجل CI 42177151822).
        // المركز عبر نقطة الوعي بالغياب حصراً (سجل CI 42180300317): اللمس الخام لـ
        // currentNotificationCenter داخل مضيف اختبار K/N العاري يرمي NSException قاتلاً
        // (bundleProxyForCurrentProcess is nil) فتُسقط العملية SIGABRT. بلا مركز
        // (تنفيذية بلا حزمة) تُهمل العملية صامتاً — والتطبيق الحقيقي (حزمة سليمة) لا أثر
        // عليه الفحص أصلاً.
        val center = iosNotificationCenterIfPresent() ?: return
        val registrar = koin.get<IosArhamNotificationCenterRegistering>()
        activeArhamRegistrar = registrar
        registrar.register(center = center)
    }

    /**
     * T8-13 (D6 — مرآة `SilaApplication.registerWidgetRefreshBridge`): يربط ناشر لقطة الودجت
     * كمستمعٍ لأحداث تغيّر البيانات على [LocalSilaDatabase] — كل تغيير أرحام يُعيد نشر اللقطة
     * في حاوية App Group (صامت عند غياب الحاوية). لا كادر دوري على iOS: الإيقاع قرار WidgetKit
     * (§M). idempotent عبر [widgetBridgeRegistered].
     */
    private fun registerWidgetRefreshBridge(koin: Koin) {
        if (widgetBridgeRegistered) return
        widgetBridgeRegistered = true
        val publisher = koin.get<IosArhamWidgetSnapshotPublisher>()
        val db = koin.get<LocalSilaDatabase>()
        val listener: ArhamDataChangeListener = publisher
        db.addArhamDataChangeListener(listener)
        activeWidgetPublisher = publisher
        activeWidgetDb = db
        activeWidgetListener = listener
    }

    /**
     * T8-13 (§N.5 — مرآة `SilaApplication.reconcileInterruptedEmergencySessions`): عند بدء
     * العملية تُصفَّر أي جلسة طوارئ IN_PROGRESS تجاوزت حدها الزمني إلى INTERRUPTED idempotent
     * (محاسبة الحد يصمد — لا وعد بأن الرنين يصمد عبر موت العملية). idempotent.
     */
    private fun reconcileInterruptedEmergencySessions(koin: Koin) {
        if (emergencyReconciled) return
        emergencyReconciled = true
        // حلّ حالة الاستخدام على خيط المستدعي (نمط activeEngine — لا koin.get متأخر
        // يضرب حاوية مغلقة بعد الاستبدال).
        val reconcile = koin.get<ReconcileInterruptedSessionUseCase>()
        engineScope.launch {
            runCatching { reconcile() }
                .onFailure {
                    if (it is CancellationException) throw it // إلغاء التصريف ليس إخفاقاً
                    println("ReconcileInterruptedSessionUseCase failed: ${it::class.simpleName}")
                }
        }
    }

    /**
     * يحسم المسار المطلق لمجلد `Application Support/Sila` وينشئه على القرص إن لم يكن موجوداً.
     * يرمي [IllegalStateException] فوراً إذا تعذر الوصول للمجلد أو إنشاؤه (Fail-Fast).
     */
    @OptIn(ExperimentalForeignApi::class)
    fun resolveApplicationSupportPath(): String {
        val basePath = NSSearchPathForDirectoriesInDomains(
            directory = NSApplicationSupportDirectory,
            domainMask = NSUserDomainMask,
            expandTilde = true
        ).firstOrNull() as? String

        check(!basePath.isNullOrBlank()) {
            "Unable to resolve iOS NSApplicationSupportDirectory in NSUserDomainMask."
        }

        val normalizedBase = basePath.trimEnd('/')
        val appSupportDir = "$normalizedBase/$APP_SUPPORT_SUBDIRECTORY"
        val fileManager = NSFileManager.defaultManager

        if (!fileManager.fileExistsAtPath(appSupportDir)) {
            val created = fileManager.createDirectoryAtPath(
                path = appSupportDir,
                withIntermediateDirectories = true,
                attributes = null,
                error = null
            )
            check(created) {
                "Failed to create iOS Application Support directory at path: $appSupportDir"
            }
        }

        check(fileManager.fileExistsAtPath(appSupportDir)) {
            "iOS Application Support directory does not exist after creation check: $appSupportDir"
        }
        check(appSupportDir != ":memory:") {
            "iOS Application Support path must never be in-memory (:memory:)."
        }

        return appSupportDir
    }

    /**
     * يضمن بدء تشغيل Koin بمسار `Application Support/Sila` الفعلي على القرص بشكل Idempotent،
     * ويتحقق من عدم استخدام التخزين المؤقت في الذاكرة (`:memory:`).
     */
    fun ensureStarted(): Koin {
        val storageDirPath = resolveApplicationSupportPath()
        val expectedDbPath = "$storageDirPath/sila_local.db"
        val expectedPrefsPath = "$storageDirPath/${SilaPreferencesDataSource.DATASTORE_FILE_NAME}"

        val existingKoin = KoinPlatformTools.defaultContext().getOrNull()
        if (existingKoin != null) {
            val existingDb = existingKoin.get<LocalSilaDatabase>()
            val existingPrefs = existingKoin.get<UserPreferencesDataSource>() as? SilaPreferencesDataSource
            if (existingDb.dbFilePath == expectedDbPath && existingPrefs?.storageFilePath == expectedPrefsPath) {
                // start() idempotent — إعادة التشغيل على حاوية قائمة لا تكرر المراقبة.
                val engine = existingKoin.get<ContactsSyncEngine>()
                engine.start(engineScope)
                activeEngine = engine
                startRetentionCleanup(existingKoin)
                startTopFiveMaterialization(existingKoin)
                rescheduleDailyReminder(existingKoin)
                startCallStateObserver(existingKoin)
                registerNotificationCenterDelegate(existingKoin)
                registerWidgetRefreshBridge(existingKoin)
                reconcileInterruptedEmergencySessions(existingKoin)
                return existingKoin
            }
            // مسار الاستبدال الشاذ: نفس ترتيب [shutdownForTest] إلزامياً قبل إغلاق
            // الحاوية القديمة — وإلا أُغلقت قاعدتها تحت عملٍ حيّ (الثغرة نفسها التي
            // أسقطت المدمج T8+DEF-01). بأفضل جهد بحدّ مهلة يُسجَّل ولا يُعطِّل الإقلاع.
            drainEngineScopeBeforeContainerClose(strictTimeout = false)
            stopKoin()
        }

        val koin = ensureKoinStarted(storageDirPath = storageDirPath)
        val db = koin.get<LocalSilaDatabase>()
        val prefs = koin.get<UserPreferencesDataSource>() as? SilaPreferencesDataSource

        check(db.dbFilePath != ":memory:" && db.dbFilePath == expectedDbPath) {
            "IosKoinBootstrap persistence check failed: expected SQLite path '$expectedDbPath', but got '${db.dbFilePath}'."
        }
        check(prefs != null && !prefs.storageFilePath.isNullOrBlank() && prefs.storageFilePath == expectedPrefsPath) {
            "IosKoinBootstrap persistence check failed: expected DataStore path '$expectedPrefsPath', but got '${prefs?.storageFilePath}'."
        }

        val engine = koin.get<ContactsSyncEngine>()
        engine.start(engineScope)
        activeEngine = engine
        startRetentionCleanup(koin)
        startTopFiveMaterialization(koin)
        rescheduleDailyReminder(koin)
        startCallStateObserver(koin)
        registerNotificationCenterDelegate(koin)
        registerWidgetRefreshBridge(koin)
        reconcileInterruptedEmergencySessions(koin)
        return koin
    }

    /**
     * يسترجع [MainViewModel] المشترك بعد ضمان تهيئة Koin بمسار التخزين الدائم.
     */
    fun getMainViewModel(): MainViewModel = ensureStarted().get()

    /**
     * يسترجع [OnboardingViewModel] المشترك (Issue #26 — T5) بعد ضمان تهيئة Koin
     * بمسار التخزين الدائم؛ تستعمله شاشات SwiftUI للتهيئة عبر نمط MainViewModelStore.
     */
    fun getOnboardingViewModel(): OnboardingViewModel = ensureStarted().get()

    /**
     * يسترجع [ContactsViewModel] المشترك (Issue #24 — T3) بعد ضمان تهيئة Koin
     * بمسار التخزين الدائم؛ تستعمله شاشة جهات الاتصال SwiftUI (T4 — Issue #25)
     * عبر نمط ContactsViewModelStore نفسه — الحالة ملكية Kotlin حصراً.
     */
    fun getContactsViewModel(): ContactsViewModel = ensureStarted().get()

    /**
     * T8-14 (§L.1): يسترجع [ArhamViewModel] المشترك بعد ضمان تهيئة Koin بمسار التخزين
     * الدائم؛ تستعمله شاشة أرحام SwiftUI عبر نمط ContactsViewModelStore نفسه —
     * الحالة ملكية Kotlin حصراً (لا منطق أعمال في SwiftUI).
     */
    fun getArhamViewModel(): ArhamViewModel = ensureStarted().get()

    /**
     * يسترجع [ContactDetailsViewModel] لجهة محددة (T6 — Issue #27) عبر `parametersOf`
     * — تعريف Koin المعياري يستقبل المعرّف من التنقل (كل زيارة شاشة = VM مستقل).
     */
    fun getContactDetailsViewModel(contactId: Long): ContactDetailsViewModel =
        ensureStarted().get { parametersOf(contactId) }

    /**
     * يسترجع [ContactEditViewModel] (T6 — Issue #27): [contactId] = `null` نموذج
     * إنشاء فارغ، وإلا نموذج تحرير يُملأ من لقطة الجهة القائمة.
     */
    fun getContactEditViewModel(contactId: Long?): ContactEditViewModel =
        ensureStarted().get { parametersOf(contactId) }

    /** يسترجع [DuplicatesReviewViewModel] لشاشة مراجعة المكررات (T7 · Issue #28). */
    fun getDuplicatesReviewViewModel(): DuplicatesReviewViewModel =
        ensureStarted().get()

    /** يسترجع [SmartFilterReviewViewModel] لشاشة مراجعة الفلترة الذكية (T7 · Issue #28). */
    fun getSmartFilterReviewViewModel(): SmartFilterReviewViewModel =
        ensureStarted().get()

    /**
     * T8-13 (D6): يُعيد ناشر لقطة الودجت لعملية التطبيق — تُستخدم من Swift لضبط مُشغِّل
     * إعادة تحميل الودجت (انظر [attachArhamWidgetReloadTrigger]).
     */
    fun getArhamWidgetPublisher(): IosArhamWidgetSnapshotPublisher = ensureStarted().get()

    /**
     * T8-13 (D6): الواجهة الموجهة لـ Swift — يضبط SwiftUI مُشغِّل إعادة التحميل بعد
     * `ensureStarted()`، ويستدعيه Kotlin بعد كل كتابة لقطةٍ ناجحة في حاوية App Group:
     *
     * ```swift
     * IosKoinBootstrap.shared.attachArhamWidgetReloadTrigger {
     *     WidgetCenter.shared.reloadTimelines(ofKind: ArhamWidgetConstants.kind)
     * }
     * ```
     *
     * سبب الجسر: لا klib لـ WidgetKit في K/N (تم التحقق) — `WidgetCenter` يُستدعى من Swift حصراً.
     */
    fun attachArhamWidgetReloadTrigger(trigger: () -> Unit): Unit =
        getArhamWidgetPublisher().setReloadTrigger(trigger)
}
