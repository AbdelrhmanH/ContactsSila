package app.sila.feature.contacts.domain

import app.sila.core.common.AppClock
import app.sila.core.common.AppResult
import app.sila.core.common.DataError
import app.sila.core.model.Contact
import kotlinx.coroutines.CancellationException

/**
 * دمج مجموعة مكررات مراجَعة (T7 · Issue #28 — spec 04 §أ) — عهد J-1 ملزم بلا استثناء
 * (ADR-0012 §1):
 *
 * 1. **التخطيط النقي**: [MergePlanner.plan] يُنتج الناجي (الأقدم إنشاءً) والاتحاد الكامل.
 * 2. **المنصة أولاً**: [ContactsMergeWriter.mergeContacts] — فشلها ينهي العملية ولا يُمس
 *    Room إطلاقاً. الناتج يحمل **الهوية الموحدة بعد التجميع** فيُهاجر صف الناجي إليها
 *    داخل المعاملة (agy F2c — المزود قد يختار مفتاح الممتص مفتاحاً موحداً).
 * 3. **معاملة Room واحدة**: اللقطة (حمولتها تُبنى داخل المعاملة من المراجع المكتشفة
 *    فيها — بلا سباق) + صف الناجي + إعادة التوجيه + قبور الدمج. فشلها = خطأ صادق،
 *    وشبكة الأمان محرك المزامنة (تعويض الجلسة — ADR-0012 §4).
 * 4. vCard كل ممتص يُولَّد نطاقياً ([VCardGenerator]) ويُخزَّن **داخل** اللقطة — بلا
 *    ملفات على القرص (agy F3a) — ويعاد إنشاؤه منصوياً عند التراجع على iOS.
 *
 * معرّف اللقطة/المجموعة = اشتقاق بطاقة الاقتراض الثابت نفسه — التراجع خلال جلسة الشاشة
 * (المواصفة: «التراجع خلال جلسة الشاشة») يُدار بمالك الجلسة في الـ ViewModel، و[SESSION_SNAPSHOT_EXPIRY_MILLIS]
 * حد أمان للاحتفاظ فقط.
 */
class MergeContactsUseCase(
    private val mergeWriter: ContactsMergeWriter,
    private val mergeStore: ContactsMergeStore,
    private val clock: AppClock,
) {

    suspend operator fun invoke(group: List<Contact>): AppResult<MergeCommit> {
        val plan = MergePlanner.plan(group)
        val now = clock.nowEpochMillis()

        // J-1 خطوة 2: المنصة أولاً — فشلها ينهي العملية بلا مساس Room.
        val platform = when (val result =
            mergeWriter.mergeContacts(PlatformMergeRequest(survivor = plan.merged, absorbed = plan.absorbed))
        ) {
            is AppResult.Error -> return result
            is AppResult.Success -> result.data
        }
        // هويات raw الممتصين المسجَّلة في اللقطة للمسح المؤجل (agy F3b — «snapshot-recorded raw ids»)
        val absorbedRawIds = platform.absorbedRawIds
        val migratedSurvivor = plan.merged.copy(
            systemContactId = platform.unifiedSystemContactId,
            lookupKey = platform.unifiedLookupKey ?: plan.merged.lookupKey,
        )

        val absorbedVCards = plan.absorbed.associate { absorbed ->
            absorbed.systemContactId to VCardGenerator.generate(absorbed)
        }
        val groupId = stableSuggestionId(
            SuggestionCard.Kind.DUPLICATES,
            group.map { it.systemContactId },
        )

        return try {
            val commit = mergeStore.mergeInRoom(
                survivorId = plan.merged.id,
                mergedContact = migratedSurvivor,
                absorbedIds = plan.absorbed.map { it.id },
                groupId = groupId,
                expiresAt = now + SESSION_SNAPSHOT_EXPIRY_MILLIS,
                nowMillis = now,
            ) { references ->
                MergeSnapshotContent(
                    members = group,
                    absorbedSystemIds = plan.absorbed.map { it.systemContactId },
                    callEventRepoints = references.callLogIds.map { MergeSnapshotContent.CallEventRepoint(it, plan.merged.id) },
                    parentLinkRepoints = references.parentLinks.map { (child, oldParent) ->
                        MergeSnapshotContent.ParentLinkRepoint(child, oldParent)
                    },
                    absorbedVCards = absorbedVCards,
                    absorbedRawIds = absorbedRawIds,
                )
            }
            AppResult.Success(
                MergeCommit(
                    snapshotId = commit.snapshotId,
                    groupId = groupId,
                    plan = plan,
                ),
            )
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            // تعويض الجلسة (J-1 §4): فشل Room بعد نجاح المنصة — محرك المزامنة يُغلق
            // الانحراف (القبور من المنصة / إعادة الاستيراد)؛ خطأ صادق بلا نجاح زائف.
            AppResult.Error(DataError.Unknown(causeMessage = t.message))
        }
    }

    /** ناتج الدمج المُوجَّه للعرض: معرّف اللقطة (مفتاح التراجع) + الخطة (المعاينة/الملخص). */
    data class MergeCommit(
        val snapshotId: Long,
        val groupId: String,
        val plan: MergePlanner.MergePlan,
    )

    companion object {
        /** حد أمان تنظيف اللقطات (الجلسة نفسها يحددها مالك الشاشة في الذاكرة). */
        const val SESSION_SNAPSHOT_EXPIRY_MILLIS: Long = 24 * 60 * 60 * 1000L
    }
}
