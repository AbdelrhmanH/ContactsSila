package app.sila.core.database.dao

import app.sila.core.model.Contact

/**
 * ممرّ الذرّية لاستعادة التصنيفات (تذكرة #60 · S1-1 · S3-22 · [ADR-0015]):
 * واجهة نطاق فوق [LocalSilaDatabase] تُلزم مسار استعادة النسخ الاحتياطية كاملاً
 * (شطف REPLACE + التصنيفات + الأرحام + التواريخ + الوسوم) في **معاملة Room واحدة**
 * بحيث تنهار العملية في أي نقطة وسط المعاملة فتعودُ القاعدة كما-كانت حرفاً —
 * لا «محوّة دون استعادة» ولا كتابات جزئية (عقد «كل-أو-لا-شيء» للاستعادة).
 *
 * النطاق يمرّر خطة عبر أنواع [app.sila.core.model] الصفية حصراً — لا أنواع ميزة
 * الإعدادات (الحمولة المشفرة تُفكّ وتُحلّل في طبقة data قبل الدعوة)، ولا أنواع
 * androidx.Room هنا إطلاقاً (ولا تسريب Room إلى `:feature:settings:data`) —
 * نمط `mergeContactsInRoom`/`undoMergeFromSnapshot` ذاته (ADR-0013).
 * المرايا (كاش جهات الاتصال ومستمعو تغيّر أرحام) تُحدَّث بعد COMMIT حصراً.
 */
interface ClassificationRestoreRunner {
    /** تنفيذ الخطة كاملة داخل معاملة IMMEDIATE واحدة، وإعادة ناتج التحقق الذرّي. */
    suspend fun applyClassificationRestore(plan: ClassificationRestorePlan): ClassificationRestoreOutcome
}

/**
 * خطة الاستعادة المُحلَّولة من النطاق قبل المعاملة — من قراءة لحظية واحدة لكاش
 * جهات الاتصال (النسخ الكاملة محتواها بنهائيتها). [replacementContacts] تحمل نسخ
 * المحو الكاملة (وضع REPLACE: تصنيف عطيف/وسوم فارغة/بلا أرحام — التواريخ والأطفال
 * محفوظة كما في العهد القديم حرفاً) و[contactRewrites] تحمل النسخة النهائية
 * الموحَّدة لكل جهة لمسها الحمولة (تصنيف/أرحام–إلزاماً FAMILY/تواريخ/وسوم
 * مُركَّبة على أساس المحو عند REPLACE) — فمعاملة واحدة تكتب الحالة النهائية
 * بلا حالات وسيطة، وكل ما بين الكتابات يتراجع كلياً عند الانقطاع.
 */
data class ClassificationRestorePlan(
    /** `true` = وضع REPLACE: تُطبَّق [replacementContacts] قبل كتابات الحمولة. */
    val replaceBeforeApply: Boolean,
    /** نسخ المحو الكاملة لكل الجهات الحية (REPLACE حصراً — فارغة في MERGE). */
    val replacementContacts: List<Contact>,
    /** النسخ النهائية الموحَّدة لكل جهة ملموسة بالحمولة (بمعرّفات صفوف قائمة). */
    val contactRewrites: List<Contact>,
)

/**
 * ناتج المعاملة: عدد المحو + معرّفات النسخ **المطبَّقة فعلياً** والمعرّفات
 * **المحدومة داخل المعاملة** (غياب/قبر حدث بين قراءة النطاق والمعاملة —
 * القرار الوحيد المُرسَم للانحراف عن الخطة)؛ النطاق يربطها بأدخلته الأصلية
 * بترتيب الحمولة نفسه لاحتساب المطابقة وغير-المطابقة — مع تعاقد أنه لا
 * كتابة لأي معرف وارد في أي قائمة غياب.
 */
data class ClassificationRestoreOutcome(
    val clearedContacts: Int,
    val appliedContactIds: List<Long>,
    val missingContactIds: List<Long>,
)
