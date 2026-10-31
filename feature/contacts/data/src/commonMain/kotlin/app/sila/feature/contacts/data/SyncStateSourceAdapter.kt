package app.sila.feature.contacts.data

import app.sila.feature.contacts.domain.ContactsSyncState
import app.sila.feature.contacts.domain.SyncStateSource
import kotlinx.coroutines.flow.Flow

/** تكييف حالة محرك المزامنة لعقد النطاق [SyncStateSource] (DIP — النطاق لا يعرف المحرك). */
class SyncStateSourceAdapter(private val engine: ContactsSyncEngine) : SyncStateSource {
    override fun observeSyncState(): Flow<ContactsSyncState> = engine.state
}
