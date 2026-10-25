# معايير الكود (Coding Guidelines)

## 1. Kotlin
- اتبع [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html) + ktlint + Detekt.
- `val` افتراضياً، Immutable data classes للحالات، `sealed interface` للـ Intents/Effects/Errors.
- لا `!!`. استخدم `requireNotNull` مع رسالة أو تعامل مع null.
- Coroutines: لا `GlobalScope`؛ حقن `AppDispatchers` بدل `Dispatchers.IO` مباشرة (للاختبار).
- Flows من Room تُعرض كـ `Flow<T>`؛ تحويل إلى `StateFlow` في الـ ViewModel فقط.
- الدوال العامة في الـ domain تحمل KDoc مختصر.

## 2. التسمية
| العنصر | النمط | مثال |
|---|---|---|
| UseCase | `VerbNounUseCase` + `operator fun invoke` | `MergeContactsUseCase` |
| Repository | `XRepository` / `XRepositoryImpl` | `ArhamRepository` |
| Source | `XLocalSource` / `XRemoteSource` / `XPlatformSource` | `CallLogPlatformSource` |
| Entity / DTO / Model | `XEntity` / `XDto` / `X` | `ServiceEntity`, `ServiceDto`, `Service` |
| Mappers | دوال امتداد `toDomain()`, `toEntity()` | |
| ViewModel | `XViewModel` + `XUiState` + `XIntent` + `XEffect` | |
| Composable شاشة | `XScreen` (stateful) + `XContent` (stateless، Preview) | `ArhamScreen`, `ArhamContent` |
| Swift View | `XView` | `ArhamView` |
| Package (Core) | `app.sila.core.<module>` | `app.sila.core.common`, `app.sila.core.model` |
| Package (Feature) | `app.sila.feature.<feature>.<layer>` | `app.sila.feature.arham.domain`, `app.sila.feature.arham.data`, `app.sila.feature.arham.presentation` |

## 3. Compose
- شاشة Stateful رفيعة تجمع الحالة: `val state by vm.state.collectAsStateWithLifecycle()`.
- `XContent(state, onIntent)` بلا ViewModel ← قابلة للـ Preview والاختبار.
- `@Preview` لكل Content بالعربية والإنجليزية، فاتح وداكن.
- استخدم `Modifier` كأول بارامتر اختياري.
- `LazyColumn` بـ `key` و `contentType`.
- لا ألوان أو أحجام ثابتة — من `SilaTheme`.

## 4. SwiftUI
- View رفيع + ViewModel wrapper `@Observable` يغلف الـ ViewModel المشترك.
- مراقبة الحالة عبر `for await state in vm.state` (SKIE) داخل `.task {}`.
- SwiftLint + `swift-format`.

## 5. النصوص والترجمة
- العربية هي اللغة الافتراضية للمحتوى + الإنجليزية.
- مفاتيح وصفية: `arham_status_overdue_days` مع Plurals (العربية لها 6 صيغ جمع: zero/one/two/few/many/other).

## 6. Git
- الفروع: `main` (محمي)، `develop`، `feature/<area>-<short-desc>`، `fix/...`.
- **Conventional Commits:** `feat(arham): add podium dashboard`, `fix(contacts): ...`, `docs:`, `refactor:`, `test:`, `chore:`.
- PR صغير ومركّز، يمر CI (build + tests + detekt + ktlint)، ومراجعة واحدة على الأقل.
- قالب PR: الوصف، الميزة المرتبطة، لقطات شاشة (عربي/إنجليزي، فاتح/داكن)، قائمة DoD من [AGENTS.md](../AGENTS.md).

## 7. ممنوعات
- منطق أعمال في Composable أو SwiftUI View.
- استدعاء Room أو Ktor من ViewModel مباشرة.
- طباعة بيانات شخصية في Logs.
- إضافة مكتبة جديدة بدون ذكرها في [TECH_STACK.md](TECH_STACK.md) (و ADR إن كانت جوهرية).

## 8. الأداء (مرجع مفصل: [PERFORMANCE.md](PERFORMANCE.md))
- **لا عمليات في Main Thread:** كل Room/Network/IO على `Dispatchers.IO` أو `Default` (عبر `AppDispatchers`).
- **`key` + `contentType`** في كل `LazyColumn`/`LazyRow`.
- **`@Stable` / `@Immutable`** على كل data class يُمرر لـ Composable.
- **Paging** لكل قائمة تتجاوز 50 عنصراً.
- **Indexes** على كل عمود يُستعلم عنه.
- **لا `SELECT *`** — حدد الأعمدة المطلوبة فقط.
- **`stateIn(WhileSubscribed(5_000))`** في كل ViewModel.
- **LeakCanary** في Debug builds — 0 تسريبات مقبولة.

## 9. الأمان (مرجع مفصل: [SECURITY_PRIVACY.md](SECURITY_PRIVACY.md))
- **`Redactor`** قبل أي Log أو Crashlytics — لا أسماء ولا أرقام.
- **Input Validation** لكل مدخل: `PhoneNormalizer`، حد الأحرف، إزالة أحرف التحكم.
- **لا `@RawQuery` بدون bound parameters.**
- **لا SharedPreferences** — DataStore فقط.
- **لا cleartext HTTP.**
- **لا أسرار في المستودع** — `local.properties` + CI Secrets.

## 10. قابلية التوسع (مرجع مفصل: [SCALABILITY.md](SCALABILITY.md))
- **كل ميزة جديدة في موديول مستقل** — لا تعديل على الموديولات الموجودة.
- **Feature Flags** عبر Remote Config لكل ميزة تجريبية.
- **Migration لكل تغيير Schema** — مع اختبار.
- **لا نصوص ثابتة في الكود** — مفاتيح ترجمة وصفية.
- **`ignoreUnknownKeys = true`** في kotlinx.serialization — لتوافق مع إصدارات API مستقبلية.

## 11. إمكانية الوصول (Accessibility) (مرجع مفصل: [DESIGN_SYSTEM.md §6](DESIGN_SYSTEM.md#6-إمكانية-الوصول-accessibility))
- **`contentDescription`** لكل أيقونة تفاعلية — Lint rule ترفض `null` على `IconButton`.
- **`semantics(mergeDescendants = true)`** لبطاقات القوائم (أرحام، جهات الاتصال).
- **`Modifier.clearAndSetSemantics`** للعناصر المركبة (مثل: صف التقييم بالنجوم).
- **لا ألوان وحدها** — كل حالة لها نص مقروء.
- كل `@Preview` يُنشأ بنسخة **Font scale 200%** للتأكد من عدم القص.
- الـ CI يشغّل **Accessibility Scanner** في Instrumented tests.

## 12. إدارة الإصدارات (Versioning)
- **Semantic Versioning:** `MAJOR.MINOR.PATCH` (مثال: `1.2.3`)
  - `MAJOR` = تغييرات جذرية (Migration غير متوافقة)
  - `MINOR` = ميزة جديدة
  - `PATCH` = إصلاح خطأ
- **`versionCode`** (Android): رقم تصاعدي مستمر — يُحسب تلقائياً: `MAJOR * 10000 + MINOR * 100 + PATCH`.
- **Build Number** (iOS): يتطابق مع `versionCode`.
- **المصدر الوحيد:** `libs.versions.toml` → `app-version = "1.0.0"` — يُقرأ في `build.gradle.kts`.
- **Force Update عبر Remote Config:**
  ```json
  {
    "min_supported_version": "1.2.0",
    "recommended_version": "1.3.0",
    "update_message_ar": "يرجى تحديث التطبيق للحصول على أحدث الميزات",
    "force_update": false
  }
  ```
  - عند `app_version < min_supported_version` → شاشة حظر إجبارية (Full-screen blocking).
  - عند `app_version < recommended_version` → شريط علوي يقترح التحديث (dismissable مرة واحدة يومياً).
- **CHANGELOG.md** يُحدّث مع كل PR مدمج.

## 13. ProGuard / R8 Rules
```proguard
# === Koin ===
-keep class org.koin.** { *; }
-keepclassmembers class * { @org.koin.core.annotation.* <methods>; }

# === Room ===
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class * { *; }

# === SKIE ===
-keep class co.touchlab.skie.** { *; }

# === kotlinx.serialization ===
-keepattributes *Annotation*, InnerClasses
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class * { @kotlinx.serialization.Serializable *; }

# === Firebase ===
-keep class com.google.firebase.** { *; }

# === Crashlytics ===
-keepattributes SourceFile, LineNumberTable
-keep public class * extends java.lang.Exception
```
- تُخزن القواعد في `androidApp/app/proguard-rules.pro`.
- **اختبار Release build** إلزامي في CI — `./gradlew :androidApp:app:assembleRelease` يجب ألا يُنتج warnings حرجة.
- **Mapping file** يُرفع تلقائياً لـ Crashlytics عبر Gradle plugin.
