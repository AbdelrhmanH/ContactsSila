package app.sila.feature.arham.data

import app.sila.core.database.dao.TopFiveMaterializationDao
import app.sila.core.database.dao.TopFiveMaterializationUpdate
import app.sila.core.datastore.UserPreferencesDataSource
import app.sila.core.model.Contact
import app.sila.core.model.TopFiveSelection
import app.sila.feature.arham.domain.TopFiveMaterializationRepository
import app.sila.feature.arham.domain.TopFiveMaterializationTarget
import app.sila.feature.arham.domain.TopFiveMaterializationWriteCounts
import kotlinx.coroutines.flow.first

/**
 * تنفيذ مستودع تجسيد «أهم 5 أشخاص» (طبقة Data داخل :feature:arham:data — T8-04 · Issue #29).
 *
 * يعمل محلياً 100% (ADR-0007): الانتقاء من [UserPreferencesDataSource] (الـ SSOT للقائمة
 * المعلّقة والعلامة الواحدة — §F.1/§F.10)، والكتابة فوق [TopFiveMaterializationDao] حصراً
 * (الواجهة تنفّذها معاملة IMMEDIATE واحدة — §F.8/§F.9). لا شبكة ولا اعتماد على
 * `:feature:contacts` إطلاقاً.
 *
 * أنواع النطاق والأنواع المرآتية في `:core:database` (بدون Room/منصة في Domain — Konsist)
 * تُربط هنا حصراً: `materialized` في نتيجة الواجهة = `applied` في أعداد النطاق.
 */
class TopFiveMaterializationRepositoryImpl(
    private val dao: TopFiveMaterializationDao,
    private val userPreferences: UserPreferencesDataSource,
) : TopFiveMaterializationRepository {

    override suspend fun isMaterialized(): Boolean =
        userPreferences.preferences.first().topFiveMaterialized

    override suspend fun markMaterialized() {
        userPreferences.setTopFiveMaterialized(materialized = true)
    }

    override suspend fun pendingSelections(): List<TopFiveSelection> =
        userPreferences.preferences.first().onboardingTopFiveSelections

    override suspend fun hasLiveContacts(): Boolean = dao.hasLiveContacts()

    override suspend fun findContactsBySystemContactIds(
        systemContactIds: Set<String>,
    ): List<Contact> = dao.findContactsBySystemContactIds(systemContactIds)

    override suspend fun applyMaterialization(
        targets: List<TopFiveMaterializationTarget>,
    ): TopFiveMaterializationWriteCounts {
        val result = dao.applyTopFiveMaterialization(
            updates = targets.map { target ->
                TopFiveMaterializationUpdate(contactId = target.contactId, arham = target.arham)
            },
        )
        return TopFiveMaterializationWriteCounts(
            applied = result.materialized,
            preservedUserOwned = result.preservedUserOwned,
            skippedMissing = result.skippedMissing,
        )
    }
}
