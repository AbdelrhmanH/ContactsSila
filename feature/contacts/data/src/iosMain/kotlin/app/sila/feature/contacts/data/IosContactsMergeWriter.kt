package app.sila.feature.contacts.data

import app.sila.core.common.AppResult
import app.sila.core.common.DataError
import app.sila.feature.contacts.domain.ContactsMergeWriter
import app.sila.feature.contacts.domain.PlatformMergeRequest
import app.sila.feature.contacts.domain.PlatformMergeResult
import app.sila.feature.contacts.domain.PlatformUnmergeRequest
import app.sila.feature.contacts.domain.PlatformSweepRequest
import app.sila.feature.contacts.domain.PlatformUnmergeResult
import app.sila.feature.contacts.domain.toWritable
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Contacts.CNContact
import platform.Contacts.CNContactStore
import platform.Contacts.CNContactVCardSerialization
import platform.Contacts.CNMutableContact
import platform.Contacts.CNSaveRequest
import platform.Foundation.NSError
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.dataUsingEncoding

/**
 * تنفيذ iOS لعقد الدمج المنصوي [ContactsMergeWriter] (T7 · Issue #28 · ADR-0013 —
 * الموضع 2 المُصادَق):
 *
 * - **mergeContacts**: طفير الناجي الضيق بالاتحاد عبر كاتب T6 القائم حصراً
 *   (`updateContact` بالإسقاط المُدار — agy F2b)، ثم حذف كل ممتص (CNSaveRequest.
 *   deleteContact — حذف CNContact دائم منصوياً، لذا **بطاقة vCard لكل ممتص تُخزَّن داخل
 *   لقطة Room قبل أي كتابة** — شرط مسبق في حالة الاستخدام، agy F3a). الهوية الموحدة =
 *   هوية الناجي نفسها (iOS لا يولّد مفاتيح جديدة عند الحذف — الناجي بلا مساس هوية).
 * - **unmergeContacts** (التراجع): **إعادة إنشاء كل ممتص من بطاقة vCard** عبر
 *   `CNContactVCardSerialization` → `CNSaveRequest.addContact` (agy F3a) — المعرفات
 *   الجديدة تُعاد لخريطة تعيين الهويات التي تطبّقها معاملة الاسترجاع على صفوف Room
 *   (بدلها يقيّمها دلتا `CNChangeHistoryFetchRequest` قبوراً فوراً).
 *
 * ملاحظة cinterop 2.4.20 الموثقة (ADR-0012 §10): `updateContact/deleteContact` بمعامل
 * واحد (الحاوية تُستنتج من هوية النسخة) و`addContact` يأخذ الحاوية صراحةً.
 */
@OptIn(ExperimentalForeignApi::class)
class IosContactsMergeWriter(
    private val fieldWriter: IosContactsPlatformWriter = IosContactsPlatformWriter(),
    private val store: CNContactStore = CNContactStore(),
) : ContactsMergeWriter {

    override suspend fun mergeContacts(request: PlatformMergeRequest): AppResult<PlatformMergeResult> {
        // (1) اتحاد الحقول على الناجي عبر الكاتب الضيق القائم
        when (val update = fieldWriter.updateContact(request.survivor.toWritable())) {
            is AppResult.Error -> return update
            is AppResult.Success -> Unit
        }
        // (2) حذف الممتصين (النسخ الاحتياطية في لقطة Room — شرط مسبق مُنفَّذ قبل هذا الصف)
        for (absorbed in request.absorbed) {
            when (val deleted = fieldWriter.deleteContact(absorbed.systemContactId, absorbed.lookupKey)) {
                is AppResult.Error -> return deleted
                is AppResult.Success -> Unit
            }
        }
        // (3) هوية الناجي بلا مساس على iOS
        return AppResult.Success(
            PlatformMergeResult(
                unifiedSystemContactId = request.survivor.systemContactId,
                unifiedLookupKey = request.survivor.lookupKey,
            ),
        )
    }

    override suspend fun unmergeContacts(request: PlatformUnmergeRequest): AppResult<PlatformUnmergeResult> {
        val identityMap = mutableMapOf<String, String>()
        for (absorbed in request.absorbed) {
            val vCard = request.absorbedVCards[absorbed.systemContactId]
                ?: return AppResult.Error(
                    DataError.Validation(
                        field = "absorbedVCards",
                        reason = "missing vCard for re-creation: ${absorbed.systemContactId}",
                    ),
                )
            val newIdentifier = runCatching { recreateFromVCard(vCard) }.fold(
                onSuccess = { identifier ->
                    identifier ?: return AppResult.Error(
                        DataError.Unknown(causeMessage = "vCard re-creation produced no identifier"),
                    )
                },
                onFailure = { t -> return AppResult.Error(DataError.Unknown(causeMessage = t.message)) },
            )
            identityMap[absorbed.systemContactId] = newIdentifier
        }
        return AppResult.Success(PlatformUnmergeResult(absorbedSystemIds = identityMap))
    }

    /**
     * المسح المؤجل على iOS: **لا شيء يُحذف** — حذف الممتصين تم في الدمج نفسه (agy F3a)
     * والخريطة فارغة بالعقد؛ نجاح فوري.
     */
    override suspend fun sweepAbsorbedRawContacts(request: PlatformSweepRequest): AppResult<Unit> =
        AppResult.Success(Unit)

    /** vCard نصاً → CNMutableContact → حفظ جديد → المعرف الدائم للصف المُنشأ (agy F3a). */
    private fun recreateFromVCard(vCardText: String): String? {
        val data = (vCardText as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return null
        val contacts = CNContactVCardSerialization.contactsWithData(data, error = null) as? List<*>
            ?: return null
        val mutable = contacts.filterIsInstance<CNContact>().firstOrNull()?.mutableCopy() as? CNMutableContact
            ?: return null
        val request = CNSaveRequest()
        request.addContact(mutable, null)
        executeSave(request, context = "unmergeContacts re-creation")
        return mutable.identifier.takeIf { it.isNotBlank() }
    }

    /** نفس نمط [IosContactsPlatformWriter] لطفير حرفي — بلا استثناءات هاربة. */
    private fun executeSave(request: CNSaveRequest, context: String): Unit = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        val success = store.executeSaveRequest(request, error.ptr)
        if (!success || error.value != null) {
            error("Contacts save failed in $context: ${error.value?.localizedDescription ?: "unknown error"}")
        }
    }
}
