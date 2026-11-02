package app.sila.feature.contacts.domain

import app.sila.core.common.AppClock
import app.sila.core.common.AppResult
import app.sila.core.common.DataError
import app.sila.core.model.Contact

/**
 * حالة استخدام حفظ جهة اتصال — **مالك ميثاق الاتساق J-1** (T6 · Issue #27 ·
 * [ADR-0012] docs/adr/0012-contact-write-path-consistency.md).
 *
 * ## الترتيب الملزم (منصة أولاً — لا استثناء)
 * 1. معالجة الصورة (إن وُجدت) عبر [ContactImageProcessor] — فشل الصورة لا يُفشل الحفظ.
 * 2. **كتابة المنصة** ([ContactsPlatformWriter]): إنشاء أو تحديث ضيق بالإسقاط المسموح
 *    [PlatformWritableContact]. فشلُها هنا يُنهي الحفظ — **لا يُمس Room إطلاقاً**.
 * 3. **كتابة Room** ([ContactsRepository.upsertContact]) داخل معاملة واحدة (صف +
 *    كل الأطفال + FTS) بالهوية المنصوية المُحلولة.
 * 4. **التعويض الآمن** عند فشل (3) — جدول الفشل ADR-0012:
 *    - بعد **إنشاء** منصوي ناجح: حذف المنصوي المُنشأ حذفاً صامتاً (لا يُترك صف يتيماً
 *      خارج SSOT) — وإذا فشل التعويض نفسه فشبكة الأمان هي محرك المزامنة القائم
 *      (المصالحة الكاملة الدورية تلتقط الانحراف — ADR-0010).
 *    - بعد **تحديث** منصوي ناجح: محاولة استعادة القيم السابقة إلى المنصة؛ وعند فشل
 *      الاستعادة أيضاً: Room محفوظ بالقيم السابقة والمنصة بالجديدة، والمزامنة القادمة
 *      (delta) تعيد محاذاة Room — الانحراف مؤقت قابل للشفاء ذاتياً، لا فقد بيانات.
 * 5. فشل كتابة المنصة في التحديث **لا يُلمس Room** — الواجهة تعرض الخطأ والتحرير
 *    يبقى مفتوحاً على الحالة السابقة.
 *
 * @return [AppResult.Success] بالجهة المحفوظة (بالمعرفات المُحلولة: Room id +
 *   هوية منصوية نهائية) أو [AppResult.Error] المُصنَّف للعرض المترجم.
 */
class SaveContactUseCase(
    private val writer: ContactsPlatformWriter,
    private val repository: ContactsRepository,
    private val imageProcessor: ContactImageProcessor,
    private val clock: AppClock,
) {

    /**
     * @param original الحالة المعروفة قبل التحرير — `null` = إنشاء جهة جديدة.
     * @param draft الحالة المُحرَّرة كاملة (كل الحقول بما فيها صِلَة-فقط من الحالة
     *   المرصودة) — يجب أن تكون منشأةً من [ObserveContactDetailsUseCase] لضمان حمل
     *   الحقول صِلَة-فقط غير المعروضة في نموذج التحرير.
     * @param newPhotoBytes بايتات الصورة الخام الجديدة — `null` = لا تغيير صورة
     *   (إلا إن كانت [removePhoto] فعلاً).
     * @param removePhoto طلب صريح بإزالة الصورة (كتابة صورة فارغة للمنصة).
     */
    suspend operator fun invoke(
        original: Contact?,
        draft: Contact,
        newPhotoBytes: ByteArray? = null,
        removePhoto: Boolean = false,
    ): AppResult<Contact> {
        val now = clock.nowEpochMillis()

        // 1) الصورة — تحسين اختياري: فشلها لا يُفشل الحفظ (تُحفظ الجهة بلا صورة جديدة).
        val photoBytes: ByteArray? = when {
            newPhotoBytes != null -> imageProcessor.process(newPhotoBytes)
            removePhoto -> ByteArray(0) // إشارة إزالة صريحة للكاتب
            else -> null
        }

        val draftWithStamp = draft.copy(
            normalizedName = draft.normalizedName.ifEmpty { draft.name.displayName },
            updatedAt = now,
        )

        return if (original == null) {
            create(draftWithStamp, photoBytes)
        } else {
            update(original, draftWithStamp, photoBytes)
        }
    }

    private suspend fun create(draft: Contact, photoBytes: ByteArray?): AppResult<Contact> {
        val created = when (val result = writer.createContact(draft.toWritable(photoBytes))) {
            is AppResult.Success -> result.data
            is AppResult.Error -> return result
        }
        // الهوية المنصوية الجديدة تُدمَج في النسخة المراد حفظها (systemContactId هو
        // مفتاح الدمج في applySyncBatch — بلاها تتيم الجهة من محرك المزامنة ADR-0009).
        val saved = draft.copy(
            systemContactId = created.systemContactId,
            lookupKey = created.lookupKey ?: draft.lookupKey,
        )
        return persist(saved, compensation = { writer.deleteContact(created.systemContactId, created.lookupKey) })
    }

    private suspend fun update(original: Contact, draft: Contact, photoBytes: ByteArray?): AppResult<Contact> {
        when (val result = writer.updateContact(draft.toWritable(photoBytes))) {
            is AppResult.Success -> Unit
            is AppResult.Error -> return result
        }
        return persist(draft, compensation = {
            // استعادة الحالة السابقة للمنصة (ضدية لأثر التحديث) — الضياع الوحيد الممكن
            // هنا هو أثر تعديل طرف آخر بين قراءتين، والمزامنة تعيد المحاذاة (J-1).
            writer.updateContact(original.toWritable())
        })
    }

    /** خطوة (3)+(4): كتابة Room؛ وعند فشلها تنفيذ التعويض وإرجاع خطأ الواجهة. */
    private suspend fun persist(
        contact: Contact,
        compensation: suspend () -> Unit,
    ): AppResult<Contact> = try {
        repository.upsertContact(contact)
        AppResult.Success(contact)
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        // التعويض الصامت — فشله يُبتَلَع عمداً: شبكة أمان المزامنة (ADR-0010) هي
        // صاحبة التقاط الانحراف، وفشل مزدوج لا يضيف خياراً أفضل للمستخدم هنا.
        runCatching { compensation() }
        AppResult.Error(DataError.Unknown(causeMessage = t.message))
    }
}
