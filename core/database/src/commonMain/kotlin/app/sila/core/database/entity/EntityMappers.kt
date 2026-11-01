package app.sila.core.database.entity

import app.sila.core.common.ArabicTextNormalizer
import app.sila.core.model.ArhamInfo
import app.sila.core.model.Contact
import app.sila.core.model.ContactAccount
import app.sila.core.model.ContactName
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
    arham: ArhamEntity?
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
    notes = notes,
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
    // لا عمودان فيزيائيان لها — قيمة محايدة مطابقة للسلوك التاريخي عند القراءة.
    label = null,
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
        excludeFromSmartFilter = false,
        normalizedName = normName,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

fun Phone.toEntity(contactId: Long): ContactPhoneEntity = ContactPhoneEntity(
    id = 0L,
    contactId = contactId,
    raw = raw,
    e164 = e164,
    type = type,
    isPrimary = isPrimary
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
