package app.sila.feature.contacts.presentation

import app.sila.core.model.ArhamFrequency
import app.sila.core.model.CallDirection
import app.sila.core.model.Category

/**
 * نصوص شاشة تفاصيل جهة الاتصال (T6 · Issue #27) — عقد بلا Compose: يُموَّل في `sharedUI`
 * من موارد الترجمة بنمط [ContactsStrings] المجرَّب (لا نصوص ثابتة في الكود — AGENTS.md).
 * القوالب ذات العدد تستخدم `%d`/`%s` وتُعوَّض يدوياً في `commonMain`.
 */
interface ContactDetailsStrings {
    // شريط التطبيق
    val backDescription: String
    val editDescription: String
    val favoriteDescription: String
    val unfavoriteDescription: String
    val moreOptionsDescription: String

    // الأزرار السريعة
    val quickActionCall: String
    val quickActionMessage: String
    val quickActionVideo: String
    val quickActionEmail: String

    // الصفوف
    val primaryPhone: String
    val copyNumber: String
    val phonesSection: String
    val appsSection: String
    val arhamSection: String
    val historySection: String
    val historyViewAll: String
    val emailsSection: String
    val addressesSection: String
    val datesSection: String
    val companySection: String
    val notesSection: String
    val customFieldsSection: String
    val websitesSection: String
    val accountSection: String

    /** اسم حساب الجهاز المحلي عند غياب `accountName` (نمط iOS `details_account_device`). */
    val accountDevice: String

    // أسماء الأنواع (هواتف/بريد — قيم String في النموذج، تُترجم هنا)
    val phoneTypeName: (type: String) -> String
    val emailTypeName: (type: String) -> String
    val addressTypeName: (type: String) -> String
    val dateTypeName: (type: String) -> String

    // التطبيقات المتصلة (معرفات مستقرة من ConnectedApp)
    val connectedAppName: (id: String) -> String

    // صف أرحام
    val arhamFrequencyLabel: (frequency: ArhamFrequency) -> String
    val arhamDaysSinceLastContact: (days: Long) -> String

    // السجل — تنسيق زمني منصوي في sharedUI
    val formatInteractionTime: (timestampMillis: Long) -> String
    val formatDurationSeconds: (seconds: Int) -> String
    val interactionMissed: String

    // اتجاهات السجل (وصف الأيقونة)
    val directionLabel: (direction: CallDirection) -> String

    // نافذة التراجع (J-5)
    val deleteUndoMessage: String
    val undo: String

    // Sheet الأفعال
    val actionShare: String
    val actionChangeCategory: String
    val actionDelete: String
    val deleteUndoHint: String

    // تصنيف (Sheet تغيير التصنيف)
    val categoryTitle: (category: Category) -> String

    // قرابة أرحام في شريحة التصنيف (الخرائط النصية للعلاقات في sharedUI — T8 يملك التحرير)
    val relationName: (relation: app.sila.core.model.Relation) -> String

    // حالات
    val loading: String
    val notFoundMessage: String
    val deleteError: String
    val noPhoneNumber: String
    val cancel: String
}
