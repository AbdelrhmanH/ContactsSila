package app.sila.feature.contacts.presentation.duplicates

import app.sila.core.model.Category

/**
 * نصوص شاشة مراجعة الفلترة الذكية (T7 · Issue #28) — عقد بلا Compose: يُموَّل في
 * `sharedUI` من موارد الترجمة بنمط [DuplicatesReviewStrings]. لا نصوص ثابتة في
 * الكود (AGENTS.md).
 */
interface SmartFilterReviewStrings {
    val screenTitle: String
    val backDescription: String
    val loading: String
    val emptyTitle: String
    val emptyMessage: String

    val confidenceHigh: String
    val confidenceMedium: String
    val confidenceLow: String

    /** الدليل المطابق للقاعدة (توكن القاموس/الرقم) — عرض شفافية قبل الالتزام. */
    val evidenceLabel: (token: String) -> String

    val categoryTitle: (category: Category) -> String
    val groupCount: (count: Int) -> String

    val selectAll: String

    val applySelected: (count: Int) -> String
    val notThisCategory: String
    val neverAsk: String

    val errorApply: String
    val errorContactNotFound: String
    val errorArhamLink: String

    /** حوار عرض أرحام بعد تطبيق العائلة (AC) — [count] عدد الجهات المُربوطة مرشّحاً. */
    val arhamOfferTitle: (count: Int) -> String
    val arhamOfferAccept: String
    val cancel: String
}
