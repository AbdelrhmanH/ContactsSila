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
import app.sila.core.database.dao.EmergencyRingingSessionEntityDao
import app.sila.core.database.dao.ServiceEntityDao
import app.sila.core.database.entity.ArhamEntity
import app.sila.core.database.entity.ArhamInboxEntity
import app.sila.core.database.entity.CallEventEntity
import app.sila.core.database.entity.ContactAddressEntity
import app.sila.core.database.entity.ContactCustomFieldEntity
import app.sila.core.database.entity.ContactDateEntity
import app.sila.core.database.entity.ContactEmailEntity
import app.sila.core.database.entity.ContactEntity
import app.sila.core.database.entity.ContactMergeSnapshotEntity
import app.sila.core.database.entity.ContactPhoneEntity
import app.sila.core.database.entity.ContactsSyncCheckpointEntity
import app.sila.core.database.entity.ContactWebsiteEntity
import app.sila.core.database.entity.DisputeLogEntity
import app.sila.core.database.entity.DuplicateDismissalEntity
import app.sila.core.database.entity.EmergencyRingingSessionEntity
import app.sila.core.database.entity.ServiceEntity
import app.sila.core.database.entity.SmartFilterFeedbackEntity

/**
 * إصدار مخطط Room الحالي — مصدر الحقيقة الوحيد للإصدار: يُستهلك في `@Database`
 * وفي التحقق اللاحق للاعتماد الآمن (`PRAGMA user_version`) حرفاً — أي ترقية مستقبلي
 * يعدّل هذا الثابت حصراً (T6 · Issue #27 · ADR-0011/0012).
 *
 * v3 (T8-02 · Issue #29): توسعة `call_events` لحقول ADR-0006 السبعة عشر (إعادة بناء
 * ونسخ عبر [MIGRATION_2_3]) + جدول جلسات الاتصال الطارئ (قرار D4 المُصادق — §W).
 *
 * v4 (T7 · Issue #28): جداول الدمج الثلاثة عبر [MIGRATION_3_4] بمصدر DDL وحيد
 * [MergeTablesDdl] (نفس نمط الشيم المزدوج) — `contact_merge_snapshots` (لقطات ما-قبل-الدمج
 * القفلية في Room — ADR-0004)، `duplicate_dismissals` (استبعاد أزواج «ليست مكررات» —
 * محجوز للميزة في ADR-0012 §9)، و`smart_filter_feedback` (التعلّم المحلي — spec 04 §ب).
 */
const val SILA_ROOM_VERSION: Int = 4

/**
 * قاعدة بيانات Room 3 KMP المركزية لتطبيق صِلَة — **صاحبة المخطط الوحيدة**
 * (Single Source of Truth — ARCHITECTURE.md §5 · DATA_MODEL.md §2 · ADR-0011).
 *
 * PR B (F-04/F-05): هذه الفئة لم تعد شلاً تجميعياً — هي المنتِج الفعلي والوحيد
 * لجداول الإنتاج ومخططها عبر KSP (`SilaRoomDatabase_Impl`)، بمحرك
 * `BundledSQLiteDriver` على أندرويد و iOS معاً، والإصدار الفعلي [SILA_ROOM_VERSION]
 * (قواعد المستخدمين القائمة من v1 تُرحَّل عبر `MIGRATION_1_2` — T6/Issue #27؛ وقواعد
 * v2 عبر `MIGRATION_2_3` — T8-02/Issue #29؛
 * قواعد `user_version = 0` القديمة تُعتمَد عبر مسار create/validate المُوثَّق
 * في ADR-0011 — ليس `Migration(0,1)`).
 *
 * الكيانات الأربعة عشر (جداول فعلية مُدارة) — بلا كيانات شبحية:
 * `scheduled_messages` أُزيل بعد التحقق من انعدام مستهلكيه الفعليين (Decision 5 ·
 * ADR-0011). خمسة جداول حقول قياسية أُضيفت في v2
 * (`contact_emails`/`contact_addresses`/`contact_dates`/`contact_custom_fields`/
 * `contact_websites` — ترحيل غير هدمي MIGRATION_1_2 · ADR-0012). وفي v3 عاد
 * `emergency_ringing_sessions` كياناً فعلياً بنموذج بيانات أدنى مُصادقاً (قرار D4 —
 * §W؛ ليس الكيان الشبحي القديم — T8-02/Issue #29).
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
    version = SILA_ROOM_VERSION,
    exportSchema = true,
    entities = [
        ContactEntity::class,
        ContactPhoneEntity::class,
        ContactEmailEntity::class,
        ContactAddressEntity::class,
        ContactDateEntity::class,
        ContactCustomFieldEntity::class,
        ContactWebsiteEntity::class,
        ArhamEntity::class,
        ServiceEntity::class,
        CallEventEntity::class,
        EmergencyRingingSessionEntity::class,
        ArhamInboxEntity::class,
        DisputeLogEntity::class,
        ContactsSyncCheckpointEntity::class,
        ContactMergeSnapshotEntity::class,
        DuplicateDismissalEntity::class,
        SmartFilterFeedbackEntity::class,
    ],
)
@ColumnTypeConverters(SilaTypeConverters::class)
@ConstructedBy(SilaRoomDatabaseConstructor::class)
abstract class SilaRoomDatabase : RoomDatabase() {

    abstract fun contactEntityDao(): ContactEntityDao

    abstract fun arhamEntityDao(): ArhamEntityDao

    abstract fun serviceEntityDao(): ServiceEntityDao

    abstract fun callEventEntityDao(): CallEventEntityDao

    abstract fun emergencySessionEntityDao(): EmergencyRingingSessionEntityDao

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
