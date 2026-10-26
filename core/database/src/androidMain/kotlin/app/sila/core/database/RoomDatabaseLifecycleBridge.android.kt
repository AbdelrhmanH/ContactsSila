package app.sila.core.database

import androidx.room.RoomDatabase

actual abstract class RoomDatabaseLifecycleBridge actual constructor() : RoomDatabase() {
    /**
     * عقد Room على Android يفرض تنفيذ clearAllTables. الجداول في هذه الوحدة تُدار عبر اتصال
     * BundledSQLiteDriver المستقل في LocalSilaDatabase، وليس عبر قاعدة Room المُولَّدة،
     * فلا جداول مُدارة من Room لإفراغها.
     */
    @Suppress("EXPECT_ACTUAL_INCOMPATIBLE_MODALITY")
    override fun clearAllTables(): Unit = Unit
}
