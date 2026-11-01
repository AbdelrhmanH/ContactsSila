# تقرير إصلاح PR B — جعل Room 3 مرجع المخطط الوحيد والاعتماد الآمن (F-04 / F-05)

**الحالة النهائية:** `VERIFIED` محلياً على Android/JVM + تجميع KSP/Native على `iosSimulatorArm64` + مراجعتان مستقلتان (`agy` + Gemini Argon)
**الفرع:** `refactor/pr-b-room-database` (Worktree: `F:\Temp\wt-pr-b-room-db`)
**الأساس:** `origin/main @ dd04f1fd13dcfad2cb68b81e14ee7500ddf0397a`
**النطاق المصرَّح:** F-04 (سلطة مخطط موحدة عبر Room 3) و F-05 (اتساق مخطط قاعدة البيانات والاعتماد الآمن لـ `user_version = 0`) — بلا أي تسرّب من PR C / PR D / PR E / T4.
**المرجع المعماري:** [ADR-0011](../adr/0011-room3-single-schema-authority.md) (يستبدل [ADR-0008](../adr/0008-room-lifecycle-bridge.md)) · [DATA_MODEL.md](../DATA_MODEL.md)

---

## 1. الملخص التنفيذي (Executive Summary)

عالج هذا المسار البندين المعماريين **F-04** و**F-05** جذرياً:

1. **إلغاء ازدواجية المحرك والمخطط (F-04):**
   - تفعيل **Room 3.0.3 KMP** (`androidx.room3` + KSP `2.3.13` + `BundledSQLiteDriver`) كسلطة وحيدة للمخطط الفيزيائي (`@Database(version = 1, exportSchema = true)` في `SilaRoomDatabase`).
   - مطابقة الكيانات الثمانية المُدارة (`ContactEntity`, `ContactPhoneEntity`, `ArhamEntity`, `ServiceEntity`, `CallEventEntity`, `ArhamInboxEntity`, `DisputeLogEntity`, `ContactsSyncCheckpointEntity`) مع الجداول الفيزيائية حرفياً، وتصدير المخطط المرجعي في `core/database/schemas/app.sila.core.database.SilaRoomDatabase/1.json` (`identityHash = 04f904a96aff819c38003c5c300fe72b`).
   - حذف الكيانين الشبحيين اللذين لم يُنشأ لهما جدول فيزيائي قط (`ScheduledMessageEntity` و`EmergencyRingingSessionEntity`)، وحذف المحولات الخمسة الميتة (`Sqlite*Dao.kt`) وجسر `RoomDatabaseLifecycleBridge` (`expect/actual`).
   - تحويل `LocalSilaDatabase` إلى واجهة موحدة (Facade) تعمل حصرياً فوق `val room: SilaRoomDatabase` وتنفّذ واجهات الـ DAO الخمس (`ContactDao`, `ArhamDao`, `ServiceDao`, `DataRetentionCleanupDao`, `ContactsSyncDao`) عبر 7 DAOs مولَّدة بـ KSP ومعاملات `room.useWriterConnection { it.immediateTransaction { ... } }`.

2. **الاعتماد الآمن غير الهدمي لقواعد `user_version = 0` (F-05):**
   - بناء إطار الاعتماد المسبق `LegacyDatabaseAdopter` + بصمة البنية الصارمة `LegacySchemaFingerprint` + النسخة الواقية الموثقة `SilaDatabaseBackup` (`sila_local.pre-room-backup` عبر `VACUUM INTO`).
   - تصنيف الأشكال التاريخية الفعلية (`L0`, `L1`, `L2_OR_L3`, `PARITY_DONE`, `ROOM_MANAGED`, `FRESH`, `UNKNOWN`) مع فشل آمن مغلق (`LegacySchemaUnknownException`) لأي جدول أو عمود أو مشغِّل أو قيد FK غير معروف قبل لمس الملف.
   - عدم استخدام `Migration(0, 1)` (غير المضمونة في Room لأن `0` يمثل قاعدة غير مهيأة) وعدم تفعيل أي `fallbackToDestructiveMigration`.

---

## 2. تشخيص وإصلاح فشل `ServicesFeatureTest` وتنافس الاتصالات في البطارية الكاملة

### السبب الجذري (Root Cause)
1. عند إنشاء `LocalSilaDatabase`، يستدعي المُنشئ `runBlocking { loadOrSeedContactsBlocking() }` و`runBlocking { loadOrSeedServicesBlocking() }` بينما يضبط سياق استعلامات Room على `.setQueryCoroutineContext(dispatchers.io)`.
2. عندما تعمل اختبارات داخل `runTest { ... }` وتُنشئ `LocalSilaDatabase` بالموزع الافتراضي `DefaultAppDispatchers()` (`dispatchers.io = Dispatchers.IO`)، يحجز `runBlocking` خيط عامل الاختبار بينما تطلق `ConnectionPoolImpl` و`InvalidationTracker` في Room كوروتينات داخلية على `Dispatchers.IO` تتنافس على اتصال `:memory:` الوحيد. تحت الحمل المتوازي الكثيف لبطارية التحقق الكاملة (10 مهام اختبار + ضغط R8 في `:androidApp:assembleRelease` في آنٍ واحد)، استنفدت `ConnectionPoolImpl.acquireWithTimeout` مهلة الـ 30 ثانية في `ServicesFeatureTest` واستغرقت `31.304s` في `ContactsRepositoryImplTest`.
3. بالإضافة إلى ذلك، لم يكن تسجيل `single { LocalSilaDatabase(...) }` في `sharedLogic/src/commonMain/kotlin/app/sila/di/KoinModule.kt` يربط خطاف إغلاق `onClose { it?.closeConnection() }`. وعند استدعاء `stopKoin()` في اختبارات Koin (`OnboardingViewModelKoinTest` وغيرها)، بقيت اتصالات WAL مفتوحة على نفس ملف `sila_local.db` عند محاكاة استئناف العملية بعد القتل، مما أدى إلى تعليق دام 73 ثانية تحت الحمل المتوازي.

### الإصلاحات المُطبَّقة
- `feature/services/data/src/commonTest/kotlin/app/sila/feature/services/ServicesFeatureTest.kt`: تمرير `dispatchers = testDispatchers` (`Dispatchers.Unconfined`) وإغلاق القاعدة حتماً داخل `try { ... } finally { db.closeConnection() }` (انخفض زمن التنفيذ إلى `0.818s`).
- `feature/arham/data/src/commonTest/kotlin/app/sila/feature/arham/ArhamFeatureTest.kt`: تمرير `dispatchers = testDispatchers` وإغلاق القاعدة في `try / finally` (`1.205s`).
- `feature/contacts/data/src/commonTest/kotlin/app/sila/feature/contacts/data/ContactsRepositoryImplTest.kt`: تمرير `dispatchers = testDispatchers` (`0.746s` بدلاً من `31.304s`).
- `sharedLogic/src/commonMain/kotlin/app/sila/di/KoinModule.kt`: إضافة `onClose { it?.closeConnection() }` لتعريف `single { LocalSilaDatabase(...) }` لضمان إغلاق `SilaRoomDatabase` وتحرير أقفال WAL فور انتهاء دورة حياة حاوية Koin (`OnboardingViewModelKoinTest` انخفض إلى `1.568s`).

---

## 3. التحقق من آلية `contacts_fts` (FTS4 Callback DDL — القسم 6)

### إثبات قيد KSP على أهداف Native من بايت-كود `room3-compiler-3.0.3.jar`
فُحصت دالة `androidx.room3.processor.FtsTableEntityProcessor.getContentEntity(XType)` عبر `javap -c -p`:
- عند الإزاحات `0..29`: إذا غاب معامل `contentEntity` في `@Fts4` (أو لم يُحلّ افتراضيه `Any::class` من بيانات `klib` على أهداف Kotlin/Native)، تكون قيمة `XType` مساوية لـ `null` فيسجّل المعالج خطأ الترجمة الحرفي: `Cannot find external content entity class.`.
- عند الإزاحات `30..59`: يقارن المعالج النوع الممرَّر بـ `processingEnv.requireType(java.lang.Object::class)` (`ldc #4 // class java/lang/Object`)، وهو صنف JVM لا يطابق `kotlin.Any` على أهداف Kotlin/Native (`iosArm64` / `iosSimulatorArm64`).
- كما أن وضع `contentEntity = ContactEntity::class` (جدول محتوى خارجي) غير متوافق مع متطلبات البحث العربي في صِلَة (`docid = contacts.id` مع تطبيع `ArabicTextNormalizer` قبل الفهرسة وحذف الصفوف المقبورة بـ `DELETE FROM contacts_fts WHERE docid = ?`).

### خصائص الحل المعتمد
- تعريف العبارة المرجعية الوحيدة في `LegacyFtsDdl.CREATE_CONTACTS_FTS`:
  `CREATE VIRTUAL TABLE IF NOT EXISTS contacts_fts USING fts4(displayName, nickname, company, department, notes, normalizedName)`
- إنشاؤها عبر `RoomDatabase.Callback.onOpen` في `LocalSilaDatabase.buildRoomDatabase()` وفي شيم الاعتماد `LegacyDatabaseAdopter.rebuildFtsIfNeeded()` حصراً — idempotent، بلا مشغّلات، وبمزامنة يدوية ذرّية داخل معاملات كتابة Room.

---

## 4. التحقق من الاعتماد الآمن والنسخة الواقية (`LegacyDatabaseAdopter` & `SilaDatabaseBackup` — القسم 7)

| البند | التحقق الفعلي |
|---|---|
| تصنيف البصمة `LegacySchemaFingerprint` | يفحص `user_version`، أسماء الجداول، المشغّلات، قيود FK، أعمدة الجداول الستة الثابتة (`contact_phones`, `arham`, `services`, `call_events`, `arham_inbox`, `dispute_logs`) وجدول `contacts_sync_checkpoints`، أعمدة وفهارس `contacts`، وأعمدة وخيارات `contacts_fts` — قراءة فقط وبلا أي PII في `safeSummary()`. |
| الفشل الآمن (`UNKNOWN`) | أي جدول غريب، مشغِّل، قيد FK، أو عمود ناقص/زائد في أي جدول أساسي يرمي `LegacySchemaUnknownException` قبل تشغيل الشيم (مع أخذ نسخة تشخيصية موثقة أفضل-جهد). |
| النسخة الواقية (`SilaDatabaseBackup`) | فحص المساحة الحرة (`>= 2 × الحجم المنطقي + 16MB`؛ `null` يفشل مغلقاً) ← `VACUUM INTO 'sila_local.pre-room-backup'` (يدمج بيانات WAL الملتزمة) ← تحقق `PRAGMA integrity_check` كامل + مطابقة عدد صفوف كل جدول منطقي بين الأصل والنسخة. |
| حماية الخصوصية للنسخة الواقية | مستثناة صراحةً في `androidApp/src/main/res/xml/backup_rules.xml` و`data_extraction_rules.xml` ومثبتة باختبار `BackupConfigVerificationTest`. |
| حذف النسخة الواقية | تُحذف في `LocalSilaDatabase.init` فقط بعد نجاح فتح Room والتحقق من المخطط وختم `user_version = 1` وإتمام قراءتَي `contactsState` و`servicesState` معاً؛ وتبقى محفوظة عند أي فشل في الشيم أو التحقق. |
| الحفاظ على بيانات المستخدم | تكرارات `systemContactId` (`idx_contacts_system_id` غير فريد)، التشكيل العربي والرموز التعبيرية، معرّفات `contact_phones.id` غير المتسلسلة، القبور `systemDeletedAt`، ونقاط الاستئناف كلها محفوظة حرفياً ومثبتة بالاختبارات. |

---

## 5. تدقيق مخطط Room والـ DAOs وربط Koin (الأقسام 8–11)

- **الكيانات الثمانية المُدارة في `SilaRoomDatabase` (`version = 1`):**
  1. `contacts` (`ContactEntity` — 29 عموداً، 4 فهارس غير فريدة)
  2. `contact_phones` (`ContactPhoneEntity` — 6 أعمدة، فهرسان على `contactId` و`e164`)
  3. `arham` (`ArhamEntity` — 8 أعمدة)
  4. `services` (`ServiceEntity` — 16 عموداً، فهرسان على `phoneE164` و`professionId`)
  5. `call_events` (`CallEventEntity` — 6 أعمدة، فهرس على `timestamp`)
  6. `arham_inbox` (`ArhamInboxEntity` — 8 أعمدة، فهرس على `sentAt`)
  7. `dispute_logs` (`DisputeLogEntity` — 7 أعمدة)
  8. `contacts_sync_checkpoints` (`ContactsSyncCheckpointEntity` — عمودان `key`, `value`)
- **ربط Koin (`KoinModule.kt` & `IosKoinBootstrap.kt`):**
  - `LocalSilaDatabase` مسجلة كـ `single` مع `onClose { it?.closeConnection() }` وبلا تمرير `seedDemoData` (يبقى `false` في الإنتاج).
  - `SilaRoomDatabase` والواجهات الخمس (`ContactDao`, `ArhamDao`, `ServiceDao`, `DataRetentionCleanupDao`, `ContactsSyncDao`) تُحقن من نفس المثيل الوحيد.

---

## 6. نتائج الاختبارات والبناء مع رموز الخروج الحقيقية (Section 5 & 12)

### أ) بطارية التحقق الكاملة (Section 5)
```powershell
.\gradlew checkArchitectureBoundaries `
  :core:common:testAndroidHostTest `
  :core:model:testAndroidHostTest `
  :core:database:testAndroidHostTest `
  :core:datastore:testAndroidHostTest `
  :feature:contacts:domain:testAndroidHostTest `
  :feature:contacts:data:testAndroidHostTest `
  :feature:arham:data:testAndroidHostTest `
  :feature:services:data:testAndroidHostTest `
  :feature:settings:data:testAndroidHostTest `
  :sharedLogic:testAndroidHostTest `
  :androidApp:testDebugUnitTest `
  :androidApp:assembleDebug `
  :androidApp:assembleRelease `
  --console=plain
```
- **النتيجة:** `BUILD SUCCESSFUL` — `GRADLE_EXIT_CODE=0`.

### ب) إعادة تشغيل جميع الاختبارات (`--rerun`) + تجميع KSP/Kotlin لـ iOS Simulator ARM64
```powershell
.\gradlew checkArchitectureBoundaries `
  :core:common:testAndroidHostTest `
  :core:model:testAndroidHostTest `
  :core:database:testAndroidHostTest `
  :core:datastore:testAndroidHostTest `
  :feature:contacts:domain:testAndroidHostTest `
  :feature:contacts:data:testAndroidHostTest `
  :feature:arham:data:testAndroidHostTest `
  :feature:services:data:testAndroidHostTest `
  :feature:settings:data:testAndroidHostTest `
  :sharedLogic:testAndroidHostTest `
  :androidApp:testDebugUnitTest `
  :core:database:kspKotlinIosSimulatorArm64 `
  :core:database:compileKotlinIosSimulatorArm64 `
  :sharedLogic:compileKotlinIosSimulatorArm64 `
  --rerun --console=plain
```
- **النتيجة:** `BUILD SUCCESSFUL` — `GRADLE_EXIT_CODE=0`.
- **إجمالي تقارير JUnit XML عبر المستودع:** **33 حزمة اختبار / 183 اختباراً / 0 فشل / 0 خطأ** (منها **44/44** اختباراً في `:core:database`).

---

## 7. نتائج المراجعات المستقلة (Independent Reviews — Section 13)

1. **المراجع الرئيسي المستقل (`agy` في وضع `--read-only`، المحادثة `10e2c852-5b67-45ab-a13c-43b777f6746d`):**
   - **الحكم:** **`APPROVE`** (0 مشاكل معطِّلة عبر الأبعاد الـ 11 كاملة: ملكية مخطط Room، صحة الاعتماد، حفظ بيانات المستخدم، أمان النسخة الواقية، سلوك الـ DAO، دورة حياة Koin، معالجة FTS4، تكافؤ Android/iOS، جودة الاختبارات، الحدود المعمارية، والالتزام بالنطاق).
2. **المراجع الخصومي الثانوي (Gemini Argon في سياق قراءة فقط مستقل، المحادثة `548f097f-3a17-46a1-b93d-b73631bcda8a`):**
   - **الحكم:** **`PASS`** (0 مشاكل معطِّلة، وملاحظتان غير معطِّلتين عُولجتا فوراً: تصحيح تنسيق علامات الاقتباس الخلفية في `docs/DATA_MODEL.md:163`، وتوسيع `LegacySchemaFingerprint.classify` و`LegacyDatabaseAdopter.normalizeContactsIndices` لشمول حالات الانقطاع الجزئي بين إنشاء `contacts_sync_checkpoints` و`contacts_fts` وإسقاط أي فهرس فريد على `contacts`).

---

## 8. المخاطر المتبقية (Remaining Risks)

- **قيود KSP لأهداف Native على `@Fts4` في Room 3.0.3:** يبقى جدول `contacts_fts` مُنشأً عبر `RoomDatabase.Callback.onOpen` بعبارة `LegacyFtsDdl.CREATE_CONTACTS_FTS` الموحدة إلى أن يحلّ Room KMP معالجة `contentEntity` الافتراضي على أهداف Native؛ وأي تغيير مستقبلي على أعمدة FTS يتطلب تحديث الثابت الموحد وترحيل `Migration(1, 2)` صريحاً كما هو موثق في [ADR-0011](../adr/0011-room3-single-schema-authority.md).
- **تشغيل اختبارات `iosSimulatorArm64Test` الفعلية:** يتطلب بيئة macOS، وقد أُضيف `./gradlew :core:database:iosSimulatorArm64Test --console=plain` إلى وظيفة `verify-ios` (`macos-15`) في `.github/workflows/ci.yml` للتحقق الآلي عند فتح الـ PR.

---

## 9. بيانات الفرع والـ PR (Commit & PR Metadata)

- **الفرع:** `refactor/pr-b-room-database`
- **الأساس:** `dd04f1fd13dcfad2cb68b81e14ee7500ddf0397a` (`main`)
- **عنوان الـ PR:** `refactor(database): make Room the single source of truth`
- **سياسة الدمج:** لا يُدمج تلقائياً — يُترك مفتوحاً بعد التحقق من نجاح فحوص GitHub Actions CI لقرار صاحب المنتج.
