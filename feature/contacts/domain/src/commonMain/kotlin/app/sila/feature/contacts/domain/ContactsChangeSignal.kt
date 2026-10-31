package app.sila.feature.contacts.domain

/**
 * إشارة "الدفتر اتّسخ" فقط — تُصدَر بعد تجميع (≥5 ثوانٍ على Android عبر ContentObserver،
 * وعند `CNContactStoreDidChangeNotification` على iOS).
 *
 * **لا تحمل أي معرفة مسبقة بالسجل الذي تغيّر** (المانع المعتمد في ADR-0010 §3/§4:
 * مراقب المحتوى لا يحمل فروقاً، وتنبيه المخزن على iOS مُشغِّل فقط يُستخدم لقراءة الدلتا
 * عبر الرمز، وليس دلتا بذاته).
 */
data class ContactsChangeSignal(
    /** طابع زمني لوقت إصدار الإشارة التجميعية (فقائي لحظة الإصدار، لا لحظة التغيير الأصلية). */
    val occurredAtMillis: Long = 0L,
)
