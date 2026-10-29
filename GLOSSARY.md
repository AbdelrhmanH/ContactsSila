# المصطلحات (Glossary)

| العربية | English | في الكود | التعريف |
|---|---|---|---|
| صِلَة | Sila | `app.sila` | اسم التطبيق |
| جهة اتصال | Contact | `Contact` | شخص أو جهة محفوظة في دفتر الهاتف |
| التصنيف | Category | `Category` | عائلة/أصدقاء/عمل/خدمات/طوارئ/غير مصنف |
| الإضافة الذكية | Smart Add | `SmartAddSheet` | زر + يفرض اختيار التصنيف قبل الحفظ |
| الفلترة الذكية | Smart Categorization | `SuggestCategoriesUseCase` | اقتراح تصنيف الأرقام القديمة بالكلمات المفتاحية |
| دمج المكرر | Merge Duplicates | `MergeContactsUseCase` | توحيد جهات الاتصال المتكررة |
| المفضلة | Favorites | `isFavorite` | جهات تظهر في الصف الدائري العلوي |
| دليل الخدمات | Services Directory | `feature:services` | الدليل السحابي للأرقام الخدمية |
| خدمة شخصية | Personal service | `PERSONAL` | رقم خدمي يراه صاحبه فقط |
| خدمة مجتمعية | Community service | `COMMUNITY` | رشّحه N مستخدمين — شارة برتقالية |
| خدمة موثقة | Verified service | `VERIFIED` | وثّقته الإدارة أو صاحبه بـ OTP — شارة خضراء ✔ |
| غرفة الانتظار | Pending services | `pending_services` | اقتراحات لم تبلغ العتبة |
| درجة الثقة | Trust Score | `trustScore` | مقياس مستنتج من عودة المستخدمين والتقييم |
| رائج | Hot | `isHot` 🔥 | طلب عالٍ في المنطقة |
| إشارة اتصال | Call signal | `call_signals` | سجل مجهول لاتصال بخدمة لحساب الثقة |
| التقييم السريع | One-tap rating | `Rating` | 🤩 / 🙂 / 😞 بعد أول مكالمة |
| البطاقة التعريفية | Digital Profile | `Profile` | هوية المستخدم وأرقامه واستخدامها |
| بطاقة العمل | Business card | `business_cards` | بطاقة صاحب الخدمة في الدليل |
| وضع الضيف | Guest mode | `isGuest` | استخدام بدون تسجيل — قراءة الدليل فقط |
| حاجز التسجيل | Login wall | `LoginWallSheet` | طلب التسجيل عند إجراء تفاعلي |
| أرحام | Arham (Kinship) | `feature:arham` | ميزة تذكير صلة الرحم |
| الوتيرة | Frequency | `ArhamFrequency` | يومي/أسبوعي/أسبوعين/شهري/مناسبات |
| الاستعجال | Urgency | `urgency` | الأيام منذ آخر اتصال ÷ أيام الوتيرة |
| النطاق الأحمر/الأصفر/الأخضر | Overdue / Soon / OK | `ArhamStatus` | حالة الوصال |
| المنصة | Podium | `ArhamPodium` | أكثر 3 أقارب تواصلت معهم |
| التتبع الصامت | Silent tracking | `SyncCallLogUseCase` | تحديث آخر اتصال تلقائياً من مكالمات صِلَة (`IN_APP`/`OBSERVER`) + تأكيد يدوي؛ لا قراءة لسجل المكالمات (ADR-0006) |
| صلة القرابة | Relation | `Relation` | أب، عم، خال… |
| طبقة القرابة | Kinship layer | `KinshipLayer` | أصول / فروع / حواشٍ |
| شجرة العائلة | Family tree | `FamilyTreeScreen` | عرض هرمي للأقارب |
| أوقات الهدوء | Quiet hours | `quietHours` | فترة بلا إشعارات |
| القائمة المنبثقة | Action sheet | `SilaActionSheet` | المعيار الموحد للنوافذ |
| المصدر الوحيد للحقيقة | SSOT | Room | الواجهة تقرأ من Room فقط |
| صندوق الإرسال | Outbox | `pending_uploads` | قائمة ما سيُرفع للسحابة |
| B2B / B2C | — | `interestTags` | تخصيص الدليل حسب المهنة |
