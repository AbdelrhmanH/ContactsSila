package app.sila.feature.arham.domain

import app.sila.core.model.CallEvent

/**
 * نتيجة إدخال حدث مكالمة عبر آلة الحالات — مسارا الرصد واليدوي كلاهما يعيدها.
 */
sealed interface CallEventIngestion {

    /** خُزّن الحدث كصف مستقل (إدراج أو استبدال حسب `id`). */
    data class Persisted(val event: CallEvent) : CallEventIngestion

    /** دُمج المرشّح في حدث قائم مطابق (منع التكرار — ADR-0006 §5) وخُزّن المدموج. */
    data class Merged(val event: CallEvent) : CallEventIngestion

    /** تطابقات متعددة — الغموض يُحل باختيار المستخدم (ADR-0006 §5)؛ لا تُكتب أي بيانات. */
    data class Ambiguous(val candidates: List<CallEvent>) : CallEventIngestion
}
