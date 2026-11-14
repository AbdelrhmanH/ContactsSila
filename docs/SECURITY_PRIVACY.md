# الأمان والخصوصية (Security & Privacy)

## 1. مبدأ الحد الأدنى من البيانات

| البيانات | تبقى على الجهاز | تُرفع للسحابة |
|---|---|---|
| أسماء جهات الاتصال وأرقامها | ✅ | ❌ |
| التصنيفات، أرحام، شجرة العائلة | ✅ | ❌ |
| سجل المكالمات (لا يُقرأ في المرحلة 1 — [ADR-0006](adr/0006-call-log-strategy.md) مقبول: بدون `READ_CALL_LOG`) | ✅ | ❌ |
| رقم خدمي يشاركه مستخدم مسجّل | ✅ | ✅ (الرقم، الاسم التجاري، المهنة، المدينة) |
| إشارة اتصال بخدمة | ✅ | ✅ (serviceId، المدة، التاريخ — بدون أي بيانات أخرى) |
| التقييم | ✅ | ✅ |
| البطاقة التعريفية | ✅ | ✅ (ما يختاره المستخدم فقط) |
| الموقع | ✅ | ⚠️ مدينة/Geohash تقريبي فقط |

## 2. الضمانات التقنية
1. **عزل الموديولات:** قواعد Konsist تُفشل البناء إن اعتمد `data:contacts`/`data:arham` على الشبكة.
2. **Outbox مُراجع:** كل ما يغادر الجهاز يمر عبر جدول `pending_uploads` بأنواع محددة (`SERVICE_SUGGESTION`, `RATING`, `CALL_SIGNAL`, `REPORT`, `IMPRESSIONS`) — لا يوجد مسار آخر.
3. **قاعدة "الخال السباك":** الرقم المحفوظ بتصنيف غير SERVICES لا يُنشئ أي عنصر في Outbox.
4. **فحص الرقم أثناء الإضافة** عبر `lookupNumber` Callable: لا يُخزن، ومحدود المعدل.
5. **App Check** على Firestore و Functions.
6. **TLS** افتراضياً + منع Cleartext (`networkSecurityConfig`).
7. **تشفير اختياري** لـ Room (SQLCipher) — قرار للمرحلة 2.
8. **النسخ الاحتياطي:** `allowBackup="true"` مع استبعاد صريح لبيانات المستخدم من أي نسخ احتياطي أو نقل بين الأجهزة عبر قاعدتين: `android:dataExtractionRules` → `androidApp/src/main/res/xml/data_extraction_rules.xml` (API 31+: قسما `cloud-backup` و`device-transfer`) و`android:fullBackupContent` → `androidApp/src/main/res/xml/backup_rules.xml` (API 26–30). الملفات المستثناة (كلها في جذر `filesDir`): قاعدة بيانات Room المحلية `sila_local.db` وملفاها المرافقان `sila_local.db-wal`/`sila_local.db-shm` وتفضيلات DataStore `sila_user_preferences.preferences_pb`. يتحقق اختبار آلي من تطابق الـ Manifest وملفي القواعد (`androidApp/src/test/kotlin/app/sila/BackupConfigVerificationTest.kt`). تصدير محلي مشفر اختياري نافذ منذ T9: AES-256-GCM بمفتاح مشتق PBKDF2-HMAC-SHA256 ([ADR-0017](adr/0017-hand-rolled-backup-crypto-engine.md)) — محكوم بمتجهات KAT (FIPS-197 C.3 · NIST GCM · RFC 7914 §11.1).
9. **Crashlytics:** لا أسماء ولا أرقام في السجلات أو المفاتيح المخصصة؛ دالة `redact()` موحدة.
10. **R8/ProGuard** مفعّل في الإصدار.
11. **أحداث المكالمات محلية فقط:** كل `call_events` تبقى في Room، ولا تُرفع إلى Analytics أو Firebase أو Crashlytics أو أي خدمة خارجية. رصد المكالمات الخارجية اختياري ويتطلب شرحاً وموافقة؛ رفضه لا يعطّل بقية التطبيق. لا تُطلب `READ_CALL_LOG` ولا `ROLE_DIALER`، ودور `ROLE_CALL_SCREENING` منفصل عن معالج الهاتف الافتراضي ولا يحجب/يرفض/يسكت أي مكالمة. ولا يُستنتج سجل المكالمات من الإشعارات أو Accessibility أو APIs خاصة. تُحجب الأرقام والهويات من السجلات ورسائل الاستثناءات. ([ADR-0006](adr/0006-call-log-strategy.md))

## 3. أمان الكود (Secure Coding Rules)

> [!CAUTION]
> هذه القواعد إلزامية لكل مطور. مخالفتها = رفض PR فوري.

### 3.1 التحقق من المدخلات (Input Validation)
```kotlin
// ✅ صحيح — تطبيع وتحقق
fun validatePhone(raw: String): Result<String> {
    val normalized = PhoneNormalizer.toE164(raw, defaultCountry)
        ?: return Result.failure(InvalidPhoneError)
    return Result.success(normalized)
}

// ❌ خطأ — استخدام المدخل مباشرة
dao.searchByPhone(userInput) // SQL Injection risk
```

| المدخل | التحقق المطلوب |
|---|---|
| **رقم الهاتف** | `PhoneNormalizer.toE164()` — يرفض أي رقم غير صالح قبل الحفظ أو الإرسال |
| **الاسم** | تقليم المسافات، حد أقصى 200 حرف، إزالة أحرف التحكم Unicode (U+0000–U+001F) |
| **الوسوم (Tags)** | حد 50 حرف لكل وسم، حد 20 وسم لكل جهة اتصال، تطبيع المسافات |
| **الملاحظات** | حد 2000 حرف، تقليم |
| **البريد الإلكتروني** | Regex أساسي + lowercase |
| **URIs (صور، نغمات)** | `ContentResolver.openInputStream` بدلاً من `File(uri)` مباشرة |
| **Deep links** | التحقق من `scheme` و `host` — رفض أي رابط غير `sila://` أو `https://sila.app/` |
| **JSON من السحابة** | `kotlinx.serialization` مع `ignoreUnknownKeys = true` — لا parsing يدوي |

### 3.2 منع حقن SQL (SQL Injection Prevention)
- **Room يمنع الحقن تلقائياً** عبر `@Query` المحددة النوع.
- **`@RawQuery`** تُستخدم فقط مع `SimpleSQLiteQuery` ومتغيرات مُربطة (bound parameters):
```kotlin
// ✅ آمن
@RawQuery
fun search(query: SupportSQLiteQuery): List<ContactEntity>

val q = SimpleSQLiteQuery(
    "SELECT * FROM contacts WHERE normalizedName LIKE ?",
    arrayOf("%${sanitized}%")
)

// ❌ خطير — لا تفعل هذا أبداً
"SELECT * FROM contacts WHERE name = '$userInput'"
```

### 3.3 أمان التخزين المحلي
| البيان | القاعدة |
|---|---|
| **DataStore** | لا تخزن أرقاماً أو أسماءً — فقط تفضيلات ومعرّفات |
| **SharedPreferences** | ممنوع — استخدم DataStore فقط |
| **Room** | البيانات الحساسة في قاعدة بيانات مشفرة (SQLCipher) عند التفعيل |
| **الصور** | تُخزن في `internal storage` (`filesDir`)، لا في `external storage` |
| **Cache** | `cacheDir` فقط — يُحذف تلقائياً عند نقص المساحة |
| **Logs** | `Kermit` مع مستوى `WARN` في الإنتاج — لا `DEBUG`/`VERBOSE` |
| **Clipboard** | عند نسخ رقم: مسح بعد 60 ثانية (Android 13+: تلقائي) |

### 3.4 أمان الشبكة
```kotlin
// Ktor Client — الإعدادات الأمنية الإلزامية
install(HttpTimeout) {
    connectTimeoutMillis = 10_000
    requestTimeoutMillis = 15_000
    socketTimeoutMillis = 15_000
}
// Certificate Pinning (اختياري — للمرحلة 3)
// expectSuccess = true — يرمي استثناء لأكواد 4xx/5xx
```
- **لا Cleartext:** `android:usesCleartextTraffic="false"` في Manifest.
- **Certificate Transparency:** مُفعّل عبر Firebase App Check.
- **Rate Limiting:** العميل لا يرسل أكثر من 1 طلب/ثانية لـ `lookupNumber`.
- **Token Refresh:** Firebase Auth token يتجدد تلقائياً — لا تخزن tokens يدوياً.

### 3.5 أمان الواجهة
| التهديد | الحماية |
|---|---|
| **Screenshot/Screen recording** | لا حماية افتراضية (ليس تطبيق بنكي) — لكن حقل OTP يستخدم `FLAG_SECURE` |
| **Overlay attacks** | `filterTouchesWhenObscured = true` على أزرار الحذف والدمج الحرجة |
| **Task manager preview** | لا حاجة لإخفائه |
| **Deep link hijacking** | التحقق من `intent.data` قبل الاستخدام + Digital Asset Links |
| **WebView** | لا WebView في التطبيق (باستثناء Firebase Auth UI إن لزم) — إن استُخدم: `setJavaScriptEnabled(false)` |

### 3.6 أمان المفاتيح والأسرار
```text
❌ ممنوع في الكود/المستودع:
  - مفاتيح Firebase API
  - google-services.json / GoogleService-Info.plist
  - أي كلمة مرور أو token

✅ المسار الآمن:
  - local.properties (في .gitignore)
  - CI Secrets (GitHub Actions Encrypted Secrets)
  - Firebase App Check (بدلاً من API Key restrictions)
```

### 3.7 دالة التنقية الموحدة (Redactor)
```kotlin
// في :core:common — الكود الفعلي (core/common/src/commonMain/kotlin/app/sila/core/common/Redactor.kt)
object Redactor {
    private val PHONE_PATTERN = Regex("(\\+?\\d[\\d\\s\\-()]{5,}\\d)")
    private val EMAIL_PATTERN = Regex("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}")

    fun scrub(message: String): String {
        if (message.isBlank()) return message
        val withoutEmails = EMAIL_PATTERN.replace(message, "[REDACTED_EMAIL]")
        return PHONE_PATTERN.replace(withoutEmails, "[REDACTED_PHONE]")
    }
}
```
الاستهلاك النافذ: تركيب تقارير الأعطال المحلية (`CrashReportComposer` — §11 أدناه) — تُنقّى أجزاء محتوى العطل غير الموثوق جزئياً عبر `scrub`.

## 4. منع التلاعب في الدليل
| التهديد | الحماية |
|---|---|
| تقييمات وهمية | لا تقييم بدون `call_signal` بمدة ≥ 30 ث، تقييم واحد لكل (حساب، خدمة) |
| تضخيم الثقة بالاتصال المتكرر | إشارة واحدة لكل (حساب، خدمة، يوم) — معرّف المستند يفرضها |
| حسابات وهمية متعددة | وزن الحساب حسب العمر والنشاط، OTP لكل حساب، كشف أنماط في Functions |
| صاحب الخدمة يقيّم نفسه | منع إن `auth.phone == service.phoneE164` أو `ownerUid == uid` |
| نشر رقم شخص كخدمة (إيذاء) | عتبة N مقترحين، استبعاد من حفظه كعائلة، زر "هذا رقمي الشخصي — أزله" يتحقق بـ OTP |
| طلبات من خارج التطبيق | App Check + القواعد |
| بلاغات كيدية | وزن الحساب + مراجعة الإدارة |

## 5. حقوق المستخدم
- **حذف الحساب** وبياناته السحابية من الإعدادات (و رابط ويب).
- **"ماذا نعرف عنك":** شاشة تعرض ما رُفع (بطاقتك، اقتراحاتك، تقييماتك).
- **إزالة رقمي من الدليل:** لأي شخص يثبت ملكية الرقم بـ OTP.
- **إلغاء الاشتراك** في التقييم وإشارات الثقة من التفضيلات.

## 6. الامتثال
- سياسة خصوصية بالعربية والإنجليزية.
- Google Play Data Safety + App Store Privacy Labels.
- مراجعة متطلبات قانون حماية البيانات الشخصية المحلي (مثل القانون المصري 151/2020) قبل الإطلاق.
- GDPR compliance (حق الوصول، النقل، الحذف) — مهم للتوسع المستقبلي.

## 7. حماية التطبيق من التحميل الخارجي (Anti-Sideloading)

> [!CAUTION]
> نسخ APK المهكرة قد تتجاوز الدفع أو تتلاعب بقاعدة البيانات. الحماية إلزامية.

| المنصة | الآلية | السلوك عند الكشف |
|---|---|---|
| **Android** | **Play Integrity API** (بديل SafetyNet) — يفحص بيئة التشغيل | شاشة حظر: "هذه النسخة غير رسمية. حمّل من Google Play" + زر مباشر للمتجر |
| **iOS** | **App Attest** (DeviceCheck framework) | شاشة حظر مماثلة + رابط App Store |

```kotlin
// Android — فحص عند بدء التشغيل
class IntegrityChecker(private val context: Context) {
    suspend fun verify(): Boolean {
        val installer = context.packageManager
            .getInstallSourceInfo(context.packageName)
            .installingPackageName
        // Google Play = "com.android.vending"
        // Galaxy Store = "com.sec.android.app.samsungapps"
        if (installer !in ALLOWED_INSTALLERS) return false
        // + Play Integrity API token verification عبر Cloud Function
        return verifyIntegrityToken()
    }
}
```

- الفحص يتم عند **أول تشغيل** + **كل 24 ساعة** (لا يُبطئ الاستخدام العادي).
- عند الفشل: **شاشة كاملة غير قابلة للتجاوز** مع زر المتجر.
- `core:platform` → `expect interface IntegrityVerifier` + `actual` لكل منصة.

## 8. التحقق الصارم من الموقع الجغرافي (Anti-GPS Spoofing)

> [!IMPORTANT]
> يُطبق **فقط** عند تفعيل بطاقة الخدمة في الدليل — لا يُطبق على المستخدم العادي.

| الطبقة | الآلية | الكشف |
|---|---|---|
| **الطبقة 1** | `Location.isMock()` (Android) / CLLocation metadata (iOS) | رفض فوري + رسالة: "أغلق تطبيقات تزييف الموقع" |
| **الطبقة 2** | مقارنة IP geolocation مع GPS coordinates (Cloud Function) | تناقض كبير = رفض + تسجيل محاولة |
| **الطبقة 3** | فحص Developer Options + Mock Location apps (Android) | تحذير عند الكشف |

```kotlin
// Android — فحص الموقع الوهمي
fun Location.isGenuine(): Boolean {
    if (Build.VERSION.SDK_INT >= 31) return !isMock
    @Suppress("DEPRECATION")
    return !isFromMockProvider
}
```

- الموقع يُحفظ كـ **Geohash** تقريبي (5 أحرف ≈ ±2.4 كم) — لا إحداثيات دقيقة.
- **لا نستخدم Google Maps API** (مكلفة) — نعتمد على `FusedLocationProviderClient` المحلي المجاني.
- عند تغيير الموقع بشكل غير طبيعي (مثلاً: انتقال 500 كم في ساعة) ← تعليق البطاقة + إشعار الإدارة.

## 9. التقييم من داخل التطبيق (In-App Reviews)

| المنصة | الأداة |
|---|---|
| Android | **Google Play In-App Review API** (`ReviewManager`) |
| iOS | **StoreKit** (`SKStoreReviewController.requestReview()`) |

**شروط الظهور الذكي (لا إزعاج):**
- يظهر **فقط** بعد تجربة إيجابية:
  - مكالمة خدمية ناجحة (≥ 30 ثانية)
  - تحول قريب من 🔴 إلى 🟢 في أرحام
  - إكمال تنظيم 50% من جهات الاتصال
- **مرة واحدة كل 90 يوماً** كحد أقصى.
- **لا يظهر** في أول أسبوع من التثبيت.
- يحترم إعداد "لا تسألني مجدداً".

## 10. سياسة الاحتفاظ بالبيانات (Data Retention Policy)

> [!IMPORTANT]
> كل بيان له عمر افتراضي — لا نحتفظ ببيانات لم يعد لها حاجة.

| البيانات | مدة الاحتفاظ | آلية التنظيف | ملاحظات |
|---|---|---|---|
| `call_events` | **365 يوماً** | `DataCleanupWorker` يومياً | حذف الأحداث الأقدم من سنة |
| `arham_inbox` (الرسائل) | **90 يوماً** | `DataCleanupWorker` يومياً | الرسائل المقروءة أولاً؛ غير المقروءة تبقى حتى القراءة + 30 يوماً |
| `dispute_logs` — `RESOLVED` | **90 يوماً** بعد الحل | `DataCleanupWorker` أسبوعياً | تُنقل لـ `ARCHIVED` ثم تُحذف |
| `dispute_logs` — `OPEN` | **180 يوماً** بدون تحديث | `DataCleanupWorker` أسبوعياً | تُؤرشف تلقائياً |
| صور النزاعات (Firebase Storage) | **14 يوماً** بعد الأرشفة | Cloud Function `cleanupDisputeImages` أسبوعياً | TTL يُعدّ عند الأرشفة |
| `emergency_ringing_sessions` | **30 يوماً** | `DataCleanupWorker` أسبوعياً | — |
| `scheduled_messages` المعطّلة | **180 يوماً** بعد التعطيل | `DataCleanupWorker` شهرياً | الأتمتات المحذوفة تُحذف فوراً |
| `pending_uploads` الفاشلة | **30 يوماً** (بعد 10 محاولات) | `DataCleanupWorker` يومياً | تُسجل كخطأ في Analytics |
| `smart_filter_feedback` | **بلا حد** (خفيفة) | — | بيانات تعلم محلي صغيرة |
| `duplicate_dismissals` | **بلا حد** | — | لمنع إعادة الاقتراح |
| Firestore `arham_messages` | **30 يوماً** (TTL) | Firestore TTL Policy | يُعدّ تلقائياً |
| Firestore `analytics_private/daily` | **90 يوماً** | Cloud Function `cleanupOldDailyStats` | بعد تجميعها في `provider_stats` |

> [!NOTE]
> **آلية التنفيذ الفعلية لتنظيف قاعدة البيانات المحلية (منفَّذة — F-02):** التنظيف المحلي (`call_events` الأقدم من 365 يوماً، `arham_inbox` الأقدم من 30 يوماً، أرشفة نزاعات `RESOLVED` الأقدم من 90 يوماً، والحذف النهائي لقبور جهات الاتصال الأقدم من 30 يوماً) لا يجري عبر `DataCleanupWorker` الدوري — بل **مرة واحدة عند بدء كل عملية تشغيل** عبر `RetentionCleanupCoordinator` في `:sharedLogic`: نداء واحد لكل عملية (single-flight عبر Mutex + علم بدء)، كل عمليات التنظيف داخل معاملة SQLite واحدة (تراجع كامل عند الفشل — لا حذف جزئي أبداً — وإكمال تلقائي عند الإقلاع التالي لأن الحدود الزمنية مطلقة)، يُطلق من `SilaApplication` (Android) و`IosKoinBootstrap.ensureStarted` (iOS) على نطاق عمر التطبيق خارج الخيط الرئيسي، والإخفاق يُسجَّل بنوعه فقط بلا أي بيانات شخصية. جدولة Worker الدورية أدناه **تصميم مستقبلي غير منفَّذ بعد**.

### Worker التنظيف التلقائي (تصميم مستقبلي — غير منفَّذ بعد؛ المسار الفعلي أعلاه)
```kotlin
// DataCleanupWorker — يعمل يومياً عبر WorkManager / BGProcessingTask
class DataCleanupWorker(private val db: SilaDatabase, private val clock: Clock) {
    suspend fun doWork() {
        val now = clock.now().toEpochMilliseconds()
        db.callEventDao().deleteOlderThan(now - 365.days.inWholeMilliseconds)
        db.arhamInboxDao().deleteReadOlderThan(now - 90.days.inWholeMilliseconds)
        db.disputeLogDao().archiveStaleOpen(now - 180.days.inWholeMilliseconds)
        db.disputeLogDao().deleteArchivedOlderThan(now - 90.days.inWholeMilliseconds)
        db.emergencyRingingDao().deleteOlderThan(now - 30.days.inWholeMilliseconds)
        db.pendingUploadDao().deleteFailedOlderThan(now - 30.days.inWholeMilliseconds, maxAttempts = 10)
    }
}
```

## 11. Crash Reporting — الالتقاط المحلي وتنفيذ الحماية (ADR-0019)

> [!CAUTION]
> **العقد المُحقق بنيوياً (معيار #31 — تنسيق v2 الصارم):** لا يدخل ملف العطل أي نص حر إطلاقاً — لا رسائل استثناءات ولا أسماء (عربية أو لاتينية) ولا أرقام هواتف بأي مجموعة أرقام موثقة أدناه ولا أحداث مكالمات ولا نص مستخدم؛ التقرير **سماحية بنيوية صارمة** حقولُه المسموحة مُثبتة اختباراً على التقرير المُخلَّد كاملاً.
> **القرار (2026-11-10 — [ADR-0019](adr/0019-local-only-crash-capture.md)):** التقاط **محلي حصراً** في المرحلة 1 — بلا شبكة ولا SDK سحابي (لا Crashlytics ولا بديل)، مع مشغّل إعادة نظر موثق للخيار السحابي عند الاقتراب من توزيع خارجي.

### الإعداد الفعلي (Local-Only Crash Capture)
معالج استثناءات غير ملتقطة على كل منصة يكتب ملف عطل منقّى في تخزين التطبيق الخاص، محكوماً بمفتاح `crashReportsEnabled` (fail-closed):
```kotlin
// Android — SilaApplication.installCrashCapture(koin) — أول onCreate بعد بدء Koin
val capture = koin.get<LocalCrashCapture>()
val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
    runCatching { capture.capture(System.currentTimeMillis(), thread.name, throwable) }
    previousHandler?.uncaughtException(thread, throwable) // يفوض دائماً — مسار الانهيار النظامي لا يُعطَّل
}
// iOS — IosKoinBootstrap.ensureStarted() عبر جسر IosCrashBridge فوق NSSetUncaughtExceptionHandler
// (يقتصر على NSException حصراً — انهيارات K/N الخام والإشارات القاتلة خارج الالتقاط؛ الحد موثق في ADR-0019)
```
- **الموقع والاحتفاظ:** أندرويد `<filesDir>/crash/crash-<millis>.txt` و iOS `Application Support/Sila/crash/` — آخر 5 تقارير (الأقدم يُحذف).
- **البوابة:** `crashReportsEnabled` تُقرأ وقت العطل حصراً بمهلة ثانيتين — فشل القراءة أو المهلة أو إعادتها `false` ⇒ لا كتابة (احترام اختيار المستخدم أولاً).
- **فشل الالتقاط نفسه** (تركيب أو كتابة) يُبتلع ولا يرمي — لا استثناءات تتصاعد من مسار العطل الطرفي.

### دالة Redactor الموحدة (الكود الفعلي — §3.7 أعلاه) — دفاع ثانوي فوق بوابات السماحية
`Redactor.scrub` في `:core:common` دفاعٌ ثانوي يعمل فوق بوابات السماحية البنيوية للمُركِّب (لا `redact()` ولا `log()` بـ Kermit ولا نمط أسماء عربية `NAME_AR` — كشف الأسماء إرشادي غير موثوق بقرار موثق في ADR-0019). **نطاق الأرقام الموثق:** ASCII (`0-9`) والأرقام العربية-الهندية (`٠-٩` U+0660–U+0669) والشرقية/الفارسية (`۰-۹` U+06F0–U+06F9) بمتطابقة مختلطة (رقم يجمع مجموعات مختلفة يُنقّى كذلك)، مع بادئة `+` الدولية والفواصل الشائعة (مسافة/شرطة/أقواس). النطاقات **صريحة لا `\d` العام** — أنظمة أرقام أخرى (ديفاناغاري، fullwidth…) خارج النطاق عمداً، وتوحيد النطاقات جعل السلوك متطابقاً بين JVM وK/N (سابقاً كان `\d` على K/N يطابق كل Nd يونيكود وعلى JVM ASCII حصراً؛ فئة الفواصل `\s` تختلف بنيوياً — JVM: ASCII فقط، K/N: فراغات يونيكود — فرق يوسّع التقاط الفواصل لا يضيّقه). البريد يبقى ASCII (النطاقات الدولية IDN خارج النطاق).

### القواعد (تنسيق v2 — سماحية بنيوية صارمة، deny-by-default)
التقرير حقوله المسموحة حصراً — أي محتوى آخر محذوف كلياً لا منقّى:
- **الترويسة الموثوقة:** سطر العنوان الثابت + `format: 2` + طابع `timestamp_epoch_ms` من إنتاج `CrashReportComposer` لا تُنقّى — الطابع الرقمي البحت يطابق نمط الهاتف فتُفسد لو نُقّي.
- **`thread:`** بنيوي حصراً (`[A-Za-z0-9_\- ]` بحد 64) — غير المطابق يُحذف السطر كاملاً؛ وأرقام الهواتف فيه تُنقّى دفاعاً ثانوياً.
- **`type:`** معرف فئة الاستثناء منقّط صارم — غير المطابق يعود `<unclassified>`.
- **`category:`** تلميح فئة مستقر عبر `CrashCategoryHint` (اسم `NSException` المستقر على iOS — يعوّض الرسالة المحذوفة) بشرط البنية ذاته وإلا حُذف السطر.
- **`stack:`** إطارات بنيوية حصراً `[A-Za-z0-9_$.,:()/+\-]` **بلا مسافات** بحد 24 إطاراً للعُقدة — يُسقط إطارات K/N العنوانية التي قد تحوي مسارات مضيف بنطاقات مستخدمين (حد منصوي موثق: iOS يُدرج type/category فقط). **بقية موثقة:** إطار K/N نظري بلا مسافات يحمل مساراً قد يمر بالبوابة — الإطارات الفعلية تفصل بـ ` + ` فتُسقط؛ البوابة تُقلّص السطح ولا تدّعي إغلاقاً مطلقاً.
- **محذوف كلياً:** رسائل الاستثناءات ورسائل الأسباب و`Throwable.toString()` وأسماء الخيوط غير البنيوية — لا حقل `message` في التقرير أصلاً.
- سلسلة الأسباب بحد عمق 5 (`MAX_CAUSE_DEPTH`) بنفس القواعد وبلا رسائل، عبر `stackFramesOf` (expect/actual — `stackTrace` خاصة على K/N).
- الاحتفاظ آخر 5 تقارير بخوارزمية نقية مشتركة (`staleReportNames`) بين sinkَي المنصتين.
- اختبار: مصفوفة الخصوصية (21 اختباراً في `:core:common` — `CrashReportComposerTests`/`CrashStructuralGateTests`/`CrashCaptureTests` واختبار `Redactor` الرقمي في `CoreCommonTests`) — عقد fail-closed كاملاً + بوابات السماحية النقية + أرقام الهواتف بمجموعاتها (ASCII/عربية-هندية/شرقية-فارسية/مختلطة/دولية/بريد) + **فحص التقرير المُخلَّد كاملاً** ضد أسرار اصطناعية (أسماء لاتينية وعربية، رسائل حرة، أرقام كل المجموعات، بريد) فلا سر ينجو من أي حقل مسموح.
