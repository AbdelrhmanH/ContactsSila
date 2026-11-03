package app.sila.feature.contacts.domain

import app.sila.core.common.AppClock
import app.sila.core.common.AppResult
import app.sila.core.model.Contact

/**
 * حالة استخدام حذف جهة من شاشة التفاصيل بنافذة تراجع (T6 · J-5 · Issue #27).
 *
 * ## الدلالات الملزمة
 * - **الحذف يؤخَّر حتى انتهاء نافذة التراجع ([UNDO_WINDOW_MILLIS] = 5 ثوانٍ):**
 *   الـ ViewModel يشغّل المؤقت ويجمع طلبات التراجع — هذا الصف **لا يُنادى إلا بعد
 *   انتهاء النافذة بلا تراجع**. إلغاء النافذة (تراجع) لا يستدعي هذا الصف ولا يكتب
 *   أي شيء (لا كتابة استباقية ثم تراجع عنها — يبقى الحذف نظيفاً حتى آخر لحظة).
 * - **المنصة أولاً** (J-1 · ADR-0012): [ContactsPlatformWriter.deleteContact] ينجح
 *   أولاً، وإلا لا يُمس Room إطلاقاً (فشل حذف المنصة = الحذف لم يحدث).
 * - **القبر بعدها حصراً**: [ContactsRepository.tombstoneContact] (حذف ناعم 30 يوماً
 *   — نفس دلالات ADR-0010 §6) — لا حذف فعلي في هذا المسار (مسار الحذف الفعلي
 *   الجماعي T3 مجمد ولا يُمس).
 * - الحماية من المكرر: صف مقبرة سلفاً (idempotent) لا يُعاد تعليمها — عقد
 *   [ContactsRepository.tombstoneContact].
 */
class DeleteContactWithUndoUseCase(
    private val writer: ContactsPlatformWriter,
    private val repository: ContactsRepository,
    private val clock: AppClock,
) {

    /** تنفيذ الحذف النهائي بعد انصراف نافذة التراجع — انظر دلالات الصف. */
    suspend operator fun invoke(contact: Contact): AppResult<Unit> {
        val now = clock.nowEpochMillis()
        return when (val result = writer.deleteContact(contact.systemContactId, contact.lookupKey)) {
            is AppResult.Success -> {
                try {
                    repository.tombstoneContact(contact.id, now)
                    AppResult.Success(Unit)
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    // تعويض ضيق: فشل القبر بعد نجاح حذف المنصة يترك Room حياً والمنصة
                    // محذوفة — المزامنة القادمة (tombstone من المنصة) تُغلقه ذاتياً
                    // (ADR-0010 §4)؛ نُرجع الخطأ للعرض فقط.
                    AppResult.Error(app.sila.core.common.DataError.Unknown(causeMessage = t.message))
                }
            }
            is AppResult.Error -> result
        }
    }

    companion object {
        const val UNDO_WINDOW_MILLIS: Long = 5_000L
    }
}
