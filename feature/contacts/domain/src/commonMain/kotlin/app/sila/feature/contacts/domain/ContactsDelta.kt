package app.sila.feature.contacts.domain

/**
 * نتيجة قراءة الدلتا من منصة الاتصالات (ADR-0010).
 *
 * - [upserted]: جهات أضيفت/تحدّثت (بحالتها النهائية الكاملة، جاهزة للـ upsert بمفتاح
 *   `contacts.systemContactId`، بلمس أي أعمدة صِلَة: التصنيف/المفضلة/أرحام/الوسوم).
 * - [deletedSystemContactIds]: هويات `systemContactId` المحذوفة من دفتر الهاتف.
 *   **لا تُستنبط أبداً من نقص الظهور أو من صلاحية `.limited` الجزئية** (ADR-0010 §5).
 * - [newSnapshot]: نقطة الاستئناف الجديدة — تكتب داخل نفس معاملة Room مع الدلتا.
 *   `null` لو فشل الإنتاج (لن تُقدَّم أي لقطة/رمز عند أي استثناء — ADR-0010 §8).
 * - [isFullResync]: الإشارة إلى استيراد كامل (أول مزامنة، أو رمز iOS مفقود/غير صالح،
 *   أو `.dropEverything`)؛ عندها تعيد المنصة الحالة الكاملة عبر [upserted] بلا حذف.
 *   الـ upsert idempotent فيُستأنف الاستيراد بعد المقاطعة دون تكرار.
 */
data class ContactsDelta(
    val upserted: List<PlatformContact>,
    val deletedSystemContactIds: List<String>,
    val newSnapshot: ContactsSnapshot?,
    val isFullResync: Boolean,
)
