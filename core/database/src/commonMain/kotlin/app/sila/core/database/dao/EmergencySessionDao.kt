package app.sila.core.database.dao

import app.sila.core.database.entity.EmergencyRingingSessionEntity
import app.sila.core.model.RingingStatus
import kotlinx.coroutines.flow.Flow

/**
 * عقد DAO جلسات رنين الطوارئ (T8-10 · قرار D4 المُصادق · §N.2/§N.4/§N.5/§N.8 · Issue #29)
 * — فوق الجدول الأدنى الموحّد `emergency_ringing_sessions` (شُحن في v3 بلا أي تعديل مخطط).
 *
 * عقود الحسم **idempotent حصراً** (§N.8): كل كتابة حالة نهائية مقيَّدة بـ
 * `WHERE status = 'IN_PROGRESS'` — التوقف المزدوج (من الواجهة/الإشعار) عمليةٌ واحدة،
 * والتكرارات عمليات ساكنة بلا أثر.
 */
interface EmergencySessionDao {

    /**
     * أساس حد المعدل (§N.2 · D4): عدد **كل** الجلسات المنشأة بـ `startedAt > cutoff`
     * بأي حالة — الصفوف المنتهية تبقى في النافذة فتُحصى (لهذا يطهَّر بعد 48 ساعة
     * = نافذة 24 ساعة + هامش). بداية جديدة تشترط `count < 3` (مقارنة صارمة).
     */
    suspend fun countSessionsStartedSince(cutoffMillis: Long): Int

    /** الجلسة الجارية إن وجدت — إعادة الالتحاق والتسوية بعد موت العملية (§N.5). */
    suspend fun findInProgressSession(): EmergencyRingingSessionEntity?

    /** مراقبة الجلسة الجارية — سطح الواجهة (T8-11) يقرأ Room حصراً (SSOT). */
    fun observeInProgressSession(): Flow<EmergencyRingingSessionEntity?>

    /**
     * إدراج جلسة `IN_PROGRESS` (attempts = 0) — **get-or-create ذرّي** (مراجعة T8-10 · P1-2):
     * وجود جارية قائمة يعيد معرفها بلا إنشاء (الالتحاق)، وغيابها يُنشئ صفاً جديداً ويعيد
     * معرفه التوليدي (§N.2). القراءة والكتابة داخل معاملة الكاتب نفسها — سباق بدايتين
     * متوازيتين لا يُنشئ صفاً ثانياً يُفسد محاسبة 3/24 ساعة.
     */
    suspend fun insertInProgressSession(contactId: Long, startedAtMillis: Long): Long

    /**
     * محاولة ذرّية: `attempts + 1` و `lastAttemptAt = atMillis` في معاملة واحدة —
     * يعيد العدد الجديد، أو `null` إن لم تكن الجلسة جارية (حُسمت سلفاً — لا تُحصى).
     */
    suspend fun recordAttempt(sessionId: Long, atMillis: Long): Int?

    /**
     * حسم نهائي idempotent (§N.8): يعبّئ الحالة النهائية بشرط أنها جارية — `true` عند
     * حسمٍ فعلي، `false` إذا حُسمت سلفاً بحالةٍ أخرى (توقف مزدوج = عملية ساكنة).
     */
    suspend fun finishSession(sessionId: Long, status: RingingStatus, atMillis: Long): Boolean
}
