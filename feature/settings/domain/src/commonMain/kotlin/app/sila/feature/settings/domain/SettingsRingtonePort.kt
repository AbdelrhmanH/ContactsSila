package app.sila.feature.settings.domain

import app.sila.core.model.Category

/**
 * بوابة نطاق ضيقة لعمليات نغمات التصنيفات (تذكرة #66 · ADR-0014) — قراءة النغمات المتاحة
 * من المنصة وكتابة نغمة تصنيف (تُطبَّق على الأعضاء غير الحاملين نغمة فردية) عبر
 * [SetCategoryRingtoneUseCase] — مسار واحد حصراً من [app.sila.feature.settings.presentation.SettingsViewModel].
 */
interface SettingsRingtonePort {
    /** النغمات المتاحة من المنصة — قائمة فارغة حين لا يوجد مصدر منصوي مسجلاً (كما في الاختبارات). */
    suspend fun availableRingtones(): List<RingtoneItem>

    /** تعيين نغمة تصنيف وتطبيقها على أعضائه غير الحاملين نغمة فردية (PRD §11). */
    suspend fun setCategoryRingtone(category: Category, uri: String?)
}

/**
 * تنفيذ البوابة: [SetCategoryRingtoneUseCase] يحتاج مصدراً منصوياً؛ بغيابه يبقى السلوك
 * السابق المُحكم: كتابة التفضيل عبر المستودع دون تطبيق منصوي (التطبيق لا معنى له بلا مصدر).
 */
class SettingsRingtonePortImpl(
    private val repository: SettingsRepository,
    private val platformRingtoneSource: PlatformRingtoneSource?
) : SettingsRingtonePort {
    private val setCategoryRingtoneUseCase = platformRingtoneSource?.let {
        SetCategoryRingtoneUseCase(repository, it)
    }

    override suspend fun availableRingtones(): List<RingtoneItem> =
        platformRingtoneSource?.getAvailableRingtones() ?: emptyList()

    override suspend fun setCategoryRingtone(category: Category, uri: String?) {
        if (setCategoryRingtoneUseCase != null) {
            setCategoryRingtoneUseCase(category, uri)
        } else {
            repository.setCategoryRingtone(category, uri)
        }
    }
}
