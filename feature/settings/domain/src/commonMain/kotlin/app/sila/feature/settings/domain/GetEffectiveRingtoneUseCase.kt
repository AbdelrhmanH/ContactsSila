package app.sila.feature.settings.domain

import app.sila.core.model.Category
import kotlinx.coroutines.flow.first

/**
 * يحدد نغمة الرنين الفعالة لجهة اتصال طبقاً لأسبقية PRD §11 و 07-ringtones-preferences.md:
 * نغمة جهة الاتصال الفردية > نغمة التصنيف > نغمة النظام الافتراضية.
 */
class GetEffectiveRingtoneUseCase(
    private val repository: SettingsRepository,
    private val platformRingtoneSource: PlatformRingtoneSource
) {
    suspend operator fun invoke(
        individualRingtoneUri: String?,
        category: Category
    ): String {
        // 1) الأولوية الأولى: نغمة جهة الاتصال الفردية إن وُجدت
        if (!individualRingtoneUri.isNullOrBlank()) {
            return individualRingtoneUri
        }

        // 2) الأولوية الثانية: نغمة التصنيف المسجلة في التفضيلات
        val prefs = repository.preferences.first()
        val categoryRingtone = prefs.categoryRingtones[category]
        if (!categoryRingtone.isNullOrBlank()) {
            return categoryRingtone
        }

        // 3) الأولوية الثالثة: نغمة النظام الافتراضية
        return platformRingtoneSource.getSystemDefaultRingtoneUri()
    }
}
