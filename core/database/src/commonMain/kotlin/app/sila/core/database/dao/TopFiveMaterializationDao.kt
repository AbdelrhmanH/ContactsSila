package app.sila.core.database.dao

import app.sila.core.model.ArhamInfo
import app.sila.core.model.Contact

/**
 * تجسيد «أهم 5 أشخاص» من التهيئة الأولى (T8-04 · §F من الخطة المُصادقة · Issue #29).
 *
 * عقد نطاق بلا شروح Room (نمط [ArhamDao]/[ContactsSyncDao] — ADR-0011): التنفيذ الفعلي
 * في الواجهة (`LocalSilaDatabase`) عبر SQL خام مُوجَّه داخل معاملة IMMEDIATE واحدة
 * (§F.8/§F.9 — نفس عهد مسار `upsertContact` المركّب).
 *
 * قيود لا تُكسر:
 * - **التطابق** هوية المنصة `systemContactId` حصراً (§F.3) — `displayName` للعرض فقط.
 * - **F.5:** معلومة أرحام يملكها المستخدم تسبق الاختيار حصراً — الحارس يُطبَّق على
 *   حقيقة القاعدة وقت الكتابة (صف `arham` قائم يبقى كما هو)، و`lastContactedAt`
 *   لا يُلمس إطلاقاً (AC-8).
 * - **لا صفوف وهمية:** التحديث يستهدف جهات حيّة موجودة حصراً — الجديدة لا تُنشأ (§F.7).
 * - **لا `updatedAt` ولا FTS:** تحديث التصنيف لا يغيّر أعمدة FTS ولا ختم آخر تحديث
 *   (سبق `updateCategories` — §F.9).
 */
interface TopFiveMaterializationDao {

    /** هل توجد جهات حيّة في المرآة (كاش ما بعد COMMIT المتطابق مع القاعدة)؟ (§F.2 حارس الجاهزية). */
    suspend fun hasLiveContacts(): Boolean

    /**
     * كل الجهات الحيّة التي `systemContactId` لها ضمن [systemContactIds] بترتيب `id`
     * تصاعدياً — قراءة من كاش الذاكرة الكامل (SSOT المتطابق مع Room بعد كل COMMIT؛
     * نمط `findContactsByE164`). فهرس الهوية النظامية **غير فريد** وقد تتكرر الهوية
     * بين صفّين — فالقائمة لا تخفي تكراراً صامتاً.
     */
    suspend fun findContactsBySystemContactIds(systemContactIds: Set<String>): List<Contact>

    /**
     * تطبيق دفعة تجسيد ذرّية (§F.8/§F.9): لكل هدف — `category = FAMILY` حصراً
     * (تحديث مُوجَّه بلا أعمدة أخرى)، وصف `arham` يُملأ **فقط** بغيابه على حقيقة
     * القاعدة (حارس NOT EXISTS على وقت الكتابة = قرار F.5 حتى بعد أي تسابق).
     * الكل داخل **معاملة IMMEDIATE واحدة**؛ فشل أي جزء يُرجِع الدفعة كلها، وعلامة
     * الإنجاز (DataStore) تبقى غير مرفوعة فيُعاد المحاولة عند الإقلاع التالي
     * (idempotent — §F.6).
     *
     * الأهداف **فوارق** لا لقطات: تُعالَج على الحالة الحيّة وقت الكتابة داخل قفل
     * الكاتب — لا يُداس على كتابة متزامنة (مزامنة/مستخدم) بين القرار والتطبيق.
     *
     * @return حقيقة ما بعد التسابق: ما مُوِّد فعلاً، وما حُفظ لأصحابه، وما تُخِطي
     *   لغياب جهة حيّة.
     */
    suspend fun applyTopFiveMaterialization(
        updates: List<TopFiveMaterializationUpdate>,
    ): TopFiveMaterializationWriteResult
}

/** هدف تجسيد فردي — الفرق المطلوب على جهة حيّة موجودة (`contactId` = معرف القاعدة). */
data class TopFiveMaterializationUpdate(
    val contactId: Long,
    val arham: ArhamInfo,
)

/** حقيقة ما بعد التسابق لدفعة تجسيد (مصدر أعداد التقرير — §F.7/§F.11). */
data class TopFiveMaterializationWriteResult(
    /** جهات مُوِّدت فعلاً: صف `arham` كان غائباً فمُلئ من الاختيار (§F.4). */
    val materialized: Int,
    /** جهات حفظت معلوماتها الذاتية القائمة (§F.5 — التصنيف وُحِّد حصراً). */
    val preservedUserOwned: Int,
    /** أهداف لم تجد جهة حيّة وقت الكتابة (حُذفت/تزالت وسطياً — §F.7). */
    val skippedMissing: Int,
)
