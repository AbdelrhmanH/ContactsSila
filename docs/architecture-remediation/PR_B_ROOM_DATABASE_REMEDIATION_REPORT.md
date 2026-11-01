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
- **عنوان الـ PR:** `refactor(database): make Room the single source of truth` (#38)
- **سياسة الدمج:** لا يُدمج تلقائياً — يُترك مفتوحاً بعد التحقق من نجاح فحوص GitHub Actions CI لقرار صاحب المنتج.

---

## 10. معالجة ملاحظات مراجعة الأمان النهائية (PR #38 Targeted Safety Remediation: SR-01, SR-02, SR-04)

- **رأس الـ PR السابق قبل المعالجة (Original Reviewed PR HEAD):** `b3cd93268557f25a4fd1f9414d32793bf593e47f` (فوق `623a0e32fcbe0b8b96b06e7188f65de1de8e86d3`)
- **رسالة التزام المتابعة (Follow-up Commit):** `fix(database): harden legacy adoption and backup recovery` (رأس الفرع المحدث لـ PR #38 على `refactor/pr-b-room-database`)
- **النطاق:** معالجة ملاحظات الأمان الثلاث المعتمدة فقط (`SR-01`, `SR-02`, `SR-04`) دون المساس ببنية Room 3.0.3 أو سلوك FTS4 (`SR-03`) أو قفل المسار المحلي (`SR-05`) أو فحص المساحة الحرة (`SR-06`).

### 10.1 SR-01 (`HIGH`): تطبيع الفهارس الفريدة بدقة دون حذف الفهارس المشروعة (`LegacyDatabaseAdopter.normalizeContactsIndices`)

- **السبب الجذري (Root Cause):**
  كانت دالة `LegacyDatabaseAdopter.normalizeContactsIndices` تقرأ `PRAGMA index_list('contacts')` وتسقط أي فهرس يحمل `unique == 1 && origin == "c"` بغضّ النظر عن اسمه أو أعمدته، كما لم تكن `LegacySchemaFingerprint.readSnapshot` تفحص البيانات الوصفية الكاملة للفهارس (`origin`, `partial`, ترتيب الأعمدة `DESC`/`ASC`, أو التعبيرات).
- **الإصلاح (Fix):**
  1. توسيع `LegacyIndex` و`LegacySchemaFingerprint.readIndicesForTable` لقراءة `PRAGMA index_list` و`PRAGMA index_xinfo` مع الحراسة الآمنة للتعبيرات (`cid < 0 || infoStmt.isNull(2)` $\rightarrow$ `hasExpression = true`) واستخراج (`name`, `unique`, `origin`, `partial`, `columns`, `orders`, `hasExpression`).
  2. تعديل `LegacyDatabaseAdopter.normalizeContactsIndices` لتسقط فقط:
     - الفهرس التاريخي الفريد المعروف على `systemContactId` (`origin == "c"`, `unique == true`, `!partial`, `!hasExpression`, `columns == listOf("systemContactId")`, `orders == listOf("ASC")`)، أو
     - فهرساً يحمل أحد الأسماء الأربعة المستهدفة (`idx_contacts_system_id`, `idx_contacts_display_name`, `idx_contacts_normalized_name`, `idx_contacts_favorite_call`) إذا اختلفت خصائصه عن مواصفة Room، ليُعاد إنشاؤه فوراً بـ `CREATE INDEX IF NOT EXISTS`.
  3. عدم المساس بأي فهرس فريد آخر (`lookupKey`، الفهارس المركبة، الفهارس الجزئية `WHERE ...`، فهارس التعبيرات، أو الفهارس الضمنية `origin = 'u'` / `origin = 'pk'`).
- **الأدلة الاختبارية (`LegacyDatabaseAdoptionTest.kt`):**
  - `sr01_normalizeContactsIndices_dropsOnlyLegacyUniqueSystemContactIdIndex_andPreservesAllOtherConstraints`: يختبر الحالات الخمس (Fixture A–E: فهرس `systemContactId` التاريخي الفريد سواء باسم `idx_contacts_system_id` أو `idx_contacts_system_contact_id_legacy`، فهرس فريد منفصل على `lookupKey`، فهرس فريد مركب على `(systemContactId, accountType)`، فهرس فريد جزئي، وقيد `UNIQUE` ضمني `origin='u'` + المفتاح الأساسي `origin='pk'`) ويثبت بالتكرار (Idempotency) بقاء قيود الفرادة الأخرى فعّالة بـ `SQLITE_CONSTRAINT` بعد التطبيع.
  - `sr01_fullAdoption_preservesDuplicateSystemContactId_andAllUserRecordsAndIds`: يثبت نجاح الاعتماد الكامل مع وجود قيم `systemContactId` مكررة/فارغة والحفاظ الحرفي على جميع السجلات والمعرفات (`contacts`, `contact_phones`, `arham`, `services`, `call_events`, `arham_inbox`, `dispute_logs`, `contacts_sync_checkpoints`).

### 10.2 SR-02 (`HIGH`): التصنيف الصارم المغلق على الفشل (`LegacySchemaFingerprint.classify`)

- **السبب الجذري (Root Cause):**
  1. كان الشرط `hasFts || hasCheckpoints` يصنّف قواعد البيانات الهجينة أو المحدثة جزئياً (وجود `contacts_fts` بدون `contacts_sync_checkpoints` أو العكس) كـ `L1` بدلاً من رفضها كـ `UNKNOWN`.
  2. كانت `hasExpectedInvariantColumns` تتحقق من أسماء الأعمدة فقط دون التحقق من (`type`, `notNull`, `pkPosition`, `defaultValue`)، وكانت `hasExpectedInvariantIndices` تتحقق من أسماء الفهارس دون التحقق من (`unique`, `origin`, `partial`, `columns`, `orders`, `hasExpression`).
  3. كانت الجداول الظلية لـ FTS4 تُستثنى ببادئة مفتوحة `startsWith("contacts_fts_")` بدلاً من القائمة المغلقة للجداول الخمسة القياسية.
- **الإصلاح (Fix):**
  1. إلزام التطابق التام `if (hasFts != hasCheckpoints) return LegacySchemaKind.UNKNOWN` بحيث لا يُقبل في `L0` سوى غياب الاثنين معاً، وفي `L1` / `L2_OR_L3` / `PARITY_DONE` / `ROOM_MANAGED` وجود الاثنين معاً ببنية صحيحة كاملة.
  2. التحقق الصارم من كامل البيانات الوصفية لكل عمود (`name`, `type`, `notNull`, `pkPosition`, `defaultValue` عبر `LegacyColumn.normalized()`) لجميع الجداول الثمانية (بما فيها الـ 13 عموداً في `L0` والـ 29 عموداً في المخطط المستهدف لجدول `contacts`).
  3. التحقق الصارم من كامل البيانات الوصفية لجميع الفهارس في الجداول الثابتة وجدول `contacts` (بما في ذلك اشتراط الاسم القياسي `idx_contacts_system_id` عندما يكون الفهرس غير فريد — `B-01`).
  4. حصر الجداول الظلية المسموحة لـ FTS4 في المجموعة المغلقة `FTS_SHADOW_TABLES` (`contacts_fts_content`, `contacts_fts_segments`, `contacts_fts_segdir`, `contacts_fts_docsize`, `contacts_fts_stat`) وفقط عند وجود `contacts_fts` (`NB-02`).
- **مصفوفة الاختبارات (`LegacyDatabaseAdoptionTest.kt`):**
  - `sr02_classificationMatrix_validSchemasAndInvalidMutations_areStrictlyFailClosedAndReadOnly`: يختبر **21 حالة** على ملفات SQLite حقيقية (Cases A–L + حالات إضافية لـ `type` و`notNull` و`pkPosition` و`defaultValue` والفهارس الجزئية والتعبيرية و`DESC` واسم فهرس `systemContactId` غير القياسي `B-01` وجدول `contacts_fts_rogue` `NB-02`)، ويثبت لكل حالة غير صالحة:
    1. إرجاع `LegacySchemaKind.UNKNOWN` ورمي `LegacySchemaUnknownException`.
    2. **عدم تعديل أي بايت أو جدول أو فهرس أو `user_version`** في القاعدة الأصلية (`readRawDatabaseState(dbPath) == stateBefore`).
    3. إنشاء نسخة تشخيصية موثقة (`sila_local.pre-room-backup`).

### 10.3 SR-04 (`MEDIUM`): بوابة حذف النسخة الواقية والتعافي من الأعطال (`Crash A–D`)

- **السبب الجذري (Root Cause):**
  1. كانت `deleteBackupAfterVerifiedAdoption()` تعتمد فقط على متغير الذاكرة `adoptionOutcome.backupPath != null`؛ فإذا قُتلت العملية بعد نجاح `LegacyDatabaseAdopter.adoptIfNeeded()` وقبل اكتمال تهيئة Room (Crash B أو Crash C)، كانت إعادة التشغيل التالية ترى `PARITY_DONE` أو `ROOM_MANAGED` فتُعيد `backupPath = null` ولا تتحقق من النسخة الواقية أو تحذفها بعد النجاح.
  2. كانت `loadContactsFromRoom()` تعود مبكراً عند خلو جدول `contacts` (`if (contactEntities.isEmpty()) return emptyList()`) دون قراءة جدولَي `contact_phones` و`arham`.
  3. لم يكن الإخفاق أثناء فتح Room أو قراءة الجداول يضمن إغلاق `room.close()` في كتلة `catch`.
- **الإصلاح (Fix):**
  1. في `LegacyDatabaseAdopter.adoptIfNeeded()`: فحص وجود النسخة الواقية وتوثيقها عبر `SilaDatabaseBackup.verifiedBackupExists` (الذي يتحقق من `fileSizeBytes > 0`، ووجود جدول `contacts`، و`PRAGMA integrity_check == "ok"` — `NB-01`) في حالتَي `PARITY_DONE` و`ROOM_MANAGED`:
     - في `PARITY_DONE`: اشتراط وجود نسخة واقية صالحة وإلا يفشل الاعتماد مغلقاً بـ `LegacyAdoptionException`.
     - في `ROOM_MANAGED`: إذا وُجدت نسخة واقية متبقية من تشغيل سابق متقطع، يُتحقق من سلامتها وتُمرَّر في `backupPath` ليُستكمل التحقق منها وحذفها بعد تمام فحوص التشغيل الحي.
  2. في `LocalSilaDatabase.loadContactsFromRoom()`: قراءة `contactEntityDao.getAllPhones()` و`arhamEntityDao.getAll()` دائماً حتى عندما يكون جدول `contacts` خالياً من الصفوف النشطة.
  3. في `LocalSilaDatabase.verifyLiveDatabaseForAdoption()` و`deleteBackupAfterVerifiedAdoption()`: التحقق من القاعدة الحية (`PRAGMA user_version == 1`، `PRAGMA integrity_check == "ok"`، ونجاح استعلام `SELECT count(*) FROM contacts_fts`) ونجاح قراءات الجداول عبر Room قبل استدعاء `adopter.deleteBackupAfterVerifiedAdoption()`.
  4. إحاطة تهيئة Room بكتلة `try { ... } catch (t: Throwable) { runCatching { room.close() }; throw t }` لضمان إغلاق الاتصال وبقاء النسخة الواقية سليمة عند أي خطأ أو إلغاء كوروتين (`CancellationException`).
- **أدلة اختبارات التعافي من الأعطال (`LegacyDatabaseAdoptionTest.kt`):**
  - `sr04_emptyContactsTable_stillVerifiesContactPhonesArhamServicesAndFts_beforeBackupDeletion`
  - `sr04_crashA_failureDuringShim_rollsBackAndPreservesBackup_andSubsequentRetrySucceeds` (يشمل اختبار `NB-01` لرفض ملف احتياطي فارغ أو غير صالح في `PARITY_DONE` و`ROOM_MANAGED`)
  - `sr04_crashB_interruptedAfterShimBeforeRoomStampsUserVersion1_preservesBackup_andRetryCompletesAndDeletesBackup`
  - `sr04_crashC_failureDuringRoomOpenOrInitialVerification_closesRoomAndPreservesBackup_acrossAllFailureStages` (يختبر 8 مراحل حقن أعطال + `CancellationException` + فشل التحقق الفعلي لمخطط Room عند إفساد عمود في `services`)
  - `sr04_crashD_backupDeletionFailure_leavesValidRoomDb_andNextStartupDeletesRemainingVerifiedBackup`

### 10.4 نتائج التحقق والمراجعات المستقلة للتحديث

- **الاختبارات المحلية (`--rerun`) وتجميع iOS Simulator ARM64:**
  - `33` حزمة اختبار / **`192` اختباراً** (منها **`53/53`** في `:core:database` شاملة **`19/19`** في `LegacyDatabaseAdoptionTest`) / `0` فشل / `0` خطأ.
  - نجاح `:core:database:kspKotlinIosSimulatorArm64` و`:core:database:compileKotlinIosSimulatorArm64` و`:sharedLogic:compileKotlinIosSimulatorArm64` و`checkArchitectureBoundaries`.
- **مراجعة `agy` المستقلة القراءة-فقط (المحادثة `89a6987b-80bc-47db-b613-adb50219c77f`):**
  - **الحكم النهائي:** **`APPROVE`** (`0` مشاكل معطِّلة، `0` ملاحظات غير معطِّلة).
- **المراجعة الخصومية المستقلة بـ Gemini Argon (المحادثة الفرعية `0d993d19-57b6-4f7d-a056-ff4bd30e5292`):**
  - اكتشفت في الجولة الأولى 3 ملاحظات دقيقة (`B-01`, `NB-01`, `NB-02`) عُولجت بالكامل وأُضيفت لها اختبارات انحدار مخصصة، ثم أعادت التحقق في الجولة الثانية وأصدرت الحكم النهائي: **`PASS`** (`0` مشاكل معطِّلة، `0` ملاحظات غير معطِّلة).
