package app.sila.core.database

import androidx.room3.ConstructedBy
import androidx.room3.ColumnTypeConverters
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import app.sila.core.database.dao.ArhamEntityDao
import app.sila.core.database.dao.ArhamInboxEntityDao
import app.sila.core.database.dao.CallEventEntityDao
import app.sila.core.database.dao.ContactEntityDao
import app.sila.core.database.dao.ContactsSyncCheckpointEntityDao
import app.sila.core.database.dao.DisputeLogEntityDao
import app.sila.core.database.dao.ServiceEntityDao
import app.sila.core.database.entity.ArhamEntity
import app.sila.core.database.entity.ArhamInboxEntity
import app.sila.core.database.entity.CallEventEntity
import app.sila.core.database.entity.ContactEntity
import app.sila.core.database.entity.ContactPhoneEntity
import app.sila.core.database.entity.ContactsSyncCheckpointEntity
import app.sila.core.database.entity.DisputeLogEntity
import app.sila.core.database.entity.ServiceEntity

/**
 * قاعدة بيانات Room 3 KMP المركزية لتطبيق صِلَة — **صاحبة المخطط الوحيدة**
 * (Single Source of Truth — ARCHITECTURE.md §5 · DATA_MODEL.md §2 · ADR-0011).
 *
 * PR B (F-04/F-05): هذه الفئة لم تعد شلاً تجميعياً — هي المنتِج الفعلي والوحيد
 * لجداول الإنتاج ومخططها عبر KSP (`SilaRoomDatabase_Impl`)، بمحرك
 * `BundledSQLiteDriver` على أندرويد و iOS معاً، والإصدار الأول الفعلي `version = 1`
 * (قواعد المستخدمين القائمة تُعتمَد من `user_version = 0` عبر مسار
 * create/validate المُوثَّق في ADR-0011 — ليس `Migration(0,1)`).
 *
 * الكيانات الثمانية (جداول فعلية مُدارة) — بلا كيانات شبحية:
 * `scheduled_messages` و`emergency_ringing_sessions` أُزيلتا بعد التحقق من انعدام
 * مستهلكيها الفعليين (Decision 5 · ADR-0011).
 *
 * جدول `contacts_fts` (FTS4 مستقل) **ليس كياناً هنا عمداً**: KSP على أهداف Native
 * (iOS) لا يعالج كيانات `@Fts4` المستقلة — `FtsTableEntityProcessor` يفشل في حلّ
 * القيمة الافتراضية لـ `contentEntity` من klib فيرمي «Cannot find external content
 * entity class» (خلل Room 3.0.3 موثق — أندرويد/JVM غير متأثرَين، ADR-0011 §FTS4).
 * يُنشأ الجدول عبر `RoomDatabase.Callback` في الواجهة الخارجية بـ DDL تراثي حرفي
 * واحد ([app.sila.core.database.legacy.LegacyFtsDdl]) ويُزامن يدوياً عبر SQL خام —
 * الدلالات الفيزيائية نفسها: مستقل بلا `content=`، توكنِزِر SIMPLE، `docid == contacts.id`.
 */
@Database(
    version = 1,
    exportSchema = true,
    entities = [
        ContactEntity::class,
        ContactPhoneEntity::class,
        ArhamEntity::class,
        ServiceEntity::class,
        CallEventEntity::class,
        ArhamInboxEntity::class,
        DisputeLogEntity::class,
        ContactsSyncCheckpointEntity::class,
    ],
)
@ColumnTypeConverters(SilaTypeConverters::class)
@ConstructedBy(SilaRoomDatabaseConstructor::class)
abstract class SilaRoomDatabase : RoomDatabase() {

    abstract fun contactEntityDao(): ContactEntityDao

    abstract fun arhamEntityDao(): ArhamEntityDao

    abstract fun serviceEntityDao(): ServiceEntityDao

    abstract fun callEventEntityDao(): CallEventEntityDao

    abstract fun arhamInboxEntityDao(): ArhamInboxEntityDao

    abstract fun disputeLogEntityDao(): DisputeLogEntityDao

    abstract fun contactsSyncCheckpointEntityDao(): ContactsSyncCheckpointEntityDao
}

/**
 * مُنشئ القاعدة عبر المنصات — النمط الرسمي لـ Room KMP: يولّد KSP فعلياً
 * `SilaRoomDatabase_Impl` المطبِّق للواجهة في كل منصة (Android عبر الانعكاس،
 * Native عبر الرمز المولَّد)، ولا يكتب المستخدم أي actual يدوي.
 */
@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object SilaRoomDatabaseConstructor : RoomDatabaseConstructor<SilaRoomDatabase>
