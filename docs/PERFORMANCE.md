# الأداء والسرعة (Performance Guidelines)

> [!IMPORTANT]
> الأداء ليس تحسيناً لاحقاً — هو متطلب من اليوم الأول. كل PR يجب أن يلتزم بهذه المعايير.

## 1. ميزانيات الأداء (Performance Budgets)

### بدء التشغيل
| المقياس | الهدف | القياس |
|---|---|---|
| **Cold Start → أول إطار تفاعلي** | ≤ 1.5 ثانية (Android) · ≤ 2 ثانية (iOS) | Macrobenchmark / Instruments |
| **Warm Start** | ≤ 500ms | |
| **Hot Start** | ≤ 200ms | |
| **Time to Interactive (TTI)** | ≤ 2 ثانية من فتح التطبيق حتى عرض أول قائمة | |

### تمرير القوائم
| المقياس | الهدف |
|---|---|
| **Jank frames** | 0% عند تمرير 5000 جهة اتصال |
| **إطار واحد** | ≤ 16ms (60fps) · ≤ 8ms (120fps) |
| **أول إطار من Room** | ≤ 500ms لـ 5000 صف |
| **استجابة البحث (FTS)** | ≤ 50ms لأول نتيجة مرئية (Debounce 150ms + FTS) |

### حجم التطبيق
| المقياس | الهدف |
|---|---|
| **APK (AAB)** | ≤ 15 MB (بدون Firebase) · ≤ 25 MB (مع Firebase) |
| **IPA** | ≤ 30 MB |
| **كل صورة جهة اتصال** | ≤ 512px مربع · ≤ 100 KB بعد الضغط |

### الذاكرة والبطارية
| المقياس | الهدف |
|---|---|
| **استهلاك RAM** | ≤ 150 MB في الاستخدام العادي |
| **تسريب ذاكرة** | 0 (LeakCanary في Debug) |
| **مهمة خلفية واحدة** | ≤ 10 ثوانٍ وقت CPU · ≤ 5 MB ذاكرة إضافية |
| **Battery drain (خلفية)** | ≤ 1% يومياً |

### الشبكة
| المقياس | الهدف |
|---|---|
| **أول تحميل للدليل (صفحة)** | ≤ 1 ثانية (30 عنصر) |
| **حجم Payload** | ≤ 50 KB لكل صفحة دليل |
| **Impressions batch** | تُرسل كل 4 ساعات أو 50 عنصر (أيهما أسبق) |
| **Offline الكامل** | كل الميزات المحلية تعمل بدون أي طلب شبكة |

### قاعدة البيانات
| المقياس | الهدف |
|---|---|
| **استعلام عادي** | ≤ 10ms |
| **استعلام FTS** | ≤ 30ms |
| **مزامنة تزايدية (1000 جهة اتصال)** | ≤ 3 ثوانٍ |
| **فحص المكررات (5000 جهة اتصال)** | ≤ 3 ثوانٍ |
| **حجم قاعدة البيانات (10,000 جهة اتصال)** | ≤ 50 MB |

## 2. قواعد الأداء في الكود

### Room / SQLite
- **لا استعلامات في Main Thread** — أبداً. كل DAOs تُرجع `Flow` أو `suspend`.
- **استخدم `@RawQuery` بحذر** — فقط للاستعلامات الديناميكية مع `SimpleSQLiteQuery`.
- **Indexes** على كل عمود يُستخدم في `WHERE` أو `ORDER BY` أو `JOIN`:
  - `contact_phones.e164`, `contacts.normalizedName`, `contacts.category`
  - `call_events.e164`, `call_events.timestamp`
  - `services.phoneE164`, `services.geohash`, `services.professionId`
- **Transactions** لعمليات الكتابة المتعددة (`@Transaction` أو `withTransaction`).
- **لا `SELECT *`** في الاستعلامات — حدد الأعمدة المطلوبة فقط (أو استخدم `@ColumnInfo` مع Data Class مصغّر).
- **Paging** لكل قائمة تتجاوز 50 عنصراً (`PagingSource` من Room).
- **WAL mode** مُفعّل (افتراضي في Room KMP مع `sqlite-bundled`).

### Compose
- **`key` و `contentType`** في كل `LazyColumn`/`LazyRow` — بدونها يُعاد إنشاء كل العناصر عند كل تحديث.
- **`derivedStateOf`** لأي حسابات مشتقة من حالة التمرير (مثل `showSearch`).
- **`remember` بذكاء** — لا `remember` لقيم رخيصة الحساب.
- **`Modifier.graphicsLayer`** بدلاً من `Modifier.alpha`/`Modifier.scale` — يتجنب invalidation.
- **`@Stable` / `@Immutable`** على كل data class يُمرَّر لـ Composable.
- **لا Bitmap كبيرة في Composition** — Coil مع `size(128)` للصور المصغرة.
- **`movableContentOf`** للعناصر التي تنتقل بين الحاويات.
- **`Modifier.drawWithCache`** لرسم ثقيل (Charts، Gauges).

### SwiftUI
- **`@Observable` بدلاً من `@ObservableObject`** (iOS 17+) — أقل overhead.
- **`LazyVStack` دائماً** بدلاً من `VStack` في القوائم.
- **`EquatableView`** لتجنب إعادة رسم العناصر غير المتغيرة.
- **صور: `UIImage` مع `downsample`** عبر `ImageIO` قبل العرض.

### Coroutines
- **لا `Dispatchers.IO` مباشرة** — حقن `AppDispatchers` للاختبار.
- **`flowOn(dispatchers.default)`** للعمليات الحسابية (الفلترة الذكية، المكررات).
- **`conflate()`** على Flows السريعة من Room إن لم تحتاج كل قيمة.
- **`stateIn(WhileSubscribed(5_000))`** في ViewModels — يوقف الاشتراك بعد 5 ثوانٍ من إغلاق الشاشة.
- **`flatMapLatest`** بدلاً من `flatMapMerge` لمنع التداخل.

### الشبكة (Ktor)
- **Timeouts:** connect = 10s, read = 15s, write = 10s.
- **Retry:** 3 محاولات مع Exponential Backoff (1s, 2s, 4s) للأخطاء 5xx و timeout.
- **Cache:** HTTP cache مع `If-Modified-Since` / `ETag` للدليل.
- **Compression:** `ContentEncoding(gzip)` مُفعّل.
- **Connection pooling:** إعادة استخدام اتصالات TCP.

## 3. أدوات القياس والمراقبة

| الأداة | المنصة | الاستخدام |
|---|---|---|
| **Baseline Profiles** | Android | تحسين بدء التشغيل والمسارات الحرجة بـ 30-50% |
| **Macrobenchmark** | Android | قياس Cold/Warm start و Frame timing |
| **Compose Compiler Metrics** | Android | كشف Composables غير المستقرة (Unstable) |
| **LeakCanary** | Android (Debug) | كشف تسريبات الذاكرة |
| **StrictMode** | Android (Debug) | كشف عمليات Main Thread المحظورة (Disk/Network) |
| **Firebase Performance** | الكل | مراقبة الأداء في الإنتاج (بدون بيانات شخصية) |
| **Instruments (Time Profiler)** | iOS | CPU profiling |
| **Instruments (Memory)** | iOS | كشف التسريبات |
| **Xcode Previews** | iOS | التحقق من أداء SwiftUI |

## 4. CI Performance Gates

```text
PR:
  ✅ Compose Compiler Metrics — 0 unstable params في الشاشات الرئيسية
  ✅ APK size delta ≤ +500 KB (مقارنة بـ main)
  ✅ لا StrictMode violations في اختبارات UI

release:
  ✅ Macrobenchmark Cold Start ≤ 1.5s (median, 5 runs)
  ✅ Macrobenchmark Scroll Jank = 0 frames (5000 contacts)
  ✅ Baseline Profile generated ← كل إصدار
```
