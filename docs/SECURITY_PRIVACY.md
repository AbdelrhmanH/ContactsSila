# الأمان والخصوصية (Security & Privacy)

## 1. مبدأ الحد الأدنى من البيانات

| البيانات | تبقى على الجهاز | تُرفع للسحابة |
|---|---|---|
| أسماء جهات الاتصال وأرقامها | ✅ | ❌ |
| التصنيفات، أرحام، شجرة العائلة | ✅ | ❌ |
| سجل المكالمات (لا يُقرأ في المرحلة 1 — القرار غير محسوم) | ✅ | ❌ |
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
8. **النسخ الاحتياطي:** `android:dataExtractionRules` تستبعد قاعدة البيانات من النسخ السحابي التلقائي إلا بموافقة؛ تصدير محلي مشفر اختياري.
9. **Crashlytics:** لا أسماء ولا أرقام في السجلات أو المفاتيح المخصصة؛ دالة `redact()` موحدة.
10. **R8/ProGuard** مفعّل في الإصدار.

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
// في core:common — تُستخدم قبل أي Log أو Crashlytics
object Redactor {
    fun phone(e164: String): String = e164.take(5) + "***" + e164.takeLast(2)
    // "+2010***67"
    fun name(name: String): String = name.take(1) + "***"
    // "أ***"
    fun email(email: String): String {
        val (local, domain) = email.split("@", limit = 2)
        return local.take(1) + "***@" + domain
    }
}
```

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

### Worker التنظيف التلقائي
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

## 11. Crash Reporting — تنفيذ الحماية

> [!CAUTION]
> **الهدف:** صفر بيانات شخصية في Crashlytics — لا أسماء، لا أرقام هواتف، لا عناوين.

### الإعداد
```kotlin
// Application.onCreate()
FirebaseCrashlytics.getInstance().apply {
    // تعطيل الجمع التلقائي — يُفعّل فقط بموافقة المستخدم
    setCrashlyticsCollectionEnabled(userPreferences.crashReports)
    // لا مفاتيح شخصية أبداً
    setCustomKey("app_locale", currentLocale)
    setCustomKey("is_guest", isGuest)
    setCustomKey("contacts_count_range", contactsCountRange()) // "0-100", "100-500", etc.
    // لا setUserId بمعرّف قابل للربط — استخدم UUID عشوائي
    setUserId(anonymousInstallId)
}
```

### دالة Redactor الموحدة
```kotlin
object Redactor {
    private val PHONE = Regex("""(\+?\d[\d\s\-()]{6,})""")
    private val EMAIL = Regex("""[\w.+-]+@[\w-]+\.[\w.]+""")
    private val NAME_AR = Regex("""[\u0600-\u06FF]{3,}(\s[\u0600-\u06FF]{3,})+""")

    fun redact(text: String): String = text
        .replace(PHONE, "[PHONE]")
        .replace(EMAIL, "[EMAIL]")
        .replace(NAME_AR, "[NAME]")

    // للاستخدام في Timber/Kermit
    fun log(tag: String, msg: String) = Kermit.d(tag) { redact(msg) }
}
```

### القواعد
- كل `Log.d/w/e` يمر عبر `Redactor.log()` — Lint rule مخصصة ترفض الاستخدام المباشر.
- `setCrashlyticsCollectionEnabled(false)` افتراضياً — يُفعّل فقط من إعداد "تقارير الأعطال".
- لا `setCustomKey` بأي قيمة قد تحتوي بيانات شخصية.
- اختبار: Unit test يتحقق أن `Redactor.redact()` يُزيل كل الأنماط.
