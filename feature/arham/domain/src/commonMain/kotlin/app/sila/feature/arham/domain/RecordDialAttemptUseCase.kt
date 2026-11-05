package app.sila.feature.arham.domain

import app.sila.core.model.CallEvent

/**
 * حالة استخدام: تسجيل **نية اتصال** من زر الاتصال كحدث `IN_APP` عبر آلة الحالات
 * (خطة §D.3-4 · ADR-0006 §1/§5 · AC-3).
 *
 * التصنيف `classifyInAppDialIntent` يُخرج `ATTEMPTED` حصراً — المحاولة ليست دليل
 * محادثة مكتملة، **ولا تكتب آخر تواصل أبداً**: `lastContactedAt` لا يُحدَّث إلا من
 * قاعدة AC-8 عبر `CallEventStateMachine.updatesLastContacted` داخل مسار الإدخال.
 *
 * المعرف حتمي البناء من (جهة الاتصال، الطابع الزمني): إعادة الاتصال داخل نافذة
 * منع التكرار تُدمج في الحدث القائم مع حفظ المعرف الأول (ADR-0006 §5)، وخارجه
 * يُنشئ حداً مستقلاً بمعرف مختلف.
 */
class RecordDialAttemptUseCase(
    private val ingest: IngestCallEventUseCase,
    private val stateMachine: CallEventStateMachine = CallEventStateMachine(),
) {
    /**
     * @param simSlot شريحة الاتصال المعلومة عند بدء المكالمة — تمريرها اختياري، ولا
     *   يُخترع `dedupeKey` في كل الأحوال (قرار D-2). ملاحظة مصدر البيانات:
     *   `Phone.preferredSimSlot` غير مُخزَّن في Room v2 (`EntityMappers` يقرأه `null`
     *   دائماً) لذا يمرّر المستدعون الحاليون `null` — التوقيع جاهز لعند تخزين
     *   التفضيل، دون سبكٍ دائم الفراغ عبر `ArhamItem`/`ArhamIntent`.
     */
    suspend operator fun invoke(
        contactId: Long,
        remoteNumber: String,
        timestampMillis: Long,
        simSlot: Int? = null,
    ): CallEventIngestion {
        val candidate = stateMachine.classifyInAppDialIntent(
            eventId = "inapp-${contactId}-${timestampMillis}",
            remoteNumber = remoteNumber,
            timestampMillis = timestampMillis,
            contactId = contactId,
            simSlot = simSlot,
        )
        return ingest(candidate)
    }
}
