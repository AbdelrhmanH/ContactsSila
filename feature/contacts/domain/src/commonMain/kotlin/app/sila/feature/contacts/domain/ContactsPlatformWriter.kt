package app.sila.feature.contacts.domain

import app.sila.core.common.AppResult
import app.sila.core.model.Contact
import app.sila.core.model.ContactAccount
import app.sila.core.model.ContactName
import app.sila.core.model.Email
import app.sila.core.model.Organization
import app.sila.core.model.Phone

/**
 * **إسقاط الكتابة المسموحة — الجسر النطاقي لكاتب المنصة** (T6 · Issue #27 ·
 * [ADR-0012] docs/adr/0012-contact-write-path-consistency.md).
 *
 * النوع موجود لكي يكون **التضييق بنيوياً لا اتفاقياً**: يُبنى حصراً من [Contact.toWritable]
 * (دالة نقية في النطاق)، ولا يحمل **أبداً** أي حقل صِلَة-فقط (ملاحظات/تواريخ/عناوين/
 * حقول مخصصة/تصنيف/مفضلة/رنّة/صد صوتي/حجب/أرحام). أي تنفيذ كاتب يستهلك هذا الإسقاط
 * فحسب — فلا سبيل نوعياً لمحو حقول لا تملكها صِلَة في دفتر الهاتف ولا كتابة بيانات
 * صِلَة الخاصة إلى تطبيقات أخرى (قاعدة الخصوصية — AGENTS.md).
 *
 * دلالات الهوية — مطابقة للعقد المجمد [ContactsPlatformSource] (ADR-0009 §4):
 * - **Android:** [systemContactId] يحمل `LOOKUP_KEY`؛ التنفيذ يحدّد الصفوف عبر
 *   `CONTENT_LOOKUP_URI` وأعضاء Data عبر `Data._ID` (تحديث مُوجَّه لا إعادة إدراج).
 * - **iOS:** [systemContactId] يحمل `CNContact.identifier`؛ التنفيذ يقيّد `keysToFetch`
 *   بالمفاتيح القابلة للكتابة فقط ويطلب `.readWrite` عند أول تعديل.
 *
 * **الطفرات الضيقة (Narrow-mutation):** كل من [phones]/[emails]/[websites] يُكتب
 * كمجموعة كاملة تابعة للجهة (حذف القديم غير المُدار من صِلَة **ممنوع**) — التنفيذات
 * تُطابق أعضاء Data الموجودة بمعرّفاتها وتحدّثها، لا تمحو RawContact ولا صف Contact.
 */
data class PlatformWritableContact(
    /** هوية الجهة الدائمة — `null` حصراً عند الإنشاء (لا صف منصوي بعد). */
    val systemContactId: String?,
    /** Android: مرآة `LOOKUP_KEY` لترميز `CONTENT_LOOKUP_URI` — `null` على iOS. */
    val lookupKey: String?,
    val name: ContactName,
    /** بايتات JPEG مُعالَجة مسبقاً (قصّ مركزي + تصغير ≤512px عبر [ContactImageProcessor]) — `null` = بلا صورة. */
    val photoBytes: ByteArray?,
    val phones: List<Phone>,
    val emails: List<Email>,
    val websites: List<String>,
    val organization: Organization?,
    /**
     * حساب الإدراج عند الإنشاء (Android: `accountType`/`accountName` — جهاز أو Google،
     * لا SIM) — يُهمَل عند التحديث (نقل الحساب خارج نطاق T6). على iOS يُهمَل دائماً.
     */
    val account: ContactAccount?,
)

/** هوية صف منصوي مُنشأ حديثاً — تُكتب فوراً في Room مع الدفعة نفسها (J-1 · ADR-0012). */
data class CreatedPlatformContact(
    val systemContactId: String,
    /** Android: `LOOKUP_KEY` الجديد للصف المُنشأ — `null` على iOS. */
    val lookupKey: String?,
)

/**
 * **العقد النطاقي لكتابة جهات الاتصال إلى دفتر الهاتف** — مرآة [ContactsPlatformSource]
 * للكتابة (DIP): واجهة نقية بلا أنواع منصات، تُنفَّذ في `:feature:contacts:data`
 * (`AndroidContactsPlatformWriter` / `IosContactsPlatformWriter`) وتُربط عبر Koin
 * في موديولات DI المنصات.
 *
 * ## ميثاق الاتساق (J-1 · ADR-0012) — المنصة أولاً
 * كل مسار حفظ: كتابة المنصة ← **ثم** كتابة Room داخل معاملة واحدة ← ثم تعويض آمن عند
 * فشل Room (حذف المُنشأ / استعادة السابق). هذا العقد يعيد [AppResult] حصراً — لا استثناءات
 * عابرة للحد؛ كل فشل يُصنَّف [DataError] ليعرضه الـ ViewModel بنص مترجم.
 */
interface ContactsPlatformWriter {

    /**
     * إنشاء صف منصوي جديد من الإسقاط المسموح.
     *
     * @return هوية الصف المُنشأ ([CreatedPlatformContact]) أو خطأ مُصنَّف — أبرزه
     *   [DataError.Permission] عند انعدام صلاحية الكتابة (Android `WRITE_CONTACTS`).
     */
    suspend fun createContact(writable: PlatformWritableContact): AppResult<CreatedPlatformContact>

    /**
     * تحديث طفير ضيق لصف منصوي قائم — بمفتاح [PlatformWritableContact.systemContactId]
     * حصراً (ADR-0009). يُحدَّث الحقول المُدارة فقط (الاسم/الأرقام/البريد/المواقع/
     * المؤسسة/الصورة)؛ أي حقل آخر في دفتر الهاتف (ملاحظات طرف ثالث، تواريخ منسوخة،
     * أعضاء MIME أخر) **يجب أن ينجو حرفياً**.
     */
    suspend fun updateContact(writable: PlatformWritableContact): AppResult<Unit>

    /**
     * حذف صف منصوي بهويته الدائمة. يُستدعى **قبل** تعليم القبر في Room حصراً —
     * حذف المنصة هو مصدر الحقيقة للحذف (J-1: إن فشل هنا لا يُمس Room إطلاقاً).
     */
    suspend fun deleteContact(systemContactId: String, lookupKey: String?): AppResult<Unit>
}

/**
 * الإسقاط النقي: [Contact] ← [PlatformWritableContact] — حقول الكتابة المسموحة حصراً
 * (دالة نقية قابلة للاختبار على JVM — قرار T6: منطق الفروق المشترك يُختبر بلا منصات).
 *
 * - **صِلَة-فقط يُهمَل بنيوياً:** notes/dates/addresses/customFields/category/isFavorite/
 *   ringtoneUri/sendToVoicemail/isBlocked/arham — لا تعبر الحد أبداً (ADR-0012).
 * - **الصورة:** [photoBytes] تُمرَّر جاهزة (ناتج [ContactImageProcessor]) — الإسقاط
 *   لا يحمل `photoUri` (مرآة قراءة Room فقط).
 * - `systemContactId/lookupKey` يُمرَّران كما هما: `null` للإنشاء، والهوية الدائمة للتحديث.
 */
fun Contact.toWritable(photoBytes: ByteArray? = null): PlatformWritableContact =
    PlatformWritableContact(
        systemContactId = systemContactId.takeIf { it.isNotBlank() },
        lookupKey = lookupKey?.takeIf { it.isNotBlank() },
        name = name,
        photoBytes = photoBytes,
        phones = phones,
        emails = emails,
        websites = websites,
        organization = organization,
        account = account,
    )
