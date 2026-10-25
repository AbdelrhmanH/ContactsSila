# ADR-0003: Koin لحقن الاعتماديات
- **الحالة:** مقبول
- **التاريخ:** 2026-10-25

## السياق
نحتاج DI يعمل في `commonMain` وفي Android و iOS، ويدعم ViewModels و WorkManager.

## القرار
**Koin** (`koin-core` في المشترك، `koin-android`, `koin-androidx-compose`, `koin-androidx-workmanager` في أندرويد). كل موديول يُصدّر `val xModule = module { ... }`، ويجمعها `initKoin(platformModule)` في umbrella. على iOS تُمرر تنفيذات Swift للواجهات عبر `platformModule`.

## البدائل المرفوضة
- **Hilt/Dagger:** أندرويد فقط.
- **kotlin-inject:** آمن وقت الترجمة، لكنه أقل انتشاراً وأصعب في التعلم.
- **DI يدوي:** لا يتوسع مع عدد الموديولات.

## العواقب
- ✅ إعداد بسيط وموحد.
- ⚠️ الأخطاء وقت التشغيل لا الترجمة ← اختبار `verify()` لكل الموديولات في CI.
