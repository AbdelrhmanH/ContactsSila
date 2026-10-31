package app.sila.core.database

import androidx.room.Database
import androidx.room.TypeConverters
import app.sila.core.database.dao.ArhamDao
import app.sila.core.database.dao.ContactDao
import app.sila.core.database.dao.ContactsSyncDao
import app.sila.core.database.dao.DataRetentionCleanupDao
import app.sila.core.database.dao.ServiceDao
import app.sila.core.database.entity.ArhamEntity
import app.sila.core.database.entity.ArhamInboxEntity
import app.sila.core.database.entity.CallEventEntity
import app.sila.core.database.entity.ContactEntity
import app.sila.core.database.entity.ContactPhoneEntity
import app.sila.core.database.entity.ContactsSyncCheckpointEntity
import app.sila.core.database.entity.DisputeLogEntity
import app.sila.core.database.entity.EmergencyRingingSessionEntity
import app.sila.core.database.entity.ScheduledMessageEntity
import app.sila.core.database.entity.ServiceEntity

/**
 * قاعدة بيانات Room KMP المركزية لتطبيق صِلَة (Single Source of Truth — ARCHITECTURE.md §5 & DATA_MODEL.md §2).
 * تعمل عبر محرك BundledSQLiteDriver الموحد بين Android و iOS.
 *
 * الإصدار 2 (Issue #23 · ADR-0010): إضافة جدول نقاط استئناف المزامنة `contacts_sync_checkpoints`
 * وأعمدة تكافؤ الكيان الكامل + tombstone `systemDeletedAt` على `contacts` وفهرس FTS البحث.
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
        EmergencyRingingSessionEntity::class,
        ContactsSyncCheckpointEntity::class
    ],
    version = 2,
    exportSchema = true
)
@TypeConverters(SilaTypeConverters::class)
abstract class SilaRoomDatabase : RoomDatabaseLifecycleBridge() {
    abstract fun contactDao(): ContactDao
    abstract fun arhamDao(): ArhamDao
    abstract fun serviceDao(): ServiceDao
    abstract fun dataRetentionCleanupDao(): DataRetentionCleanupDao
    abstract fun contactsSyncDao(): ContactsSyncDao
}
