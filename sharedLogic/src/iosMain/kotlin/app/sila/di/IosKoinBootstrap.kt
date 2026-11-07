package app.sila.di

import app.sila.core.database.dao.LocalSilaDatabase
import app.sila.core.datastore.SilaPreferencesDataSource
import app.sila.core.datastore.UserPreferencesDataSource
import app.sila.feature.contacts.data.ContactsSyncEngine
import app.sila.feature.contacts.presentation.ContactDetailsViewModel
import app.sila.feature.contacts.presentation.ContactEditViewModel
import app.sila.feature.contacts.presentation.ContactsViewModel
import app.sila.feature.contacts.presentation.onboarding.OnboardingViewModel
import app.sila.feature.main.MainViewModel
import app.sila.maintenance.RetentionCleanupCoordinator
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
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

    /**
     * المحرك الذي أطلقه [ensureStarted] على الحاوية الحالية — يُلتقط هنا كي يصرفه
     * [drainEngineScopeBeforeContainerClose] بلا بحث Koin متأخر (بلا إنشاء حثيث
     * لـ `CNContactStore` على حاوية غريبة، وبلا فشل تعريف على حاوية دنيا).
     */
    @Volatile
    private var activeEngine: ContactsSyncEngine? = null

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
     * التغطية: كل ما يُطلقه [ensureStarted] يعيش في [engineScope] — مهمة دورة حياة
     * المحرك (كوروتين المشاركة + جامعا الصلاحيات/الإشارات) ومهمة صيانة الاستبقاء —
     * فالتصريف يغطيها كلها. **خارج التغطية عمداً:** نطاقات `viewModelScope` لـ
     * ViewModels حاوية Koin (مالكها من استدعى الـ getters — يلغيها من حلّها في
     * اختباره بنمط `ContactsViewModelKoinTest` المستقر)، ويلزم تغطيتها عند دمج T8
     * (سجّل في تقرير المهمة ضمن متطلبات الدمج).
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
                    try {
                        activeEngine?.stop()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        println(
                            "[IosKoinBootstrap] engine stop skipped during drain: " +
                                "${e::class.simpleName}"
                        )
                    }
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
        resetCleanupLaunchForTest()
        activeEngine = null
        engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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
}
