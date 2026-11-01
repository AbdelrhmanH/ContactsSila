# تقرير إصلاح PR A — أمان الإنتاج والخصوصية (P0)

**الحالة النهائية:** `VERIFIED` محلياً على Android/JVM + مراجعتان مستقلتان + تحقق CI على مستضيفي macOS
**الفرع:** `fix/p0-production-safety-privacy` (Worktree: `F:\Temp\wt-p0-production-safety`)
**الأساس:** `origin/main @ 098c19b` — مطابق تماماً لقطة التدقيق (صفر انحراف)
**الـ PR:** https://github.com/AbdelrhmanH/ContactsSila/pull/37 (base: `main`) — **لم يُدمج؛ قرار الدمج لصاحب المنتج**

---

## 1. الفروع والتدافعات (Branch & Commits)

| التدافع | الرسالة | البند |
|---|---|---|
| `a0b132d` | fix(f01): never seed demo data in production - explicit test-only opt-in | F-01 |
| `7b03868` | fix(f02): activate retention cleanup - atomic transaction + single-flight trigger | F-02 |
| `706e817` | fix(f03): add NSContactsUsageDescription and verify the built bundle in CI | F-03 |
| `db8e5c6` | fix(f06): exclude user data from Android cloud backup and device transfer | F-06 |
| `d5c5fe4` | docs: document backup exclusions (F-06) and the real retention execution path (F-02) | Docs |
| `36f6c95` | fix(f01): adapt the remaining seed-dependent tests missed by the first pass | F-01 (مراجعات) |
| `4e773fb` | fix(review): harden iOS bootstrap test, CI bundle path, backup exclusions, Koin pin | تحصين مراجعات |
| `71514c0` | fix(ci): use common kotlin.concurrent.Volatile - bare @Volatile does not resolve on Native | إصلاح CI |
| `3a3b5d3` | fix(ci): make the iOS retention-cleanup bootstrap test order-independent | إصلاح CI |

تدافع التخطيط `e65bce0` (الأساس/المتتبع) **ليس** في هذا الفرع — يبقى في worktree التخطيط المعزول.

## 2. الملفات المتغيرة (30 ملفاً)

**كود الإنتاج (8):**
- `core/database/src/commonMain/kotlin/app/sila/core/database/dao/LocalSilaDatabase.kt` — F-01 بوابة البذر + F-02 معاملة ذرّية + `cleanupFaultHook`
- `sharedLogic/src/commonMain/kotlin/app/sila/maintenance/RetentionCleanupCoordinator.kt` — جديد (F-02)
- `sharedLogic/src/commonMain/kotlin/app/sila/di/KoinModule.kt` — تسجيل المنسق + تعليق F-01
- `androidApp/src/main/kotlin/app/sila/SilaApplication.kt` — إطلاق التنظيف مرة/عملية (Android)
- `sharedLogic/src/iosMain/kotlin/app/sila/di/IosKoinBootstrap.kt` — إطلاق التنظيف من كلا مساري ensureStarted (iOS)
- `androidApp/src/main/AndroidManifest.xml` — مراجع قاعدتي النسخ الاحتياطي (F-06)
- `iosApp/iosApp/Info.plist` — `NSContactsUsageDescription` (F-03)
- `.github/workflows/ci.yml` — `:androidApp:testDebugUnitTest` + مسار حزمة حتمي + خطوة تحقق F-03

**موارد (5):** `res/xml/data_extraction_rules.xml` (جديد)، `res/xml/backup_rules.xml` (جديد)، `iosApp/iosApp/ar.lproj/InfoPlist.strings` (جديد)، `iosApp/iosApp/en.lproj/InfoPlist.strings` (جديد)، `androidApp/build.gradle.kts` (اعتماد اختبار `libs.junit` الموجود في الكتالوج — بلا اعتمادات جديدة).

**اختبارات (14):** جديدة: `LocalSilaDatabaseSeedingTest` (5)، `RetentionCleanupTest` (4)، `RetentionCleanupCoordinatorTest` (6)، `BackupConfigVerificationTest` (4)، + اختبار iOS في `IosKoinBootstrapTest` (1). مُكيَّفة بلا إضعاف تأكيدات: `LocalSilaDatabaseTest` (×2)، `MainViewModelTest` (×3)، `ContactsSyncEngineTest` (×2)، `ServicesFeatureTest` (×1)، `ArhamFeatureTest` (×1)، `FeatureViewModelsTest`، `ContactsViewModelKoinTest`، تعليقات قديمة في `ContactsSearchDaoTest`/`OnboardingViewModelKoinTest`.

**توثيق (3):** `docs/SECURITY_PRIVACY.md` (آلية النسخ الاحتياطي الفعلية + مسار تنفيذ الاستبقاء الفعلي — بلمسة جراحية حصراً)، `CHANGELOG.md` (قيود Fixed للبنود الأربعة)، وهذا التقرير `docs/architecture-remediation/PR_A_P0_REMEDIATION_REPORT.md` (جديد).

## 3. نتائج البنود الأربعة

### F-01 — VERIFIED
الإنتاج لا يبذر: معامل `seedDemoData = false` افتراضياً، مسارا البذر يعيدان فارغاً إلا بتصريح، Koin الإنتاجي لا يمرر العلم (موقع الإنشاء الإنتاجي الوحيد — تدقيق شامل لكل مواقع `LocalSilaDatabase(`)، البذر يكتب في الجداول الفارغة حصراً. اختبارات الانحدار تُثبت: بدء فارغ، بذر صريح 7+3، بقاء بيانات المستخدم عبر إعادة الفتح بلا بذور، لا تكرار، أسبقية بيانات المستخدم. 11 موقع اختبار كان يعتمد البذر الضمني كُيِّفت (بذر صريح أو تجهيزات DAO) — صفر حذف/إضعاف تأكيدات.

### F-02 — VERIFIED
الجذر: التنظيف كان بلا أي مشغِّل إنتاجي (`git grep` على `098c19b`). الآن: معاملة واحدة ذرّية (فشل منتصف = تراجع كامل؛ استئناف تلقائي لأن الحدود مطلقة)، منسق single-flight لكل عملية، ساعة محقونة، تقرير StateFlow، تسجيل إخفاق بلا PII، إطلاق من المنصتين على نطاق عمر التطبيق. القيم: 365ي/30ي/90ي/30ي بحدود `<` حصراً — مُثبتة باختبارات مللي-ثانية. ملاحظة المنصة: مرة واحدة عند بدء العملية — لا جدولة دورية خلفية على iOS (موثق).

### F-03 — VERIFIED (البنية محلياً؛ الحزمة الفعلية على macOS CI)
المفتاح موجود في `Info.plist` + ترجمتان؛ Xcode 16 buildable folders يلتفّ تلقائياً؛ خطوة CI تفحص **الحزمة المبنية** (`ContactsSila.app` — مطابق `PRODUCT_NAME` في `Config.xcconfig`) عبر `plutil` + وجود ملفي `.lproj` في الحزمة. النتيجة: انظر §6 — **اجتازت على macOS CI** (التشغيل النهائي).

### F-06 — VERIFIED
القاعدتان تستثنيان `sila_local.db` + `-wal`/`-shm`/`-journal` + `sila_user_preferences.preferences_pb` (`domain="file"`, جذر `filesDir` — مطابق لمواقع الإنتاج) من cloud-backup وdevice-transfer (API 31+) والنسخ القديم (API 26–30). `allowBackup="true"` يبقى بقواعد صريحة. اختبار تحقق آلي (JAXP) يفشل عند أي انحراف Manifest↔القواعد.

## 4. الاختبارات الست التي فشلت سابقاً (اختناق البذر) — جميعها PASS
`observeContacts_filtersByArabicNormalizedQuery`, `observeContacts_filtersByCategory` (`LocalSilaDatabaseTest`), `getArhamPriorityList_ordersOverdueFirst`, `callingOverdueRelative_transitionsStatusFromOverdueToOk` (`MainViewModelTest`), `listLoads_fromRoomThroughFullPipeline` (`ContactsViewModelKoinTest`), `koinContainer_resolvesAllFeatureViewModelsAndSingletons` (`FeatureViewModelsTest`) — PASS مؤكد من XML النتائج.

## 5. أوامر التحقق والنتائج

**محلياً (Windows/JVM — على HEAD النهائي `3a3b5d3`):**
```
./gradlew :core:database:testAndroidHostTest :sharedLogic:testAndroidHostTest :androidApp:testDebugUnitTest :feature:contacts:data:testAndroidHostTest :feature:services:data:testAndroidHostTest :feature:arham:data:testAndroidHostTest
→ BUILD SUCCESSFUL — 25 حزمة / 144 اختبار / 0 فشل+أخطاء
./gradlew checkArchitectureBoundaries → ✅ 19 موديول، 0 مخالفة
./gradlew :androidApp:assembleDebug → BUILD SUCCESSFUL
```
المراجعان المستقلان نفّذا أيضاً القائمة الكاملة لاختبارات CI محلياً → BUILD SUCCESSFUL 100%.

**iOS على مستضيفات macOS (CI):** انظر §6.

## 6. تشغيل CI (GitHub Actions)
- **التشغيل 1 (`41299421761`):** ubuntu ✅ **PASS**؛ macOS ❌ — فشل تجميع `:sharedLogic:compileKotlinIosSimulatorArm64`: `@Volatile` المجردة تُحلّ عبر الاستيرادات الافتراضية على JVM فقط (`kotlin.jvm.*`) ولا تُحلّ على Kotlin/Native. أُصلح بالتدافع `71514c0` باستيراد `kotlin.concurrent.Volatile` المشترك (نفس نمط `ContactsSyncEngine.kt:21` القائم).
- **التشغيل 2 (`41299725582`):** ubuntu ✅ **PASS**؛ macOS ❌ — التجميع نجح ونُفذت 26 اختباراً وفشل اختبار واحد فقط: اختبار مسار الإنتاج iOS كان معتمداً على الترتيب (علم `cleanupLaunched` على مستوى الكائن استُهلك باختبار سابق بينما `stopKoin` بين الاختبارات يُنشئ حاوية جديدة بمنسق جديد لا يُطلق). أُصلح بالتدافع `3a3b5d3` بخطاف إعادة التعيين الداخلي (نفس نمط خطافات `LocalSilaDatabase`).
- **التشغيل النهائي (`41300896787`) — أخضر بالكامل:**
  - **Ubuntu** (حدود معمارية + اختبارات KMP + `:androidApp:testDebugUnitTest` + APK): ✅ **PASS** (3m28s)
  - **macOS** (`iosSimulatorArm64Test` — 26/26 بما فيها اختبار مسار إنتاج iOS + SKIE + بناء Xcode + **تحقق F-03 على الحزمة المبنية فعلياً** `ContactsSila.app`): ✅ **PASS** (9m31s)
  - رابط التشغيل: https://github.com/AbdelrhmanH/ContactsSila/actions/runs/41300896787

## 7. المراجعات المستقلة (قبل فتح الـ PR + دلتا بعد الإصلاحات)

**agy (رئيسية — قراءة فقط):**
- الجولة الأولى: F-02/F-06/Docs VERIFIED؛ عثرت على ملاحظتين معطّلتين بأدلة تنفيذ: (1) `ContactsSyncEngineTest` ×2 معتمدة على البذر، (2) مسار حزمة CI خاطئ (`iosApp.app` بدل `ContactsSila.app`).
- جولة الدلتا: **BLOCKER-RESOLVED ×2 — الحكم النهائي: APPROVED / READY**، بلا مشاكل جديدة، وأكدت إعادة تشغيل قائمة اختبارات CI كاملة محلياً + `checkArchitectureBoundaries` + `assembleDebug` أخضر.
- جولة الدلتا 2 (بعد إصلاحي CI): تشخيص فشل التشغيلين دقيق 100% (تتبّع دورة حياة العلم/الحاويات عبر اختبارات الملف الخمسة)؛ الإصلاح مستقل عن الترتيب تماماً؛ الخطاف `internal` لا يُصدَّر حتى لترويسات Obj-C/Swift (لا تسريب ممكن)؛ أثره على بقية اختبارات الملف حميد — **الحكم النهائي: READY**.

**GLM-5.3-Flash (مناقشة — قراءة فقط، سياق جديد):**
- الجولة الأولى: الأربعة تثبت جذرياً؛ عثرت بتنفيذ فعلي على ملاحظتين معطّلتين: (1) `ServicesFeatureTest`/`ArhamFeatureTest` معتمدة على البذر (موديولات لم تغطها بوابة التحقق الأولية)، (2) فخ الزمن الافتراضي في اختبار iOS (`withTimeoutOrNull` داخل `runTest` يتنافس مع نشر من خيوط حقيقية). + ملاحظات: استبعاد `-journal`، تثبيت تسجيل Koin للمنسق، تعليقات قديمة، «أخطر ثغرة»: موقع إطلاق Android بلا اختبار.
- جولة الدلتا: كل البنود Blocker-Resolved/NOTE بادلة ثابتة؛ ثغرة Android مُخفَّفة جزئياً بتثبيت تسجيل Koin والمتبقي مقبول موثقاً (Robolectric = اعتماد جديد محظور في هذه الدورة).
- جولة الدلتا 2 (بعد إصلاحي CI): تحققت تشخيص فشل التشغيلين (تتبّع دورة حياة العلم/الحاويات عبر اختبارات الملف الخمسة)، وأكدت أن الإصلاح صحيح تحت كلا الترتيبين وأن الخطاف الداخلي بلا أي مسار إنتاجي — **الحكم النهائي: READY**.

**أُصلحت جميع الملاحظات المعطّلة والتحصينية عالية الثقة، وأُعيد الاختبار، وأُكّدت بالدلتا من المراجعَين — كلاهما حكما في النهاية: READY، وCI النهائي أخضر بالكامل.**

## 8. المخاطر والقيود المتبقية
1. **بدء Android بلا اختبار آلي:** `SilaApplication.startRetentionCleanup` نفسه لا يُختبر (Robolectric اعتماد جديد — محظور هذه الدورة). مُخفَّف: تسجيل Koin مُثبت، والمراجعة الكودية أكدت الإطلاق. المتبقي: إذا حُذف النداء مستقبلاً قد لا يفشل اختبار — رُشِّح لمتابعة لاحقة.
2. **لا جدولة دورية على iOS:** التنظيف عند بدء العملية فقط؛ العملية طويلة العمر لا تعيد التنظيف (موثق في SECURITY_PRIVACY.md — الـ Worker الدوري تصميم مستقبلي).
3. **علم `cleanupLaunched` في iOS:** مسار exotic (stopKoin ثم حاوية جديدة بمسار مختلف) قد يترك منسقاً جديداً بلا إطلاق — غير قابل للبلوغ في مسار الإنتاج الحتمي.
4. **ارتباط أسماء ملفات F-06:** أسماء الملفات المحمية ثابتة نصياً في القواعد والاختبار؛ إعادة تسمية الـ DB في KoinModule لا يفشل الاختبار تلقائياً (مُلاحظة مراجعة — غير معطِّلة).
5. **تحذيرات `Instant`deprecated** في اختبارات (نمط موجود سلفاً — غير من هذا الـ PR).

## 9. تأكيدات صريحة
- **لا تغيير على مخطط قاعدة البيانات أو الهجرات:** مؤكد بمراجعتين مستقلتين (فحص كامل diff الـ 30 ملفاً — صفر `@Entity`/`@Database`/`ALTER`/إصدارات/قيود) + `checkArchitectureBoundaries` أخضر.
- **مساحات العمل المحمية بلا مساس:** `F:\ContactsSila` (لم يُقرأ منه إلا `local.properties` للنسخ للخارج في جلسة سابقة)، `F:\Temp\audit-main`، `F:\Temp\wt-architecture-remediation` (`e65bce0` نظيف)، `F:\Temp\wt-pr-a-p0` (أدلة سابقة — قراءة فقط، تطابق بالبايت مؤكد).
- **لم يُدمج الـ PR؛ لم يُنشأ/يُعدَّل أي GitHub Issue؛ بلا force-push ولا auto-merge.**
