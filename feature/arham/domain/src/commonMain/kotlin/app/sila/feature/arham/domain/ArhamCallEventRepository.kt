package app.sila.feature.arham.domain

import app.sila.core.model.CallEvent

/**
 * عقد مستودع أحداث المكالمات (ADR-0006) — طبقة Domain داخل `:feature:arham:domain` (DIP).
 *
 * عقدٌ مستقل بتعمد: **لا يمدّ** `ArhamRepository` حتى لا يُكسر تنفيذه في المرحلة 0
 * قبل توحيد الكتابة في T8-03. بلا أنواع Room أو منصة (مذكور بـ Konsist).
 */
interface ArhamCallEventRepository {

    /**
     * يقرأ حداً بمعرفه، أو `null` إن لم يوجد.
     */
    suspend fun getEvent(eventId: String): CallEvent?

    /**
     * يقرأ كل الأحداث التي وقت رصدها الأول ضمن النافذة الشاملة `[fromMillis, toMillis]`.
     * تُستخدم لتجميع مرشّحي منع التكرار — فلترة التطابق نفسها مسؤولية آلة الحالات.
     */
    suspend fun findEventsBetween(fromMillis: Long, toMillis: Long): List<CallEvent>

    /**
     * يُدرج الحدث أو يستبدل نسخته السابقة حسب `id` (upsert).
     */
    suspend fun upsertEvent(event: CallEvent)

    /**
     * يحدّث `arham.lastContactedAt` لجهة الاتصال وفق قاعدة ADR-0006 §6:
     * `lastContactedAt = max(lastContactedAt, timestampMillis)`.
     *
     * هذا هو المسار **الوحيد** لكتابة آخر تواصل من أحداث المكالمات (AC-8)؛
     * ولا يستدعيه النطاق إلا بعد استيفاء قاعدة `updatesLastContacted`.
     */
    suspend fun updateLastContacted(contactId: Long, timestampMillis: Long)
}
