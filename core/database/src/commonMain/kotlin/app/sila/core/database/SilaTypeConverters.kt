package app.sila.core.database

import androidx.room3.ColumnTypeConverter
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.CallAssociation
import app.sila.core.model.CallConfirmation
import app.sila.core.model.CallConfidence
import app.sila.core.model.CallDirection
import app.sila.core.model.CallOutcome
import app.sila.core.model.CallSource
import app.sila.core.model.Category
import app.sila.core.model.DisputeStatus
import app.sila.core.model.KinshipLayer
import app.sila.core.model.MessageDirection
import app.sila.core.model.MessageStatus
import app.sila.core.model.Relation
import app.sila.core.model.RingingStatus
import app.sila.core.model.ServiceVisibility

/**
 * محولات الأعمدة الخاصة بـ Room 3 KMP (DATA_MODEL.md §2 · ADR-0011).
 *
 * كل تعداد يُخزَّن كنص `name` مطابقةً لطريقة التخزين الفعلية في قواعد المستخدمين
 * القائمة (adoption) وقواعد التثبيت الجديدة على السواء. القراءة عبر `valueOf` حصراً —
 * نص مجهول يرمي (fail-closed — §H.4 من تقرير بوابة T8-02) ولا يُكبَر لقيمة مضللة.
 *
 * T8-02: محولات تعدادات حدث المكالمة الأربعة (v3 — ADR-0006)، محول مجموعة الأدلة،
 * ومحول `RingingStatus` المُسجَّل مجدداً legitimately لجدول جلسات الطوارئ (قرار D4 —
 * كان محوله تابعاً للكيان الشبحي المحذوف — ADR-0011).
 */
class SilaTypeConverters {

    @ColumnTypeConverter
    fun fromCategory(value: Category): String = value.name

    @ColumnTypeConverter
    fun toCategory(value: String): Category = Category.valueOf(value)

    @ColumnTypeConverter
    fun fromRelation(value: Relation): String = value.name

    @ColumnTypeConverter
    fun toRelation(value: String): Relation = Relation.valueOf(value)

    @ColumnTypeConverter
    fun fromKinshipLayer(value: KinshipLayer): String = value.name

    @ColumnTypeConverter
    fun toKinshipLayer(value: String): KinshipLayer = KinshipLayer.valueOf(value)

    @ColumnTypeConverter
    fun fromArhamFrequency(value: ArhamFrequency): String = value.name

    @ColumnTypeConverter
    fun toArhamFrequency(value: String): ArhamFrequency = ArhamFrequency.valueOf(value)

    @ColumnTypeConverter
    fun fromCallDirection(value: CallDirection): String = value.name

    @ColumnTypeConverter
    fun toCallDirection(value: String): CallDirection = CallDirection.valueOf(value)

    @ColumnTypeConverter
    fun fromCallSource(value: CallSource): String = value.name

    @ColumnTypeConverter
    fun toCallSource(value: String): CallSource = CallSource.valueOf(value)

    @ColumnTypeConverter
    fun fromCallOutcome(value: CallOutcome): String = value.name

    @ColumnTypeConverter
    fun toCallOutcome(value: String): CallOutcome = CallOutcome.valueOf(value)

    @ColumnTypeConverter
    fun fromCallAssociation(value: CallAssociation): String = value.name

    @ColumnTypeConverter
    fun toCallAssociation(value: String): CallAssociation = CallAssociation.valueOf(value)

    @ColumnTypeConverter
    fun fromCallConfirmation(value: CallConfirmation): String = value.name

    @ColumnTypeConverter
    fun toCallConfirmation(value: String): CallConfirmation = CallConfirmation.valueOf(value)

    @ColumnTypeConverter
    fun fromCallConfidence(value: CallConfidence): String = value.name

    @ColumnTypeConverter
    fun toCallConfidence(value: String): CallConfidence = CallConfidence.valueOf(value)

    /** مجموعة الأدلة → نص مفصول بفواصل بترتيب `name` المرتب (لا تكون فارغة أبداً). */
    @ColumnTypeConverter
    fun fromEvidenceSources(value: Set<CallSource>): String =
        value.map { it.name }.sorted().joinToString(separator = ",")

    @ColumnTypeConverter
    fun toEvidenceSources(value: String): Set<CallSource> =
        value.split(',').filter { it.isNotEmpty() }.map { CallSource.valueOf(it) }.toSet()

    /** إعادة تسجيل مشروعة لجدول جلسات الطوارئ (قرار D4 — T8-02). */
    @ColumnTypeConverter
    fun fromRingingStatus(value: RingingStatus): String = value.name

    @ColumnTypeConverter
    fun toRingingStatus(value: String): RingingStatus = RingingStatus.valueOf(value)

    @ColumnTypeConverter
    fun fromServiceVisibility(value: ServiceVisibility): String = value.name

    @ColumnTypeConverter
    fun toServiceVisibility(value: String): ServiceVisibility = ServiceVisibility.valueOf(value)

    @ColumnTypeConverter
    fun fromDisputeStatus(value: DisputeStatus): String = value.name

    @ColumnTypeConverter
    fun toDisputeStatus(value: String): DisputeStatus = DisputeStatus.valueOf(value)

    @ColumnTypeConverter
    fun fromMessageDirection(value: MessageDirection): String = value.name

    @ColumnTypeConverter
    fun toMessageDirection(value: String): MessageDirection = MessageDirection.valueOf(value)

    @ColumnTypeConverter
    fun fromMessageStatus(value: MessageStatus): String = value.name

    @ColumnTypeConverter
    fun toMessageStatus(value: String): MessageStatus = MessageStatus.valueOf(value)
}
