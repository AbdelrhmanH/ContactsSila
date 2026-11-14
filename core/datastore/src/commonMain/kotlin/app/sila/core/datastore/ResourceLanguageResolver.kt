package app.sila.core.datastore

import app.sila.core.model.AppLanguage

/**
 * حسم لغة الموارد من رمز لغة المنصة — جوهر إصلاح عيب DEF-02 (تذكرة #97).
 *
 * موارد الواجهة (composeResources) تُحسم سلسلةً وفق لغة المنصة الفعلية:
 * `values-en` للإنجليزية، و`values` الافتراضية (العربية) لأي رمز آخر لعدم وجود
 * ملفات مؤهلة لها — فحسم الشريط والسمة (chrome) يجب أن يتبع الحسم ذاته فلا
 * واجهة مختلطة في حالة اللغة غير المحفوظة (تثبيت جديد: انظر
 * [ResourceLanguageResolutionTests]).
 *
 * هذا التابع نقي ومشترك؛ وصول المنصة لقراءة الرمز الفعلي يمر عبر منبع محقون
 * (`expect/actual` في `sharedLogic` — لأي وصول نظامي)، والاختيار الصريح
 * للمستخدم (المكتوب عبر منفذ `AppLocalePort` الواحد) يفوز على هذا الحسم دوماً.
 */
fun resolveResourceLanguage(platformLanguageCode: String): AppLanguage =
    if (platformLanguageCode.equals("en", ignoreCase = true)) {
        AppLanguage.ENGLISH
    } else {
        AppLanguage.ARABIC
    }
