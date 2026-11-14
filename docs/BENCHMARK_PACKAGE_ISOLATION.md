# تقرير عزل هوية حزمة نسخة القياس عن التطبيق الحقيقي (تذكرة `#95`)

- **التاريخ:** 2026-11-11
- **الفرع:** `fix/95-benchmark-package-isolation` (من `origin/main` = `5b0271c293bbece9565edf1d99f68ca3ccacc722`)
- **القرار المعماري:** [ADR-0018 — تعديل 2026-11-11](adr/0018-macrobenchmark-device-gate-harness.md)
- **الشريط التحفيزي:** جلسة القياس الميدانية 2026-11-10 (Redmi Note 9S / MIUI 14 — تقرير الجلسة المحلي `issues/device-qa-redmi-note-9s-2026-11-10.md` غير المُتتبَّع، وتأكيده المستقل في [RELEASE_GATE_PHASE1.md](RELEASE_GATE_PHASE1.md) §تحديث 2026-11-10) — بنية `benchmark` كانت تحمل معرّف التطبيق الحقيقي `app.sila`، فاستبدلت الجلسةُ التطبيقَ الحقيقي على الجهاز ثم **أزالته عند الـ teardown** (وأعاد المالك تثبيته — التطبيق كان بلا بيانات مهمة حينها).

---

## 1. المعرفات: قبل ← بعد

| الدور | قبل (الخلل) | بعد (العزل) | المصدر |
|---|---|---|---|
| التطبيق الحقيقي (إنتاج/debug) | `app.sila` | `app.sila` — **بلا تغيير** | `defaultConfig` في `androidApp/build.gradle.kts` |
| المقيَّس (نسخة القياس) | `app.sila` — **مطابق للإنتاج (الخلل #95)** | `app.sila.bench` | `applicationIdSuffix = ".bench"` في بنية `benchmark` |
| حزمة الأجهزة الاختبارية (`:benchmark`) | `app.sila.benchmark` | `app.sila.benchmark` — بلا تغيير | `namespace` في `benchmark/build.gradle.kts` |

الثلاثي متميز **زوجياً**، ومصدر القياس `StartupBenchmark.TARGET_PACKAGE` حُدِّث من `"app.sila"` إلى `"app.sila.bench"` ليستهدف النسخة المعزولة.

## 2. الحارس الآلي — فشل إعداد فوري (TDD أحمر ← أخضر)

كتلة `androidComponents.onVariants` في `androidApp/build.gradle.kts` تمنع أي عودة صامتة للاصطدام، بحارس متناظر `check(namespace == "app.sila.benchmark")` في `benchmark/build.gradle.kts`:

1. بنية `benchmark` بمعرف الإنتاج `app.sila` ← **فشل إعداد**.
2. بنية `benchmark` بمعرف حزمة الأجهزة `app.sila.benchmark` ← **فشل إعداد**.
3. أي بنية أخرى غير `app.sila` (انحراف هوية المتجر) ← **فشل إعداد**.
4. حزمة الأجهزة تحمل هوية الإنتاج ← **فشل إعداد** (الحارس المتناظر).

**إثبات الأحمر** — الحارس وحده دون لاحقة (`.\gradlew :androidApp:help`):

```
FAILURE: Build failed with an exception.
* What went wrong:
A problem occurred configuring project ':androidApp'.
> java.lang.IllegalStateException: بنية القياس `benchmark` تحمل معرّف التطبيق الحقيقي `app.sila` (تذكرة #95) — أضف `applicationIdSuffix` معزولاً لبنية `benchmark` في `:androidApp`.
BUILD FAILED in 1m 34s
```

> **إعادة إثبات على رأس التكامل (2026-11-11):** أُزيلت اللاحقة مؤقتاً من شجرة `integration/phase1-finalization-local` وأُعيد تنفيذ `:androidApp:help` ففشل الإعداد بالرسالة ذاتها حرفاً — الحارس قائم على الالتزام المدموج وليس على فرع `#95` وحده.

**إثبات الأخضر** — بعد `applicationIdSuffix = ".bench"` (`.\gradlew :androidApp:help :benchmark:help`): `BUILD SUCCESSFUL` (الموديولان معاً).

**نطاق الإثبات بأمانة:** الأحمر المُنفَّذ فعلياً يغطي **القاعدة (1)** حصراً (المخالفة الحقيقية للجلسة 2026-11-10). بقية القواعد (اصطدام `app.sila.benchmark`، انحراف بنية غير قياس عن `app.sila`، وإنكار حزمة الأجهزة معرفها المعلن) تمر عبر **الآلية ذاتها** — `check(...)` في مرحلة إعداد Gradle داخل `onVariants`/جذر `android {}`، وهي مرحلة تعمل على كل استدعاء بناء (`gradle.properties` بلا `configureondemand`) بما فيها خطوة تجميع CI التي تبني الموديولين معاً — فأي مخالفة تفشل الإعداد فوراً بنفس نمط الأحمر أعلاه، دون أن تُنفَّذ كلٌّ منها كإثبات مستقل هنا.

## 3. دليل الحزم المولدة (`aapt dump badging`)

البناء: `.\gradlew :androidApp:assembleDebug :androidApp:assembleBenchmark :benchmark:assembleBenchmark` — **BUILD SUCCESSFUL in 8m 21s** (532 مهمة)، وتضمن نجاح **`checkTestedAppObfuscationBenchmark`** (فحص توافق التصغير بين `:benchmark` والمقيَّس — بند قبول #95).

الأداة: `aapt` من `build-tools;34.0.0`. السطر الأول لكل حزمة:

```
=== androidApp-debug.apk ===
package: name='app.sila' versionCode='10000' versionName='1.0.0' platformBuildVersionName='17' platformBuildVersionCode='37' compileSdkVersion='37' compileSdkVersionCodename='17'

=== androidApp-benchmark.apk ===
package: name='app.sila.bench' versionCode='10000' versionName='1.0.0' platformBuildVersionName='17' platformBuildVersionCode='37' compileSdkVersion='37' compileSdkVersionCodename='17'

=== benchmark-benchmark.apk (الأجهزة الاختبارية) ===
package: name='app.sila.benchmark' versionCode='' versionName='' platformBuildVersionName='17' platformBuildVersionCode='37' compileSdkVersion='37' compileSdkVersionCodename='17'
```

(`versionCode` الفارغ طبيعي لحزمة أجهزة اختبارية `com.android.test`.)

### 3.1 ربط الأجهزة بالمقيَّس وفحوص الترجمة (بندا 2 و3 من القضية)

**الربط في الـ manifest المدمج** — `aapt dump xmltree benchmark-benchmark.apk AndroidManifest.xml`:

```
A: package="app.sila.benchmark" (Raw: "app.sila.benchmark")
E: instrumentation (line=9)
  A: android:targetPackage(0x01010021)="app.sila.benchmark" (Raw: "app.sila.benchmark")
```

`targetPackage` يشير إلى **حزمة الأجهزة نفسها** — وهذا هو سلوك `android.experimental.self-instrumenting = true` القياسي والدقيق: عدّادة Macrobenchmark تعمل داخل حزمة الأجهزة وتقود التطبيق المقيس عبر اسم حزمته (`TARGET_PACKAGE`) بـ shell وآثار النظام، لا عبر وضع نفسها داخل عمليته. هذا السلوك **لم يتغير** في هذه الشريحة (كان `app.sila.benchmark` قبل التعديل أيضاً)؛ ما تغيّر حصراً هو هوية التطبيق المقيس الذي يثبّته مزوّد الأجهزة بجوار حزمة الأجهزة ويُزيله عند الـ teardown (`app.sila.bench` بدل `app.sila`) — وقد أثبتت §4 بقاءَ الإنتاج سليماً بعد الدورة.

**فحص توافق التصغير** — `checkTestedAppObfuscationBenchmark` ناجح ضمن دورة البناء أعلاه (`:benchmark:assembleBenchmark`)، وخريطة R8 للمقيَّس المعزول متولدة عند `androidApp/build/outputs/mapping/benchmark/mapping.txt` (بترويسة `# compiler: R8 # compiler_version: 9.1.31`) لتستهلكها أدوات القياس.

**`StartupProfile`: غير منطبق في المرحلة 1** — لا يوجد ملف تعريف أساسي (Baseline Profile) في المرحلة 1 والقياس بـ `CompilationMode.None()` بقرار ADR-0018 §3، فلا ربط StartupProfile مطلوباً الآن؛ يُعاد التقييم عند إدخال ملفات التعريف.

**عدم مساس المنصة والتبعيات:** المغيرات في هذه الشريحة لا تشمل أي تبعية أو إعداد KMP أو نسخة AGP (`applicationIdSuffix` + حارسا إعداد + ثابت مصدر قياس + وثائق) — تبعية `androidx-benchmark-junit4` الصريحة من PR #93 وتوقيع debug لبنية القياس كما هما حرفاً.

## 4. اختبار سلامة التثبيت/الإزالة — محاكي مستهلك حصراً

**لم يُلمس جهاز المالك إطلاقاً** (لم يكن متصلاً أصلاً — `adb devices` فارغ عدا المحاكي). المحاكي: AVD مستهلك أُنشئ خصيصاً لهذا الاختبار تحت `F:\Temp\avd-wt95` (خارج المستودع، بلا مساس بـ AVD المالك على `C:`) — الجهاز: `sdk_gphone64_x86_64`، أندرويد 16، البصمة `google/sdk_gphone64_x86_64/emu64xa:16/BE2A.250530.026.D1/13818094:user/release-keys`. (محاولة أولى على AVD المالك فشلت قبل أي تشغيل: مساحة `C:` غير كافية — 2.5 GB متاح مقابل 7.4 GB مطلوبة — فلم يُشغَّل ولم يُعدَّل شيء.)

| # | الخطوة | النتيجة |
|---|---|---|
| 1 | تثبيت حارس الإنتاج `androidApp-debug.apk` | `Success` |
| 2 | توثيق الحزمة ومجلد بياناتها | `pm path` → `/data/app/.../app.sila-.../base.apk` · `dataDir=/data/user/0/app.sila` (من `dumpsys package app.sila`) |
| 3 | كتابة ملف شاهد داخل بيانات الإنتاج | `run-as app.sila sh -c 'echo W95-SENTINEL-2026-11-11 > files/sentinel.txt'` ← قراءة عكست `W95-SENTINEL-2026-11-11` |
| 4 | تثبيت المقيَّس `androidApp-benchmark.apk` | `Success` |
| 5 | تثبيت الأجهزة `benchmark-benchmark.apk` | `Success` |
| 6 | التثليث قائماً | `pm list packages app.sila` → `app.sila.bench` + `app.sila` + `app.sila.benchmark` |
| 7 | تمييز مجلدات البيانات | `dataDir=/data/user/0/app.sila.bench` ≠ `/data/user/0/app.sila` |
| 8 | مسار التفكيك (ما يفعله الـ teardown) | `uninstall app.sila.benchmark` → `Success` · `uninstall app.sila.bench` → `Success` |
| 9 | **الإنتاج يبقى وبياناته سليمة** | `pm list packages app.sila` → `package:app.sila` وحده · `run-as app.sila cat files/sentinel.txt` → `W95-SENTINEL-2026-11-11` |

الدورة أُعيدت ثانية كاملة بعد التفكيك الأول (تثبيت ← تفكيك ← فحص الشاهد) بنفس النتيجة. أي أن سيناريو جلسة 2026-11-10 (زوال `app.sila` عند الـ teardown) **مستحيل الآن بنيوياً**: التفكيك يزيل `app.sila.bench`/`app.sila.benchmark` فقط.

بعد الاختبار: أُزيل حارس الإنتاج من المحاكي (`uninstall app.sila` → `Success`) وأُوقف المحاكي — لا تثبيتات باقية على المحاكي، وتبقى صور الـ AVD المستهلكة على القرص خارج المستودع (`F:\Temp\avd-wt95`) للجولات التالية.

### 4.1 الجولة الدخانية الميكانيكية عبر `connectedBenchmarkAndroidTest` — المحاكي المستهلك

**النتيجة: المسار الميكانيكي (تثبيت الزوج/تفكيكه عبر AGP) مثبت — جسم القياس غير منفَّذ بالتصميم. لا توجد أرقام محاكي إطلاقاً.**

محاولتان على المحاكي المستهلك نفسه:

- **المحاولة 1 — محاكي بعمر تشغيل ~50 دقيقة:** فشل بيئي قبل أي تثبيت — `ddmlib` علِق في جلب خصائص الجهاز (`PropertyFetcher: ShellCommandUnresponsiveException` ×3 → `Skipping device 'wt95-package-safety(AVD)' ... Unknown API Level` → `No compatible devices connected`، 3m35s) بينما استجابت واجهة `adb` السطرية طوال الوقت. **`NOT EXECUTED (environment)`** — عولج بإعادة إقلاع المحاكي من Fresh (إقلاع 155 ثانية) فعادت استجابة الخصائص فورية.
- **المحاولة 2 — محاكي طازج** (نفس بصمة الجهاز الموثقة في §4): المسار الحقيقي عبر AGP اكتمل ميكانيكياً:

| # | الخطوة | الدليل |
|---|---|---|
| 1 | حارس إنتاج مثبت مسبقاً بشاهد بيانات | `app.sila` مثبت · `W95-CONNECTED-SMOKE-RETRY` في `/data/user/0/app.sila/files/` |
| 2 | AGP يرصد الجهاز ويعلن بدء الاختبار | `Starting 1 tests on wt95-package-safety(AVD) - 16` — في المحاولة 1 كان الفشل عند هذه المرحلة حصراً |
| 3 | AGP يثبّت زوج القياس ويشغّل العدّادة | بدء تنفيذ `app.sila.benchmark.StartupBenchmark.startupColdCompilationNone` — لا يبدأ إلا بعد تثبيت حزمتي المقيَّس والأجهزة |
| 4 | جسم القياس يُرفض قبل أي تكرار أو إطلاق للمقيَّس | `AssertionError: ERRORS (not suppressed): EMULATOR` — تحقق سياسة `androidx.benchmark` قبل القياس: «Benchmark is running on an emulator, which is not representative of real user devices. Use a physical device to benchmark.» |
| 5 | تفكيك AGP يجري رغم فشل الاختبار | `pm list packages app.sila` بعد الجلسة → `package:app.sila` وحده — الزوج `app.sila.bench`/`app.sila.benchmark` أُزيل كلياً |
| 6 | بقاء الإنتاج وبياناته سليمة | `run-as app.sila cat files/sentinel.txt` → `W95-CONNECTED-SMOKE-RETRY` · `dataDir=/data/user/0/app.sila` |

**قراءة النتيجة (4) بأمانة:** الفشل عند هذه النقطة ليس عيباً بل هو **سياسة مكتبة القياس ذاتها** تُنفَّذ: `androidx.benchmark` يرفض إنتاج أي قياس على محاكٍ ما لم يُكبَت الخطأ صراحة (`testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"`) — وهذا الكبت **غير موجود عمداً** في هذا المستودع (منهجية T8 §2 المحمية). أي أن المنصة تمنع بنيوياً حتى ولو «أرقام محاكي تُنتَج ثم تُهمَل» — لا يوجد ما يُهمَل أصلاً. أما الحدود المطلوبة من هذه الشريحة (تثبيت زوج القياس/تفكيكه دون أي مساس بالإنتاج) فقد ثبتت عبر **المسار الحقيقي** (AGP بدل `pm` اليدوي في §4).

التنظيف النهائي بعد الجولة: `uninstall app.sila` → `Success`، ثم `emu kill` — لا تثبيتات باقية على المحاكي المستهلك؛ صور الـ AVD تبقى على القرص خارج المستودع لإعادة الاستخدام.

## 5. القيود المتبقية (بلا تجميل)

- **لم تُنفَّذ أي جلسة Macrobenchmark مادية** — شرط التوقف في `#95` محترم: لا جلسة قبل إتمام هذا العزل وتوثيقه، ولا جهاز مؤهل متاح أصلاً (`#96` — `BLOCKED (MIUI device policy)`).
- اختبار السلامة الميداني على **محاكي**، وهو مقبول لسلوك التثبيت/الإزالة الوظيفي حصراً — ولا يُقبل كدليل أداء (منهجية T8 §2 المحمية) ولا كبديل عن جلسة العتاد المرجعي.
- مسار `connectedBenchmarkAndroidTest` الفعلي نُفِّذ **ميكانيكياً** على المحاكي المستهلك (تثبيت AGP للزوج + تشغيل العدّادة + تفكيك تلقائي رغم فشل جسم القياس — تفصيل §4.1)؛ جسم القياس نفسه يبقى حصرياً للعتاد المرجعي المؤهل (`#96`)، والمكتبة ذاتها تمنع إنتاجه على المحاكي ما لم يُكبَت الخطأ. وما نُفِّذ يدوياً في §4 يبقى إعادة إنتاج مستقلة للعمليتين ذاتيهما (`pm install`/`pm uninstall` على المقيَّس والأجهزة حصراً) مع حارس إنتاج بشاهد بيانات.
- عند أول جلسة قياس فعلية على العتاد المؤهل يجب توثيق `pm list packages` قبل/بعد في تقرير الجلسة نفسه كإعادة تأكيد ميدانية لهذا العزل.
- **بقايا انجراف محتملة (موثقة لا مخفية):** ثابت `TARGET_PACKAGE` في `StartupBenchmark.kt` وثابتا الحارسين نصوص مكررة عبر ثلاثة مواضع؛ تغييرها منسَّقاً معاً (لاحقة جديدة مثلاً) يمر بالحارسين إن حُدِّثت الثوابت المتزامنة، وتغيير وحيد يفشل فوراً في الإعداد — وأقصى المخاطرة فشل صاخب وقت الجلسة ("package not found") لا اصطدام صامت بالإنتاج. التخفيف الكامل (حقن `BuildConfig` من خاصية مشتركة يستهلكه الحارسان والثابت) أُرجئ عمداً حفاظاً على ضيق الشريحة؛ عند أول إعادة تسمية معرفات يُعتمد الحقن بدل التكرار.

## 6. الملفات المغيرة في هذه الشريحة

- `androidApp/build.gradle.kts` — الحارس (`androidComponents.onVariants`) + `applicationIdSuffix = ".bench"` لبنية `benchmark`.
- `benchmark/build.gradle.kts` — الحارس المتناظر لهوية حزمة الأجهزة.
- `benchmark/src/main/kotlin/app/sila/benchmark/StartupBenchmark.kt` — `TARGET_PACKAGE = "app.sila.bench"`.
- `docs/adr/0018-macrobenchmark-device-gate-harness.md` — تعديل مؤرَّخ 2026-11-11.
- `CHANGELOG.md` — مدخلة Fixed.
- هذا التقرير.
