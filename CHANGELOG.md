# سجل التغييرات (Changelog)

يتبع التنسيق [Keep a Changelog](https://keepachangelog.com/) وترقيم [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Fixed
- أخطاء ترجمة Kotlin/Native في `:core:database` على iOS (`'clearAllTables' overrides nothing` و `'close' in 'RoomDatabase' is final`): إدخال جسر `RoomDatabaseLifecycleBridge` بنمط expect/actual (ADR-0008)، حذف `clearAllTables` الميت، واستبدال `close()` بـ `closeConnection()`.

### Changed
- **إعادة فتح قرار تتبع المكالمات ([ADR-0006](docs/adr/0006-call-log-strategy.md)):** سُحب قبول الخيار B (2026-10-29) لأنه ضيّق متطلب أرحام — المكالمات من أي مصدر — دون إثبات أن المنصات تفرض هذا التضييق. أُضيفت مصفوفة قدرات موثقة لكل أصل مكالمة على Android/iOS، وخيارات A/B/B+/C/D/E، وعقد `CallEventSource`. لا تغيير في الكود: `READ_CALL_LOG` لا تُطلب في المرحلة 1، والتتبع المتاح داخلي (`IN_APP`/`OBSERVER`) + تأكيد يدوي (`MANUAL`).

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
