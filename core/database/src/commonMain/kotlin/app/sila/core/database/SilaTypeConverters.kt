package app.sila.core.database

import androidx.room.TypeConverter
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.AutomationType
import app.sila.core.model.CallDirection
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
 * محولات الأنواع الخاصة بـ Room KMP (DATA_MODEL.md §2).
 */
class SilaTypeConverters {

    @TypeConverter
    fun fromCategory(value: Category): String = value.name

    @TypeConverter
    fun toCategory(value: String): Category = Category.valueOf(value)

    @TypeConverter
    fun fromRelation(value: Relation): String = value.name

    @TypeConverter
    fun toRelation(value: String): Relation = Relation.valueOf(value)

    @TypeConverter
    fun fromKinshipLayer(value: KinshipLayer): String = value.name

    @TypeConverter
    fun toKinshipLayer(value: String): KinshipLayer = KinshipLayer.valueOf(value)

    @TypeConverter
    fun fromArhamFrequency(value: ArhamFrequency): String = value.name

    @TypeConverter
    fun toArhamFrequency(value: String): ArhamFrequency = ArhamFrequency.valueOf(value)

    @TypeConverter
    fun fromCallDirection(value: CallDirection): String = value.name

    @TypeConverter
    fun toCallDirection(value: String): CallDirection = CallDirection.valueOf(value)

    @TypeConverter
    fun fromCallSource(value: CallSource): String = value.name

    @TypeConverter
    fun toCallSource(value: String): CallSource = CallSource.valueOf(value)

    @TypeConverter
    fun fromServiceVisibility(value: ServiceVisibility): String = value.name

    @TypeConverter
    fun toServiceVisibility(value: String): ServiceVisibility = ServiceVisibility.valueOf(value)

    @TypeConverter
    fun fromDisputeStatus(value: DisputeStatus): String = value.name

    @TypeConverter
    fun toDisputeStatus(value: String): DisputeStatus = DisputeStatus.valueOf(value)

    @TypeConverter
    fun fromAutomationType(value: AutomationType): String = value.name

    @TypeConverter
    fun toAutomationType(value: String): AutomationType = AutomationType.valueOf(value)

    @TypeConverter
    fun fromMessageDirection(value: MessageDirection): String = value.name

    @TypeConverter
    fun toMessageDirection(value: String): MessageDirection = MessageDirection.valueOf(value)

    @TypeConverter
    fun fromMessageStatus(value: MessageStatus): String = value.name

    @TypeConverter
    fun toMessageStatus(value: String): MessageStatus = MessageStatus.valueOf(value)

    @TypeConverter
    fun fromRingingStatus(value: RingingStatus): String = value.name

    @TypeConverter
    fun toRingingStatus(value: String): RingingStatus = RingingStatus.valueOf(value)
}
