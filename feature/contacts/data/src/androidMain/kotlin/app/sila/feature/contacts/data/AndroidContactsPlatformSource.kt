package app.sila.feature.contacts.data

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import app.sila.feature.contacts.domain.ContactsChangeSignal
import app.sila.feature.contacts.domain.ContactsDelta
import app.sila.feature.contacts.domain.ContactsPage
import app.sila.feature.contacts.domain.ContactsPermissionState
import app.sila.feature.contacts.domain.ContactsPlatformSource
import app.sila.feature.contacts.domain.ContactsSnapshot
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * تنفيذ Android لعقد [ContactsPlatformSource] (Issue #22 · ADR-0009 §4 · ADR-0010 §3):
 *
 * - **readPage:** استعلام `ContactsContract.Contacts` مرتب بـ `Contacts._ID ASC` مع
 *   `LIMIT/OFFSET` (التقسيم بالإزاحة — ADR-0010 §2) ثم استعلام `Data` لهوية الصفحة
 *   حصراً (`Contacts._ID` لحساب الدفاتر فقط ولا يُخزَّن كهوية).
 * - **readDelta:** مسح كامل لـ `RawContacts(_ID, CONTACT_ID, VERSION, DELETED)` ومقاصفة
 *   الإصدارات مع اللقطة عبر [diffRawContactVersions]، و`Data` تُقرأ للسجلات الخام
 *   المتغيرة حصراً، مع مجهود أفضل من `DeletedContacts` (يُدمج بلا تكرار).
 *   الحذف يُحل حصراً عبر `LOOKUP_KEY` المحفوظ في اللقطة — النقص/الصلاحية الجزئية لا حذف.
 * - **observeChangeSignals:** `ContentObserver` على `Contacts.CONTENT_URI` مع تجميع
 *   (debounce) ≥ [DEFAULT_CHANGE_SIGNAL_DEBOUNCE_MILLIS] — إشارة اتساخ فقط بلا هوية السجل.
 * - **observePermissionState:** `checkSelfPermission` فقط — يُميّز GRANTED عن غير
 *   المنح (DENIED). لا يُبلَّغ عن `PERMANENTLY_DENIED` من هنا: الكشف العلني الوحيد
 *   لـ"لا تسأل مرة أخرى" على Android هو نمط الطلبين عبر
 *   `shouldShowRequestPermissionRationale` داخل تدفق الطلب (يتطلب Activity)،
 *   فيُكمّله UI/الصلاحيات فوق حالة DENIED (راجع D1 في مراجعة PR #32).
 *
 * كل تحويل يمر حصراً عبر المنطق النقي في [AndroidContactsSourceLogic] (JVM-testable).
 * `readPage` بلا صلاحية/مع خطأ مؤقت يعيد صفحة فارغة، و`readDelta` عند الفشل يعيد
 * [ContactsDelta.newSnapshot] = null (لا تطبيق جزئي أبداً — ADR-0010 §8).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidContactsPlatformSource(
    private val contentResolver: ContentResolver,
    private val applicationContext: Context,
    private val changeSignalDebounceMillis: Long = DEFAULT_CHANGE_SIGNAL_DEBOUNCE_MILLIS,
) : ContactsPlatformSource {

    override suspend fun readPage(offset: Int, limit: Int): ContactsPage {
        if (limit <= 0 || !isReadGranted()) return ContactsPage(emptyList(), offset, limit, hasMore = false)
        return runCatching {
            val contactRows = queryContactRowsByPage(offset = offset, limit = limit)
            val contacts = mapAndroidContactRows(
                contactRows = contactRows,
                dataRecords = queryDataForContactIds(contactRows.map(AndroidContactRow::aggregateContactId))
            )
            ContactsPage(contacts = contacts, offset = offset, limit = limit, hasMore = contactRows.size == limit)
        }.getOrElse {
            // خطأ مؤقت (Provider غير متاح / صلاحية سُحبت أثناء القراءة) — صفحة فارغة (العقد).
            ContactsPage(emptyList(), offset, limit, hasMore = false)
        }
    }

    override suspend fun readDelta(previousSnapshot: ContactsSnapshot?): ContactsDelta = runCatching {
        if (previousSnapshot == null || previousSnapshot.platformMarker != ContactsSnapshot.PLATFORM_ANDROID) {
            fullResyncDelta(previousSnapshot)
        } else {
            incrementalDelta(previousSnapshot)
        }
    }.getOrElse {
        // أي فشل → بلا لقطة جديدة وقائمة فارغة (لا حذف أبداً) — ADR-0010 §8.
        ContactsDelta(upserted = emptyList(), deletedSystemContactIds = emptyList(), newSnapshot = null, isFullResync = false)
    }

    override fun observeChangeSignals(): Flow<ContactsChangeSignal> = callbackFlow {
        var debounceJob: Job? = null
        val observer = object : android.database.ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                debounceJob?.cancel()
                debounceJob = launch {
                    delay(changeSignalDebounceMillis)
                    trySend(ContactsChangeSignal(occurredAtMillis = System.currentTimeMillis()))
                }
            }
        }
        contentResolver.registerContentObserver(
            ContactsContract.Contacts.CONTENT_URI,
            /* notifyForDescendants = */ true,
            observer
        )
        awaitClose {
            debounceJob?.cancel()
            contentResolver.unregisterContentObserver(observer)
        }
    }

    override fun observePermissionState(): Flow<ContactsPermissionState> = flow {
        while (true) {
            emit(currentPermissionState())
            delay(PERMISSION_POLL_INTERVAL_MILLIS)
        }
    }

    // ---------------------------------------------------------------- Read page

    private fun queryContactRowsByPage(offset: Int, limit: Int): List<AndroidContactRow> =
        contentResolver.useQuery(
            uri = ContactsContract.Contacts.CONTENT_URI,
            projection = CONTACT_PROJECTION,
            sortOrder = "${ContactsContract.Contacts._ID} ASC LIMIT $limit OFFSET $offset"
        ) { cursor -> cursor.collect { it.androidContactRow() } }

    private fun Cursor.androidContactRow(): AndroidContactRow = AndroidContactRow(
        aggregateContactId = getLong(getColumnIndexOrThrow(ContactsContract.Contacts._ID)),
        lookupKey = getString(getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY)),
        displayName = getString(getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)),
        photoUri = getString(getColumnIndex(ContactsContract.Contacts.PHOTO_URI)),
        isFavorite = getInt(getColumnIndex(AndroidContactsColumns.STARRED)) == 1
    )

    private fun queryDataForContactIds(contactIds: List<Long>): List<AndroidDataRecord> =
        contactIds.chunked(SQL_IN_BATCH_SIZE).flatMap { chunk ->
            queryDataRecords(
                selection = "${ContactsContract.Data.CONTACT_ID} IN (${inSelectionPlaceholders(chunk.size)})",
                selectionArgs = chunk.map(Long::toString).toTypedArray()
            )
        }

    private fun queryDataForRawContactIds(rawContactIds: List<String>): List<AndroidDataRecord> =
        rawContactIds.chunked(SQL_IN_BATCH_SIZE).flatMap { chunk ->
            queryDataRecords(
                selection = "${ContactsContract.Data.RAW_CONTACT_ID} IN (${inSelectionPlaceholders(chunk.size)})",
                selectionArgs = chunk.toTypedArray()
            )
        }

    private fun queryDataRecords(selection: String, selectionArgs: Array<String>): List<AndroidDataRecord> =
        contentResolver.useQuery(
            uri = ContactsContract.Data.CONTENT_URI,
            projection = DATA_PROJECTION,
            selection = selection,
            selectionArgs = selectionArgs
        ) { cursor ->
            cursor.collect {
                AndroidDataRecord(
                    aggregateContactId = it.getLong(it.getColumnIndexOrThrow(AndroidContactsColumns.AGGREGATE_CONTACT_ID)),
                    mimeType = it.getString(it.getColumnIndexOrThrow(AndroidContactsColumns.MIMETYPE)).orEmpty(),
                    values = it.androidDataValues()
                )
            }
        }

    /** DATA1..DATA7 من الصف الحالي — أسماء المفاتيح من [AndroidContactsColumns] حصراً. */
    private fun Cursor.androidDataValues(): Map<String, String?> = buildMap {
        for (column in DATA_VALUE_COLUMNS) {
            val index = getColumnIndex(column)
            if (index >= 0) put(column, getString(index))
        }
    }

    // ---------------------------------------------------------------- Delta

    private suspend fun fullResyncDelta(previousSnapshot: ContactsSnapshot?): ContactsDelta {
        val upserted = buildList {
            var offset = 0
            while (true) {
                val contactRows = queryContactRowsByPage(offset = offset, limit = DEFAULT_PAGE_LIMIT)
                if (contactRows.isEmpty()) break
                addAll(
                    mapAndroidContactRows(
                        contactRows = contactRows,
                        dataRecords = queryDataForContactIds(contactRows.map(AndroidContactRow::aggregateContactId))
                    )
                )
                offset += contactRows.size
            }
        }
        val scanRows = scanRawContacts()
        val resolvedLookupKeys = resolveLookupKeysForRecentScan(scanRows)
        return ContactsDelta(
            upserted = upserted,
            // القراءة الكاملة لا تحذف أبداً — الحذف يبقى مصالحة كاملة دورية (ADR-0010 §3).
            deletedSystemContactIds = emptyList(),
            newSnapshot = buildNextAndroidSnapshot(scanRows, resolvedLookupKeys, previousSnapshot),
            isFullResync = true
        )
    }

    private suspend fun incrementalDelta(previousSnapshot: ContactsSnapshot): ContactsDelta {
        val currentRows = scanRawContacts()
        val diff = diffRawContactVersions(
            savedRawContacts = previousSnapshot.androidRawContacts,
            currentRows = currentRows
        )
        // أفضل جهد: سجل حذف من DeletedContacts يُربط بقيم اللقطة (بلا تكرار).
        val deletedSystemContactIds = (
            diff.deletedSystemContactIds +
                resolveProviderDeletedContacts(queryRecentDeletedAggregateContacts(), previousSnapshot.androidRawContacts)
            ).distinct()

        val changedRawIds = diff.changedRawContactIds
        if (changedRawIds.isEmpty()) {
            return ContactsDelta(
                upserted = emptyList(),
                deletedSystemContactIds = deletedSystemContactIds,
                newSnapshot = buildNextAndroidSnapshot(currentRows, emptyMap(), previousSnapshot),
                isFullResync = false
            )
        }

        val changedAggregatesById = currentRows
            .filter { !it.deleted && it.rawContactId in changedRawIds.toSet() }
            .associate { it.rawContactId to it.aggregateContactId }
        val changedAggregateIds = changedAggregatesById.values.filterNotNull().distinct()

        // الصفوف الكاملة للجهات المجمّعة المتأثرة + سجلاتها من جدول Data.
        val contactRows = queryContactRowsByIds(changedAggregateIds)
        val dataRecords = queryDataForRawContactIds(changedRawIds)

        // مفاتيح LOOKUP_KEY المُحلَّة للسجلات الخام المتغيرة (للقطة الجديدة).
        val lookupKeyByAggregateId = contactRows.associate { it.aggregateContactId to it.lookupKey }
        val resolvedLookupKeys = changedAggregatesById.mapNotNull { (rawId, aggregateId) ->
            val lookupKey = lookupKeyByAggregateId[aggregateId] ?: return@mapNotNull null
            rawId to lookupKey
        }.toMap()

        return ContactsDelta(
            upserted = mapAndroidContactRows(contactRows, dataRecords),
            deletedSystemContactIds = deletedSystemContactIds,
            newSnapshot = buildNextAndroidSnapshot(currentRows, resolvedLookupKeys, previousSnapshot),
            isFullResync = false
        )
    }

    /**
     * مسح كامل لـ `RawContacts` — [_ID, CONTACT_ID, VERSION, DELETED] مدخل حصري لحساب
     * الفرق (`VERSION`/`DELETED`) وصلة لحلّ LOOKUP_KEY من `Contacts` (ADR-0010 §3).
     */
    private fun scanRawContacts(): List<RawContactScanRow> =
        contentResolver.useQuery(
            uri = ContactsContract.RawContacts.CONTENT_URI,
            projection = RAW_CONTACT_PROJECTION,
            selection = null,
            selectionArgs = null
        ) { cursor ->
            cursor.collect {
                RawContactScanRow(
                    rawContactId = it.getLong(it.getColumnIndexOrThrow(ContactsContract.RawContacts._ID)).toString(),
                    version = it.getLong(it.getColumnIndexOrThrow(ContactsContract.RawContacts.VERSION)),
                    deleted = it.getInt(it.getColumnIndexOrThrow(ContactsContract.RawContacts.DELETED)) == 1,
                    aggregateContactId = it.getLongOrNull(it.getColumnIndex(ContactsContract.RawContacts.CONTACT_ID))
                )
            }
        }

    /** صفوف `Contacts` لمعرّفات مجمّعة محددة (تُستخدم في الدلتا المتزايدة). */
    private fun queryContactRowsByIds(aggregateIds: List<Long>): List<AndroidContactRow> =
        aggregateIds.chunked(SQL_IN_BATCH_SIZE).flatMap { chunk ->
            contentResolver.useQuery(
                uri = ContactsContract.Contacts.CONTENT_URI,
                projection = CONTACT_PROJECTION,
                selection = "${ContactsContract.Contacts._ID} IN (${inSelectionPlaceholders(chunk.size)})",
                selectionArgs = chunk.map(Long::toString).toTypedArray()
            ) { cursor -> cursor.collect { it.androidContactRow() } }
        }

    /**
     * حلّ LOOKUP_KEY لكل السجلات الخام غير المحذوفة (تُستخدم في مسار إعادة المزامنة
     * الكاملة حيث لا توجد لقطة سابقة تحفظ المفاتيح).
     */
    private fun resolveLookupKeysForRecentScan(scanRows: List<RawContactScanRow>): Map<String, String> {
        val aggregateIds = scanRows.mapNotNull(RawContactScanRow::aggregateContactId).distinct()
        if (aggregateIds.isEmpty()) return emptyMap()

        val lookupByAggregateId = aggregateIds.chunked(SQL_IN_BATCH_SIZE).flatMap { chunk ->
            contentResolver.useQuery(
                uri = ContactsContract.Contacts.CONTENT_URI,
                projection = arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY),
                selection = "${ContactsContract.Contacts._ID} IN (${inSelectionPlaceholders(chunk.size)})",
                selectionArgs = chunk.map(Long::toString).toTypedArray()
            ) { cursor ->
                val pairs = mutableListOf<Pair<Long, String>>()
                while (cursor.moveToNext()) {
                    val lookupKey = cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY)) ?: continue
                    pairs.add(cursor.getLong(cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)) to lookupKey)
                }
                pairs
            }
        }.toMap()

        val resolved = mutableMapOf<String, String>()
        for (row in scanRows) {
            if (row.deleted) continue
            val aggregateId = row.aggregateContactId ?: continue
            val lookupKey = lookupByAggregateId[aggregateId] ?: continue
            resolved[row.rawContactId] = lookupKey
        }
        return resolved
    }

    /**
     * أحدث سجلات الحذف من `ContactsContract.DeletedContacts` (مجهود أفضل — يُحفظ ~30 يوماً).
     * تُربط حصراً بمعرّفات المجمّعة المحفوظة في اللقطة عبر [resolveProviderDeletedContacts]،
     * فلا يمكن أن ينتج عنها حذف وهمي.
     */
    private fun queryRecentDeletedAggregateContacts(): List<DeletedAggregateContactRecord> =
        contentResolver.useQuery(
            uri = ContactsContract.DeletedContacts.CONTENT_URI,
            projection = DELETED_CONTACT_PROJECTION,
            selection = null,
            selectionArgs = null,
            sortOrder = "${ContactsContract.DeletedContacts.CONTACT_DELETED_TIMESTAMP} DESC LIMIT $DELETED_CONTACTS_LOOKBACK_LIMIT"
        ) { cursor ->
            cursor.collect {
                DeletedAggregateContactRecord(
                    aggregateContactId = it.getLong(it.getColumnIndexOrThrow(ContactsContract.DeletedContacts.CONTACT_ID)),
                    deletedAtMillis = it.getLongOrNull(
                        it.getColumnIndex(ContactsContract.DeletedContacts.CONTACT_DELETED_TIMESTAMP)
                    )
                )
            }
        }

    // ---------------------------------------------------------------- Permission

    private fun isReadGranted(): Boolean =
        applicationContext.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun currentPermissionState(): ContactsPermissionState =
        if (isReadGranted()) ContactsPermissionState.GRANTED else ContactsPermissionState.DENIED

    // D1 (مراجعة PR #32): كان يُفترض هنا كشف "لا تسأل مرة أخرى" عبر
    // `AppOpsManager.unsafeCheckOpNoThrow(OPSTR_READ_CONTACTS) == MODE_ERRORED` — وهو خطأ:
    // العمليات المرتبطة بصلاحيات زمن التشغيل تُحلّ وضعها من حالة الصلاحية نفسها في
    // AppOpsService (منحة → MODE_ALLOWED، وعدم منحة → MODE_ERRORED)، أي أن MODE_ERRORED
    // يعني "غير ممنوح" لا "رفض نهائي" — على تثبيت جديد أو بعد رفض عادي على API 29+
    // تُعاد MODE_ERRORED دائماً، فانطفل طلب الصلاحية كلياً وتحوّل كل رفض إلى دائم.
    // التوثيق الرسمي لـ MODE_ERRORED حجبٌ عام (SecurityException) بلا دلالة "لا تسأل
    // مرة أخرى"، والكشف الموثوق يتطلب تدفق الطلب (النمط الثنائي) في طبقة الواجهة.

    private fun inSelectionPlaceholders(count: Int): String =
        List(count) { "?" }.joinToString(separator = ",")

    private companion object {
        const val DEFAULT_PAGE_LIMIT = 500

        /** حجم دفعة `IN (..)` — بلا استعلامات بمعاملات غير محدودة فوق حد متغيرات SQLite (ADR-0010 §2). */
        const val SQL_IN_BATCH_SIZE = 500

        /** 30 يوماً حصراً من سجلات الحذف — مجهود أفضل، وليس مصدر التوثيق الأساسي (ADR-0010 §3). */
        const val DELETED_CONTACTS_LOOKBACK_LIMIT = 500

        const val DEFAULT_CHANGE_SIGNAL_DEBOUNCE_MILLIS = 5_000L
        const val PERMISSION_POLL_INTERVAL_MILLIS = 2_000L

        val DATA_VALUE_COLUMNS = listOf(
            AndroidContactsColumns.DATA1,
            AndroidContactsColumns.DATA2,
            AndroidContactsColumns.DATA3,
            AndroidContactsColumns.DATA4,
            AndroidContactsColumns.DATA5,
            AndroidContactsColumns.DATA6,
            AndroidContactsColumns.DATA7
        )

        val CONTACT_PROJECTION = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.PHOTO_URI,
            ContactsContract.Contacts.STARRED
        )

        val RAW_CONTACT_PROJECTION = arrayOf(
            ContactsContract.RawContacts._ID,
            ContactsContract.RawContacts.CONTACT_ID,
            ContactsContract.RawContacts.VERSION,
            ContactsContract.RawContacts.DELETED
        )

        val DELETED_CONTACT_PROJECTION = arrayOf(
            ContactsContract.DeletedContacts.CONTACT_ID,
            ContactsContract.DeletedContacts.CONTACT_DELETED_TIMESTAMP
        )

        val DATA_PROJECTION = arrayOf(
            ContactsContract.Data.CONTACT_ID,
            ContactsContract.Data.RAW_CONTACT_ID,
            ContactsContract.Data.MIMETYPE,
            ContactsContract.Data.DATA1,
            ContactsContract.Data.DATA2,
            ContactsContract.Data.DATA3,
            ContactsContract.Data.DATA4,
            ContactsContract.Data.DATA5,
            ContactsContract.Data.DATA6,
            ContactsContract.Data.DATA7
        )
    }
}

/** استعلام Brief بغلق المؤشر حصراً (Single source of truth: كل القراءات عبر هذه الدالة). */
private inline fun <T> ContentResolver.useQuery(
    uri: Uri,
    projection: Array<String>,
    selection: String? = null,
    selectionArgs: Array<String>? = null,
    sortOrder: String? = null,
    block: (Cursor) -> List<T>
): List<T> {
    val cursor = query(uri, projection, selection, selectionArgs, sortOrder) ?: return emptyList()
    return cursor.use { current -> block(current) }
}

/** يجمع عناصر المؤشر كاملاً بإغلاقه تلقائياً (مرجع واحد لمسح الصفوف). */
private inline fun <T> Cursor.collect(block: (Cursor) -> T): List<T> {
    val out = ArrayList<T>(count)
    while (moveToNext()) out.add(block(this))
    return out
}

private fun Cursor.getLongOrNull(columnIndex: Int): Long? =
    if (columnIndex >= 0 && !isNull(columnIndex)) getLong(columnIndex) else null
