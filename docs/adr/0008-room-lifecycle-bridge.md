# ADR-0008: جسر expect/actual لدورة حياة RoomDatabase عبر المنصات
- **الحالة:** مقبول
- **التاريخ:** 2026-10-26

## السياق
وحدة `:core:database` تستخدم Room KMP 2.7.0 كمزوّد أنواع/واجهات فقط (بدون مُولِّد KSP)، بينما التخزين الفعلي يُدار عبر اتصال `BundledSQLiteDriver` مستقل داخل `LocalSilaDatabase`. كان `SilaRoomDatabase` يرث `RoomDatabase()` مباشرة، وكان `LocalSilaDatabase` يعيد تعريف `clearAllTables()` و `close()`.

كشف فحص CI على macOS (مهمة `verify-ios`) أن واجهة `RoomDatabase` تختلف بين المنصات في Room KMP:
- على **Android**: `clearAllTables()` مُعلنة `abstract` (يجب تنفيذها)، و `close()` مفتوحة (`open`).
- على **iOS/Native**: `clearAllTables()` **غير موجودة** إطلاقاً، و `close()` **نهائية** (`final` — لا يجوز إعادة تعريفها).

لذا فإن أي `override` لهذين العضوين في `commonMain` يُترجم على Android ويفشل على iOS، والعكس صحيح — ولا يمكن حسم ذلك في ملف واحد مشترك.

## القرار
1. إدخال جسر `expect abstract class RoomDatabaseLifecycleBridge : RoomDatabase` في `commonMain`، مع `actual` لكل منصة:
   - **androidMain:** يُنفِّذ `clearAllTables()` كعملية لا-تفعل (`Unit`) لأن الجداول تُدار عبر اتصال `BundledSQLiteDriver` المستقل — لا جداول مُدارة من Room لإفراغها. (التوقيع يحمل `@Suppress("EXPECT_ACTUAL_INCOMPATIBLE_MODALITY")`؛ أعد النظر فيه عند أي ترقية لـ Kotlin/Room.)
   - **iosMain:** جسر فارغ يرث `RoomDatabase()` فقط.
2. تصبح `SilaRoomDatabase` ترث من الجسر بدلاً من `RoomDatabase` مباشرة.
3. حذف `override fun clearAllTables()` من `LocalSilaDatabase` (كود ميت بلا مستدعيات)، واستبدال `override fun close()` بدالة مستقلة `fun closeConnection()` تُغلق اتصال SQLite المستقل على كل المنصات.

## البدائل المرفوضة
- **ترك الـ override كما هو:** لا يُترجم على iOS نهائياً (رسالتا الخطأ في CI).
- **إزالة وراثة `RoomDatabase` من commonMain وإعلانها في الـ actuals:** يفقد `commonMain` رؤية النوع `RoomDatabase` المطلوبة لتوقيع `createInvalidationTracker()` والتعليقات التوضيحية `@Database`.
- **تكرار المنطق في مجلدات مصدر لكل منصة:** يضاعف كود التخزين ويخرق عنصر القيمة الوحيدة (Single Source of Truth).

## العواقب
- ✅ يُترجم `:core:database` على Android و iOS بنفس شجرة `commonMain` دون `if` خاصة بمنصة في منطق الأعمال.
- ✅ حذف كود ميت كان يكرر قائمة الجداول (كانت موجودة أصلاً في `createInvalidationTracker`).
- ⚠️ ميناء الإغلاق العام للقاعدة أصبح `closeConnection()`؛ أي مستدعٍ مستقبلي لـ `close()` الموروثة سيُغلق اتصال Room الداخلي فقط — يُستدعى `closeConnection()` دائماً لإنهاء الاتصال الفعلي.
