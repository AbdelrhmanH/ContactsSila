package app.sila.core.database

import androidx.room.RoomDatabase

/**
 * جسر دورة حياة Room عبر المنصات (ADR-0008).
 * Room KMP يعلن `clearAllTables()` مجردة على Android فقط وغير موجودة على iOS،
 * لذا تُدار هذه العضوية لكل منصة عبر expect/actual.
 * أما إغلاق القاعدة فيُنفَّذ عبر `LocalSilaDatabase.closeConnection()`
 * لأن `close()` في `RoomDatabase` نهائية على iOS.
 */
expect abstract class RoomDatabaseLifecycleBridge : RoomDatabase {
    constructor()
}
