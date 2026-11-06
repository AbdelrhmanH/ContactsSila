package app.sila.di

import app.sila.arham.notification.IosArhamNotificationCenterDelegate
import app.sila.arham.notification.IosArhamReminderDispatch
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
import app.sila.feature.contacts.presentation.onboarding.OnboardingViewModel
import app.sila.feature.arham.domain.ReconcileInterruptedSessionUseCase
import app.sila.feature.arham.presentation.ArhamViewModel
import app.sila.feature.main.MainViewModel
import app.sila.maintenance.RetentionCleanupCoordinator
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

    /** نطاق كائن محفوظ لمهام محرك المزامنة طويلة العمر على Native — كائن `object`
     * لا يُجمع مهما طال عمر المراقبة (إلا بتفكيك العملية نفسها). */
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** F-02: علم تشغيل واحد لصيانة الاستبقاء لكل عملية (idempotent — كما start أعلاه). */
    private var cleanupLaunched = false

    /** T8-12 (D2 §8): علم إعادة جدولة واحدة للتذكير اليومي لكل عملية (idempotent). */
    private var reminderRescheduled = false

    /** T8-12 (AC-6): علم بدء مراقب المكالمات لكل عملية — ضمن حياة التطبيق فقط. */
    private var callObserverStarted = false

    /** T8-12: علم تقييد مندوب مركز الإشعارات لكل عملية (التعيين idempotent). */
    private var notificationDelegateRegistered = false

    /** T8-13 (D6): علم تسجيل جسر نشر لقطة الودجت على قاعدة البيانات (مرآة أندرويد، idempotent). */
    private var widgetBridgeRegistered = false

    /** T8-13 (§N.5): علم مُوافَة جلسات الطوارئ المقطوعة عند بدء العملية (مرآة أندرويد، idempotent). */
    private var emergencyReconciled = false

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
    }

    /**
     * T8-12 (D2 §8 — مرآة SilaApplication): إعادة جدولة التذكير اليومي عند كل تشغيل
     * عملية — الميزة معطّلة؟ إلغاء الطلب المعلّق (فارق iOS الموثّق)؛ وإلا خطةٌ جديدة
     * من `now` (الموعد المفقود أثناء الغياب يُحسب من جديد). idempotent بمرآة الأعلام.
     */
    private fun rescheduleDailyReminder(koin: Koin) {
        if (reminderRescheduled) return
        reminderRescheduled = true
        engineScope.launch {
            runCatching {
                IosArhamReminderDispatch.rescheduleFromNow(
                    preferences = koin.get(),
                    planner = koin.get(),
                    scheduler = koin.get(),
                )
            }.onFailure { println("IosArhamReminderDispatch failed: ${it::class.simpleName}") }
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
        koin.get<ArhamCallStateObserver>().start(engineScope)
    }

    /**
     * T8-12: تقييد مندوب مركز الإشعارات + تسجيل فئة الأفعال — قبل أول تفاعل مع
     * الإشعارات (استدعاء المهيئ يسبق جسم الواجهة — iOSApp.init).
     */
    private fun registerNotificationCenterDelegate(koin: Koin) {
        if (notificationDelegateRegistered) return
        notificationDelegateRegistered = true
        koin.get<IosArhamNotificationCenterDelegate>().register()
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
        val listener: ArhamDataChangeListener = koin.get()
        koin.get<LocalSilaDatabase>().addArhamDataChangeListener(listener)
    }

    /**
     * T8-13 (§N.5 — مرآة `SilaApplication.reconcileInterruptedEmergencySessions`): عند بدء
     * العملية تُصفَّر أي جلسة طوارئ IN_PROGRESS تجاوزت حدها الزمني إلى INTERRUPTED idempotent
     * (محاسبة الحد يصمد — لا وعد بأن الرنين يصمد عبر موت العملية). idempotent.
     */
    private fun reconcileInterruptedEmergencySessions(koin: Koin) {
        if (emergencyReconciled) return
        emergencyReconciled = true
        engineScope.launch {
            runCatching { koin.get<ReconcileInterruptedSessionUseCase>()() }
                .onFailure { println("ReconcileInterruptedSessionUseCase failed: ${it::class.simpleName}") }
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
                existingKoin.get<ContactsSyncEngine>().start(engineScope)
                startRetentionCleanup(existingKoin)
                rescheduleDailyReminder(existingKoin)
                startCallStateObserver(existingKoin)
                registerNotificationCenterDelegate(existingKoin)
                registerWidgetRefreshBridge(existingKoin)
                reconcileInterruptedEmergencySessions(existingKoin)
                return existingKoin
            }
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

        koin.get<ContactsSyncEngine>().start(engineScope)
        startRetentionCleanup(koin)
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
