package app.sila.core.database.entity

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import app.sila.core.model.CallAssociation
import app.sila.core.model.CallConfirmation
import app.sila.core.model.CallConfidence
import app.sila.core.model.CallDirection
import app.sila.core.model.CallOutcome
import app.sila.core.model.CallSource

/**
 * كيان سجل أحداث المكالمات — الصورة الفيزيائية الحرفية لجدول `call_events` بصورة v3:
 * 18 عمود SQL يحملون حقول ADR-0006 السبعة عشر كاملة (عمود المفتاح `callLogId` هو حقل
 * `id`) — T8-02 · Issue #29 (§F.2 من تقرير بوابة T8-02).
 *
 * التزامات لا تُكسر (§G.3 من الخطة المُصادقة):
 * - المفتاح الأساسي `callLogId` TEXT NOT NULL — بلا إعادة تمكين ولا AUTOINCREMENT،
 *   ومعرفات الإرث تُحفظ حرفاً.
 * - `e164` NOT NULL — **بلا** فهرس فريد (صفوف e164 مكررة بمفاتيح مختلفة تبقى).
 * - فهرس واحد حصراً على `timestamp`؛ فهرس `contactId` خيار PROPOSED بقياس — لا يُضاف
 *   استباقياً.
 * - بلا مفاتيح أجرة ولا قيود CHECK (ADR-0011: القيود توثيقية؛ محولات `valueOf` هي
 *   الحارس — نص تعداد مجهول يرمي).
 * - كل قيمة فارغة تعني «المنصة لم تعطِ هذا الدليل» — لا يُستنتج ولا يُخترع (ADR-0006).
 *
 * أعمدة بلا قيم افتراضية Kotlin عمداً: كل كاتب يصرّح بكل قيمة — لا افتراضات صامتة
 * تصنع دليلاً مضللاً (قرارfail-closed؛ خرائط T8-03 هي من يبني الصف من [CallEvent]).
 *
 * @param callLogId       معرف محلي ثابت للحدث (مفتاح أساسي — ADR `id`).
 * @param e164            الرقم بصيغة E.164 — مرآة رقم الحدث للأرقام المرتبطة/المعروفة.
 * @param timestamp       وقت أول رصد (الفهرس الزمني `idx_call_events_ts`).
 * @param primarySource   مصدر الرصد الأول — يُحفظ ولا يُستبدل عند الدمج (ترقية `source` v2).
 * @param evidenceSources كل المصادر المساهمة، وتشمل `MANUAL` عند التأكيد — نص مفصول
 *                        بفواصل مرتب (محول `SilaTypeConverters`)، ولا يكون فارغاً أبداً.
 * @param remoteNumber    `null` = المنصة لم تعطِ رقماً.
 * @param contactId       `null` = لم تُربط جهة اتصال بعد.
 * @param direction       `null` = غير معروف؛ `MISSED` حصرية لسجل الاتصال القديم ولا
 *                        تُكتب هنا أبداً (الصفوف المهاجرة MISSED → `null` + UNANSWERED).
 * @param startedAt       `null` = البداية غير معروفة.
 * @param connectedAt     `null` = لم يُرصد اتصال؛ وجوده لا يعني النهاية.
 * @param endedAt         `null` = النهاية غير مرصودة.
 * @param durationSec     `null` = مدة غير موثوقة؛ لا تُشتق من `connectedAt` وحده.
 * @param outcome         نتيجة الحدث.
 * @param association     حالة الربط بجهة الاتصال.
 * @param confirmation    حالة التأكيد الاحتياطي.
 * @param confidence      قوة الدليل.
 * @param dedupeKey       مفتاح دمج غير حساس اختياري؛ `null` إذا تعذّر توليده.
 * @param simSlot         شريحة الهاتف إن عُرفت؛ `null` = غير معروف (الحقل السابع عشر — §H.0).
 */
@Entity(
    tableName = "call_events",
    indices = [
        Index(name = "idx_call_events_ts", value = ["timestamp"]),
    ],
)
data class CallEventEntity(
    @PrimaryKey val callLogId: String,
    val e164: String,
    val timestamp: Long,
    val primarySource: CallSource,
    val evidenceSources: Set<CallSource>,
    val remoteNumber: String?,
    val contactId: Long?,
    val direction: CallDirection?,
    val startedAt: Long?,
    val connectedAt: Long?,
    val endedAt: Long?,
    val durationSec: Int?,
    val outcome: CallOutcome,
    val association: CallAssociation,
    val confirmation: CallConfirmation,
    val confidence: CallConfidence,
    val dedupeKey: String?,
    val simSlot: Int?,
)
