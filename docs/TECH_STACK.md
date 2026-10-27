# حزمة التقنيات (Tech Stack)

> ثبّت أحدث الإصدارات المستقرة في `gradle/libs.versions.toml` عند البدء، وحدّثها دورياً (Renovate/Dependabot).

## 1. المشترك (KMP)

| الغرض | المكتبة | لماذا |
|---|---|---|
| اللغة | Kotlin 2.x (K2) | بناء أسرع، دعم KMP كامل |
| التزامن | kotlinx.coroutines | Flow/StateFlow للواجهات التفاعلية |
| DI | **Koin** | يعمل في KMP (Hilt أندرويد فقط) — [ADR-0003](adr/0003-koin-for-di.md) |
| ViewModel | androidx.lifecycle-viewmodel (KMP) | ViewModel مشترك بين المنصتين |
| قاعدة البيانات | **Room KMP** + `sqlite-bundled` | مألوفة، FTS، Migrations — [ADR-0004](adr/0004-room-offline-first.md) |
| Paging | androidx.paging-common | قوائم بآلاف العناصر بدون Lag |
| التفضيلات | DataStore KMP | بديل SharedPreferences، آمن للخيوط |
| الشبكة | **Ktor Client** | بديل Retrofit في KMP — [ADR-0002](adr/0002-ktor-instead-of-retrofit.md) |
| التسلسل | kotlinx.serialization | |
| الوقت | kotlinx-datetime | |
| Firebase | Firebase Kotlin SDK (GitLive) أو SDKs أصلية خلف واجهات | انظر القرار المفتوح |
| Logging | Kermit | |
| جسر iOS | **SKIE** | Flow → AsyncSequence، sealed → enum، suspend → async |
| الاختبارات | kotlin.test, Turbine, Mokkery/MockK, kotlinx-coroutines-test | |

## 2. أندرويد

| الغرض | المكتبة |
|---|---|
| الواجهة | Jetpack Compose (BOM) + Material 3 + material-icons-extended |
| التنقل | Navigation Compose (type-safe routes) — [ADR-0005](adr/0005-navigation.md) |
| الحركات | Compose Animation, SharedTransitionLayout, Lottie Compose |
| الصور | Coil 3 |
| DI Android | koin-android, koin-androidx-compose, koin-androidx-workmanager |
| الخلفية | WorkManager |
| الأرقام | libphonenumber (Google) |
| QR | ML Kit Barcode + CameraX · ZXing core (توليد) |
| Firebase | BoM: auth, firestore, messaging, analytics, crashlytics, remote-config, appcheck-playintegrity |
| اختبارات UI | Compose UI Test, Roborazzi/Paparazzi |
| الأداء | Baseline Profiles, Macrobenchmark |

## 3. iOS

| الغرض | الأداة |
|---|---|
| الواجهة | SwiftUI (iOS 17+) |
| التنقل | NavigationStack, TabView |
| جهات الاتصال | Contacts.framework |
| الخلفية | BackgroundTasks (BGTaskScheduler) |
| الإشعارات | UserNotifications |
| المكالمات | CallKit (CXCallObserver, Call Directory Extension) |
| الأرقام | PhoneNumberKit (SPM) |
| QR | VisionKit / AVFoundation + CoreImage |
| Lottie | lottie-ios (SPM) |
| Firebase | firebase-ios-sdk (SPM) |
| الاختبارات | XCTest, swift-snapshot-testing |

## 4. السحابة
- Firebase: Auth (Phone OTP)، Firestore، Cloud Functions (TypeScript)، FCM، Remote Config، App Check، Crashlytics، Analytics، Hosting (لوحة الإدارة).
- لاحقاً: Algolia أو Typesense للبحث النصي/الجغرافي.
- المدفوعات (Pro): Google Play Billing و StoreKit 2 (أو RevenueCat).

## 5. الأدوات
| الغرض | الأداة |
|---|---|
| إدارة الإصدارات | Version Catalog + Convention Plugins (`build-logic`) |
| التحليل الساكن | Detekt, ktlint, SwiftLint, Konsist |
| CI | GitHub Actions |
| التوزيع | Fastlane, Firebase App Distribution |
| تحديث المكتبات | Renovate / Dependabot |

## 6. مثال `libs.versions.toml` (هيكل فقط)

```toml
[versions]
kotlin = "<latest>"
agp = "<latest>"
compose-bom = "<latest>"
room = "<latest>"
ktor = "<latest>"
koin = "<latest>"

[libraries]
room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
sqlite-bundled = { module = "androidx.sqlite:sqlite-bundled", version = "<latest>" }
ktor-client-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
ktor-client-okhttp = { module = "io.ktor:ktor-client-okhttp", version.ref = "ktor" }
ktor-client-darwin = { module = "io.ktor:ktor-client-darwin", version.ref = "ktor" }
koin-core = { module = "io.insert-koin:koin-core", version.ref = "koin" }

[plugins]
kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
room = { id = "androidx.room", version.ref = "room" }
ksp = { id = "com.google.devtools.ksp", version = "<latest>" }
skie = { id = "co.touchlab.skie", version = "<latest>" }
```
