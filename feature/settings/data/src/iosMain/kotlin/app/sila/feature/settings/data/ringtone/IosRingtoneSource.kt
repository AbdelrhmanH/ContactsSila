package app.sila.feature.settings.data.ringtone

import app.sila.core.model.Category
import app.sila.feature.settings.domain.PlatformRingtoneSource
import app.sila.feature.settings.domain.RingtoneItem

/**
 * تنفيذ مصدر نغمات الرنين لنظام iOS (PRD §11 و 07-ringtones-preferences.md).
 * على منصة iOS، تُدار نغمات الرنين عبر تطبيق جهات اتصال آبل أو إعدادات النظام مباشرة.
 * يقدّم هذا الصنف استجابة آمنة وتوجيهاً للمستخدم دون أي انهيارات.
 */
class IosRingtoneSource : PlatformRingtoneSource {

    override suspend fun applyCategoryRingtone(category: Category, ringtoneUri: String?) {
        // Safe no-op on iOS: iOS sandbox does not allow programmatic custom ringtone overrides
    }

    override fun getSystemDefaultRingtoneUri(): String = "system://default"

    override fun getAvailableRingtones(): List<RingtoneItem> = emptyList()
}
