package app.sila.feature.contacts.data

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import app.sila.core.common.AppResult
import app.sila.core.common.DataError
import app.sila.feature.contacts.domain.PlatformMergeRequest
import app.sila.feature.contacts.domain.PlatformMergeResult
import app.sila.feature.contacts.domain.PlatformUnmergeRequest
import app.sila.feature.contacts.domain.PlatformSweepRequest
import app.sila.feature.contacts.domain.PlatformUnmergeResult
import app.sila.feature.contacts.domain.ContactsMergeWriter
import app.sila.feature.contacts.domain.toWritable

/**
 * تنفيذ Android لعقد الدمج المنصوي [ContactsMergeWriter] (T7 · Issue #28 · ADR-0013 —
 * الموضع 2 المُصادَق من النقاش):
 *
 * - **mergeContacts**: (1) طفير حقول الناجي الضيق بالاتحاد عبر كاتب T6 القائم حصراً
 *   (`updateContact` بالإسقاط المسموح — agy F2b: بلا هذه الكتابة يعيد الدلتا التالي
 *   `firstOrNull` قيم الممتص فيطمس فائزي [MergePlanner])، ثم (2)
 *   `AggregationExceptions.TYPE_KEEP_TOGETHER` لكل ثنائية (ناجي، كل raw ممتص) — عمليات
 *   على معرفات `RawContacts._ID` لا LOOKUP_KEY، ثم (3) **إعادة حل LOOKUP_KEY الموحّد بعد
 *   التجميع** — المزود قد يجعل مفتاح الممتص هو الموحد (agy P3a) فيُهاجر صف Room الناجي
 *   إليه داخل المعاملة. **لا حذف منصوياً خلال الجلسة إطلاقاً** — المسح المؤجل بعد
 *   انقضاءها (منسّق `:sharedLogic`) هو من ينفّذ «حدّث واحداً واحذف الباقي» بمستوى Raw
 *   حصراً (حذف CONTENT_LOOKUP_URI الكلي يقتل raws حسابات أخرى في المجمّعة — خطر Lane D).
 * - **unmergeContacts** (التراجع): `TYPE_KEEP_SEPARATE` لكل ثنائية مسجَّلة — idempotent —
 *   ثم **إعادة حل مفتاح كل ممتص بعد الفصل** (التجميع قد يولّد مفاتيح جديدة) لإعادة
 *   تعيين هويات صفوف Room المسترجعة داخل المعاملة (agy F2c).
 * - الصلاحية: `WRITE_CONTACTS` قبل كل عملية (فشل صادق — J-1).
 */
class AndroidContactsMergeWriter(
    private val contentResolver: android.content.ContentResolver,
    private val applicationContext: Context,
) : ContactsMergeWriter {

    /** كاتب حقول T6 القائم — طفير الناجي الضيق يُفوَّض له حصراً (لا تكرار منطق Data._ID). */
    private val fieldWriter = AndroidContactsPlatformWriter(
        contentResolver = contentResolver,
        applicationContext = applicationContext,
    )

    override suspend fun mergeContacts(request: PlatformMergeRequest): AppResult<PlatformMergeResult> {
        if (!isWriteGranted()) return permissionError()

        val survivorRawId = resolveSurvivorRawId(request.survivor)
            ?: return AppResult.Error(DataError.NotFound(entityId = request.survivor.systemContactId))

        // (1) اتحاد الحقول على الناجي عبر الكاتب الضيق القائم (الإسقاط المسموح حصراً)
        val updateResult = fieldWriter.updateContact(request.survivor.toWritable())
        if (updateResult is AppResult.Error) return updateResult

        // (2) KEEP_TOGETHER مع كل raw ممتص (الممتص لم يُحذف — قبور الدمج Room-فقط خلال الجلسة)
        val absorbedRawIdsBySystemId = request.absorbed.associate { absorbed -> absorbed.systemContactId to rawIdsOf(absorbed) }
        val absorbedRawIds = absorbedRawIdsBySystemId.values.flatten()
        if (absorbedRawIds.isNotEmpty()) {
            val ops = absorbedRawIds.map { absorbedRawId ->
                ContentProviderOperation.newInsert(ContactsContract.AggregationExceptions.CONTENT_URI)
                    .withValue(ContactsContract.AggregationExceptions.TYPE, ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER)
                    .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, survivorRawId)
                    .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, absorbedRawId)
                    .build()
            }
            runCatching { contentResolver.applyBatch(ContactsContract.AUTHORITY, ArrayList(ops)) }
                .onFailure { return AppResult.Error(DataError.Unknown(causeMessage = it.message)) }
        }

        // (3) الهوية الموحدة بعد التجميع — مفتاح الناجي المُعاد حله حرفاً
        val unifiedLookupKey = resolveLookupKey(survivorRawId)
            ?: return AppResult.Error(DataError.NotFound(entityId = "unified_lookup_key"))
        return AppResult.Success(
            PlatformMergeResult(
                unifiedSystemContactId = unifiedLookupKey,
                unifiedLookupKey = unifiedLookupKey,
                absorbedRawIds = absorbedRawIdsBySystemId.mapValues { (_, ids) -> ids.map { it.toString() } },
            ),
        )
    }

    /**
     * المسح المؤجل (agy F3b): حذف raws الممتصين المسجَّلة في اللقطة — **raw-مستوى حصراً**
     * (ContentProviderOperation.delete على RawContacts)، بلا مساس aggregate LOOKUP_URI
     * كلياً (خطر raws الحسابات الأخرى — Lane D). الحذف يتحقق عبر هوية raw المسجَّلة ذاتها.
     */
    override suspend fun sweepAbsorbedRawContacts(request: PlatformSweepRequest): AppResult<Unit> {
        if (!isWriteGranted()) return permissionError()
        if (request.absorbedRawIds.isEmpty()) return AppResult.Success(Unit)
        val ops = request.absorbedRawIds.values.flatten().mapNotNull { rawId ->
            rawId.toLongOrNull()?.let { id ->
                ContentProviderOperation.newDelete(
                    ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, id),
                ).build()
            }
        }
        if (ops.isEmpty()) return AppResult.Success(Unit)
        return runCatching { contentResolver.applyBatch(ContactsContract.AUTHORITY, ArrayList(ops)) }.fold(
            onSuccess = { AppResult.Success(Unit) },
            onFailure = { t -> AppResult.Error(DataError.Unknown(causeMessage = t.message)) },
        )
    }

    override suspend fun unmergeContacts(request: PlatformUnmergeRequest): AppResult<PlatformUnmergeResult> {
        if (!isWriteGranted()) return permissionError()

        val survivorRawId = resolveSurvivorRawId(request.survivor)
            ?: return AppResult.Error(DataError.NotFound(entityId = request.survivor.systemContactId))

        val identityMap = mutableMapOf<String, String>()
        for (absorbed in request.absorbed) {
            val absorbedRawIds = rawIdsOf(absorbed)
            if (absorbedRawIds.isNotEmpty()) {
                val ops = absorbedRawIds.map { absorbedRawId ->
                    ContentProviderOperation.newInsert(ContactsContract.AggregationExceptions.CONTENT_URI)
                        .withValue(ContactsContract.AggregationExceptions.TYPE, ContactsContract.AggregationExceptions.TYPE_KEEP_SEPARATE)
                        .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, survivorRawId)
                        .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, absorbedRawId)
                        .build()
                }
                runCatching { contentResolver.applyBatch(ContactsContract.AUTHORITY, ArrayList(ops)) }
                    .onFailure { return AppResult.Error(DataError.Unknown(causeMessage = it.message)) }
            }
            // مفتاح الممتص بعد الفصل — قد يتجدد (نمط ADR-0009 §4 للمفاتيح المُعاد توليدها)
            val freshKey = absorbedRawIds.firstOrNull()?.let { resolveLookupKey(it) }
            if (freshKey != null && freshKey != absorbed.systemContactId) {
                identityMap[absorbed.systemContactId] = freshKey
            }
        }
        return AppResult.Success(PlatformUnmergeResult(absorbedSystemIds = identityMap))
    }

    // ---------------------------------------------------------------- محلولات الهوية

    private fun resolveSurvivorRawId(survivor: app.sila.core.model.Contact): Long? {
        val key = survivor.lookupKey ?: survivor.systemContactId
        val lookupUri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, key)
        val aggregateId = contentResolver.queryFirstLong(
            uri = lookupUri,
            projection = arrayOf(ContactsContract.Contacts._ID),
            selection = null,
            selectionArgs = null,
            column = ContactsContract.Contacts._ID,
        ) ?: contentResolver.queryFirstLong(
            uri = ContactsContract.RawContacts.CONTENT_URI,
            projection = arrayOf(ContactsContract.RawContacts.CONTACT_ID),
            selection = "${ContactsContract.RawContacts._ID} = ?",
            selectionArgs = arrayOf(survivor.systemContactId),
            column = ContactsContract.RawContacts.CONTACT_ID,
        ) ?: return null
        return contentResolver.useQuery(
            uri = ContactsContract.RawContacts.CONTENT_URI,
            projection = arrayOf(ContactsContract.RawContacts._ID),
            selection = "${ContactsContract.RawContacts.CONTACT_ID} = ? AND ${ContactsContract.RawContacts.DELETED} = 0",
            selectionArgs = arrayOf(aggregateId.toString()),
            sortOrder = "${ContactsContract.RawContacts._ID} ASC",
        ) { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(cursor.getColumnIndexOrThrow(ContactsContract.RawContacts._ID)) else null
        }
    }

    /** كل سجلات raw الحية للممتص — KEEP_TOGETHER/الفتح يعملان بأزواج raw لا بالمجمّعة. */
    private fun rawIdsOf(absorbed: app.sila.core.model.Contact): List<Long> {
        val key = absorbed.lookupKey ?: absorbed.systemContactId
        val lookupUri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, key)
        val aggregateId = contentResolver.queryFirstLong(
            uri = lookupUri,
            projection = arrayOf(ContactsContract.Contacts._ID),
            selection = null,
            selectionArgs = null,
            column = ContactsContract.Contacts._ID,
        ) ?: contentResolver.queryFirstLong(
            uri = ContactsContract.RawContacts.CONTENT_URI,
            projection = arrayOf(ContactsContract.RawContacts.CONTACT_ID),
            selection = "${ContactsContract.RawContacts._ID} = ?",
            selectionArgs = arrayOf(absorbed.systemContactId),
            column = ContactsContract.RawContacts.CONTACT_ID,
        ) ?: return emptyList()
        return contentResolver.useQuery(
            uri = ContactsContract.RawContacts.CONTENT_URI,
            projection = arrayOf(ContactsContract.RawContacts._ID),
            selection = "${ContactsContract.RawContacts.CONTACT_ID} = ? AND ${ContactsContract.RawContacts.DELETED} = 0",
            selectionArgs = arrayOf(aggregateId.toString()),
            sortOrder = "${ContactsContract.RawContacts._ID} ASC",
        ) { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getLong(cursor.getColumnIndexOrThrow(ContactsContract.RawContacts._ID)))
            }
        }
    }

    private fun resolveLookupKey(rawContactId: Long): String? {
        val aggregateId = contentResolver.queryFirstLong(
            uri = ContactsContract.RawContacts.CONTENT_URI,
            projection = arrayOf(ContactsContract.RawContacts.CONTACT_ID),
            selection = "${ContactsContract.RawContacts._ID} = ?",
            selectionArgs = arrayOf(rawContactId.toString()),
            column = ContactsContract.RawContacts.CONTACT_ID,
        ) ?: return null
        return contentResolver.useQuery(
            uri = ContactsContract.Contacts.CONTENT_URI,
            projection = arrayOf(ContactsContract.Contacts.LOOKUP_KEY),
            selection = "${ContactsContract.Contacts._ID} = ?",
            selectionArgs = arrayOf(aggregateId.toString()),
        ) { cursor ->
            if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.LOOKUP_KEY)) else null
        }
    }

    // ---------------------------------------------------------------- أدوات (ذاتية — useQuery خاصة بالكاتب الأصلي)

    private inline fun <T> android.content.ContentResolver.useQuery(
        uri: Uri,
        projection: Array<String>,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
        sortOrder: String? = null,
        block: (android.database.Cursor) -> T,
    ): T {
        val cursor = query(uri, projection, selection, selectionArgs, sortOrder)
            ?: throw IllegalStateException("ContactsProvider returned a null cursor for $uri")
        return cursor.use { block(it) }
    }

    private fun android.content.ContentResolver.queryFirstLong(
        uri: Uri,
        projection: Array<String>,
        selection: String?,
        selectionArgs: Array<String>?,
        column: String,
    ): Long? = useQuery(uri, projection, selection, selectionArgs) { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(cursor.getColumnIndex(column))) {
            cursor.getLong(cursor.getColumnIndex(column))
        } else {
            null
        }
    }

    private fun isWriteGranted(): Boolean =
        applicationContext.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun permissionError(): AppResult.Error =
        AppResult.Error(DataError.Permission(permissionName = WRITE_PERMISSION_NAME))

    private companion object {
        const val WRITE_PERMISSION_NAME = "android.permission.WRITE_CONTACTS"
    }
}
