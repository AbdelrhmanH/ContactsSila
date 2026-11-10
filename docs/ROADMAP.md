# خارطة الطريق (Roadmap)

> كل مهمة = PR واحد تقريباً. ضع `[x]` عند الإنجاز.

## المرحلة 0 — الأساس ✅ (على Android + iOS + المشترك KMP)
**الهدف:** هيكل يعمل على المنصتين بشريط تنقل موحد.

- [x] إنشاء مشروع KMP (`sharedLogic`, `sharedUI`, `androidApp`, `iosApp`)
- [x] موديولات الأساس المعيارية (`:core:common`, `:core:model`, `:core:database`, `:core:datastore`, `:core:designsystem`) + 12 موديولاً فرعياً للميزات (`:feature:*:{domain,data,presentation}`)
- [x] `libs.versions.toml` مع توحيد `app-version` وحساب `versionCode` تلقائياً
- [x] اختبارات المعمارية والخصوصية (`checkArchitectureBoundaries`) + تفعيل قواعد ProGuard/R8 (`isMinifyEnabled = true`)
- [x] GitHub Actions (`ci.yml`: architecture check + unit tests + debug/release APK)
- [x] `core:common` (Result, AppDispatchers, Clock, PhoneNormalizer, ArabicTextNormalizer, Redactor, MviViewModel + اختبارات)
- [x] `core:database` (جداول SQLite علائقية عبر `BundledSQLiteDriver` + EntityMappers + DAOs + DataRetentionCleanupDao + اختبار حفظ فعلي على القرص)
- [x] `core:datastore` (`UserPreferencesDataSource` عبر `PreferenceDataStoreFactory` على القرص + اختبار استمرارية)
- [x] Koin: `ensureKoinStarted()` في `SilaApplication` و `sharedLogic` + ربط التبعيات كـ Singletons
- [x] `MainViewModel` و ViewModels الميزات بنمط MVI + UDF
- [x] `core:designsystem`: Theme (فاتح/داكن)، الخطوط، الألوان، `SilaActionSheet`، `SilaCategoryChip`، `SilaContactRow`، `SilaServiceCard`، `SilaArhamRankedRow`، `SilaSettingsGroup`
- [x] ربط SwiftUI بـ `MainViewModel` عبر SKIE (يتطلب بيئة macOS / Xcode للتحقق)
- [x] Navigation: 3 تبويبات (`CONTACTS`, `SERVICES`, `ARHAM`) مع شارة عدد المتأخرين في أرحام
- [x] الترجمة عربي/إنجليزي + RTL-First

## المرحلة 1 — MVP محلي (بدون سيرفر) ✅ (مكتملة ومدمجة بالكامل في main)
**الهدف:** المستخدم يستغني عن تطبيق الأسماء الافتراضي.

### Onboarding (ضيف) — [01](features/01-onboarding-profile.md) (T5 · Issue #26 · PR #33)
- [x] شاشات الترحيب + شرح الصلاحيات → [01](features/01-onboarding-profile.md)
- [x] اختيار أهم 5 أشخاص

### جهات الاتصال — [02](features/02-contacts-list.md) (T1-T4)
- [x] `ContactsPlatformSource` (Android Contacts Provider / iOS CNContactStore) (T1 · Issue #22 · PR #32)
- [x] مزامنة تزايدية مع Room + مراقبة التغييرات (T2 · Issue #23 · PR #35)
- [x] القائمة بقراءة مجزأة مخصصة + فهرس أبجدي (T3 · Issue #24 · PR #36)
- [x] Chips مع العدادات (T3)
- [x] صف المفضلة (T3)
- [x] البحث FTS (T3)
- [x] Smart FAB + Sheet (T3)
- [x] التحديد المتعدد + السحب (T3)
- [x] واجهة iOS الأصلية وتكامل SwiftUI مع SKIE (T4 · Issue #25 · PR #39)

### التفاصيل والتعديل — [03](features/03-contact-details-edit.md) (T6 · Issue #27 · PR #40)
- [x] صفحة التفاصيل (Calling Card، Connected Apps، Recent History)
- [x] قائمة "المزيد": مفضلة، مشاركة vCard/QR، اختصار، نقل حساب، بريد صوتي، حظر، حذف + Undo
- [x] صفحة الإضافة/التعديل بكل الحقول + Expand
- [x] الصورة (كاميرا/معرض/قص)
- [ ] Shared Element transition — *غير منفذ (لم يُقس ولم يُنفذ)؛ مؤجل مع النشر المرحلي*

### التنظيم — [04](features/04-duplicates-smart-filter.md) (T7 · Issue #28 · PR #50)
- [x] كشف المكررات + شاشة المراجعة + الدمج + التراجع — *فحص 5,000 جهة: 462ms على مضيف JVM حتمي (250 مجموعة بالضبط — اختبار `DuplicateScanBenchmarkTest`)؛ قياس الجهاز الفعلي (Redmi 9S) مؤجل ولا يُستدل نجاح جهازٍ من مضيف*
- [x] الفلترة الذكية + شاشة المراجعة
- [ ] التصنيف التدريجي بعد المكالمة — *مستبعد من نطاق المرحلة 1 صراحةً (قرار #16)*

### أرحام — [06](features/06-arham-family-tree.md) (T8 · Issue #29 · PR #41 · PR #49)
- [x] Kinship Setup Sheet
- [x] حساب الأولوية والحالة
- [x] القائمة المرقمة
- [x] المنصة + الإحصائيات + التقدم
- [x] التتبع ([ADR-0006](adr/0006-call-log-strategy.md) — B+ متعدد المصادر مع تأكيد احتياطي)
- [x] الإشعارات + أوقات الهدوء
- [x] Confetti والانتقال اللوني
- [x] شجرة العائلة
- [x] رنين الطوارئ المتواصل (جهات EMERGENCY فقط + Foreground Service)
- [x] أرحام Widget (Glance / WidgetKit — Small + Medium + Large)
- *QA العتاد (جلسات 2026-11-06..08 على D1 Redmi Note 9S / Android 12 / MIUI 14 — تقرير الجلسة-3 غير المتتبَّع):* PASS بأدلة `A-N01` (×3) و`B-W14` و`F-L01` (×2) و`F-L05` بفرعيه و`B-W02` جزئياً؛ انحدار DEF-01 مؤكد مُصلاً (`64a59a1`/PR #43)؛ جزئي `F-L02`/`A-N14` (قيد إقلاع MIUI — عيب بيئة `T8-QA-3.1`)؛ الباقي NOT EXECUTED أو BLOCKED (صفوف iOS بلا مسار بناء موقّع)؛ وDEF-02 (P3) بقرار المالك — التفاصيل في قائمة QA المحمية وتعليقات `#29`

### النغمات والتفضيلات — [07](features/07-ringtones-preferences.md) (T9 · Issue #30 · PR #51)
- [x] نغمة لجهة اتصال + لتصنيف (Android)
- [x] شاشة الإعدادات كاملة (Compose + SwiftUI)
- [x] النسخ الاحتياطي المشفر محلياً (AES-256-GCM) مع الاسترجاع

### الإصدار وبوابة الإطلاق — (T10 · Issue #31)
- [x] التحقق المعماري الصارم (0 violations عبر 20 موديولاً)
- [x] تدقيق الخصوصية وخلو التقارير من أي PII عبر Redactor ومفتاح crashReportsEnabled
- [x] الامتثال التام لـ ADR-0006 (لا READ_CALL_LOG ولا مشغل افتراضي)
- [x] اجتياز جميع اختبارات الوحدة والمحاكي و CI لنظامي Android و iOS
- [ ] الاختبارات اليدوية على العتاد المادي (Macrobenchmark، Play Internal Testing، TestFlight) — *مؤجلة لحين توفر الأجهزة المادية وفق توجيهات المستخدم*

## المرحلة 2 — السحابة والدليل → [05](features/05-services-directory.md)
- [ ] مشاريع Firebase (dev/staging/prod) + App Check
- [ ] Auth بالهاتف + OTP
- [ ] البطاقة التعريفية + بطاقة العمل + QR + Deep Links
- [ ] Firestore + القواعد + اختبارات Emulator
- [ ] قاموس المهن
- [ ] الدليل (قراءة للضيوف) + Cache في Room + Paging
- [ ] فحص الرقم أثناء الإضافة (`lookupNumber`)
- [ ] Outbox + Crowdsourcing + Functions الترقية
- [ ] الشارات
- [ ] التقييم بعد المكالمة + Login Walls
- [ ] البلاغات
- [ ] سجل النزاعات (Dispute Log) — توثيق نصي + صور، تخزين محلي + Firestore
- [ ] أتمتة الوصال (In-App Automation) — جدولة رسائل داخلية/رنات + Smart Fallback + صندوق أرحام الوارد
- [ ] FCM Topics + المزامنة الصامتة
- [ ] جمع Impressions الصامت (Pro)
- [ ] لوحة إدارة ويب للتوثيق
- [ ] حذف الحساب + شاشة "ماذا نعرف عنك"

## المرحلة 3 — التخصيص والثقة
- [ ] Trust Score + Hotness + منع التلاعب
- [ ] مجال العمل → وسوم → Smart Matching (B2B/B2C)
- [ ] Dual Geo-Fencing (Geohash)
- [ ] Remote Config للعتبات والقاموس
- [ ] (عند الحاجة) Algolia/Typesense

## المرحلة 4 — Pro
- [ ] الاشتراكات (Play Billing / StoreKit 2 أو RevenueCat)
- [ ] لوحة التحكم: الشريط العلوي (محدد الزمن + إشعارات + بروفايل)
- [ ] لوحة التحكم: بطاقة مؤشر الثقة الدائري + Sparklines (ظهور/زيارات/حفظ)
- [ ] لوحة التحكم: شبكة الإحصائيات (Bento Grid — 6 بطاقات: مكالمات، تقييمات، متوسط المدة، مشاركات، عملاء فريدون، نسبة العودة)
- [ ] لوحة التحكم: بطاقات التحكم السريع (متاح الآن + الظهور + أوقات العمل + الرد التلقائي)
- [ ] صور الأعمال (حتى 5 صور)
- [ ] الظهور المميز (Promoted badge + ترتيب أعلى)
- [ ] رسالة الرد التلقائي خارج أوقات العمل
- [ ] تقارير شهرية (PDF بالبريد)
- [ ] فصل مكالمات الشخصي/العمل

## مؤشرات النجاح (KPIs)
| المؤشر | الهدف المبدئي |
|---|---|
| نسبة جهات الاتصال المصنفة بعد أسبوع | ≥ 60% |
| مستخدمون فعّلوا أرحام | ≥ 40% |
| نسبة الأقارب في النطاق الأخضر بعد شهر | تحسن ≥ 20% |
| تحويل الضيف إلى مسجّل | ≥ 15% |
| الاحتفاظ بعد 30 يوماً (D30) | ≥ 25% |
| معدل الانهيارات | < 0.5% |

## مقاييس النجاح لكل مرحلة (OKRs)

### المرحلة 0 — الأساس
| الهدف (Objective) | النتيجة الرئيسية (Key Result) |
|---|---|
| **O1: بنية تقنية مستقرة** | KR1: التطبيق يبني ويعمل على Android + iOS بدون أخطاء |
| | KR2: CI (build + tests + lint) أخضر بنسبة 100% |
| | KR3: 0 انتهاكات Konsist للاعتمادات الممنوعة |

### المرحلة 1 — MVP محلي
| الهدف (Objective) | النتيجة الرئيسية (Key Result) |
|---|---|
| **O1: المستخدم يستغني عن التطبيق الافتراضي** | KR1: ≥ 60% من جهات الاتصال مصنفة بعد أسبوع |
| | KR2: Cold start ≤ 1.5s · 0 Jank في التمرير |
| | KR3: ≥ 40% فعّلوا أرحام |
| **O2: تجربة بدون احتكاك** | KR1: Onboarding ≤ 90 ثانية (ضيف) |
| | KR2: ≤ 2 طلبات صلاحية في Onboarding |
| | KR3: D7 Retention ≥ 40% |

### المرحلة 2 — السحابة والدليل
| الهدف (Objective) | النتيجة الرئيسية (Key Result) |
|---|---|
| **O1: دليل خدمات حي وموثوق** | KR1: ≥ 500 خدمة موثقة في المدينة الأولى |
| | KR2: تحويل الضيف → مسجّل ≥ 15% |
| | KR3: ≥ 30% من المكالمات الخدمية تُقيَّم |
| **O2: نمو عضوي** | KR1: 10K مستخدم (MAU) |
| | KR2: ≥ 20% من التحميلات عبر QR / Deep Link (viral) |
| | KR3: تقييم المتجر ≥ 4.5 ⭐ |

### المرحلة 3 — التخصيص والثقة
| الهدف (Objective) | النتيجة الرئيسية (Key Result) |
|---|---|
| **O1: دليل مخصص ودقيق** | KR1: ≥ 80% من نتائج البحث ملائمة (حسب النقرات) |
| | KR2: نسبة البلاغات "رقم لا يعمل" ≤ 5% |
| | KR3: ≥ 3 مدن/مناطق نشطة |
| **O2: ثقة المجتمع** | KR1: Trust Score correlation مع Retention Rate ≥ 0.7 |
| | KR2: ≥ 50% من الخدمات لديها ≥ 3 تقييمات |

### المرحلة 4 — Pro
| الهدف (Objective) | النتيجة الرئيسية (Key Result) |
|---|---|
| **O1: أول إيراد مستدام** | KR1: ≥ 250 مشترك Pro (نقطة التعادل) |
| | KR2: Churn rate ≤ 10%/شهر |
| | KR3: NPS ≥ 50 لمشتركي Pro |
| **O2: قيمة واضحة لمزودي الخدمات** | KR1: ≥ 70% من المشتركين يفتحون لوحة التحكم أسبوعياً |
| | KR2: ≥ 30% يُعدّلون أوقات العمل بعد الاشتراك |

### North Star Metric لكل مرحلة
| المرحلة | المقياس النجمي | لماذا |
|---|---|---|
| **0** | CI Green Rate = 100% | البنية هي الأساس |
| **1** | Weekly Active Arham Users | أرحام هي الميزة الفريدة والـ Retention driver |
| **2** | Monthly Verified Services Added | الدليل هو Network Effect |
| **3** | Search → Call Conversion Rate | دقة البحث = ثقة المستخدم |
| **4** | Monthly Recurring Revenue (MRR) | الاستدامة المالية |
