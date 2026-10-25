package app.sila.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import app.sila.core.database.dao.ArhamDao
import app.sila.core.database.dao.ContactDao
import app.sila.core.database.dao.DataRetentionCleanupDao
import app.sila.core.database.dao.ServiceDao
import app.sila.core.database.entity.ArhamEntity
import app.sila.core.database.entity.ArhamInboxEntity
import app.sila.core.database.entity.CallEventEntity
import app.sila.core.database.entity.ContactEntity
import app.sila.core.database.entity.ContactPhoneEntity
import app.sila.core.database.entity.DisputeLogEntity
import app.sila.core.database.entity.EmergencyRingingSessionEntity
import app.sila.core.database.entity.ScheduledMessageEntity
import app.sila.core.database.entity.ServiceEntity

/**
 * قاعدة بيانات Room KMP المركزية لتطبيق صِلَة (Single Source of Truth — ARCHITECTURE.md §5 & DATA_MODEL.md §2).
 * تعمل عبر محرك BundledSQLiteDriver الموحد بين Android و iOS.
 */
@Database(
    entities = [
        ContactEntity::class,
        ContactPhoneEntity::class,
        ArhamEntity::class,
        CallEventEntity::class,
        ServiceEntity::class,
        DisputeLogEntity::class,
        ScheduledMessageEntity::class,
        ArhamInboxEntity::class,
        EmergencyRingingSessionEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(SilaTypeConverters::class)
abstract class SilaRoomDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun arhamDao(): ArhamDao
    abstract fun serviceDao(): ServiceDao
    abstract fun dataRetentionCleanupDao(): DataRetentionCleanupDao
}
