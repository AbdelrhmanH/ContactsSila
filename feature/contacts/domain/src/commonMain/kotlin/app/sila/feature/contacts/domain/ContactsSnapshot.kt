package app.sila.feature.contacts.domain

/**
 * نقطة استئناف معتمة (opaque resume point) للمزامنة التزايدية (ADR-0010).
 *
 * يجب حفظها داخل نفس معاملة كتابة الدلتا في Room ليستأنف الاستيراد دون فقد بيانات.
 * تحتوي:
 * - **iOS:** بايتات رمز سجل التغييرات (`CNFetchResult.currentHistoryToken` مُأرشف عبر
 *   NSKeyedArchiver) في [iosHistoryTokenBytes].
 * - **Android:** خريطة نُسَخ السجلات الخام (`RawContacts._ID -> الإصدار والمفتاح`) في
 *   [androidRawContacts] كمدخل حصري لحساب الفروق بين الإصدارات.
 *
 * مبادئ الاتفاق:
 * - خالٍ تماماً من أي أنواع منصات (`android.*` / `platform.*`) — BYTES/ فقط.
 * - [platformMarker] يميّز ملكية اللقطة: لقطة أُنتجت على منصة أخرى (مثلاً بعد إعادة
 *   استعادة بيانات بين المنصتين) تُعامل كلاقطة **غير صالحة** → إعادة مزامنة كاملة.
 * - قيم الفساد: رمس iOS فارغ/غير قابل لفك الأرشفة → إعادة مزامنة كاملة، لا حذف أبداً.
 */
class ContactsSnapshot(
    val platformMarker: String? = null,
    val iosHistoryTokenBytes: ByteArray? = null,
    val androidRawContacts: Map<String, AndroidRawContactState> = emptyMap(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ContactsSnapshot) return false
        return platformMarker == other.platformMarker &&
            (iosHistoryTokenBytes?.contentEquals(other.iosHistoryTokenBytes) ?: (other.iosHistoryTokenBytes == null)) &&
            androidRawContacts == other.androidRawContacts
    }

    override fun hashCode(): Int {
        var result = platformMarker?.hashCode() ?: 0
        result = 31 * result + (iosHistoryTokenBytes?.contentHashCode() ?: 0)
        result = 31 * result + androidRawContacts.hashCode()
        return result
    }

    override fun toString(): String =
        "ContactsSnapshot(platformMarker=$platformMarker, " +
            "iosHistoryTokenBytes=${iosHistoryTokenBytes?.size ?: 0} bytes, " +
            "androidRawContacts=${androidRawContacts.size} raw contacts)"

    companion object {
        /** علامة اللقطات التي أنتجها AndroidContactsPlatformSource. */
        const val PLATFORM_ANDROID = "android"

        /** علامة اللقطات التي أنتجها IosContactsPlatformSource. */
        const val PLATFORM_IOS = "ios"
    }
}
