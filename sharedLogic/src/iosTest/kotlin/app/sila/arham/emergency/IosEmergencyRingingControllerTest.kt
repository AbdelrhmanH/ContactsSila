package app.sila.arham.emergency

import app.sila.arham.notification.ArhamAuthorizationStatus
import app.sila.arham.notification.ArhamNotificationCapability
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.EmergencyRingingSession
import app.sila.core.model.Phone
import app.sila.core.model.RingingStatus
import app.sila.feature.arham.domain.EmergencySessionRepository
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import platform.UserNotifications.UNNotificationInterruptionLevel
import platform.UserNotifications.UNMutableNotificationContent

/**
 * اختبارات مُتحكم حلقة الطوارئ على iOS (T8-13 · §L.6/§N.3 · AC-14).
 *
 * كل أعتماديات المنصة مخيطة حصصاً: `openUrl/canOpenUrl` مسجِّلان، ومركز الإشعارات `null`
 * (مسار النشر يتخطاه بهدوء — مرآة T8-12)، وخيط الواجهة `EmptyCoroutineContext` والنطاق
 * `Unconfined` فيتزامن `start/placeCall` كلياً داخل خيط الاختبار — بلا انتظار ولا `runBlocking`
 * ولا `Dispatchers.setMain` (المُزوِّد حثيف التقييم لا يُلمس عند الإنشاء).
 *
 * قراءة Room تُعاد في كل محاولة (ids-only): المزيّف يسجّل عدّاد القراءات ليُثبت
 * إعادة القراءة الحية (لا احتفاظ برقم في الحالة بين الطبقات — ADR-0006).
 */
class IosEmergencyRingingControllerTest {

    // ---------------------------------------------------------------- مزيّفات

    private class FakeEmergencySessionRepository(initial: Contact?) : EmergencySessionRepository {
        var contact: Contact? = initial
        var getContactByIdCalls = 0

        override suspend fun countSessionsStartedSince(cutoffMillis: Long): Int = 0

        override suspend fun findInProgressSession(): EmergencyRingingSession? = null

        override fun observeInProgressSession(): Flow<EmergencyRingingSession?> = MutableStateFlow(null)

        override suspend fun insertInProgressSession(contactId: Long, startedAtMillis: Long): Long = 0L

        override suspend fun recordAttempt(sessionId: Long, atMillis: Long): Int? = null

        override suspend fun finishSession(sessionId: Long, status: RingingStatus, atMillis: Long): Boolean = false

        override suspend fun getContactById(contactId: Long): Contact? {
            getContactByIdCalls++
            return contact
        }
    }

    /** مُحلّ نصوص مُرسَب — يُثبت أن نصوص الإشعار تأتي من المحقون حصراً. */
    private object MarkerStrings : IosArhamEmergencyStrings {
        override fun activeTitle(): String = "عنوان_طوارئ"
        override fun activeBody(displayName: String): String = "متن:$displayName"
        override fun targetUnknown(): String = "مجهول"
    }

    private class Harness(repo: FakeEmergencySessionRepository, canOpen: Boolean = true) {
        val openedUrls = mutableListOf<String>()
        val controller = IosEmergencyRingingController(
            repository = repo,
            strings = MarkerStrings,
            actionContext = Dispatchers.Unconfined,
            mainDispatcherProvider = { EmptyCoroutineContext },
            canOpenUrl = { canOpen },
            openUrl = { url -> openedUrls += url.absoluteString ?: "<null>" },
            centerProvider = { null }, // بلا مركز إشعارات في الاختبار — النشر يتخطى بهدوء
        )
    }

    private fun contact(displayName: String = "أم الاختبار", phones: List<Phone> = emptyList()) = Contact(
        id = 99L,
        systemContactId = "sys-99",
        name = ContactName(displayName = displayName),
        phones = phones,
        category = Category.EMERGENCY,
    )

    private fun phone(e164: String, primary: Boolean) = Phone(raw = e164, e164 = e164, isPrimary = primary)

    // ------------------------------------------------------------- start: الاتصال

    @Test
    fun start_dialsThePrimaryPhoneReadFromRoomAtCallTime() {
        val repo = FakeEmergencySessionRepository(
            contact(phones = listOf(phone("+96650000002", primary = false), phone("+96650000001", primary = true))),
        )
        val harness = Harness(repo)

        harness.controller.start(sessionId = 10L, contactId = 99L)

        assertEquals(listOf("tel:+96650000001"), harness.openedUrls, "الرقم الأساسي يُفضَّل ويُفتح عبر tel:")
        assertTrue(harness.controller.isRunning(10L), "بداية الحلقة تُعلَّم الجلسة جارية")
    }

    @Test
    fun start_fallsBackToTheFirstPhone_whenNoneIsPrimary() {
        val repo = FakeEmergencySessionRepository(
            contact(phones = listOf(phone("+96650000007", primary = false), phone("+96650000008", primary = false))),
        )
        val harness = Harness(repo)

        harness.controller.start(sessionId = 10L, contactId = 99L)

        assertEquals(listOf("tel:+96650000007"), harness.openedUrls, "بلا أساسي: أول رقم بالترتيب — مرآة حلقة أندرويد")
    }

    @Test
    fun start_skipsTheDialSilently_whenContactHasNoPhones() {
        val repo = FakeEmergencySessionRepository(contact(displayName = "بلا أرقام", phones = emptyList()))
        val harness = Harness(repo)

        harness.controller.start(sessionId = 10L, contactId = 99L)

        assertTrue(harness.openedUrls.isEmpty(), "بلا أرقام: لا اتصال إطلاقاً")
        assertTrue(harness.controller.isRunning(10L), "الجلسة تبقى جارية — الإيقاف قرار الواجهة (T8-14)")
    }

    @Test
    fun start_skipsTheDialSilently_whenContactWasDeleted() {
        val repo = FakeEmergencySessionRepository(initial = null)
        val harness = Harness(repo)

        harness.controller.start(sessionId = 10L, contactId = 99L)

        assertTrue(harness.openedUrls.isEmpty(), "جهة محذوفة لحظة الاتصال: تخطٍّ هادئ بلا استثناء")
        assertEquals(1, repo.getContactByIdCalls, "قراءة Room لحظة الاتصال حصراً")
    }

    @Test
    fun start_skipsTheDial_whenTelSchemeIsUnavailable() {
        val repo = FakeEmergencySessionRepository(contact(phones = listOf(phone("+96650000001", primary = true))))
        val harness = Harness(repo, canOpen = false)

        harness.controller.start(sessionId = 10L, contactId = 99L)

        assertTrue(harness.openedUrls.isEmpty(), "بلا شريحة/iPad: canOpenURL يمنع فتح tel: هادئاً")
    }

    @Test
    fun start_isIdempotent_onDuplicateSessionStart() {
        val repo = FakeEmergencySessionRepository(contact(phones = listOf(phone("+96650000001", primary = true))))
        val harness = Harness(repo)

        harness.controller.start(sessionId = 10L, contactId = 99L)
        harness.controller.start(sessionId = 11L, contactId = 99L)

        assertEquals(1, repo.getContactByIdCalls, "البداية الثانية تُتجاهل قبل أي قراءة Room")
        assertEquals(1, harness.openedUrls.size, "ولا اتصال ثانٍ مُفتوح")
        assertTrue(harness.controller.isRunning(10L), "الجلسة الأولى تبقى الحاوية للحلقة")
        assertFalse(harness.controller.isRunning(11L), "الجلسة الثانية لم تُقبل")
    }

    @Test
    fun isRunning_isFalseForAnyOtherSession() {
        val repo = FakeEmergencySessionRepository(contact(phones = listOf(phone("+96650000001", primary = true))))
        val harness = Harness(repo)

        harness.controller.start(sessionId = 10L, contactId = 99L)

        assertFalse(harness.controller.isRunning(11L), "isRunning مقيَّدة بمعرف الجلسة الحصراً")
    }

    // --------------------------------------------------------------- stop / placeCall

    @Test
    fun stop_clearsTheRunningState_andStaysIdempotent() {
        val repo = FakeEmergencySessionRepository(contact(phones = listOf(phone("+96650000001", primary = true))))
        val harness = Harness(repo)
        harness.controller.start(sessionId = 10L, contactId = 99L)

        harness.controller.stop() // مركز الإشعارات null: يُصفَّر الحالة ويتخطى إزالة الإشعار بهدوء
        harness.controller.stop() // ثانٍ بلا أثر (idempotent §N.8)

        assertFalse(harness.controller.isRunning(10L), "stop يُصفّر حالة الحلقة")
    }

    @Test
    fun stop_beforeAnyStart_isAQuietNoOp() {
        val harness = Harness(FakeEmergencySessionRepository(initial = null))

        harness.controller.stop()

        assertFalse(harness.controller.isRunning(10L), "stop قبل البدء عملية ساكنة (عقد الواجهة)")
    }

    @Test
    fun placeCall_reReadsRoomAndDialsTheFreshNumber() {
        val repo = FakeEmergencySessionRepository(contact(phones = listOf(phone("+96650000001", primary = true))))
        val harness = Harness(repo)

        harness.controller.placeCall(contactId = 99L)
        repo.contact = contact(phones = listOf(phone("+96650000009", primary = true)))
        harness.controller.placeCall(contactId = 99L)

        assertEquals(
            listOf("tel:+96650000001", "tel:+96650000009"),
            harness.openedUrls,
            "كل محاولة تُعيد قراءة الرقم من Room لحظتها — لا احتفاظ بالرقم بين الطبقات",
        )
        assertEquals(2, repo.getContactByIdCalls, "قراءة حرة مستقلة لكل محاولة (ids-only · §N.3)")
    }

    @Test
    fun placeCall_skipsQuietly_whenContactWasDeleted() {
        val repo = FakeEmergencySessionRepository(initial = null)
        val harness = Harness(repo)

        harness.controller.placeCall(contactId = 99L)

        assertTrue(harness.openedUrls.isEmpty(), "إعادة الاتصال لجهة محذوفة: هدوء تام")
    }

    // ------------------------------------------------------- محتوى الإشعار (فرع D7)

    @Test
    fun buildActiveContent_standardVariant_isFallbackFirstWithoutCriticalClaim() {
        val content: UNMutableNotificationContent =
            Harness(FakeEmergencySessionRepository(initial = null))
                .controller
                .buildActiveContent(displayName = "أم الاختبار", critical = false)

        assertEquals("عنوان_طوارئ", content.title, "العنوان من المُحلّ المحقون حصراً")
        assertEquals("متن:أم الاختبار", content.body, "المتن يُنسَّق داخل المُحلّ باسم الظهور")
        // النسخة القياسية لا تلمس مستوى المقاطعة إطلاقاً — وجسر K/N يُرجع افتراض المنصة
        // (`active`) للخاصية غير المضبوطة (لا قيمة null على ObjC). الافتراضي بلا ادعاء
        // حرج — D7 fallback-first كما هو.
        assertEquals(
            UNNotificationInterruptionLevel.UNNotificationInterruptionLevelActive,
            content.interruptionLevel,
            "النسخة القياسية بلا ادعاء حرج: افتراض المنصة Active حصراً — D7 fallback-first",
        )
        assertEquals(
            "app.sila.arham.emergency.active",
            IosEmergencyRingingController.EMERGENCY_NOTIFICATION_IDENTIFIER,
            "المُعرّف الثابت عقد الاستبدال لا التراكم",
        )
    }

    @Test
    fun buildActiveContent_criticalVariant_setsTheCriticalInterruptionLevel() {
        val content: UNMutableNotificationContent =
            Harness(FakeEmergencySessionRepository(initial = null))
                .controller
                .buildActiveContent(displayName = "أم الاختبار", critical = true)

        assertEquals(
            UNNotificationInterruptionLevel.UNNotificationInterruptionLevelCritical,
            content.interruptionLevel,
            "النسخة الحرجة تُبنى فقط بعد فحص القدرة — تُستخدم حين يُعلن الفحص الدعم والتفعيل معاً",
        )
    }

    // -------------------------------------------------------------- دوال نقية مساندة

    @Test
    fun pickPrimaryPhone_prefersPrimary_thenFirstFallback() {
        val both = contact(
            phones = listOf(phone("+96650000002", primary = false), phone("+96650000001", primary = true)),
        )
        assertEquals("+96650000001", IosEmergencyRingingController.pickPrimaryPhone(both))

        val nonePrimary = contact(
            phones = listOf(phone("+96650000002", primary = false), phone("+96650000003", primary = false)),
        )
        assertEquals("+96650000002", IosEmergencyRingingController.pickPrimaryPhone(nonePrimary))

        assertEquals(null, IosEmergencyRingingController.pickPrimaryPhone(contact(phones = emptyList())))
    }

    @Test
    fun displayNameOf_fallsBackToPlaceholder_whenBlank() {
        assertEquals("اسم ظهور", IosEmergencyRingingController.displayNameOf(contact(displayName = "اسم ظهور")))
        assertEquals(
            " ",
            IosEmergencyRingingController.displayNameOf(contact(displayName = "   ")),
            "الاسم الأبيض يُستبدل بمسافة بدل فراغٍ صامت في متن الإشعار",
        )
    }

    @Test
    fun isCriticalEligible_requiresBothSupportAndEnablement() {
        fun capability(supported: Boolean, enabled: Boolean) = ArhamNotificationCapability(
            authorizationStatus = ArhamAuthorizationStatus.AUTHORIZED,
            criticalAlertSupported = supported,
            criticalAlertEnabled = enabled,
        )

        assertTrue(
            capability(supported = true, enabled = true).isCriticalEligible(),
            "الدعم والتفعيل معاً ⇒ النسخة الحرجة مسموحة",
        )
        assertFalse(
            capability(supported = true, enabled = false).isCriticalEligible(),
            "دعم بلا تفعيل ⇒ قياسي (fallback-first)",
        )
        assertFalse(
            capability(supported = false, enabled = true).isCriticalEligible(),
            "تفعيل بلا دعم (محاكي/بلا استحقاق) ⇒ قياسي — لا ادعاء حرج",
        )
        assertFalse(
            capability(supported = false, enabled = false).isCriticalEligible(),
            "لا دعم ولا تفعيل ⇒ قياسي",
        )
    }
}
