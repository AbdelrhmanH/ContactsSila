package app.sila.core.database.entity

import app.sila.core.common.ArabicTextNormalizer
import app.sila.core.model.Address
import app.sila.core.model.ArhamInfo
import app.sila.core.model.CallDirection
import app.sila.core.model.CallEvent
import app.sila.core.model.Contact
import app.sila.core.model.ContactAccount
import app.sila.core.model.ContactName
import app.sila.core.model.CustomField
import app.sila.core.model.Email
import app.sila.core.model.ImportantDate
import app.sila.core.model.Organization
import app.sila.core.model.Phone
import app.sila.core.model.Rating
import app.sila.core.model.Service

/**
 * دوال التحويل بين كيانات قاعدة البيانات العلائقية (Entities) ونماذج النطاق (Domain Models)
 * طبقاً لـ CODING_GUIDELINES.md §2.
 *
 * PR B (ADR-0011): الكيانات هي الصورة الفيزيائية الحرفية — الحقول التي لا يملكها الجدول
 * الفيزيائي (Phone.label/preferredSimSlot، Service.geohash/lat/lng/…) تُشتق عند القراءة
 * بقيم افتراضية محايدة، مطابقةً لسلوك الشل القديمة عند البناء من 6/16 عموداً الفيزيائيين.
 */
fun ContactEntity.toDomain(
    phones: List<ContactPhoneEntity>,
    arham: ArhamEntity?,
    tags: List<String> = emptyList()
): Contact = Contact(
    id = id,
    systemContactId = systemContactId,
    lookupKey = lookupKey,
    name = ContactName(
        displayName = displayName,
        // صفوف L0 المعتمدة تملك fileAs = '' بعد الشيم — البديل القرائي displayName (سلوك تاريخي).
        fileAs = fileAs.ifEmpty { displayName },
        prefix = prefix,
        firstName = firstName,
        middleName = middleName,
        lastName = lastName,
        suffix = suffix,
        nickname = nickname,
        phoneticName = phoneticName
    ),
    photoUri = photoUri,
    phones = phones.map { it.toDomain() },
    organization = if (company != null || department != null) {
        Organization(company = company, department = department)
    } else {
        null
    },
    category = category,
    isFavorite = isFavorite,
    ringtoneUri = customRingtoneUri,
    sendToVoicemail = sendToVoicemail,
    isBlocked = isBlocked,
    excludeFromSmartFilter = excludeFromSmartFilter,
    notes = notes,
    tags = tags,
    account = if (accountType != null || accountName != null) {
        ContactAccount(accountType = accountType, accountName = accountName)
    } else {
        null
    },
    arham = arham?.toDomain(),
    normalizedName = normalizedName,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun ContactPhoneEntity.toDomain(): Phone = Phone(
    raw = raw,
    e164 = e164,
    type = type,
    // عمودا T6 (v2) — label فيزيائي الآن؛ preferredSimSlot لا يزال غير مُخزَّن.
    label = label,
    isPrimary = isPrimary,
    preferredSimSlot = null
)

fun ArhamEntity.toDomain(): ArhamInfo = ArhamInfo(
    relation = relation,
    layer = layer,
    parentContactId = parentContactId,
    frequency = frequency,
    lastContactedAt = lastContactedAt,
    pausedUntil = pausedUntil
)

fun Contact.toContactEntity(): ContactEntity {
    val normName = normalizedName.ifEmpty { ArabicTextNormalizer.normalize(name.displayName) }
    return ContactEntity(
        id = id,
        systemContactId = systemContactId,
        lookupKey = lookupKey,
        accountType = account?.accountType,
        accountName = account?.accountName,
        displayName = name.displayName,
        fileAs = name.fileAs.ifEmpty { name.displayName },
        prefix = name.prefix,
        firstName = name.firstName,
        middleName = name.middleName,
        lastName = name.lastName,
        suffix = name.suffix,
        nickname = name.nickname,
        phoneticName = name.phoneticName,
        company = organization?.company,
        department = organization?.department,
        photoUri = photoUri,
        category = category,
        isFavorite = isFavorite,
        customRingtoneUri = ringtoneUri,
        sendToVoicemail = sendToVoicemail,
        isBlocked = isBlocked,
        notes = notes,
        linkedServiceCloudId = linkedService?.cloudId,
        excludeFromSmartFilter = excludeFromSmartFilter,
        normalizedName = normName,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

fun Phone.toEntity(contactId: Long, sortOrder: Int = 0): ContactPhoneEntity = ContactPhoneEntity(
    id = 0L,
    contactId = contactId,
    raw = raw,
    e164 = e164,
    type = type,
    isPrimary = isPrimary,
    label = label,
    sortOrder = sortOrder
)

// ===== Mappers متعددة القيم — T6 (ترحيل v1→v2 · Issue #27 · ADR-0012) =====
// الملكية: الهواتف/البريد/المواقع معكوسة من المنصة؛ العناوين/التواريخ/الحقول المخصصة
// صِلَة-فقط. الترتيب دائماً `sortOrder` ثم `id` — موضع العنصر في قائمة النطاق هو مصدره.

fun ContactEmailEntity.toDomain(): Email = Email(
    address = address,
    type = type,
    label = label
)

fun Email.toEntity(contactId: Long, sortOrder: Int = 0): ContactEmailEntity = ContactEmailEntity(
    id = 0L,
    contactId = contactId,
    address = address,
    type = type,
    label = label,
    sortOrder = sortOrder
)

fun ContactAddressEntity.toDomain(): Address = Address(
    street = street,
    city = city,
    region = region,
    postalCode = postalCode,
    country = country,
    type = type
)

fun Address.toEntity(contactId: Long, sortOrder: Int = 0): ContactAddressEntity = ContactAddressEntity(
    id = 0L,
    contactId = contactId,
    street = street,
    city = city,
    region = region,
    postalCode = postalCode,
    country = country,
    type = type,
    sortOrder = sortOrder
)

fun ContactDateEntity.toDomain(): ImportantDate = ImportantDate(
    type = type,
    label = label,
    month = month,
    day = day,
    year = year
)

fun ImportantDate.toEntity(contactId: Long, sortOrder: Int = 0): ContactDateEntity = ContactDateEntity(
    id = 0L,
    contactId = contactId,
    type = type,
    label = label,
    month = month,
    day = day,
    year = year,
    sortOrder = sortOrder
)

fun ContactCustomFieldEntity.toDomain(): CustomField = CustomField(
    label = label,
    value = value
)

fun CustomField.toEntity(contactId: Long, sortOrder: Int = 0): ContactCustomFieldEntity =
    ContactCustomFieldEntity(
        id = 0L,
        contactId = contactId,
        label = label,
        value = value,
        sortOrder = sortOrder
    )

fun ContactWebsiteEntity.toDomain(): String = url

fun String.toWebsiteEntity(contactId: Long, sortOrder: Int = 0): ContactWebsiteEntity =
    ContactWebsiteEntity(
        id = 0L,
        contactId = contactId,
        url = this,
        sortOrder = sortOrder
    )

fun ArhamInfo.toEntity(contactId: Long, createdAt: Long = 0L): ArhamEntity = ArhamEntity(
    contactId = contactId,
    relation = relation,
    layer = layer,
    parentContactId = parentContactId,
    frequency = frequency,
    lastContactedAt = lastContactedAt,
    pausedUntil = pausedUntil,
    createdAt = createdAt
)

fun ServiceEntity.toDomain(): Service = Service(
    cloudId = cloudId,
    name = name,
    professionId = professionId,
    professionNameAr = professionNameAr,
    professionNameEn = professionNameEn,
    phoneE164 = phoneE164,
    visibility = visibility,
    trustScore = trustScore,
    uniqueCallers = uniqueCallers,
    retentionRate = retentionRate,
    ratingAvg = ratingAvg,
    ratingCount = ratingCount,
    isHot = isHot,
    // أعمدة غير مخزنة فيزيائياً — قيم محايدة مطابقة للسلوك التاريخي عند القراءة من 16 عموداً.
    geohash = null,
    lat = null,
    lng = null,
    city = city,
    ownerVerified = ownerVerified,
    isSavedByUser = isSavedByUser,
    myRating = null,
    myCallCount = 0,
    myLastCallAt = null,
    possiblyBroken = false,
    syncedAt = 0L
)

fun Service.toEntity(syncedAt: Long = 0L): ServiceEntity = ServiceEntity(
    cloudId = cloudId,
    name = name,
    professionId = professionId,
    professionNameAr = professionNameAr,
    professionNameEn = professionNameEn,
    phoneE164 = phoneE164,
    visibility = visibility,
    trustScore = trustScore,
    uniqueCallers = uniqueCallers,
    retentionRate = retentionRate,
    ratingAvg = ratingAvg,
    ratingCount = ratingCount,
    isHot = isHot,
    city = city,
    ownerVerified = ownerVerified,
    isSavedByUser = isSavedByUser
)

// ===== T8-03: خرائط أحداث المكالمات (CallEvent ↔ CallEventEntity · ADR-0006 · Issue #29) =====

/**
 * يبني صف v3 من حدث النطاق (خريطة T8-03 — لا افتراضات صامتة):
 * - `e164` مرآة بحث تُشتق من `remoteNumber` حصراً — الحدث **بلا رقم** لا يمكن استمرارته
 *   في v3 (عمود NOT NULL بلا قيمة مشروعة)؛ قرار استمرارية المرصود بلا رقم UNRESOLVED
 *   مؤجل إلى T8-06 (§G.3) — الإخفاق مغلق الصمام بلا اختلاق (§H.4). الرقم نفسه لا يدخل
 *   رسالة الخطأ (لا PII في السجلات/الاستثناءات).
 * - `direction = MISSED` لا يُكتب أبداً (حصرية لسجل الاتصال القديم — F-3 من مراجعة
 *   T8-01: انضباط الخريطة المطلوب في T8-03)؛ إشارة «لم يُرد» تُصنَّف باتجاه فارغ.
 *
 * @throws IllegalArgumentException حدث بلا رقم أو باتجاه `MISSED` — بلا أي كتابة.
 */
fun CallEvent.toCallEventEntity(): CallEventEntity {
    val e164Mirror = remoteNumber
        ?: throw IllegalArgumentException(
            "call event has no remoteNumber: v3 e164 mirror (NOT NULL) cannot be derived;" +
                " persisting unnumbered events is deferred (T8-06, plan §G.3 UNRESOLVED)",
        )
    require(direction != CallDirection.MISSED) {
        "call event direction=MISSED is legacy call-log only and must never be written (ADR-0006, T8-01 review F-3)"
    }
    return CallEventEntity(
        callLogId = id,
        e164 = e164Mirror,
        timestamp = timestamp,
        primarySource = primarySource,
        evidenceSources = evidenceSources,
        remoteNumber = remoteNumber,
        contactId = contactId,
        direction = direction,
        startedAt = startedAt,
        connectedAt = connectedAt,
        endedAt = endedAt,
        durationSec = durationSec,
        outcome = outcome,
        association = association,
        confirmation = confirmation,
        confidence = confidence,
        dedupeKey = dedupeKey,
        simSlot = simSlot,
    )
}

/**
 * يقرأ حدث النطاق من صف v3 (خريطة T8-03). مرآة `e164` عمود بحث حصراً ولا يملكه عقد
 * النطاق (`CallEvent` ينطق `remoteNumber`) — القراءة تمرّ ببقية الحقول السبعة عشر حرفاً
 * (صفوف الإرث المهاجرة: `remoteNumber` فارغ ومرآة `e164` لا تُنسى في النطاق — قراءة
 * الأحداث التامة للعرض/التجميع لا تحتاجها، و«التفاعلات الأخيرة» T6 تقرأها من العقد
 * المخصص `RecentInteraction`).
 */
fun CallEventEntity.toCallEvent(): CallEvent = CallEvent(
    id = callLogId,
    primarySource = primarySource,
    evidenceSources = evidenceSources,
    timestamp = timestamp,
    remoteNumber = remoteNumber,
    contactId = contactId,
    direction = direction,
    startedAt = startedAt,
    connectedAt = connectedAt,
    endedAt = endedAt,
    durationSec = durationSec,
    outcome = outcome,
    association = association,
    confirmation = confirmation,
    confidence = confidence,
    dedupeKey = dedupeKey,
    simSlot = simSlot,
)
