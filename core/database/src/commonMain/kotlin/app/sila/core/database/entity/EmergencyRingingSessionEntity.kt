package app.sila.core.database.entity

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import app.sila.core.model.RingingStatus

/**
 * كيان جلسات اتصال الطوارئ — الصورة الفيزيائية الحرفية لجدول `emergency_ringing_sessions`
 * بصورة v3 (قرار D4 المُصادق في §W من الخطة · T8-02 · Issue #29).
 *
 * الحد الأدنى من البيانات حصراً — هوية جهة الاتصال والعدادات وحالة الرنين: **بلا أي
 * PII مكرر** (اسم جهة/رقم لا يُخزَّنان؛ تُستعاد وقت التشغيل عبر `contactId` — تصغير
 * بيانات ADR-0006). الجلسة المنتهية (أي `RingingStatus` غير `IN_PROGRESS`) تُطهَّر عبر
 * `cleanupExpiredData` بعد 48 ساعة (نافذة 24 ساعة + هامش — §H من تقرير بوابة T8-02).
 *
 * الحقول حرفياً بلا إضافات (قرار D4: نموذج بيانات أدنى — لا إعادة تصميم بلا موافقة).
 *
 * @param id           معرف توليدي تلقائي (مفتاح أساسي).
 * @param contactId    هوية جهة الاتصال المستهدَفة (لو حُذفت الجهة وسط جلسة تُجهَض الجلسة).
 * @param startedAt    بداية الجلسة — أساس نافذة الحد اليومية (≤ 3 جلسات / 24 ساعة).
 * @param lastAttemptAt آخر محاولة رنين — أساس التطهير بعد 48 ساعة.
 * @param attempts      عدد محاولات الرنين حتى الآن (حد أعلى 10).
 * @param status        حالة الجلسة (نص `.name` عبر محول `SilaTypeConverters` — مُسجَّل
 *                      مجدداً legitimately مع هذا الكيان بعد حذف الكيان الشبحي في Phase 0).
 */
@Entity(tableName = "emergency_ringing_sessions")
data class EmergencyRingingSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val contactId: Long,
    val startedAt: Long,
    val lastAttemptAt: Long,
    val attempts: Int,
    val status: RingingStatus,
)
