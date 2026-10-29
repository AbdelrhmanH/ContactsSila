# ADR-0009: حدود تكامل جهات الاتصال مع المنصات (Android Contacts Provider / iOS CNContactStore)
- **الحالة:** مقبول
- **التاريخ:** 2026-10-29
- **المرجع:** [Wayfinder Phase 1 map](https://github.com/AbdelrhmanH/ContactsSila/issues/16) · [ADR-0007](0007-hybrid-feature-modularization.md) · [DATA_MODEL](../DATA_MODEL.md) · [PLATFORM_PERMISSIONS](../PLATFORM_PERMISSIONS.md)

## السياق
المرحلة 1 تتطلب قراءة جهات الاتصال من نظام التشغيل (Android `ContactsContract`، iOS `CNContactStore`) مع الحفاظ على:
- نقاء الـ `domain` (بلا أنواع منصات أو أطر عمل).
- قاعدة الخصوصية: لا شبكة ولا Firebase داخل `:feature:contacts`.
- قابلية الاختبار على Windows `androidHostTest` وعلى محاكي iOS دون جهاز حقيقي.
- هوية مستقرة تسمح بالمزامنة التزايدية والربط بين سجل Room وجهة الاتصال الأصلية.

الخيارات المدروسة:
1. `expect/actual` كحدود أساسية لطبقة المصدر.
2. واجهة `ContactsPlatformSource` في الـ domain + تنفيذات منصات في `data` تربطها Koin.
3. تنفيذ Repository مختلف لكل منصة (تكرار منطق المزامنة).

## القرار
1. **الواجهة أولاً:** `ContactsPlatformSource` واجهة في `:feature:contacts:domain` تعيد نماذج domain فقط (`PlatformContact`، `ContactsDelta`، `ContactsChangeSignal`) ولا تسرّب أي نوع من `android.*` أو `platform.**` أو `Contacts`/`CNContact`.
2. **التنفيذات داخل `data`:** `AndroidContactsPlatformSource` في `src/androidMain` و`IosContactsPlatformSource` في `src/iosMain` داخل `:feature:contacts:data`، مع ربط Koin في موديولات DI المنصات داخل `:sharedLogic`.
3. **`expect/actual` محصور في الغراء النظامي** فقط (الحصول على `ContentResolver` / `CNContactStore` ومراقبة الإشعارات)، وليس في عقد المصدر.
4. **الهوية المستقرة ومفتاح الـ upsert:**
   - **مفتاح الـ upsert هو `contacts.systemContactId`** (الفهرس الفريد الموجود فعلاً في `ContactEntity`). لا يُضاف عمود مفتاح جديد، ولا يُستخدم `id` المحلي ولا `Contacts._ID` للمطابقة.
   - **Android:** تُخزَّن قيمة `LOOKUP_KEY` في `systemContactId` وتُعاد حلّها في كل مسار مزامنة؛ `Contacts._ID` و`RawContacts.CONTACT_ID` غير صالحين كمفاتيح دائمة (يتغيران عند إعادة التجميع/الدمج). العمود `lookupKey` يبقى مرآةً لنفس قيمة `LOOKUP_KEY` على Android (لبناء `CONTENT_LOOKUP_URI`) ويكون `null` على iOS. المصدر: [ContactsContract.Contacts](https://developer.android.com/reference/android/provider/ContactsContract.Contacts) · [RawContacts](https://developer.android.com/reference/android/provider/ContactsContract.RawContacts).
   - **iOS:** `CNContact.identifier` للـ `unifiedContact` هو المفتاح الدائم في `systemContactId`. المصدر: [CNContact.identifier](https://developer.apple.com/documentation/contacts/cncontact/identifier).
   - **إعادة الربط/الفك (relink/unlink):** إذا تغيّر المعرّف (تجميع جديد على Android أو ربط/فك على iOS) يُنشأ سجل جديد ويُعلَّم القديم `systemDeletedAt` (ADR-0010)؛ **لا تُنقل بيانات صِلَة تلقائياً**، بل تظهر الحالة كاقتراح دمج في ميزة المكررات (04) ليتخذ المستخدم القرار — لا دمج صامت.
5. **الصلاحيات ونطاق الكتابة:**
   - **الاستيراد والمزامنة قراءة فقط:** `READ_CONTACTS` (Android؛ شامل لا جزئي حتى Android 16) و`requestAccess(for: .contacts)` (iOS، بما فيها `.limited` في iOS 18+). المزامنة لا تكتب في دفتر الهاتف إطلاقاً. المصدر: [Android permissions](https://developer.android.com/guide/topics/permissions/overview) · [Accessing the contact store](https://developer.apple.com/documentation/contacts/requesting-authorization-to-access-contacts).
   - **الكتابة ضمن المرحلة 1 بعقد منفصل:** صفحة التفاصيل/التعديل (الميزة 03) تكتب الحقول القياسية عبر واجهة `ContactsPlatformWriter` في الـ domain (تنفيذات منصات في `data`)، ولا تستخدمها المزامنة. على Android تقع `READ_CONTACTS` و`WRITE_CONTACTS` في مجموعة صلاحيات runtime واحدة (CONTACTS) فتُمنحان بطلب واحد؛ على iOS يُستخدم نفس تفويض `.contacts` بنمط `.readWrite` عند أول تعديل.
   - **الصلاحية الجزئية:** على iOS `.limited` يعطي قائمة جزئية؛ توسيعها عبر `ContactAccessButton` / منتقي الصلاحية، وسلوك المزامنة في [ADR-0010](0010-incremental-contacts-sync.md). المصدر: [ContactAccessButton](https://developer.apple.com/documentation/contactsui/contactaccessbutton).
6. **الخصوصية:** التنفيذات لا تستورد أي رمز شبكي؛ قاعدة Konsist الحالية (`contacts`/`arham` بلا `ktor`/`firebase`) تُبقي هذا الحدود مفروضة آلياً.

## البدائل المرفوضة
- **`expect/actual` كحدود أساسية:** يصعّب الاختبار المشترك (لا fake بسيط)، ويضاعف واجهات DI، ويكسر نمط Repository/UseCase المعتمد في ADR-0007.
- **Repository مختلف لكل منصة:** يكرر منطق الدمج والحماية ويجعل سلوك القائمة غير موحّد بين المنصتين.

## العواقب
- ✅ مصدر قابل للاستبدال في الاختبارات (`FakeContactsPlatformSource`) وسلوك domain موحّد.
- ✅ اختبار Android عبر Robolectric/instrumented وiOS عبر المحاكي فقط عند حدود المنصة.
- ⚠️ اختلافات المنصات (LookupKey مقابل identifier، الصلاحيات الجزئية) تُدار داخل التنفيذات وتُختبر منفصلة.
- ⚠️ يتطلب عقداً صريحاً لتمثيل "نطاق الصلاحية" حتى لا يُفسَّر نقص الظهور في iOS `.limited` كحذف.
