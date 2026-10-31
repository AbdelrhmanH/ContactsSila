package app.sila.feature.contacts.domain

import kotlinx.coroutines.flow.Flow

/**
 * **العقد المجمد (Frozen Contract) — مصدر جهات الاتصال من المنصة** (Issues #22/#16 ·
 * [ADR-0009](docs/adr/0009-contacts-platform-boundary.md) · [ADR-0010](docs/adr/0010-incremental-contacts-sync.md)).
 *
 * واجهة نقية في `:feature:contacts:domain` بلا أي أنواع منصات (`android.*` / `platform.*`
 * / `Contacts` / `CNContact`)؛ تُنفَّذ في `:feature:contacts:data` (`AndroidContactsPlatformSource`
 * في `androidMain`، `IosContactsPlatformSource` في `iosMain`) وتُربَط عبر Koin في موديولات
 * DI المنصات تحت `:sharedLogic`.
 *
 * ## دلالات الهوية (غير قابلة للتغيير — ADR-0009 §4)
 * - المفتاح الدائم الموحّد هو `PlatformContact.systemContactId`:
 *   - **Android:** قيمة `ContactsContract.Contacts.LOOKUP_KEY`، وتبقى `lookupKey` مرآة لها.
 *     `Contacts._ID` و`RawContacts.CONTACT_ID` **غير صالحين كمفاتيح دائمة** — يُستخدمان
 *     لحساب الفروق والدفاتر فقط ولا يُخزن أي منهما كهوية.
 *   - **iOS:** قيمة `CNContact.identifier` وتبقى `lookupKey` فارغة.
 *
 * ## دلالات الدلتا (ADR-0010 §3/§4/§7)
 * - [readDelta] مع لقطة `null` يعني قراءة كاملة تُعرض كدلتا `isFullResync = true`.
 * - كل استدعاء يعيد [ContactsDelta.newSnapshot] جديدة يجب حفظها داخل نفس معاملة Room
 *   مع تطبيق الدلتا؛ عند أي فشل لا تُقدَّم لقطة/رمز (لا تطبيق جزئي أبداً).
 * - `Contacts._ID`/`RawContacts.VERSION` على Android إدخال حصري لحساب الفروق؛
 *   حذف السجلات من `DeletedContacts` مجهود أفضل + مصالحة كاملة دورية.
 * - iOS: أحداث `CNChangeHistoryFetchRequest` هي مصدر الدلتا الموثوق، و`CNContactStoreDidChangeNotification`
 *   مُشغِّل فقط يُستخدم لاستدعاء [readDelta]، وليس دلتا.
 *
 * ## الصلاحيات
 * - [observePermissionState] يفرّق بين GRANTED / LIMITED / DENIED / PERMANENTLY_DENIED.
 * - **`.limited` (iOS) لا يُعامل رفضاً ولا حذفاً أبداً**: نقص الظهور للنطاق الجزئي
 *   لا ينتج `deletedSystemContactIds` ولا تعليم حذف (ADR-0010 §5).
 * - رمز iOS مفقود/غير صالح أو حدث `.dropEverything` يُعرض كإشارة إعادة مزامنة كاملة
 *   ([ContactsDelta.isFullResync])، ولا يُعرض أبداً كحذف.
 */
interface ContactsPlatformSource {

    /**
     * قراءة كاملة مقطّعة (~500 لكل الدفعة الافتراضية — ADR-0010 §2).
     *
     * @param offset إزاحة البدء في الترتيب الطبيعي للدفتر.
     * @param limit حجم الدفعة المطلوب.
     * @return [ContactsPage] مع `hasMore` لكمل القراءة. بلا صلاحية/مع خطأ مؤقت تُعاد
     *   صفحة فارغة (`hasMore = false`) — حالة الصلاحية تُقرأ من [observePermissionState].
     */
    suspend fun readPage(offset: Int, limit: Int = 500): ContactsPage

    /**
     * قراءة التغييرات منذ [previousSnapshot] (ADR-0010).
     *
     * @param previousSnapshot آخر لقطة محفوظة داخل نفس معاملة الدلتا السابقة؛
     *   `null` = قراءة كاملة تُعرض كدلتا `[isFullResync = true]`؛ لقطة من منصة أخرى
     *   أو رمز iOS مفقود/فاسد تعود بإعادة مزامنة كاملة أيضاً.
     * @return [ContactsDelta] بجهات الـ upsert (بالحالة النهائية الكاملة)، حذفها (لا
     *   يُستنبط من `.limited` ولا من نقص الظهور)، ونقطة الاستئناف الجديدة.
     */
    suspend fun readDelta(previousSnapshot: ContactsSnapshot?): ContactsDelta

    /**
     * إشارة "الدفتر اتّسخ" فقط — **لا تحمل أي معرفة بالسجل الذي تغيّر**.
     * الرمز التجميعي: ≥5 ثوانٍ على Android (ContentObserver)؛ تنبيه المخزن على iOS مُشغِّل فقط.
     */
    fun observeChangeSignals(): Flow<ContactsChangeSignal>

    /** تدفق حالات الصلاحية للقراءة (انظر [ContactsPermissionState] لدلالات `.limited`). */
    fun observePermissionState(): Flow<ContactsPermissionState>
}
