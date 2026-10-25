# ADR-0002: Ktor بدلاً من Retrofit
- **الحالة:** مقبول
- **التاريخ:** 2026-10-25

## السياق
طُلب استخدام Retrofit، لكن طبقة الشبكة موجودة في الكود المشترك (KMP)، و Retrofit مكتبة JVM/Android فقط.

## القرار
استخدام **Ktor Client** في `core:network` (محرك OkHttp لأندرويد، Darwin لـ iOS) مع kotlinx.serialization. يؤدي نفس دور Retrofit: تعريف الـ Endpoints في `XRemoteSource`، دوال `suspend`، تحويل JSON إلى DTOs.

```kotlin
class ProfessionsRemoteSource(private val client: HttpClient) {
    suspend fun fetchAll(): List<ProfessionDto> = client.get("v1/professions").body()
}
```

## البدائل المرفوضة
- **Retrofit في androidMain فقط:** يكسر مشاركة الكود ويضاعف العمل لـ iOS.
- **Ktorfit:** واجهة شبيهة بـ Retrofit فوق Ktor — خيار مقبول مستقبلاً إن أردنا أسلوب Annotations.

## العواقب
- ✅ كود شبكة واحد للمنصتين.
- ℹ️ معظم البيانات عبر Firebase SDK؛ Ktor لـ Cloud Functions HTTP وأي API خارجي (Algolia، خرائط).
