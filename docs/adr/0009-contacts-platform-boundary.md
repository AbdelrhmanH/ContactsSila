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
4. **الهوية المستقرة:**
   - Android: `LOOKUP_KEY` هو المفتاح الدائم (يُخزَّن في `contacts.lookupKey`)، ويُعاد حلّه في كل مسار مزامنة؛ `Contacts._ID` و`RawContacts.CONTACT_ID` غير صالحين كمفاتيح دائمة (يتغيران عند إعادة التجميع/الدمج).
   - iOS: `CNContact.identifier` (الموحّد `unifiedContact`) هو المفتاح الدائم في `contacts.systemContactId`، مع افتراض أن الربط/الفك قد يغيّر المعرّف ويُعالَج كسجل جديد.
5. **الصلاحيات:** قراءة فقط في مرحلة الاستيراد: `READ_CONTACTS` (Android، شامل ولا جزئي حتى Android 16) و`CNContactStore` authorization (iOS، بما فيها `.limited` في iOS 18+). لا `WRITE_CONTACTS` في الاستيراد؛ الكتابة تُقرَّر لاحقاً في ميزة التفاصيل/التعديل بعقد منفصل.
6. **الخصوصية:** التنفيذات لا تستورد أي رمز شبكي؛ قاعدة Konsist الحالية (`contacts`/`arham` بلا `ktor`/`firebase`) تُبقي هذا الحدود مفروضة آلياً.

## البدائل المرفوضة
- **`expect/actual` كحدود أساسية:** يصعّب الاختبار المشترك (لا fake بسيط)، ويضاعف واجهات DI، ويكسر نمط Repository/UseCase المعتمد في ADR-0007.
- **Repository مختلف لكل منصة:** يكرر منطق الدمج والحماية ويجعل سلوك القائمة غير موحّد بين المنصتين.

## العواقب
- ✅ مصدر قابل للاستبدال في الاختبارات (`FakeContactsPlatformSource`) وسلوك domain موحّد.
- ✅ اختبار Android عبر Robolectric/instrumented وiOS عبر المحاكي فقط عند حدود المنصة.
- ⚠️ اختلافات المنصات (LookupKey مقابل identifier، الصلاحيات الجزئية) تُدار داخل التنفيذات وتُختبر منفصلة.
- ⚠️ يتطلب عقداً صريحاً لتمثيل "نطاق الصلاحية" حتى لا يُفسَّر نقص الظهور في iOS `.limited` كحذف.
