package app.sila.feature.contacts.domain

import app.sila.core.model.Contact

/**
 * فلتر الحساب في قائمة جهات الاتصال (Issue #24 — "الكل / الهاتف / Google / أخرى").
 *
 * المطابقة تقدير-أفضل على `accountType` المعكوس من المنصة:
 * - [DEVICE]: بلا حساب (`null` — جهات الجهاز المحلية) أو نوعه يحمل `local`/`device`.
 * - [GOOGLE]: نوع الحساب يحمل `google`.
 * - [OTHER]: أي نوع حساب آخر غير فارغ.
 *
 * قيد موثّق: مصادر T1 الحالية لا تُعبّئ `accountType` بعد (يُصلَح في ميزة المنصة)،
 * فعندئذٍ تظهر كل المُزامَنة تحت [DEVICE] و [ALL] — الفلتر بنيوياً صحيح وسيُشتغل
 * فور تعبئة الحقل دون أي تغيير هنا.
 */
enum class AccountFilter {
    ALL,
    DEVICE,
    GOOGLE,
    OTHER;

    fun matches(contact: Contact): Boolean {
        val accountType = contact.account?.accountType
        return when (this) {
            ALL -> true
            DEVICE -> accountType == null ||
                accountType.contains("local", ignoreCase = true) ||
                accountType.contains("device", ignoreCase = true)
            GOOGLE -> accountType?.contains("google", ignoreCase = true) == true
            OTHER -> accountType != null &&
                !accountType.contains("google", ignoreCase = true) &&
                !accountType.contains("local", ignoreCase = true) &&
                !accountType.contains("device", ignoreCase = true)
        }
    }
}
