package app.sila.core.database

import androidx.room3.ColumnTypeConverter
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.CallDirection
import app.sila.core.model.CallSource
import app.sila.core.model.Category
import app.sila.core.model.DisputeStatus
import app.sila.core.model.KinshipLayer
import app.sila.core.model.MessageDirection
import app.sila.core.model.MessageStatus
import app.sila.core.model.Relation
import app.sila.core.model.ServiceVisibility

/**
 * محولات الأعمدة الخاصة بـ Room 3 KMP (DATA_MODEL.md §2 · ADR-0011).
 *
 * كل تعداد يُخزَّن كنص `name` مطابقةً لطريقة التخزين الفعلية في قواعد المستخدمين
 * القائمة (adoption) وقواعد التثبيت الجديدة على السواء.
 * (محولا `AutomationType`/`RingingStatus` تابعان للكيانتين الشبحتين المحذوفتين — ADR-0011.)
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
