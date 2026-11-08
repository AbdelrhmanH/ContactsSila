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
| تتبع المكالمات | Call tracking | `CallEventStateMachine` · `IngestCallEventUseCase` · `CorrelateObservedCallEndUseCase` | تحديث `lastContactedAt` من أحداث المكالمات المؤكدة من أي مصدر تسمح به المنصة؛ المرحلة 1 ([ADR-0006](docs/adr/0006-call-log-strategy.md) مقبول — B+): `IN_APP` + `ANDROID_CALL_SCREENING` + `ANDROID_TELEPHONY_OBSERVER` + `IOS_CALL_OBSERVER` + `MANUAL`، مع تأكيد احتياطي ومنع تكرار؛ لا `CALL_LOG` ولا `READ_CALL_LOG` |
| مصدر حدث المكالمة | Call source | `CallSource` | منشأ الدليل (enum في `:core:model`): `IN_APP` · `ANDROID_CALL_SCREENING` · `ANDROID_TELEPHONY_OBSERVER` · `IOS_CALL_OBSERVER` · `MANUAL`؛ لا يضمن المصدر توفر كل الحقول — والقيمتان الإرثيتان `CALL_LOG`/`OBSERVER` للبيانات القديمة فقط |
| آلة حالات المكالمات | Call event state machine | `CallEventStateMachine` | التبويب والدمج ومنع التكرار وقاعدة `lastContactedAt` الجدولية — عقد نقي في `:feature:arham:domain` (ADR-0006) |
| تأكيد المكالمة | Call confirmation | `CallConfirmation` · `CorrelateObservedCallEndUseCase` | الأحداث الملاحظة غير المكتملة تدخل قائمة التأكيد — لا يجوز تحديث `lastContactedAt` قبل الحسم |
| فرز المكالمات | Call screening | `SilaCallScreeningService` · `ScreeningRoleController` | دور `ROLE_CALL_SCREENING` الاختياري بأندرويد بموافقة صريحة؛ استجابة محايدة خلال 5 ثوانٍ — لا حجب ولا رفض ولا صمت ولا تحويل، ولا قراءة سجل اتصالات |
| مراقب حالة المكالمة | Call state observer | `ArhamCallStateObserver` · `IosCallStateTracker` | حالة المكالمة فقط (جارٍ/انتهى) بلا هوية ولا أرقام؛ على iOS ضمن حياة التطبيق فقط (AC-6) |
| جلسة الاستعجال | Emergency session | `EmergencySessionDao` · `ArhamEmergencyRingingService` | رنين طوارئ لأجراء EMERGENCY حصراً بحد معدل وحجب وأقصى 10 محاولات — قابل للإيقاف دائماً (AC-14) |
| تجسيد أهم 5 | Top-Five materialization | `MaterializeTopFiveUseCase` · `TopFiveMaterializationDao` | نقل اختيارات تهيئة الضيف إلى أرحام بمعاملة واحدة idempotent، بعلامة إنجاز لمرة واحدة (raise-only) ومشغّل إقلاع single-fire |
| لقطة الودجت | Widget snapshot | `arham-widget-snapshot.json` (App Group) | الناشر يكتب اللقطة من Room حصراً؛ الودجت (Glance/WidgetKit) يقرأها ولا يكتب أبداً (D6 — AC-15) |
| صلة القرابة | Relation | `Relation` | أب، عم، خال… |
| لقطة الدمج | Merge snapshot | `ContactMergeSnapshotEntity` · `contact_merge_snapshots` | ما-قبل-الدمج كاملاً (أعضاء + vCards الممتصين + المراجع) داخل صف Room — أساس التراجع خلال جلسة الشاشة والمسح المؤجل ([ADR-0013](docs/adr/0013-merge-write-path.md)) |
| جلسة التراجع | Undo session | `SESSION_SNAPSHOT_EXPIRY_MILLIS` | نافذة التراجع عن الدمج = زيارة شاشة المراجعة حصراً (24 ساعة حد مطلق للمسح)؛ مغادرة الشاشة تنهيها |
| المسح المؤجل | Deferred sweep | `MergeSweepCoordinator` · `sweepAbsorbedRawContacts` | «حدّث واحداً واحذف الباقي» بعد انقضاء الجلسة — حذف raw-مستوى حصراً بمعرفات مسجَّلة في اللقطة (لا حذف LOOKUP_URI الكلي) |
| فاصل دمج الملاحظات | Notes separator | `MergePlanner.NOTES_SEPARATOR` | «
—
» يفصل ملاحظات الممتصين عن الناجي عند الدمج — موثق ومعروض في المعاينة |
| رفض الأزواج | Pair dismissal | `duplicate_dismissals` · `dismissPair` | استبعاد ثنائية مكررة نهائياً بمفتاحها الثابت — الكشف القادم لا يُنشئ حافةً لها، والدليل الجديد عبر جهة ثالثة يبقى مشروعاً |
| التغذية الراجعة للفلترة | Smart-filter feedback | `smart_filter_feedback` · `SmartFilterWeight` | تعلّم محلي token→category: الرفض المسيطر يُنزل درجة ثقة الاقتراح (لا يُحدد مسبقاً) والقبول يثبّته |
| القاموس المضمّن | Embedded dictionary | `SmartFilterDictionary` | مصطلحات تصنيف عربية متعددة اللهجات بصيغة ما-بعد-التطبيع حصراً — بلا Remote Config (قرار #28) |
| طبقة القرابة | Kinship layer | `KinshipLayer` | أصول / فروع / حواشٍ |
| شجرة العائلة | Family tree | `FamilyTreeScreen` | عرض هرمي للأقارب |
| أوقات الهدوء | Quiet hours | `quietHours` | فترة بلا إشعارات |
| القائمة المنبثقة | Action sheet | `SilaActionSheet` | المعيار الموحد للنوافذ |
| المصدر الوحيد للحقيقة | SSOT | Room | الواجهة تقرأ من Room فقط |
| صندوق الإرسال | Outbox | `pending_uploads` | قائمة ما سيُرفع للسحابة |
| B2B / B2C | — | `interestTags` | تخصيص الدليل حسب المهنة |
