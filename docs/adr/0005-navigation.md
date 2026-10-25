# ADR-0005: Navigation Compose و NavigationStack
- **الحالة:** مقبول
- **التاريخ:** 2026-10-25

## السياق
طُلب Navigation Component مع Safe Args. الواجهة على أندرويد Compose (لا Fragments)، و Safe Args مصمم لـ XML/Fragments.

## القرار
- **أندرويد:** Navigation Compose بمسارات **type-safe** (`@Serializable` routes) — المكافئ الحديث لـ Safe Args، مع Deep Links و Nested Graphs و back stack مستقل لكل تبويب.
- **iOS:** `TabView` + `NavigationStack(path:)` لكل تبويب.
- أحداث التنقل تخرج من الـ ViewModel كـ `UiEffect` والواجهة تنفذها.

```kotlin
@Serializable data class ContactDetailsRoute(val contactId: Long)
composable<ContactDetailsRoute>(deepLinks = listOf(navDeepLink<ContactDetailsRoute>(basePath = "sila://contact"))) {
    val route = it.toRoute<ContactDetailsRoute>()
    ContactDetailsScreen(route.contactId)
}
```

## البدائل المرفوضة
- Navigation Component + Fragments + Safe Args: لا يتناسب مع Compose.
- مكتبات تنقل مشتركة (Voyager/Decompose): تعني منطق تنقل مشترك بينما الواجهات أصلية.

## العواقب
- ✅ أمان الأنواع، Deep Links، حفظ الحالة.
- ⚠️ التنقل يُكتب لكل منصة (بسيط نسبياً).
