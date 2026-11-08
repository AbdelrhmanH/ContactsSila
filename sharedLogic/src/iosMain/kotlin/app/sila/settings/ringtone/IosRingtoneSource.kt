package app.sila.settings.ringtone

import app.sila.core.model.Category
import app.sila.feature.settings.domain.PlatformRingtoneSource
import app.sila.feature.settings.domain.RingtoneItem

/**
 * تنفيذ مصدر نغمات الرنين لنظام iOS (PRD §11 و 07-ringtones-preferences.md).
 * iOS لا يسمح بالكتابة البرمجية للنغمات في دفتر الهاتف —
 * يُعرض شرح للمستخدم ويوجه لـ CNContactViewController.
 */
class IosRingtoneSource : PlatformRingtoneSource {

    override suspend fun applyCategoryRingtone(category: Category, ringtoneUri: String?) {
        // iOS does not permit programmatic ringtone writes
    }

    override fun getSystemDefaultRingtoneUri(): String = "system_default"

    override fun getAvailableRingtones(): List<RingtoneItem> = listOf(
        RingtoneItem("System Default", "system_default")
    )
}
