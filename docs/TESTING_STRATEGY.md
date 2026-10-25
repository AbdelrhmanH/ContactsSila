# استراتيجية الاختبارات (Testing Strategy)

## 1. الهرم
| المستوى | الأداة | النطاق | الهدف |
|---|---|---|---|
| Unit (commonTest) | kotlin.test, Turbine, Mokkery, coroutines-test | UseCases، ViewModels، Mappers، الخوارزميات | ≥ 80% للـ domain |
| Database | Room in-memory (androidHostTest/iosTest) | DAOs، FTS، Migrations | كل DAO |
| Platform | Robolectric / Instrumented | ContactsSource، CallLogSource، Workers | المسارات الأساسية |
| UI | Compose UI Test, XCUITest | الشاشات الرئيسية والتدفقات | التدفقات الحرجة |
| Screenshot | Roborazzi/Paparazzi, swift-snapshot-testing | المكونات (عربي/إنجليزي، فاتح/داكن) | Design System |
| Backend | Firebase Emulator + rules-unit-testing + Jest | قواعد الأمان و Functions | كل قاعدة ودالة |
| Performance | Macrobenchmark, Baseline Profiles | بدء التشغيل، تمرير 5000 جهة اتصال | بدء بارد < 1.5s، بلا Jank |

## 2. حالات اختبار حرجة (يجب تغطيتها)

### الخوارزميات
- **تطبيع الأرقام:** `0100 123 4567` ، `+20 100 123 4567` ، `00201001234567` ← نفس E.164؛ أرقام قصيرة (19xxx)؛ أرقام مع رموز وإيموجي.
- **تطبيع الأسماء العربية:** `أحمد` = `احمد` = `إحمد`؛ `فاطمة` = `فاطمه`؛ إزالة التشكيل والتطويل `ـ`.
- **المكررات:** نفس الرقم بصيغ مختلفة؛ أسماء متشابهة فوق/تحت العتبة؛ تجاهل المرفوض سابقاً.
- **الدمج:** لا فقدان لأي رقم أو حقل؛ التراجع يعيد الحالة كما كانت.
- **الفلترة الذكية:** "د. محمد" ← خدمات؛ "عمي حسن" ← عائلة؛ "محمد دكتوري" لا يخلط؛ احترام `excludeFromSmartFilter`.
- **أرحام:** حساب Urgency والحالة عند الحدود (0.7، 1.0)؛ OCCASIONS؛ `pausedUntil`؛ مكالمة مدتها 0 لا تُحتسب؛ ترتيب الأولوية.
- **أوقات الهدوء:** إشعار في الفترة الممنوعة يؤجَّل؛ فترة تعبر منتصف الليل.
- **منطق الإضافة:** رقم موجود في الدليل + اختيار "شخصي" ← لا Outbox؛ ضيف ← لا Outbox.
- **التقييم:** لا يظهر لرقم عائلة؛ لا يظهر لمكالمة < 30 ث؛ لا يظهر بعد التقييم الأول؛ يتحول لإشارة ثقة عند التكرار.

### الواجهة
- Onboarding كامل كضيف وكمسجّل.
- إضافة جهة اتصال بكل الحقول ثم التعديل.
- الفلترة بالـ Chips والبحث.
- رفض الصلاحيات (مؤقت ودائم).
- RTL: عدم انعكاس الأرقام والهواتف بشكل خاطئ (أرقام الهاتف دائماً LTR).

### Backend
- ضيف: يقرأ `services`، لا يكتب شيئاً.
- مستخدم لا يكتب `trustScore` أو `visibility`.
- بطاقة عمل برقم لا يطابق رقم الحساب ← مرفوضة.
- إشارتان في نفس اليوم ← الثانية مرفوضة.
- الترقية إلى COMMUNITY عند العتبة بالضبط.

## 3. بيانات الاختبار
- مولّد جهات اتصال وهمية (عربية/إنجليزية/مختلطة، 5000+) في `shared/core/testing`.
- Fakes لكل Platform Source (`FakeContactsSource`, `FakeCallLogSource`, `FakeClock`).

## 4. CI
```text
PR:      build → ktlint → detekt → konsist → allTests → screenshot verify
main:    + instrumented (Firebase Test Lab) + rules tests + assemble release
release: + macrobenchmark + fastlane → internal track / TestFlight
```
