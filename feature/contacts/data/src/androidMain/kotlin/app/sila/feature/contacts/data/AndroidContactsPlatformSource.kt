package app.sila.feature.contacts.data

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
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
 *   **محمية (DEF-01 · Issue #43):** بلا `READ_CONTACTS` لا تسجيل ولا لمس للمزوّد ولا
 *   أحداث مزيفة — تدفق خامل حتى إلغاء الجمع، ونداء التسجيل نفسه مُحاط بالتقاط ضيق
 *   لـ [SecurityException] (نافذة سحب بين الفحص والنداء — TOCTOU) يُسجَّل بلا PII.
 * - **observePermissionState:** `checkSelfPermission` فقط — يُميّز GRANTED عن غير
 *   المنح (DENIED). لا يُبلَّغ عن `PERMANENTLY_DENIED` من هنا: الكشف العلني الوحيد
 *   لـ"لا تسأل مرة أخرى" على Android هو نمط الطلبين عبر
 *   `shouldShowRequestPermissionRationale` داخل تدفق الطلب (يتطلب Activity)،
 *   فيُكمّله UI/الصلاحيات فوق حالة DENIED (راجع D1 في مراجعة PR #32).
 *
 * كل تحويل يمر حصراً عبر المنطق النقي في [AndroidContactsSourceLogic] (JVM-testable)،
 * ومراقبة الإشارات عبر [observeChangeSignalsFlow] بنفس النمط — عبر بوابة
 * [ContactsProviderAccessGateway] حدّ اللمس الوحيد للمزوّد في هذا الصنف.
 * `readPage` بلا صلاحية/مع خطأ مؤقت يعيد صفحة فارغة، و`readDelta` عند الفشل يعيد
 * [ContactsDelta.newSnapshot] = null (لا تطبيق جزئي أبداً — ADR-0010 §8).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidContactsPlatformSource(
    private val contentResolver: ContentResolver,
    private val applicationContext: Context,
    private val changeSignalDebounceMillis: Long = DEFAULT_CHANGE_SIGNAL_DEBOUNCE_MILLIS,
) : ContactsPlatformSource {

    /** حدّ اللمس الوحيد للمزوّد — الإنتاج عبر [ContentResolverBackedGateway] حصراً. */
    private val providerGateway: ContactsProviderAccessGateway =
        ContentResolverBackedGateway(contentResolver, applicationContext)

    override suspend fun readPage(offset: Int, limit: Int): ContactsPage {
        if (limit <= 0 || !providerGateway.isReadPermissionGranted()) {
            return ContactsPage(emptyList(), offset, limit, hasMore = false)
        }
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

    override fun observeChangeSignals(): Flow<ContactsChangeSignal> =
        observeChangeSignalsFlow(providerGateway, changeSignalDebounceMillis)

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

    /** DATA1..DATA9 من الصف الحالي — أسماء المفاتيح من [AndroidContactsColumns] حصراً. */
    private fun Cursor.androidDataValues(): Map<String, String?> = buildMap {
        for (column in DATA_VALUE_COLUMNS) {
            val index = getColumnIndex(column)
            if (index >= 0) put(column, getString(index))
        }
    }

    // ---------------------------------------------------------------- Delta

    private suspend fun fullResyncDelta(previousSnapshot: ContactsSnapshot?): ContactsDelta {
        // D2 (جولة 5): مسح الإصدارات وحل المفاتيح **أولاً** — اللقطة تُختَم بلحظة T0
        // سابقة لقراءة البيانات، فأي تعديل يقع أثناء الاستيراد (بين المسح وقراءة الصفحات)
        // يُرى كفارق إصدار في الدلتا التزايدية التالية ويُعاد رفعه idempotent. الترتيب
        // المعاكس (صفحات ثم مسح) كان يطبع اللقطة بإصدارات أحدث من البيانات المقروءة
        // فيفقِد التعديلات المتزامنة إلى الأبد.
        val scanRows = scanRawContacts()
        val resolvedLookupKeys = resolveLookupKeysForRecentScan(scanRows)
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
        // F4 (جولة 4): قراءة `DeletedContacts` أفضل جهد (ADR-0010 §3 — يُحفظ ~30 يوماً):
        // فشلها (مزود غير متاح/مؤشر null) لا يُبطل الدلتا كلها — كان يرمي فيُمسك بـ
        // runCatching الخارجي فتُلغى الدلتا وتُفقد upserts جاهزة أيضاً. الفقد الآن بلاغات
        // حذف هذه الدورة فقط، والحذف الأساسي يبقى عبر diff (DELETED=1/اختفاء السجل).
        val providerDeletedRecords = runCatching { queryRecentDeletedAggregateContacts() }
            .getOrElse { emptyList() }
        val deletedSystemContactIds = (
            diff.deletedSystemContactIds +
                resolveProviderDeletedContacts(providerDeletedRecords, previousSnapshot.androidRawContacts)
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

        // الصفوف الكاملة للجهات المجمّعة المتأثرة + سجلاتها من جدول Data — تُقرأ بحسب
        // CONTACT_ID (المجمّع) لا RAW_CONTACT_ID: الـ upsert يَعِد بالحالة النهائية الكاملة،
        // وصف Data مُسجَّل بـ CONTACT_ID؛ جلب بيانات السجلات الخام المتغيرة حصراً يُسقط
        // بيانات السجلات الشقيقة (حساب ثانٍ/واتساب) من الـ upsert فيمحوها Room (D2).
        val contactRows = queryContactRowsByIds(changedAggregateIds)
        val dataRecords = queryDataForContactIds(changedAggregateIds)

        // مفاتيح LOOKUP_KEY المُحلَّة للسجلات الخام المتغيرة (للقطة الجديدة).
        val lookupKeyByAggregateId = contactRows.associate { it.aggregateContactId to it.lookupKey }
        val resolvedLookupKeys = changedAggregatesById.mapNotNull { (rawId, aggregateId) ->
            val lookupKey = lookupKeyByAggregateId[aggregateId] ?: return@mapNotNull null
            rawId to lookupKey
        }.toMap()

        return ContactsDelta(
            upserted = mapAndroidContactRows(contactRows, dataRecords),
            // + المفاتيح الملغاة: سجل خام متغير حُدّث مفتاحه داخل تجمّعته (إعادة توليد
            // مفتاح/مزامن) — القديم هوية ماتت تُحذف من Room مع upsert الجديد وإلا تكرار
            // (D1 جولة 3). بدلالة التجمّعة (F1 جولة 4): إعادة ربط سجل بتجمّعة أخرى لا
            // تُبطل مفتاح القديمة — الشقيقات غير المتغيرة تحمله حية فلا يُبلَغ عنه.
            deletedSystemContactIds = (
                deletedSystemContactIds +
                    supersededLookupKeys(
                        resolvedLookupKeys = resolvedLookupKeys,
                        changedAggregatesById = changedAggregatesById,
                        savedRawContacts = previousSnapshot.androidRawContacts
                    )
                ).distinct(),
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

    private fun currentPermissionState(): ContactsPermissionState =
        if (providerGateway.isReadPermissionGranted()) ContactsPermissionState.GRANTED else ContactsPermissionState.DENIED

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
            AndroidContactsColumns.DATA7,
            AndroidContactsColumns.DATA8,
            AndroidContactsColumns.DATA9
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
            ContactsContract.Data.DATA7,
            ContactsContract.Data.DATA8,
            ContactsContract.Data.DATA9
        )
    }
}

/**
 * بوابة الوصول لمزوّد الاتصالات — حدّ اللمس الوحيد للمزوّد في
 * [AndroidContactsPlatformSource] (DEF-01 · Issue #43): فحص صلاحية القراءة +
 * تسجيل/إلغاء مراقب إشارات التغيير. استُخرجت بنمط [AndroidContactsSourceLogic]
 * نفسه (منطق قابل للاختبار على مضيف JVM بلا أنواع أندرويد). الإنتاج عبر
 * [ContentResolverBackedGateway] حصراً؛ وبدائل الاختبارات تقع على حدّ التسجيل
 * نفسه (لا تتجاوزه — القيمة المُختبرة هي حماية المصدر لا النداء البيني، الذي
 * تثبته اختبارات الجهاز الفعلية في قائمة QA الأجهزة).
 */
internal interface ContactsProviderAccessGateway {

    /**
     * سياسة القراءة المرجعية — `READ_CONTACTS` حصراً (لا `WRITE_CONTACTS` وحدها
     * كبديل: أقل امتياز — ADR-0009 §4). المصدر الوحيد لهذا الفحص في الصنف
     * (readPage + observePermissionState + مراقبة الإشارات).
     */
    fun isReadPermissionGranted(): Boolean

    /**
     * تسجيل مراقب يُبلّغ عبر [onChange] عند أي تغيير في دفتر الاتصالات. يرمي
     * [SecurityException] حصراً حين يرفض المزوّد التسجيل (نافذة سحبٍ بين فحص
     * الصلاحية والنداء البيني — TOCTOU). يعيد مقبض إلغاء التسجيل — يُستدعى
     * مرة واحدة بعد تسجيل ناجح حصراً (لا unregister لتسجيل لم يحدث — A3).
     */
    fun registerChangeObserver(onChange: () -> Unit): AutoCloseable
}

/** الإنتاج: نداء `ContentResolver` البيني حصراً — نفس دلالات URI/السلالات السابقة (A4). */
private class ContentResolverBackedGateway(
    private val contentResolver: ContentResolver,
    private val applicationContext: Context,
) : ContactsProviderAccessGateway {

    override fun isReadPermissionGranted(): Boolean =
        applicationContext.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    override fun registerChangeObserver(onChange: () -> Unit): AutoCloseable {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                onChange()
            }
        }
        contentResolver.registerContentObserver(
            ContactsContract.Contacts.CONTENT_URI,
            /* notifyForDescendants = */ true,
            observer
        )
        return AutoCloseable { contentResolver.unregisterContentObserver(observer) }
    }
}

/** وسم اللوح لفشل تسجيل مراقب الإشارات — سياق تقني حصراً، بلا أسماء ولا أرقام (الإرشادات §7 و§9). */
private const val CHANGE_SIGNAL_OBSERVER_TAG = "ContactsChangeSignals"

/**
 * تدفق إشارات التغيير **المحمي** (DEF-01 · Issue #43) — منطق الحماية الكامل
 * JVM-testable بنفس نمط [AndroidContactsSourceLogic]:
 *
 * 1. **حارس الصلاحية (A1):** بلا `READ_CONTACTS` لا تسجيل ولا نداء للمزوّد ولا
 *    أحداث مزيفة — تدفق خامل حتى إلغاء الجمع (نمط «مراقب خامل» في
 *    `ArhamCallStateObserver`). كان غياب هذا الحارس يرمي `SecurityException`
 *    قاتلة للعملية عند أول جمع على تثبيت جديد.
 * 2. **التقاط ضيق (A2):** نافذة سحبٍ بين الفحص والنداء البيني فقط — الالتقاط حول
 *    نداء التسجيل حصراً (لا `runCatching` حول `callbackFlow` كله، لا `Throwable`،
 *    ولا معالج استثناءات عام). الرفض المتوقع يُسجَّل بلا PII ويعود التدفق خاملاً
 *    بلا ادعاء نشاط؛ إعادة الاشتراك عند عودة الصلاحية من مسؤولية دورة حياة
 *    [ContactsSyncEngine] (النطاق B) — بلا حلقة إعادة محاولة ضيقة.
 * 3. **التوازن (A3):** إلغاء التسجيل مرة واحدة بعد نجاح التسجيل حصراً عبر
 *    `awaitClose`؛ تسجيل لم ينجح لا يُلغى إطلاقاً.
 */
internal fun observeChangeSignalsFlow(
    gateway: ContactsProviderAccessGateway,
    debounceMillis: Long,
): Flow<ContactsChangeSignal> = callbackFlow {
    var debounceJob: Job? = null
    if (!gateway.isReadPermissionGranted()) {
        awaitClose { }
        return@callbackFlow
    }
    val registration: AutoCloseable = try {
        gateway.registerChangeObserver(
            onChange = {
                debounceJob?.cancel()
                debounceJob = launch {
                    delay(debounceMillis)
                    trySend(ContactsChangeSignal(occurredAtMillis = System.currentTimeMillis()))
                }
            }
        )
    } catch (registrationDenied: SecurityException) {
        println(
            "$CHANGE_SIGNAL_OBSERVER_TAG: observer registration denied " +
                "(${registrationDenied.javaClass.simpleName}); observer idle until permission re-grant"
        )
        awaitClose { }
        return@callbackFlow
    }
    awaitClose {
        debounceJob?.cancel()
        registration.close()
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
    // المؤشر null = فشل Provider (عملية المزود قُتلت/غير متاح) — لا يُطوى إلى نجاح فارغ أبداً:
    // رميٌ هنا يُمسَك بـ runCatching في readPage (صفحة فارغة بالعقد) وreadDelta (بلا لقطة
    // جديدة — لا تطبيق جزئي ولا حذف وهمي). الإرجاع الفارغ الصامت كان يجعل scanRawContacts
    // يُبلّغ "كل السجلات اختفت" فيُمحى دفتر Room كله (D3).
    val cursor = query(uri, projection, selection, selectionArgs, sortOrder)
        ?: throw IllegalStateException("ContactsProvider returned a null cursor for $uri")
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
