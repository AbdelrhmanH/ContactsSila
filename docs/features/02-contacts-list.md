# 02 — شاشة جهات الاتصال (الرئيسية)

> المرجع: [PRD §4](../PRD.md#4-الصفحة-1-جهات-الاتصال) · التصميم: [contacts-favorites-tabs.png](../design-references/contacts-favorites-tabs.png)

## التخطيط
```text
┌ جهات الاتصال                         ⚙  ✎ ┐
│ (◯)(◯)(◯)(◯)(◯) ← المفضلة                 │
│ [🔍 بحث في جهات الاتصال…              ]   │
│ [الكل 214][عائلة 12][أصدقاء][عمل 30][خدمات]│
│ ┌ اقتراح: وجدنا 15 رقم خدمة — راجعها ┐     │
│ أ                                          │
│ (ص) أمي الغالية · أم            ❤  📞     │
│ (ص) أحمد · أخي                  ❤  📞     │
│ س                                          │
│ (س) سباك أبو محمد · ✔ موثق       🔧 📞     │
│                                     (+)    │
├────────────────────────────────────────────┤
│  📇 جهات الاتصال │ 🧰 الدليل │ 👨‍👩‍👧 أرحام (3) │
└────────────────────────────────────────────┘
```

## قصص المستخدم
- **US-02.1** أريد الوصول لأي رقم بأقل عدد نقرات عبر التصنيف أو البحث.
- **US-02.2** أريد رؤية أهم أشخاصي في الأعلى والاتصال بهم بنقرة.
- **US-02.3** أريد أن يجبرني التطبيق بلطف على تصنيف كل رقم جديد.
- **US-02.4** أريد تصنيف أو حذف عدة أسماء دفعة واحدة.

## معايير القبول
- [ ] فتح الشاشة بـ 5000 جهة اتصال: أول إطار < 500ms من Room، تمرير بدون Jank.
- [ ] البحث يُظهر النتائج أثناء الكتابة (Debounce 150ms) ويطابق: الاسم بعد التطبيع العربي، أي جزء من الرقم، الكنية، الشركة، المسمى، الوسوم.
- [ ] العدادات في الـ Chips تتحدث لحظياً.
- [ ] الـ Chip المحدد يُحفظ عند العودة للشاشة.
- [ ] صف المفضلة يخفي نفسه إن لم توجد مفضلة، ويعرض حلقة حالة أرحام لأفراد العائلة.
- [ ] نقرة صف = التفاصيل؛ زر 📞 = اتصال مباشر (أو اختيار الرقم إن تعدد).
- [ ] FAB يفتح `SilaActionSheet`: جهة اتصال جديدة / فرد من العائلة / رقم خدمي / مسح QR — كل خيار يفتح نموذج الإضافة بالتصنيف مُحدداً مسبقاً.
- [ ] ضغطة مطولة ← وضع التحديد المتعدد: تصنيف، مفضلة، مشاركة، دمج، حذف.
- [ ] السحب: يمين=اتصال، يسار=رسالة (قابل للتغيير، يدعم RTL بعكس الاتجاه منطقياً).
- [ ] فهرس أبجدي جانبي عربي + لاتيني حسب الأسماء الموجودة.
- [ ] فلتر الحساب (الكل / الهاتف / Google…) من قائمة العنوان.
- [ ] بطاقات الاقتراح (مكررات، فلترة ذكية) قابلة للإغلاق ولا تعود لنفس العناصر.
- [ ] الحالات: لا صلاحية / لا جهات اتصال / لا نتائج بحث.

## الـ ViewModel
```kotlin
data class ContactsUiState(
    val isLoading: Boolean = true,
    val query: String = "",
    val selectedCategory: Category? = null,          // null = الكل
    val categoryCounts: Map<Category, Int> = emptyMap(),
    val favorites: List<FavoriteItem> = emptyList(),
    val suggestions: List<SuggestionCard> = emptyList(),
    val selection: Set<Long> = emptySet(),
    val permission: PermissionState = PermissionState.Unknown,
)
// القائمة نفسها: Flow<PagingData<ContactListItem>> منفصل
sealed interface ContactsIntent {
    data class Search(val q: String) : ContactsIntent
    data class SelectCategory(val c: Category?) : ContactsIntent
    data class Call(val contactId: Long) : ContactsIntent
    data class ToggleSelect(val id: Long) : ContactsIntent
    data class BulkCategorize(val c: Category) : ContactsIntent
    data object BulkDelete : ContactsIntent
    data class DismissSuggestion(val id: String) : ContactsIntent
    data class OpenAdd(val preset: AddPreset) : ContactsIntent
}
```

## UseCases
`ObserveContactsPagedUseCase`, `ObserveCategoryCountsUseCase`, `ObserveFavoritesUseCase`, `SearchContactsUseCase`, `BulkCategorizeUseCase`, `DeleteContactsUseCase`, `ObserveSuggestionsUseCase`.

## أنماط التفاعل المستخدمة
> المرجع: [DESIGN_SYSTEM.md §5.2](../DESIGN_SYSTEM.md#52-أنماط-التفاعل-المتقدمة-مستوحاة-من-المراجع)

| النمط | الموقع |
|---|---|
| **النمط 3 — السحب لكشف البحث** | السحب للأسفل يكشف شريط البحث + Chips التصنيف → تبديل الفلتر يغيّر القائمة بحركة Crossfade |

## التحليلات
`contacts_search`, `category_chip_selected(category)`, `smart_add_option(option)`, `bulk_action(type,count)`, `call_from(list|favorites|swipe)`.
