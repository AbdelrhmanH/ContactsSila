# سجل التغييرات (Changelog)

يتبع التنسيق [Keep a Changelog](https://keepachangelog.com/) وترقيم [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- **تهيئة المرحلة 1 للضيف (Issue #26 — T5 · معايير الإضافة Issue #20):** تدفق تهيئة كامل (ترحيب بثلاث شرائح ← شرح صلاحية جهات الاتصال ← متابعة كضيف ← أهم 5 أشخاص ← جارٍ التنظيم) عبر `OnboardingViewModel` (MVI/UDF) في `:feature:contacts:presentation` مع حالات استخدام نقية في `:feature:contacts:domain` وتدويم محلي 100% عبر DataStore (`onboardingCompleted`, `isGuest`, `onboardingResumeStep` للاستئناف بعد موت العملية، سجل تسليم `TopFiveSelection` لميزة أرحام T8). مصفوفة الصلاحية (GRANTED/DENIED/DENIED_FOREVER/LIMITED/SKIPPED) بحد طلبات ≤ 2 مع عدم طلبِ النظام تلقائياً أبداً بعد الرفض النهائي — Android: `ActivityResultContracts.RequestPermission` في `androidApp` يترجم المنح/الرفض/"لا تسأل مرة أخرى" إلى Intents و`ACTION_APPLICATION_DETAILS_SETTINGS` للإعدادات؛ iOS: شاشات SwiftUI تلاحظ الـ ViewModel المشترك عبر SKIE مع مسار `.limited` الجزئي ومسار الإعدادات. نصوص عربية/إنجليزية في `composeResources` وقوائم `.lproj` بلا نص ثابت في الكود، واختبارات Koin/ViewModel لكل حالة صلاحية وتخطي/استئناف/إكمال في `:sharedLogic` (`androidHostTest`).
- **عقد مصدر جهات الاتصال من المنصة (Issue #22 · ADR-0009/0010):** `ContactsPlatformSource` في `:feature:contacts:domain` مع نماذج نقية (`PlatformContact`, `ContactsPage`, `ContactsDelta`, `ContactsChangeSignal`, `ContactsPermissionState`, `ContactsSnapshot`) تقرأ دفعات كاملة (~500/دفعة) ودلتا وإشارات اتساخ وحالة الصلاحية (GRANTED/LIMITED/DENIED/PERMANENTLY_DENIED). تنفيذ Android (`ContactsContract`: هوية `LOOKUP_KEY`، مقاصفة إصدارات `RawContacts`، مجهود أفضل من `DeletedContacts`، مراقب بتجميع ≥5 ثوانٍ) وتنفيذ iOS (`CNContactStore`: هوية `identifier`، دلتا عبر `CNChangeHistoryFetchRequest`، `.limited` لا يُعامل رفضاً ولا حذفاً) — قراءة فقط، بلا شبكة. ربط Koin عبر expect/actual في `:sharedLogic` وإضافة `READ_CONTACTS` إلى `androidApp`.

### Fixed
- أخطاء ترجمة Kotlin/Native في `:core:database` على iOS (`'clearAllTables' overrides nothing` و `'close' in 'RoomDatabase' is final`): إدخال جسر `RoomDatabaseLifecycleBridge` بنمط expect/actual (ADR-0008)، حذف `clearAllTables` الميت، واستبدال `close()` بـ `closeConnection()`.

### Changed
- **اعتماد قرار تتبع المكالمات ([ADR-0006](docs/adr/0006-call-log-strategy.md)):** اعتمد صاحب المنتج في 2026-10-30 الخيار **B+ — التتبع العملي متعدد المصادر مع تأكيد احتياطي**. المصادر: `IN_APP` · `ANDROID_CALL_SCREENING` · `ANDROID_TELEPHONY_OBSERVER` · `IOS_CALL_OBSERVER` · `MANUAL`. المكالمات من صِلَة آلية، والخارجية تُرصد حيث تسمح المنصة وتدخل التأكيد عند نقص الهوية/النتيجة، وغير المرصودة تُسجَّل يدوياً. لا `READ_CALL_LOG`، ولا `ROLE_DIALER`، ولا Apple Default Dialer entitlement، ولا رفع سحابي. حُدِّثت وثائق أرحام وPRD والصلاحيات والخصوصية ونموذج البيانات والمصطلحات.
- **إعادة فتح قرار تتبع المكالمات ([ADR-0006](docs/adr/0006-call-log-strategy.md)):** سُحب قبول الخيار B (2026-10-29) لأنه ضيّق متطلب أرحام — المكالمات من أي مصدر — دون إثبات أن المنصات تفرض هذا التضييق. أُضيفت مصفوفة قدرات موثقة لكل أصل مكالمة على Android/iOS، وخيارات A/B/B+/C/D/E، وعقد `CallEventSource`. لا تغيير في الكود: `READ_CALL_LOG` لا تُطلب في المرحلة 1.

### Added
- مستند متطلبات المنتج (PRD) ومواصفات الميزات والتوثيق الهندسي الكامل (34 ملفاً هندسياً يغطي كل جوانب النظام).
- مراجع التصميم البصرية وقواعد الـ UI/UX وإمكانية الوصول (WCAG 2.1 AA).
- هوية التطبيق البصرية وشخصية صِلَة (3D Mascot App Icon) في `docs/design-references/sila-logo-mascot.jpg`.
- **المرحلة 0 (الأساس التقني — Foundation):**
  - هيكلة الموديولات متعددة المنصات: `:core:common`, `:core:model`, `:core:database`, `:core:datastore`, `:core:designsystem`, `:sharedLogic`, `:sharedUI`, `:androidApp`.
  - أدوات التطبيع والخصوصية في `:core:common`: `ArabicTextNormalizer`, `PhoneNormalizer`, `Redactor`, `AppResult`, `BaseMviViewModel`.
  - جميع النماذج والتعدادات (18 Enum) وحساب الأولوية (`calculateUrgency`) في `:core:model`.
  - كيانات Room KMP وواجهات الوصول التفاعلية (`ContactDao`, `ArhamDao`, `ServiceDao`, `DataRetentionCleanupDao`) في `:core:database`.
  - إدارة التفضيلات واللغة والمظهر في `:core:datastore`.
  - نظام التصميم الكامل ومكوناته (`SilaTheme`, `SilaActionSheet`, `SilaCategoryChip`, `SilaContactRow`, `SilaServiceCard`, `SilaArhamRankedRow`, `SilaSettingsGroup`) في `:core:designsystem`.
  - وحدة حقن التبعيات Koin (`silaSharedModule`, `initKoin`) و `MainViewModel` المشترك في `:sharedLogic`.
  - واجهة التبويبات الثلاثة (جهات الاتصال، دليل الخدمات، أرحام) مع دعم العربية والإنجليزية و RTL-First.
- **معمارية التقسيم الهجين (Layers inside Features — ADR-0007):**
  - إنشاء قرار معماري جديد `docs/adr/0007-hybrid-feature-modularization.md` وتحديث `ARCHITECTURE.md`، `SCALABILITY.md`، `CODING_GUIDELINES.md`، و `AGENTS.md`.
  - إنشاء موديولات الميزات المستقلة (`:feature:contacts`, `:feature:arham`, `:feature:services`, `:feature:settings`) مع تقسيم كل ميزة داخلياً إلى حزم Clean Architecture (`domain`, `data`, `presentation`) واختبارات وحدة لكل ميزة.
