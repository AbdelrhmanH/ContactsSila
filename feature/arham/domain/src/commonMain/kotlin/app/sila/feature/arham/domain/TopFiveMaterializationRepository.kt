package app.sila.feature.arham.domain

import app.sila.core.model.ArhamInfo
import app.sila.core.model.Contact
import app.sila.core.model.TopFiveSelection

/**
 * عقد مستودع تجسيد «أهم 5 أشخاص» (طبقة Domain داخل :feature:arham:domain — DIP · T8-04).
 *
 * يعمل محلياً 100%: الانتقاء من DataStore (الـ SSOT للقائمة المعلّقة — §F.1)، والكتابة
 * عبر عقود الواجهة في `:core:database` حصراً (§F.8/§F.9) — لا اعتماد على
 * `:feature:contacts` إطلاقاً (Konsist). بلا أنواع Room أو منصة (نمط [ArhamCallEventRepository]).
 */
interface TopFiveMaterializationRepository {

    /** هل رُفعت علامة الإنجاز الواحدة؟ (§F.10 — الحارس الأول للتشغيل). */
    suspend fun isMaterialized(): Boolean

    /**
     * رفع علامة الإنجاز (§F.9/§F.10) — تُستدعى **بعد** نجاح معاملة Room حصراً؛
     * المسار رفعٌ حصراً ولا يُعاد فتحها أبداً (one-shot).
     */
    suspend fun markMaterialized()

    /** القائمة المعلّقة من التهيئة الأولى (§F.1 — مصدر البيانات المُنتظِرة للتجسيد). */
    suspend fun pendingSelections(): List<TopFiveSelection>

    /** هل جهات الاتصال جاهزة (سجل حيّ غير فارغ)؟ (§F.2 — حارس ما قبل الإسقاط). */
    suspend fun hasLiveContacts(): Boolean

    /**
     * الجهات الحيّة التي هويتها النظامية ضمن [systemContactIds] (§F.3). القائمة قد تحمل
     * هوية مكررة (فهرس غير فريد) — التقارير والقرارات تتعامل مع القائمة لا خريطة.
     */
    suspend fun findContactsBySystemContactIds(systemContactIds: Set<String>): List<Contact>

    /**
     * تطبيق دفعة التجسيد — معاملة Room واحدة في التنفيذ (§F.8/§F.9). الفشل ينتشر
     * بلا ابتلاع: تُرجَع المعاملة كلها وتظل علامة الإنجاز غير مرفوعة (إعادة تشغيل
     * idempotent — §F.6).
     */
    suspend fun applyMaterialization(
        targets: List<TopFiveMaterializationTarget>,
    ): TopFiveMaterializationWriteCounts
}

/**
 * هدف تجسيد فردي (T8-04 · §F.4/§F.5): الفرق المطلوب على جهة حيّة موجودة — [contactId]
 * معرف القاعدة، و[arham] المعلومة المُراد ملؤها إن غاب صفها وقت الكتابة (الحارس النهائي
 * على حقيقة القاعدة داخل المستودع؛ معلومة المستخدم القائمة تكسب بعد أي تسابق).
 */
data class TopFiveMaterializationTarget(
    val contactId: Long,
    val arham: ArhamInfo,
)

/**
 * أعداد الكتابة كما أعادتها طبقة البيانات **بعد التسابق** (T8-04 · §F.7/§F.11) — مصدر
 * حقيقة تقرير النتيجة (لا أعداد اللقطة التي بُني عليها القرار).
 */
data class TopFiveMaterializationWriteCounts(
    /** جهات مُوِّدت فعلاً: صف `arham` كان غائباً فمُلئ من الاختيار (§F.4). */
    val applied: Int,
    /** جهات حُفظت معلوماتها الذاتية القائمة — وُحِّد تصنيفها حصراً (§F.5). */
    val preservedUserOwned: Int,
    /** أهداف لم تجد جهة حيّة وقت الكتابة (حُذفت/تزالت وسطياً — تُحسب إسقاطاً §F.7). */
    val skippedMissing: Int,
)
