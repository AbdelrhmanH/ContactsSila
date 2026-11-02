package app.sila.feature.contacts.data

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import app.sila.core.common.AppResult
import app.sila.core.common.DataError
import app.sila.core.model.ContactAccount
import app.sila.core.model.Email
import app.sila.core.model.Organization
import app.sila.core.model.Phone
import app.sila.feature.contacts.domain.ContactsPlatformWriter
import app.sila.feature.contacts.domain.CreatedPlatformContact
import app.sila.feature.contacts.domain.PlatformWritableContact

/**
 * تنفيذ Android لعقد الكتابة [ContactsPlatformWriter] (T6 · Issue #27 · ADR-0012):
 *
 * - **الإنشاء:** `applyBatch` واحدة — صف `RawContacts` (بحساب صريح من العرض: جهاز أو
 *   Google — لا SIM: المزود لا يسمح بإنشاء سجلات على حسابات SIM — قرار موثق) + صفوف
 *   `Data` بمراجع خلفية للسجل الجديد، ثم يُحلَّ `LOOKUP_KEY` للمجمّعة كاملة كهوية
 *   دائمة (ADR-0009 §4).
 * - **التحديث — طفير ضيق حصراً:** يُحدَّد السجل الخام الهدف (مطابقة الحساب وإلا الأول
 *   بـ `_ID`)، وتُحدَّث صفوف `Data` الخاصة به فقط عبر `Data._ID` مباشرة: الاسم/
 *   المؤسسة/الصورة صفوف مفردة، والأرقام/البريد/المواقع تطابق موضعي بمعرفات الصفوف
 *   (لا حذف/إعادة إدراج شامل). أنواع MIME غير المُدارة (Note/IM/SIP/Events/Relation/…)
 *   **لا تُمس إطلاقاً** — تنجو حرفياً (عقد ADR-0012).
 * - **الحذف:** عبر `CONTENT_LOOKUP_URI` (الهوية الدائمة) حصراً.
 * - **الصلاحية:** `WRITE_CONTACTS` تُفحص في كل عملية — الانعدام يعيد
 *   [DataError.Permission] ولا يُرمى استثناء.
 *
 * كل فشل مزود يُغلَّف [AppResult.Error] مُصنَّفاً — لا استثناءات عابرة للحد النطاقي.
 */
class AndroidContactsPlatformWriter(
    private val contentResolver: ContentResolver,
    private val applicationContext: Context,
) : ContactsPlatformWriter {

    override suspend fun createContact(writable: PlatformWritableContact): AppResult<CreatedPlatformContact> {
        if (!isWriteGranted()) return permissionError()
        return runCatching {
            val ops = ArrayList<ContentProviderOperation>()
            // 1) السجل الخام — الحساب يأتي من العرض (منتقي الحساب) أو جهاز (null/null).
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, writable.account?.accountType)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, writable.account?.accountName)
                    .build()
            )
            appendInsert(ops, rawContactIndex = 0, mimeType = StructuredNameMime, values = nameValues(writable))
            writable.name.nickname?.takeIf { it.isNotBlank() }?.let { nickname ->
                appendInsert(ops, rawContactIndex = 0, mimeType = NicknameMime, values = contentValuesOf(NicknameName to nickname))
            }
            writable.phones.forEach { appendInsert(ops, 0, PhoneMime, phoneValues(it)) }
            writable.emails.forEach { appendInsert(ops, 0, EmailMime, emailValues(it)) }
            writable.websites.forEach { appendInsert(ops, 0, WebsiteMime, websiteValues(it)) }
            writable.organization?.let { appendInsert(ops, 0, OrganizationMime, organizationValues(it)) }
            writable.photoBytes?.takeIf { it.isNotEmpty() }?.let {
                appendInsert(ops, 0, PhotoMime, photoValues(it))
            }

            val results = contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            val rawContactId = results.firstOrNull()?.uri?.lastPathSegment?.toLongOrNull()
                ?: error("applyBatch returned no raw-contact URI")
            resolveLookupKey(rawContactId)
                ?: error("LOOKUP_KEY not resolvable for new raw contact $rawContactId")
        }.fold(
            onSuccess = { lookupKey ->
                AppResult.Success(CreatedPlatformContact(systemContactId = lookupKey, lookupKey = lookupKey))
            },
            onFailure = { t -> AppResult.Error(DataError.Unknown(causeMessage = t.message)) }
        )
    }

    override suspend fun updateContact(writable: PlatformWritableContact): AppResult<Unit> {
        if (!isWriteGranted()) return permissionError()
        val systemId = writable.systemContactId
            ?: return AppResult.Error(DataError.Validation(field = "systemContactId", reason = "update requires identity"))
        return runCatching {
            val aggregateId = resolveAggregateId(systemId, writable.lookupKey)
                ?: error("aggregate not resolvable for $systemId")
            val targetRawId = resolveTargetRawContactId(aggregateId, writable.account)
                ?: error("no live raw contact under aggregate $aggregateId")
            val existing = dataRowsForRawContact(targetRawId)

            val ops = ArrayList<ContentProviderOperation>()

            // الاسم — صف مفرد: تحديث بالمعرف أو إدراج بمعرف السجل الخام المعروف.
            val nameRow = existing.firstOrNull { it.mimeType == StructuredNameMime }
            if (nameRow != null) {
                ops.add(updateOp(nameRow.id, nameValues(writable)))
            } else {
                ops.add(insertOp(targetRawId, StructuredNameMime, nameValues(writable)))
            }

            // اللقب — صف مفرد (MIME Nickname): غيابه من الرغبة = حذف الصف القائم —
            // وإلا بقي لقباً شبحاً يعود عبر المزامنة بعد كل مسح في النموذج.
            val nicknameRow = existing.firstOrNull { it.mimeType == NicknameMime }
            val nickname = writable.name.nickname?.takeIf { it.isNotBlank() }
            when {
                nickname == null && nicknameRow != null -> ops.add(deleteOp(nicknameRow.id))
                nickname != null && nicknameRow != null ->
                    ops.add(updateOp(nicknameRow.id, contentValuesOf(NicknameName to nickname)))
                nickname != null ->
                    ops.add(insertOp(targetRawId, NicknameMime, contentValuesOf(NicknameName to nickname)))
            }

            // المؤسسة — صف مفرد: غيابها من الرغبة = حذف الصف القائم (حقل مُدار).
            val orgRow = existing.firstOrNull { it.mimeType == OrganizationMime }
            val org = writable.organization
            when {
                org == null && orgRow != null -> ops.add(deleteOp(orgRow.id))
                org != null && orgRow != null -> ops.add(updateOp(orgRow.id, organizationValues(org)))
                org != null -> ops.add(insertOp(targetRawId, OrganizationMime, organizationValues(org)))
            }

            // المجموعات متعددة القيم — تطابق موضعي بمعرفات صفوف `_ID` (عقد الكاتب).
            applyChildSet(ops, targetRawId, existing, PhoneMime, writable.phones) { phoneValues(it) }
            applyChildSet(ops, targetRawId, existing, EmailMime, writable.emails) { emailValues(it) }
            applyChildSet(ops, targetRawId, existing, WebsiteMime, writable.websites) { websiteValues(it) }

            // الصورة — إشارة الإزالة = بايتات فارغة؛ null = لا مسّ (عقد الإسقاط).
            val photoRow = existing.firstOrNull { it.mimeType == PhotoMime }
            val bytes = writable.photoBytes
            when {
                bytes == null -> Unit
                bytes.isEmpty() && photoRow != null -> ops.add(deleteOp(photoRow.id))
                bytes.isEmpty() -> Unit
                photoRow != null -> ops.add(updateOp(photoRow.id, photoValues(bytes)))
                else -> ops.add(insertOp(targetRawId, PhotoMime, photoValues(bytes)))
            }

            if (ops.isNotEmpty()) contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        }.fold(
            onSuccess = { AppResult.Success(Unit) },
            onFailure = { t -> AppResult.Error(DataError.Unknown(causeMessage = t.message)) }
        )
    }

    override suspend fun deleteContact(systemContactId: String, lookupKey: String?): AppResult<Unit> {
        if (!isWriteGranted()) return permissionError()
        return runCatching {
            val key = lookupKey ?: systemContactId
            val lookupUri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, key)
            if (contentResolver.delete(lookupUri, null, null) <= 0) {
                error("aggregate not resolvable for lookup $key")
            }
        }.fold(
            onSuccess = { AppResult.Success(Unit) },
            onFailure = { t -> AppResult.Error(DataError.Unknown(causeMessage = t.message)) }
        )
    }

    // ---------------------------------------------------------------- بناء العمليات

    private fun <T> applyChildSet(
        ops: MutableList<ContentProviderOperation>,
        targetRawId: Long,
        existing: List<DataRowLite>,
        mimeType: String,
        desired: List<T>,
        toValues: (T) -> ContentValues,
    ) {
        val ordered = existing.filter { it.mimeType == mimeType }.sortedBy { it.id }
        val overlap = minOf(ordered.size, desired.size)
        for (index in 0 until overlap) {
            ops.add(updateOp(ordered[index].id, toValues(desired[index])))
        }
        for (index in overlap until desired.size) {
            ops.add(insertOp(targetRawId, mimeType, toValues(desired[index])))
        }
        for (index in overlap until ordered.size) {
            ops.add(deleteOp(ordered[index].id))
        }
    }

    /** تحديث صف `Data` بمعرفه — الطفير الضيق الحرفي (لا إعادة إدراج ولا مسّ للسجل). */
    private fun updateOp(dataId: Long, values: ContentValues): ContentProviderOperation {
        val builder = ContentProviderOperation.newUpdate(dataUri(dataId))
        for (key in values.keySet()) builder.withValue(key, values.get(key))
        return builder.build()
    }

    private fun deleteOp(dataId: Long): ContentProviderOperation =
        ContentProviderOperation.newDelete(dataUri(dataId)).build()

    /**
     * إدراج صف `Data` تحت السجل الخام الهدف — في مسار الإنشاء يُستبدل [rawContactId]
     * بمرجع خلفي للفهرس 0 (السجل لم يُنشأ بعد)؛ في مسار التحديث المعرف معلوم.
     */
    private fun insertOp(rawContactId: Long, mimeType: String, values: ContentValues): ContentProviderOperation {
        val builder = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValue(ContactsContract.Data.MIMETYPE, mimeType)
        for (key in values.keySet()) builder.withValue(key, values.get(key))
        if (rawContactId >= 0) {
            builder.withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
        }
        return builder.build()
    }

    private fun appendInsert(
        ops: MutableList<ContentProviderOperation>,
        rawContactIndex: Int,
        mimeType: String,
        values: ContentValues,
    ) {
        val builder = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactIndex)
            .withValue(ContactsContract.Data.MIMETYPE, mimeType)
        for (key in values.keySet()) builder.withValue(key, values.get(key))
        ops.add(builder.build())
    }

    // ---------------------------------------------------------------- قيم الصفوف (مرايا قارئ T1 — round-trip متماثل)

    private fun nameValues(writable: PlatformWritableContact): ContentValues = with(writable.name) {
        contentValuesOf(
            StructuredNameDisplay to displayName,
            StructuredNameGiven to firstName,
            StructuredNameFamily to lastName,
            StructuredNameMiddle to middleName,
            StructuredNamePrefix to prefix,
            StructuredNameSuffix to suffix,
            // الصوتي مُجزَّأ في القارئ (DATA7/8/9) ويُكتب موحداً في PHONETIC_GIVEN مع
            // تصفير الوسط/العائلة — توحيد موضع بلا فقد، وبلا انجراف عبر حفظات متكررة
            // (القرار الموثق في T6؛ التصفير يمنع إعادة ضم قيمة قديمة إلى السلسلة).
            StructuredNamePhoneticGiven to phoneticName,
            StructuredNamePhoneticMiddle to null,
            StructuredNamePhoneticFamily to null,
        )
    }

    private fun phoneValues(phone: Phone): ContentValues = ContentValues().apply {
        put(PhoneNumber, phone.raw)
        val typeInt = phoneTypeInt(phone.type)
        put(PhoneType, typeInt)
        // label صِلَة-فقط لا يُكتب أبداً (ADR-0012)؛ لكن type غير المعياري ورد من
        // المنصة كتسمية مخصصة — تُعاد كما وردت (مرآة لا ملكية).
        if (typeInt == ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM) {
            put(PhoneLabel, phone.type)
        }
    }

    private fun emailValues(email: Email): ContentValues = ContentValues().apply {
        put(EmailAddress, email.address)
        val typeInt = emailTypeInt(email.type)
        put(EmailType, typeInt)
        if (typeInt == ContactsContract.CommonDataKinds.Email.TYPE_CUSTOM) {
            put(EmailLabel, email.type)
        }
    }

    private fun websiteValues(url: String): ContentValues = contentValuesOf(
        WebsiteUrl to url,
        WebsiteType to ContactsContract.CommonDataKinds.Website.TYPE_OTHER,
    )

    private fun organizationValues(org: Organization): ContentValues = contentValuesOf(
        OrganizationCompany to org.company,
        OrganizationDepartment to org.department,
        OrganizationTitle to org.jobTitles.firstOrNull(),
        OrganizationType to ContactsContract.CommonDataKinds.Organization.TYPE_WORK,
    )

    private fun photoValues(bytes: ByteArray): ContentValues = contentValuesOf(PhotoBytes to bytes)

    private fun contentValuesOf(vararg pairs: Pair<String, Any?>): ContentValues = ContentValues().apply {
        pairs.forEach { (key, value) ->
            when (value) {
                null -> putNull(key)
                is String -> put(key, value)
                is Int -> put(key, value)
                is ByteArray -> put(key, value)
            }
        }
    }

    // ---------------------------------------------------------------- محلولات الهوية وقراءات Data

    /** حلّ معرّف المجمّعة من الهوية الدائمة (LOOKUP_URI أولاً — ADR-0009 §4). */
    private fun resolveAggregateId(systemContactId: String, lookupKey: String?): Long? {
        val key = lookupKey ?: systemContactId
        val lookupUri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, key)
        queryFirstLong(
            uri = lookupUri,
            projection = arrayOf(ContactsContract.Contacts._ID),
            selection = null,
            selectionArgs = null,
            column = ContactsContract.Contacts._ID,
        )?.let { return it }
        // مفتاح قديم أُعيد توليده (مزامن خارجي) — محاولة أنجيلية عبر معرف السجل الخام.
        return queryFirstLong(
            uri = ContactsContract.RawContacts.CONTENT_URI,
            projection = arrayOf(ContactsContract.RawContacts.CONTACT_ID),
            selection = "${ContactsContract.RawContacts._ID} = ?",
            selectionArgs = arrayOf(systemContactId),
            column = ContactsContract.RawContacts.CONTACT_ID,
        )
    }

    /** السجل الخام الهدف: مطابقة الحساب أولاً ثم الأقدم `_ID` (عقد الكاتب — KDoc الصف). */
    private fun resolveTargetRawContactId(aggregateId: Long, account: ContactAccount?): Long? {
        data class RawRow(val id: Long, val accountType: String?, val accountName: String?)

        val rows = contentResolver.useQuery(
            uri = ContactsContract.RawContacts.CONTENT_URI,
            projection = arrayOf(
                ContactsContract.RawContacts._ID,
                ContactsContract.RawContacts.ACCOUNT_TYPE,
                ContactsContract.RawContacts.ACCOUNT_NAME,
            ),
            selection = "${ContactsContract.RawContacts.CONTACT_ID} = ? AND ${ContactsContract.RawContacts.DELETED} = 0",
            selectionArgs = arrayOf(aggregateId.toString()),
            sortOrder = "${ContactsContract.RawContacts._ID} ASC",
        ) { cursor ->
            cursor.collect {
                RawRow(
                    id = it.getLong(it.getColumnIndexOrThrow(ContactsContract.RawContacts._ID)),
                    accountType = it.getString(it.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_TYPE)),
                    accountName = it.getString(it.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_NAME)),
                )
            }
        }
        return rows.firstOrNull {
            account != null && it.accountType == account.accountType && it.accountName == account.accountName
        }?.id ?: rows.firstOrNull()?.id
    }

    /** صفوف `Data` للسجل الخام الهدف — المدخل الوحيد للطفير الضيق بمعرفات الصفوف. */
    private fun dataRowsForRawContact(rawContactId: Long): List<DataRowLite> =
        contentResolver.useQuery(
            uri = ContactsContract.Data.CONTENT_URI,
            projection = arrayOf(ContactsContract.Data._ID, ContactsContract.Data.MIMETYPE),
            selection = "${ContactsContract.Data.RAW_CONTACT_ID} = ?",
            selectionArgs = arrayOf(rawContactId.toString()),
            sortOrder = "${ContactsContract.Data._ID} ASC",
        ) { cursor ->
            cursor.collect {
                DataRowLite(
                    id = it.getLong(it.getColumnIndexOrThrow(ContactsContract.Data._ID)),
                    mimeType = it.getString(it.getColumnIndexOrThrow(ContactsContract.Data.MIMETYPE)).orEmpty(),
                )
            }
        }

    private fun resolveLookupKey(rawContactId: Long): String? {
        val aggregateId = queryFirstLong(
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
            if (cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.LOOKUP_KEY))
            } else {
                null
            }
        }
    }

    private fun queryFirstLong(
        uri: Uri,
        projection: Array<String>,
        selection: String?,
        selectionArgs: Array<String>?,
        column: String,
    ): Long? = contentResolver.useQuery(uri, projection, selection, selectionArgs) { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(cursor.getColumnIndex(column))) {
            cursor.getLong(cursor.getColumnIndex(column))
        } else {
            null
        }
    }

    // ---------------------------------------------------------------- أدوات

    private fun isWriteGranted(): Boolean =
        applicationContext.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun permissionError(): AppResult.Error =
        AppResult.Error(DataError.Permission(permissionName = WRITE_PERMISSION_NAME))

    private fun dataUri(dataId: Long): Uri =
        ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, dataId)

    private inline fun <T> ContentResolver.useQuery(
        uri: Uri,
        projection: Array<String>,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
        sortOrder: String? = null,
        block: (Cursor) -> T,
    ): T {
        val cursor = query(uri, projection, selection, selectionArgs, sortOrder)
            ?: throw IllegalStateException("ContactsProvider returned a null cursor for $uri")
        return cursor.use { block(it) }
    }

    private companion object {
        const val WRITE_PERMISSION_NAME = "android.permission.WRITE_CONTACTS"

        val StructuredNameMime = ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
        val NicknameMime = ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE
        val PhoneMime = ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
        val EmailMime = ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE
        val WebsiteMime = ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE
        val OrganizationMime = ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE
        val PhotoMime = ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE

        val StructuredNameDisplay = ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME
        val StructuredNameGiven = ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME
        val StructuredNameFamily = ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME
        val StructuredNameMiddle = ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME
        val StructuredNamePrefix = ContactsContract.CommonDataKinds.StructuredName.PREFIX
        val StructuredNameSuffix = ContactsContract.CommonDataKinds.StructuredName.SUFFIX
        val StructuredNamePhoneticGiven = ContactsContract.CommonDataKinds.StructuredName.PHONETIC_GIVEN_NAME
        val StructuredNamePhoneticMiddle = ContactsContract.CommonDataKinds.StructuredName.PHONETIC_MIDDLE_NAME
        val StructuredNamePhoneticFamily = ContactsContract.CommonDataKinds.StructuredName.PHONETIC_FAMILY_NAME
        val NicknameName = ContactsContract.CommonDataKinds.Nickname.NAME

        val PhoneNumber = ContactsContract.CommonDataKinds.Phone.NUMBER
        val PhoneType = ContactsContract.CommonDataKinds.Phone.TYPE
        val PhoneLabel = ContactsContract.CommonDataKinds.Phone.LABEL

        val EmailAddress = ContactsContract.CommonDataKinds.Email.ADDRESS
        val EmailType = ContactsContract.CommonDataKinds.Email.TYPE
        val EmailLabel = ContactsContract.CommonDataKinds.Email.LABEL

        val WebsiteUrl = ContactsContract.CommonDataKinds.Website.URL
        val WebsiteType = ContactsContract.CommonDataKinds.Website.TYPE

        val OrganizationCompany = ContactsContract.CommonDataKinds.Organization.COMPANY
        val OrganizationDepartment = ContactsContract.CommonDataKinds.Organization.DEPARTMENT
        val OrganizationTitle = ContactsContract.CommonDataKinds.Organization.TITLE
        val OrganizationType = ContactsContract.CommonDataKinds.Organization.TYPE

        val PhotoBytes = ContactsContract.CommonDataKinds.Photo.PHOTO
    }
}

/** صف `Data` محدود لغرض الطفير الضيق (معرّف + نوع MIME فقط — لا قيم تُقرأ هنا). */
private data class DataRowLite(val id: Long, val mimeType: String)

/** عكس [mapAndroidPhoneType] حرفياً — جدول `ContactsContract.Phone`. */
internal fun phoneTypeInt(type: String): Int = when (type.uppercase()) {
    "HOME" -> ContactsContract.CommonDataKinds.Phone.TYPE_HOME
    "MOBILE" -> ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
    "WORK" -> ContactsContract.CommonDataKinds.Phone.TYPE_WORK
    "WORK_FAX" -> ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK
    "HOME_FAX" -> ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME
    "PAGER" -> ContactsContract.CommonDataKinds.Phone.TYPE_PAGER
    "CALLBACK" -> ContactsContract.CommonDataKinds.Phone.TYPE_CALLBACK
    "CAR" -> ContactsContract.CommonDataKinds.Phone.TYPE_CAR
    "COMPANY_MAIN" -> ContactsContract.CommonDataKinds.Phone.TYPE_COMPANY_MAIN
    "MAIN" -> ContactsContract.CommonDataKinds.Phone.TYPE_MAIN
    "WORK_MOBILE" -> ContactsContract.CommonDataKinds.Phone.TYPE_WORK_MOBILE
    "WORK_PAGER" -> ContactsContract.CommonDataKinds.Phone.TYPE_WORK_PAGER
    else -> ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM // type غير المعياري = تسمية منصوية مخصصة
}

/** عكس [mapAndroidEmailType] حرفياً — جدول `ContactsContract.Email`. */
internal fun emailTypeInt(type: String): Int = when (type.uppercase()) {
    "HOME" -> ContactsContract.CommonDataKinds.Email.TYPE_HOME
    "WORK" -> ContactsContract.CommonDataKinds.Email.TYPE_WORK
    "MOBILE" -> ContactsContract.CommonDataKinds.Email.TYPE_MOBILE
    else -> ContactsContract.CommonDataKinds.Email.TYPE_CUSTOM
}

/** يجمع عناصر المؤشر كاملاً بإغلاقه تلقائياً — مرجع واحد لمسح الصفوف (نمط T1). */
private inline fun <T> Cursor.collect(block: (Cursor) -> T): List<T> {
    val out = ArrayList<T>(count)
    while (moveToNext()) out.add(block(this))
    return out
}
