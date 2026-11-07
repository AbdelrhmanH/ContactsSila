package app.sila.arham.notification

import app.sila.core.datastore.UserPreferencesDataSource
import app.sila.feature.arham.domain.PlanArhamDailyNotificationUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationAction
import platform.UserNotifications.UNNotificationActionOptionForeground
import platform.UserNotifications.UNNotificationCategory
import platform.UserNotifications.UNNotificationDefaultActionIdentifier
import platform.UserNotifications.UNNotificationPresentationOptionBanner
import platform.UserNotifications.UNNotificationPresentationOptionList
import platform.UserNotifications.UNNotificationResponse
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.darwin.NSObject

/**
 * عقد تقييد مندوب مركز الإشعارات — واجهة Kotlin صرفة بلا ObjC، وهي **مفتاح Koin حصراً**
 * لهذه القدرة: KClass لأنواع Kotlin الفرعية من `NSObject` غير مدعوم على K/N — تسجيل
 * `single` فوق النوع الخرساني يرمي `KClassUnsupportedImpl` وقت تهيئة الملف فيسمّ كلَّ
 * الملفات المتسلسلة (سجل CI run 42177151822)، كما أن خلط Supertypes الكوتلنية مع ObjC
 * ممنوع أصلاً في K/N. لذلك تُربط القدرة عبر غلاف Kotlin صرف
 * [IosArhamNotificationCenterRegistrar] يُغلِّف الخرسانة ObjC.
 */
interface IosArhamNotificationCenterRegistering {

    /** يقيّد المندوب مع مركز الإشعارات المُمرَّر ويسجّل فئة الأفعال (التعيين idempotent). */
    fun register(center: UNUserNotificationCenter)

    /**
     * إيقاف نطاق أفعال الإزاحة القابل للانتظار (تكامل دورة حياة الموارد — للتصريف
     * قبل إغلاق الحاوية حصراً): يلغي نطاق المندوب وينتظر اكتمال أي «ذكّرني غداً»
     * جارية — فلا قراءة Room/DataStore على حاوية مغلقة بعد عودته. لا يلمس تسجيل
     * المندوب لدى النظام (استبدال الحاوية يعيد التقييد بنفسه). Idempotent.
     */
    suspend fun drainForShutdown()
}

/**
 * الغلاف Kotlin الصرف للمندوب — ما يُربط في Koin باسم [IosArhamNotificationCenterRegistering]:
 * الخرسانة ObjC ([IosArhamNotificationCenterDelegate]) تُبنى داخله لحظة الحلّ، ويبقى
 * المفرد مقيَّداً في الرسم عبر هذا العقد.
 */
internal class IosArhamNotificationCenterRegistrar(
    private val delegate: IosArhamNotificationCenterDelegate,
) : IosArhamNotificationCenterRegistering {

    override fun register(center: UNUserNotificationCenter) = delegate.register(center = center)

    override suspend fun drainForShutdown() = delegate.drainForShutdown()
}

/**
 * مندوب مركز إشعارات أرحام على iOS (T8-12 · §M · D1 بند 7) — النقطة الوحيدة التي تلمس
 * `UNUserNotificationCenterDelegate`؛ كل الأفعال ترِد إلى النطاق المشترك حصراً:
 * - **العرض في المقدمة:** بانر + قائمة (مرآة أندرويد: التذكير يظهر حتى والتطبيق مقدماً).
 * - **«اتصل الآن»:** فعل مقدمة — يفتح التطبيق بلا بيانات (المنصة تقدّمه تلقائياً) — بلا
 *   أرقام ولا أسماء في الـ notification، وسجل المحاولات يُكتب من داخل التطبيق عبر مسار
 *   T8-06 القياسي (نمط `ArhamNotificationActionReceiver` — لا PII).
 * - **«ذكّرني غداً»:** فعل خلفي — إزاحةُ جدولةٍ لا حالةُ كتابة: إعادة خطة من `now`
 *   عبر [IosArhamReminderDispatch] ثم مسح الإشعار الحالي (ميزانية ظاهر واحد).
 *
 * لا كتبات Room هنا ولا قرارات حالة — الإخفاق يُسجَّل بنوعٍ حصراً (§O.5).
 */
class IosArhamNotificationCenterDelegate(
    /** نطاق أفعال الإزاحة — عمر تطبيق (كائن المندوب يعيش عمرَ العملية)؛ حقنٌ للاختبار. */
    private val dispatchScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val preferences: UserPreferencesDataSource,
    private val planner: PlanArhamDailyNotificationUseCase,
    private val scheduler: IosArhamNotificationScheduler,
    private val strings: IosArhamNotificationStrings = IosBundleArhamNotificationStrings(),
) : NSObject(), UNUserNotificationCenterDelegateProtocol {

    /**
     * يقيّد المندوب مع تسجيل فئة الأفعال — يُستدعى مرةً من المهيئ (التعيين idempotent).
     * المركز مُمرَّر إلزامياً — عبر [iosNotificationCenterIfPresent] حصراً؛ الافتراضي
     * الخام يُسقط مضيف اختبار K/N (تنفيذية عارية — سجل CI 42180300317).
     */
    fun register(center: UNUserNotificationCenter) {
        center.delegate = this
        val actions = listOf(
            UNNotificationAction.actionWithIdentifier(
                identifier = ARHAM_ACTION_CALL_NOW,
                title = strings.callNowActionTitle(),
                options = UNNotificationActionOptionForeground,
            ),
            UNNotificationAction.actionWithIdentifier(
                identifier = ARHAM_ACTION_REMIND_TOMORROW,
                title = strings.remindTomorrowActionTitle(),
                options = 0UL, // فعل خلفي — بلا تدمير للإشعار قبل المعالجة (وهو يمسحه بنفسه)
            ),
        )
        val category = UNNotificationCategory.categoryWithIdentifier(
            identifier = ARHAM_CATEGORY_REMINDER,
            actions = actions,
            intentIdentifiers = listOf<String>(), // جسر K/N: List<*> نجمي — الاستدلال يحتاج تثبيتاً
            options = 0UL,
        )
        // K/N: معامل `NSSet` يُموَّل بمجموعة Kotlin — لا إنشاء NSSet يدوي.
        center.setNotificationCategories(categories = setOf(category))
    }

    /**
     * إيقاف نطاق الأفعال القابل للانتظار (تكامل دورة حياة الموارد — يُستدعى من
     * الغلاف [IosArhamNotificationCenterRegistrar] في التصريف قبل إغلاق الحاوية
     * حصراً): يلغي [dispatchScope] وينتظر اكتمال أي «ذكّرني غداً» جارية. Idempotent.
     */
    internal suspend fun drainForShutdown() {
        dispatchScope.coroutineContext[Job]?.cancelAndJoin()
    }

    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        willPresentNotification: UNNotification,
        withCompletionHandler: (ULong) -> Unit,
    ) {
        // عرضٌ في المقدمة للتذكير اليومي (بانر + قائمة) — الإشعارات الأخرى (إن أُضيفت
        // لاحقاً) تُقدَّم بالافتراضي المنصّي. ثابتا الخيارات ULong-محوران (جسر K/N).
        withCompletionHandler(UNNotificationPresentationOptionBanner or UNNotificationPresentationOptionList)
    }

    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        didReceiveNotificationResponse: UNNotificationResponse,
        withCompletionHandler: () -> Unit,
    ) {
        when (val action = didReceiveNotificationResponse.actionIdentifier) {
            ARHAM_ACTION_REMIND_TOMORROW -> dispatchScope.launch {
                runCatching {
                    IosArhamReminderDispatch.rescheduleFromNow(
                        preferences = preferences,
                        planner = planner,
                        scheduler = scheduler,
                    )
                }.onFailure {
                    // إعادة رمي الإلغاء إلزامية: كتمها يجعل تفكيك نطاق الأفعال في
                    // drainForShutdown يُسجَّل زوراً كإخفاق إزاحة جدولة.
                    if (it is CancellationException) throw it
                    IosArhamNotificationScheduler.logTypeOnly("Remind-tomorrow reschedule failed", it)
                }
                center.removeDeliveredNotificationsWithIdentifiers(
                    identifiers = listOf(IosArhamNotificationScheduler.REMINDER_IDENTIFIER),
                )
                withCompletionHandler()
            }
            else -> {
                // «اتصل الآن» = فعل مقدمة يفتح التطبيق بلا بيانات؛ نقرة الجسد نفسها
                // (المعرّف الافتراضي) تُصرف بلا عمل — والإشعار يُمسح كما في أندرويد.
                if (action == UNNotificationDefaultActionIdentifier || action == ARHAM_ACTION_CALL_NOW) {
                    center.removeDeliveredNotificationsWithIdentifiers(
                        identifiers = listOf(IosArhamNotificationScheduler.REMINDER_IDENTIFIER),
                    )
                }
                withCompletionHandler()
            }
        }
    }
}

// مُعرّفات عقد الإشعار — على مستوى الملف حصراً (قيود K/N: لا حقول const في Companion
// لأنواع ObjC الفرعية)؛ قيم مُعقومة ثابتة يستهلكها المندوب والتسجيل (T8-14 لاحقاً إن لزم).
internal const val ARHAM_ACTION_CALL_NOW = "app.sila.arham.action.CALL_NOW"
internal const val ARHAM_ACTION_REMIND_TOMORROW = "app.sila.arham.action.REMIND_TOMORROW"
internal const val ARHAM_CATEGORY_REMINDER = "app.sila.arham.category.REMINDER"
