package app.sila.feature.settings.domain

import app.sila.core.model.Category

/**
 * عنصر نغمة رنين للنظام أو ملف مخصص.
 */
data class RingtoneItem(
    val title: String,
    val uri: String
)

/**
 * عقد المنصة للتعامل مع النغمات (PRD §11 و 07-ringtones-preferences.md).
 * - على أندرويد: يطبق نغمة التصنيف على الأعضاء الذين ليس لديهم نغمة فردية عبر Worker.
 * - على iOS: يعرض شرحاً ويوجه لـ CNContactViewController بدون محاولة كتابة برمجية غير مدعومة.
 */
interface PlatformRingtoneSource {
    suspend fun applyCategoryRingtone(category: Category, ringtoneUri: String?)
    fun getSystemDefaultRingtoneUri(): String
    fun getAvailableRingtones(): List<RingtoneItem>
}
