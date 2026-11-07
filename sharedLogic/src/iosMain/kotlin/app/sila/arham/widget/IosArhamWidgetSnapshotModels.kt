package app.sila.arham.widget

import app.sila.core.model.ArhamStatus
import app.sila.core.model.Relation
import app.sila.feature.arham.domain.ArhamWidgetEntry
import app.sila.feature.arham.domain.ArhamWidgetSnapshot
import kotlinx.serialization.Serializable

/**
 * تمثيل قابل للتسلسل للقطة الودجت (T8-13 · D6) — جسر بين نمط المجال النقي
 * [ArhamWidgetSnapshot] و JSON ملف حاوية App Group الذي تقرؤه إضافة الودجت (Swift/Codable).
 *
 * لماذا DTO في iosMain وليس `@Serializable` على نمط المجال؟ نمط المجال يبقى خالياً من
 * تفاصيل النقل (مرآة `IosArhamNotificationStrings` التي تُترجم محتوى المجال إلى منصة)،
 * ولا يتغير شكل JSON إلا بقرارٍ واعٍ هنا مع توافق حقل Swift `ArhamWidgetSnapshotReader`.
 *
 * **لا PII زائداً**: نفس حدود اللقطة المجالية — أسماء الظهور فقط، بلا أرقام (§D4-13).
 *
 * @property relation اسم نُمط `Relation` كنص (Swift يفكه كنص خام).
 * @property status اسم نُمط `ArhamStatus` كنص (OVERDUE/DUE_SOON/OK/PAUSED…).
 * @property generatedAtMillis زمن النشر بالألفية — لعبارة "آخر تحديث" في الودجت.
 */
@Serializable
internal data class IosArhamWidgetSnapshotDto(
    val overdueCount: Int,
    val entries: List<IosArhamWidgetEntryDto>,
    val totalFamilyCalls: Int,
    val longestCallSec: Int,
    val goalCompletionFraction: Float,
    val generatedAtMillis: Long,
)

/** مُدخل واحد في اللقطة — المرآة المسلسلة لـ [ArhamWidgetEntry]. */
@Serializable
internal data class IosArhamWidgetEntryDto(
    val contactId: Long,
    val displayName: String,
    val relation: String,
    val daysSinceLastContact: Int?,
    val status: String,
)

/** تحويل المجال ← DTO (للنشر). */
internal fun ArhamWidgetSnapshot.toDto(generatedAtMillis: Long): IosArhamWidgetSnapshotDto =
    IosArhamWidgetSnapshotDto(
        overdueCount = overdueCount,
        entries = entries.map { entry -> entry.toDto() },
        totalFamilyCalls = totalFamilyCalls,
        longestCallSec = longestCallSec,
        goalCompletionFraction = goalCompletionFraction,
        generatedAtMillis = generatedAtMillis,
    )

private fun ArhamWidgetEntry.toDto(): IosArhamWidgetEntryDto =
    IosArhamWidgetEntryDto(
        contactId = contactId,
        displayName = displayName,
        relation = relation.name,
        daysSinceLastContact = daysSinceLastContact,
        status = status.name,
    )

/** تحويل DTO ← المجال (لفكّ التسلسل في اختبارات الذهاب والإياب فقط). */
internal fun IosArhamWidgetSnapshotDto.toDomain(): ArhamWidgetSnapshot =
    ArhamWidgetSnapshot(
        overdueCount = overdueCount,
        entries = entries.map { entry -> entry.toDomain() },
        totalFamilyCalls = totalFamilyCalls,
        longestCallSec = longestCallSec,
        goalCompletionFraction = goalCompletionFraction,
    )

private fun IosArhamWidgetEntryDto.toDomain(): ArhamWidgetEntry =
    ArhamWidgetEntry(
        contactId = contactId,
        displayName = displayName,
        relation = Relation.valueOf(relation),
        daysSinceLastContact = daysSinceLastContact,
        status = ArhamStatus.valueOf(status),
    )
