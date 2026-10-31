package app.sila.feature.contacts.domain

import kotlinx.coroutines.flow.Flow

/**
 * مصدر حالة مزامنة جهات الاتصال للعرض (Issue #24 — حالة رفض الصلاحية/الخطأ).
 * عقد نطاق (DIP): النطاق لا يعرف [ContactsSyncEngine] — طبقة البيانات تُكيّف المحرك.
 */
fun interface SyncStateSource {
    fun observeSyncState(): Flow<ContactsSyncState>
}
