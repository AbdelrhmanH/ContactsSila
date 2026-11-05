package app.sila.feature.arham.domain

import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.EmergencyRingingSession
import app.sila.core.model.RingingStatus
import kotlinx.coroutines.flow.Flow

/**
 * عقد نطاق جلسات الطوارئ (T8-10 · Issue #29) — مرآة DAO الجدول الأدنى (D4) + القراءة
 * اللحظية للهوية للتسوية stop-on-block (§N.4). **قواعد الخصوصية بلا مساومة**: الهوية
 * تُقرأ وقت التشغيل حصراً ولا تُخزَّن ولا تُمرَّر خارج النطاق (ظرف PII — ADR-0006)،
 * والجلسة نفسها معرفية حصراً (contactId + عدادات — نموذج بيانات D4 الأدنى).
 */
interface EmergencySessionRepository {

    /** أساس حد الـ 3/24 ساعة — كل الحالات تُحصى (انظر [EmergencySessionPolicy]). */
    suspend fun countSessionsStartedSince(cutoffMillis: Long): Int

    /** الجلسة الجارية إن وجدت — إعادة الالتحاق والتسوية (§N.5). */
    suspend fun findInProgressSession(): EmergencyRingingSession?

    /** مراقبة الجلسة الجارية — الواجهة (T8-11) تقرأ من هنا (SSOT). */
    fun observeInProgressSession(): Flow<EmergencyRingingSession?>

    /**
     * إدراج جلسة `IN_PROGRESS` (attempts = 0) — **get-or-create ذرّي** (مراجعة T8-10 · P1-2):
     * جارية قائمة ⇒ معرفها بلا إنشاء (الالتحاق)، غيابها ⇒ صف جديد بمعرفه التوليدي (§N.2).
     */
    suspend fun insertInProgressSession(contactId: Long, startedAtMillis: Long): Long

    /**
     * محاولة ذرّية: `attempts + 1` و `lastAttemptAt` — يعيد العدد الجديد أو `null` إن
     * كانت الجلسة محسومة سلفاً (idempotent §N.8).
     */
    suspend fun recordAttempt(sessionId: Long, atMillis: Long): Int?

    /** حسم نهائي idempotent (§N.8) — `true` عند حسمٍ فعلي، `false` إذا حُسمت سلفاً. */
    suspend fun finishSession(sessionId: Long, status: RingingStatus, atMillis: Long): Boolean

    /**
     * هوية جهة الاتصال لحظة التشغيل — بوابة [Category.EMERGENCY] في البدء وحكم
     * stop-on-block في كل محاولة (§N.4). قراءة حصراً: لا يُخزَّن منها شيء.
     */
    suspend fun getContactById(contactId: Long): Contact?
}
