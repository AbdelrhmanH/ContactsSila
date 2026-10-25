# ADR-0007: اعتماد معمارية التقسيم الهجين (الطبقات كموديولات فرعية داخل الميزات — Layers inside Features)
- **الحالة:** مقبول
- **التاريخ:** 2026-10-25

## السياق
عند تقسيم مشروع Kotlin Multiplatform قابل للتوسع مثل "صِلَة"، هناك منهجيتان كلاسيكيتان:
1. **التقسيم حسب الطبقة فقط (Modularization by Layer):** يجعل أي تعديل في ميزة واحدة يتطلب التنقل بين عدة موديولات (`domain`, `data`, `presentation`) ويعيد بناء طبقات كاملة عند أي تغيير بسيط.
2. **التقسيم حسب الميزة فقط بدون طبقات داخلية:** يجعل الموديول الواحد فوضوياً مع نمو حجم الكود ولا يفصل تبعيات العرض (`Compose`) عن تبعيات البيانات (`Room`) وقت الترجمة.

بالإضافة إلى ذلك، يفرض "صِلَة" قيد خصوصية صارم: ميزات `contacts` و `arham` محلية 100% وممنوعة من الاتصال بالشبكة أو Firebase، بينما ميزة `services` تتصل بالسحابة.

## القرار
اعتماد **النهج الهجين: الطبقات كموديولات Gradle فرعية داخل كل ميزة (Layers as Gradle Sub-Modules inside Features — Vertical Slice Architecture)** فوق طبقة نواة مشتركة (`:core:*`):

1. **التقسيم الخارجي حسب الميزة (`feature/<name>`):**
   - `feature/contacts` (إدارة جهات الاتصال، الفلترة الذكية، دمج المكررات — محلي 100%).
   - `feature/arham` (صلة الرحم، الأولوية، المنصة، شجرة العائلة — محلي 100%).
   - `feature/services` (دليل الخدمات الحي، التقييمات، مؤشر الثقة — محلي + سحابي).
   - `feature/settings` (التفضيلات، المظهر، اللغة، النغمات).

2. **التقسيم الداخلي كموديولات Gradle فرعية مستقلة (`build.gradle.kts` لكل طبقة):**
   - `:feature:<name>:domain` (`app.sila.feature.<name>.domain`): واجهات الـ Repository، حالات الاستخدام (`UseCases`)، والقواعد النقية — يعتمد فقط على `:core:common` و `:core:model`.
   - `:feature:<name>:data` (`app.sila.feature.<name>.data`): تنفيذ الـ Repository (`RepositoryImpl`) — يعتمد على `:feature:<name>:domain` و `:core:database` (أو `:core:datastore`).
   - `:feature:<name>:presentation` (`app.sila.feature.<name>.presentation`): الـ `ViewModel` المشترك وشاشات Compose — يعتمد على `:feature:<name>:domain` و `:core:designsystem` فقط (لا يرى طبقة `data` أو `database`).

3. **منع الاعتماد المتبادل بين الميزات (Zero Feature-to-Feature Coupling):**
   - الميزات **لا تعتمد على بعضها مباشرة أبداً**.
   - التواصل ومشاركة البيانات يتم حصرياً عبر موديولات النواة `:core:model` و `:core:database` (Single Source of Truth).

4. **تجميع KMP عبر `:sharedLogic` و `:sharedUI`:**
   - يعمل `:sharedLogic` كـ Umbrella Module يجمع الموديولات الفرعية للميزات ويوفر `initKoin()` ويُصدّر `SharedLogic.framework` لـ iOS (SwiftUI).
   - يجمع `:sharedUI` شاشات Compose المشتركة بالاعتماد على `:core:designsystem` و `:feature:*:presentation`.

## البدائل المرفوضة
- **الميزات داخل الطبقات (Features inside Layers):** يبطئ وقت البناء (Incremental Build Time) ويجعل عزل صلاحيات الشبكة عن `contacts` و `arham` أكثر تعقيداً.
- **موديول واحد للميزة بدون فصل طبقاتها كموديولات فرعية:** لا يمنع طبقة `presentation` من الوصول المباشر إلى `Room DAO` أو طبقة `data` وقت الترجمة.

## العواقب
- ✅ **فرض الخصوصية والمعمارية بقوة المترجم (Compile-time Enforcement):**
  - موديولات `:feature:contacts:*` و `:feature:arham:*` لا تحتوي على تبعية `:core:network` أو `:core:firebase`.
  - موديولات `:feature:*:presentation` لا تملك تبعية على `:feature:*:data` أو `:core:database`.
- ✅ **سرعة بناء عالية وتعرف كامل في Android Studio:** يظهر كل من `domain` و `data` و `presentation` كموديول Gradle مستقل بعلامة الموديول الزرقاء `[🟦]`.
