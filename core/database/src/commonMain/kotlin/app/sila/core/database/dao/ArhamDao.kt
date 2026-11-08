package app.sila.core.database.dao

import app.sila.core.model.ArhamInfo
import app.sila.core.model.CallEvent
import app.sila.core.model.Contact
import kotlinx.coroutines.flow.Flow

/**
 * عقد DAO لبيانات صلة الرحم (أرحام) المحلية (ARCHITECTURE.md §5).
 *
 * PR B (ADR-0011): عقد نطاق بلا شروح Room — التنفيذ الفعلي في طبقة data
 * (`LocalSilaDatabase` + `ArhamEntityDao`).
 *
 * T8-03 (Issue #29): نمو عضوية العقد باستمرارية إدخال أحداث المكالمات (ADR-0006) —
 * القراءات تنطق نماذج النطاق، والكتابة المركّبة (الحدث + آخر التواصل المؤهَّل) معاملة
 * واحدة حصراً (قيود T8-01 مراجعة F-2: لا انفصام بين الحدث وتحديث `lastContactedAt`).
 */
interface ArhamDao {

    fun observeArhamContacts(): Flow<List<Contact>>

    suspend fun upsertArhamInfo(contactId: Long, info: ArhamInfo)

    suspend fun deleteArhamInfo(contactId: Long)

    /** يرصد أحداث المكالمات غير المحسومة (تأكيدها `PENDING` حصراً — T8-06 · AC-7)، الأحدث أولاً. */
    fun observePendingCallEvents(): Flow<List<CallEvent>>

    /**
     * يرصد كل أحداث النافذة الشاملة `[fromMillis, toMillis]` (T8-07 · AC-2)، الأحدث أولاً —
     * مرصدٌ تفاعلي لتجميع إحصائيات اللوحة فوق أحداث حقيقية؛ تأهيلُ كل حدث (قاعدة AC-8)
     * قرار آلة الحالات في النطاق، لا هذا العقد.
     */
    fun observeCallEventsBetween(fromMillis: Long, toMillis: Long): Flow<List<CallEvent>>

    /** يقرأ حدث مكالمة بمعرفه، أو `null` إن لم يوجد (T8-03). */
    suspend fun getCallEventById(eventId: String): CallEvent?

    /**
     * يقرأ كل الأحداث التي وقت رصدها الأول ضمن النافذة الشاملة `[fromMillis, toMillis]`
     * بترتيب زمني تصاعدي (T8-03) — تُستخدم لتجميع مرشّحي منع التكرار؛ فلترة التطابق
     * نفسها مسؤولية آلة الحالات في النطاق.
     */
    suspend fun findCallEventsBetween(fromMillis: Long, toMillis: Long): List<CallEvent>

    /**
     * الكتابة المركّبة الذرية لإدخال حدث (T8-03 · قيد F-2 من مراجعة T8-01): إدراج/استبدال
     * صف الحدث **و** — عندما تكون [lastContactedUpdate] غير فارغة — تحديث
     * `arham.lastContactedAt = max(lastContactedAt, timestampMillis)` **داخل معاملة
     * IMMEDIATE واحدة**؛ فشل أي جزء يُرجِع العملية كلها (لا حدث بلا تحديث مؤهَّله).
     *
     * تقييم أهلية التحديث (قاعدة AC-8) قرار النطاق عبر آلة الحالات — هذا العقد ينفّذ
     * القرار الوارد إليه حصراً ولا يعيد تقييمه. لا يُنشأ صف `arham` غائباً أبداً.
     *
     * @throws IllegalArgumentException حدثٌ بلا رقم (`remoteNumber = null`) — مرآة `e164`
     *   NOT NULL لا قيمة مشروعة لها؛ استمرارية المرصود بلا رقم قرارٌ مؤجل (T8-06 · §G.3
     *   UNRESOLVED) — الإخفاق مغلق الصمام بلا اختلاق قيمة (§H.4).
     */
    suspend fun upsertCallEvent(event: CallEvent, lastContactedUpdate: LastContactedUpdate?)

    /**
     * تحديث آخر تواصل بحدّه الأقصى: `lastContactedAt = max(lastContactedAt, timestampMillis)`
     * (قاعدة ADR-0006 §6) — الصف الغائب لا يُنشأ، والقيمة الأحدث قائماً لا تُنقص أبداً
     * (الاستدعاء التالي للمسار المؤهَّل من النطاق idempotent بلا أثر دلالي).
     */
    suspend fun updateLastContacted(contactId: Long, timestampMillis: Long)
}

/**
 * تحديث آخر تواصل المؤهَّل المصاحب لإدخال حدث (T8-03) — يُمرَّر من طبقة data بعد تقييم
 * قاعدة AC-8 (`CallEventStateMachine.updatesLastContacted`) في النطاق؛ `null` = حدثٌ
 * بلا تحديث مصاحب.
 */
data class LastContactedUpdate(
    val contactId: Long,
    val timestampMillis: Long,
)
