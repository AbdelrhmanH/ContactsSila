package app.sila.feature.contacts.domain

/**
 * دفعة واحدة من القراءة الكاملة المقطّعة (~500 جهة لكل دفعة — ADR-0010 §2).
 *
 * - [offset]/[limit] هما ما طلبه المستدعي؛ [contacts] قد تكون أقل من [limit] عند نهاية التسلسل.
 * - [hasMore] صحيحة إن وجدت جهات بعد هذه الدفعة.
 * - [continuation] مفتاح استئناف معتم داخل التنفيذ (يظل `null` على Android/iOS الحاليين لأن
 *   التقسيم يعتمد الإزاحة، ويُترك للمنصات المستقبلية).
 */
data class ContactsPage(
    val contacts: List<PlatformContact>,
    val offset: Int,
    val limit: Int,
    val hasMore: Boolean,
    val continuation: String? = null,
)
