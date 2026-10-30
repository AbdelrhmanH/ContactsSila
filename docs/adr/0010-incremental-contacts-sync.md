# ADR-0010: المزامنة التزايدية لجهات الاتصال إلى Room
- **الحالة:** مقبول
- **التاريخ:** 2026-10-29
- **المرجع:** [ADR-0004](0004-room-offline-first.md) · [ADR-0009](0009-contacts-platform-boundary.md) · [ARCHITECTURE §6](../ARCHITECTURE.md) · [Wayfinder Phase 1 map](https://github.com/AbdelrhmanH/ContactsSila/issues/16)

## السياق
Room هو المصدر الوحيد للواجهة (ADR-0004). يجب استيراد جهات الاتصال من النظام ثم إبقاؤها متزامنة دون فقدان بيانات صِلَة (التصنيف، المفضلة، أرحام، الوسوم) ودون افتراض أن المنصتين توفران نفس قدرات تتبع التغيير:
- **Android:** لا توجد إشعارات تفصيلية؛ `ContentObserver` على سلطة جهات الاتصال يُطلق إشعاراً مجمّعاً (~5 ثوانٍ) بلا معرفة ما تغيّر. `RawContacts.VERSION` يتغيّر مع أي تعديل، و`DeletedContacts` يسجل حذف السجلات المجمعة فقط لمدة ~30 يوماً وليس سجلاً كاملاً.
- **iOS:** `CNContactStoreDidChangeNotification` (Swift: `.CNContactStoreDidChange`) إشعار عام بلا فروق، بينما `CNChangeHistoryFetchRequest` (iOS 13+) يعطي أحداث إضافة/تعديل/حذف موثوقة مع رمز استمرارية (token)، ويفقد الرمز دوره عند إعادة التثبيت/الاستعادة فيتحول إلى إعادة مزامنة كاملة (drop-everything ثم add-all).

## القرار
1. **محرك مزامنة مشترك** (`ContactsSyncEngine` في `:feature:contacts:data`) يعتمد فقط على `ContactsPlatformSource` (ADR-0009)، ويكتب في Room داخل معاملة واحدة لكل دفعة.
2. **الاستيراد الأولي:** قراءة كاملة مجزّأة (دفعات ~500) عند أول تشغيل بعد منح الصلاحية؛ تُعرض القائمة بعد أول دفعة ناجحة لا بعد اكتمال الاستيراد كله؛ الـ upsert idempotent فيُستأنف الاستيراد بعد المقاطعة دون تكرار. تُبنى فهارس FTS ضمن دفعات الكتابة نفسها.
3. **Android — إبطال + مقارنة إصدارات:**
   - `ContentObserver` على `ContactsContract.Contacts.CONTENT_URI` (يستقبل إشعارات سلطة جهات الاتصال كاملة) مجمّع بفاصل ≥5 ثوانٍ، ويُستخدم كإشارة "قذرة" فقط.
   - الدلتا: مقارنة `RawContacts(_ID, CONTACT_ID, VERSION, DELETED)` مع لقطة الإصدارات المحفوظة، ثم قراءة `Data` للسجلات المتغيرة فقط.
   - الحذف: `DeletedContacts` أفضل جهد + **مصالحة كاملة دورية** (عند فتح التطبيق، وأسبوعياً على الأقل) لأن `DeletedContacts` يحتفظ بالسجلات ~30 يوماً ويسجّل الحذف على مستوى السجل المجمّع فقط. المصدر: [ContactsContract.DeletedContacts](https://developer.android.com/reference/android/provider/ContactsContract.DeletedContacts) · [ContentResolver.registerContentObserver](https://developer.android.com/reference/android/content/ContentResolver).
4. **iOS — سجل تغييرات موثوق:**
   - `CNChangeHistoryFetchRequest` مع حفظ `CNFetchResult.currentHistoryToken` **داخل نفس معاملة** كتابة الدلتا.
   - `CNContactStoreDidChangeNotification` لا يحمل فروقاً؛ يُستخدم كمُشغِّل فقط.
   - رمز مفقود/غير صالح أو `.dropEverything` ← إعادة مزامنة كاملة ثم حفظ الرمز الجديد. المصدر: [CNChangeHistoryFetchRequest](https://developer.apple.com/documentation/contacts/cnchangehistoryfetchrequest) · [CNContactStore](https://developer.apple.com/documentation/contacts/cncontactstore).
5. **تغيّر الصلاحية (المنصتان):**
   - **iOS 18+ `.limited`:** تغيّر النطاق يُعالَج كإعادة مزامنة كاملة داخل النطاق المتاح؛ **غياب جهة اتصال بسبب الصلاحية الجزئية لا يُنتج حذفاً ولا تعليماً**، وتبقى صفوفها وبيانات صِلَة سليمة حتى تعود للظهور.
   - **Android — منح بعد الرفض:** عند منح `READ_CONTACTS` لاحقاً تُشغَّل مزامنة كاملة (استيراد أولي) ثم تُستأنف الإشارات التزايدية.
   - **Android — سحب الصلاحية:** تتوقف المزامنة ولا يُحذف أي صف من Room؛ تنتقل الواجهة إلى حالة Permission-denied مع مسار الإعدادات، وتبقى البيانات مقروءة.
6. **الحذف والحفاظ على بيانات صِلَة (يتطلب تغيير مخطط):** حذف في النظام ← تعليم محلي جديد `contacts.systemDeletedAt` وإخفاء من القائمة، مع الاحتفاظ بالتصنيف/أرحام 30 يوماً ثم حذف نهائي لدعم التراجع.
   - **بصراحة:** `systemDeletedAt` **غير موجود** في `ContactEntity` الحالي، و`DataRetentionCleanupDao` الحالي لا يمسّ جدول `contacts` إطلاقاً (يغطي `call_events` 365 يوماً، `arham_inbox` 30/90 يوماً، `dispute_logs` 90 يوماً). لذا تتطلب المرحلة 1: هجرة Room تضيف العمود + توسيع `cleanupExpiredData` بحذف صفوف `contacts` التي تجاوز `systemDeletedAt` فيها 30 يوماً.
7. **الدمج الأحادي (Idempotency):** الـ upsert بمفتاح `systemContactId` فقط (ADR-0009)؛ لا تُلمس أعمدة صِلَة (`category`, `isFavorite`, `arham`, `tags`) أثناء المزامنة؛ إعادة تشغيل نفس الدفعة لا تُنشئ تكراراً.
8. **التعافي من الفشل:** عند أي استثناء لا يُقدَّم رمز/لقطة الإصدار؛ تُعاد المحاولة في الإشارة التالية أو المصالحة الدورية، دون تطبيق جزئي.

## البدائل المرفوضة
- **مزامنة كاملة عند كل تغيير على المنصتين:** تكلفة غير مقبولة مع 5000 جهة اتصال (هدف KR2: cold start ≤1.5s و0 Jank).
- **الاعتماد على `ContentObserver` كدلتا على Android:** لا يحمل هوية السجل المتغير، ويؤدي إما لتكرار كامل أو فقدان تغييرات.
- **الاعتماد على `CNContactStoreDidChangeNotification` وحده على iOS:** بلا فروق، ويستلزم إعادة قراءة كاملة.

## العواقب
- ✅ سلوك موحّد في الـ domain مع تنفيذ تزايدي أصيل لكل منصة.
- ✅ حفظ الرمز/اللقطة داخل نفس معاملة Room يمنع فقدان دلتا عند الانهيار.
- ⚠️ Android يحتاج مصالحة كاملة دورية؛ يجب قياس أثرها على 5000 جهة اتصال.
- ⚠️ iOS `.limited` يتطلب واجهة توسيع صلاحية (`ContactAccessButton` / `contactAccessPicker`) وإلا بدت القائمة ناقصة للمستخدم.
- ⚠️ لا يمكن ضمان تطابق لحظي بين دفتر الهاتف وRoom؛ SLA: تحديث خلال ثوانٍ من الإشعار + مصالحة عند فتح التطبيق.
- ⚠️ يتطلب العمل هجرة Room تضيف `contacts.systemDeletedAt`، وتوسيع `DataRetentionCleanupDao` ليشمل `contacts` (غير مشمول حالياً) — كلاهما ضمن مهام المرحلة 1 لا خارجها.
