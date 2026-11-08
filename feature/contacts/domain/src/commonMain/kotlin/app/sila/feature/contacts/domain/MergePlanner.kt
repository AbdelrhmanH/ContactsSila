package app.sila.feature.contacts.domain

import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.Phone

/**
 * مُخطِّط الدمج النقي (T7 — Issue #28 · spec 04 §أ «قواعد الدمج») — بلا I/O ولا ساعة ولا
 * مستودعات: يُدخل أعضاء المجموعة ويُخرج الخطة الكاملة (الناجي + الممتصون + المعاينة).
 *
 * **اختيار الناجي حتمي**: الأقدم إنشاءً (createdAt) ثم الأصغر id — نفس أساس قاعدة أرحام
 * («للأقدم إنشاءً») فيبقى هوية المنصة للناجي ثابتة والاسترجاع الإنشائي متسقاً.
 *
 * قواعد كل حقل (المواصفة + agy F4/F8):
 * - الاسم: الأكمل (أكثر المكونات غير الفارغة) ثم الأطول displayName ثم الناجي.
 * - الأرقام: اتحاد بـ E.164 (والفارغ بـ raw) بترتيب الناجي ثم الممتصين زمنياً — بلا تكرار.
 * - البريد/العناوين/المواقع: اتحاد بقيمة مطبَّعة. التواريخ: اتحاد بالصيغة الكاملة
 *   `(type,label,month,day,year)`. الحقول المخصصة: اتحاد بـ
 *   `(label,value)` مطبَّعَين — كلها مخزَّنة فعلاً في جداول v2 (agy F4).
 * - الصورة: الناجي وإلا أول غير فارغ زمنياً (مقارنة الدقة تحتاج قراءات منصوية ثنائية —
 *   خارج النطاق النقي؛ سياسة حتمية موثقة تُعرض في المعاينة).
 * - الملاحظات: دمج المتميزة غير الفارغة بفاصل [NOTES_SEPARATOR] الموثق.
 * - التصنيف: أولوية صريحة [CATEGORY_PRIORITY] — لا UNCATEGORIZED ما دام هناك معنوي
 *   (ترتيب التعداد نفسه ليس أولوية الدمج — فخ موثق).
 * - المفضلة: OR. النغمة: الوحيدة كما هي؛ المتعددة → ناغم الناجي + [ringtoneConflict]
 *   للمعاينة (لا إسقاط صامت — القيم الممتصة تُعرض).
 * - excludeFromSmartFilter/إلى-البريد-الصوتي/المحظور: الناجي وإلا OR المحافظ.
 */
object MergePlanner {

    /** الفاصل الموثق لدمج الملاحظات (GLOSSARY + spec 04 §أ «دمج بفاصل»). */
    const val NOTES_SEPARATOR = "\n—\n"

    /** أولوية التصنيف عند الدمج — عمداً ليست ترتيب التعداد. */
    private val CATEGORY_PRIORITY = listOf(
        Category.FAMILY, Category.EMERGENCY, Category.WORK, Category.FRIENDS, Category.SERVICES,
    )

    data class MergePlan(
        val merged: Contact,
        val absorbed: List<Contact>,
        val ringtoneConflict: Boolean,
    )

    fun plan(contacts: List<Contact>): MergePlan {
        require(contacts.size >= 2) { "دمج يتطلب عضوين على الأقل" }
        val survivor = contacts.minWith(compareBy({ it.createdAt }, { it.id }))
        val absorbed = contacts.filterNot { it.id == survivor.id }.sortedBy { it.createdAt }

        val merged = Contact(
            id = survivor.id,
            systemContactId = survivor.systemContactId,
            lookupKey = survivor.lookupKey,
            name = mostCompleteName(contacts, survivor),
            photoUri = survivor.photoUri ?: absorbed.firstNotNullOfOrNull { it.photoUri },
            phones = unionPhones(contacts),
            emails = unionBy(contacts, { it.emails }, { it.address.trim().lowercase() }),
            addresses = unionBy(contacts, { it.addresses }, { listOf(it.street, it.city, it.region, it.postalCode, it.country, it.type).joinToString("|") }),
            organization = survivor.organization
                ?: absorbed.firstOrNull { it.organization != null }?.organization,
            dates = unionBy(contacts, { it.dates }, { listOf(it.type, it.label, it.month, it.day, it.year).joinToString("|") }),
            customFields = unionBy(
                contacts,
                { it.customFields },
                { (it.label.trim().lowercase() to it.value.trim().lowercase()).toString() },
            ),
            websites = contacts.flatMap { it.websites }.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            tags = contacts.flatMap { it.tags }.distinct(),
            category = mergedCategory(contacts),
            isFavorite = contacts.any { it.isFavorite },
            ringtoneUri = mergedRingtone(survivor, absorbed)?.first,
            sendToVoicemail = survivor.sendToVoicemail || absorbed.any { it.sendToVoicemail },
            isBlocked = survivor.isBlocked || absorbed.any { it.isBlocked },
            excludeFromSmartFilter = contacts.any { it.excludeFromSmartFilter },
            notes = mergedNotes(contacts),
            account = survivor.account,
            arham = survivor.arham,
            linkedService = survivor.linkedService ?: absorbed.firstNotNullOfOrNull { it.linkedService },
            normalizedName = survivor.normalizedName,
            createdAt = survivor.createdAt,
            updatedAt = survivor.updatedAt,
        )

        return MergePlan(
            merged = merged,
            absorbed = absorbed,
            ringtoneConflict = distinctRingtones(survivor, absorbed).size > 1,
        )
    }

    /** ثنائية (النغمة المختارة، تعارض؟) — الوحيدة كما هي؛ المتعددة نغمة الناجي + تعارض. */
    fun mergedRingtone(survivor: Contact, absorbed: List<Contact>): Pair<String?, Boolean> {
        val distinct = distinctRingtones(survivor, absorbed)
        return when (distinct.size) {
            0 -> null to false
            1 -> distinct.first() to false
            else -> survivor.ringtoneUri to true
        }
    }

    private fun distinctRingtones(survivor: Contact, absorbed: List<Contact>): List<String> =
        (listOf(survivor) + absorbed).mapNotNull { it.ringtoneUri?.takeIf { uri -> uri.isNotBlank() } }.distinct()

    private fun mostCompleteName(contacts: List<Contact>, survivor: Contact) =
        contacts.maxWith(
            compareBy(
                { it.name.let { n -> listOf(n.prefix, n.firstName, n.middleName, n.lastName, n.suffix, n.nickname, n.phoneticName).count { part -> !part.isNullOrBlank() } } },
                { it.name.displayName.length },
                { it.id == survivor.id }, // التعادل الأخير: الناجي
            ),
        ).name

    private fun unionPhones(contacts: List<Contact>): List<Phone> {
        val seenE164 = mutableSetOf<String>()
        val seenRaw = mutableSetOf<String>()
        val result = mutableListOf<Phone>()
        for (contact in contacts) {
            for (phone in contact.phones) {
                val key = phone.e164.takeIf { it.isNotBlank() }?.let { "e:$it" }
                    ?: phone.raw.trim().takeIf { it.isNotEmpty() }?.let { "r:$it" }
                    ?: continue
                val seen = if (key.startsWith("e:")) !seenE164.add(key.substring(2)) else !seenRaw.add(key.substring(2))
                if (!seen) result += phone
            }
        }
        // isPrimary واحد حصراً: الأول الذي كان أساسياً وإلا أول صف — يُفرَض على الصف المختار
        val primaryIndex = result.indexOfFirst { it.isPrimary }
        val chosen = if (primaryIndex >= 0) primaryIndex else 0
        return result.mapIndexed { index, phone -> phone.copy(isPrimary = index == chosen) }
    }

    private inline fun <reified T, reified K> unionBy(
        contacts: List<Contact>,
        items: (Contact) -> List<T>,
        key: (T) -> K,
    ): List<T> {
        val seen = mutableSetOf<K>()
        val result = mutableListOf<T>()
        for (contact in contacts) {
            for (item in items(contact)) {
                if (seen.add(key(item))) result += item
            }
        }
        return result
    }

    private fun mergedCategory(contacts: List<Contact>): Category =
        CATEGORY_PRIORITY.firstOrNull { priority -> contacts.any { it.category == priority } }
            ?: contacts.firstOrNull { it.category != Category.UNCATEGORIZED }?.category
            ?: Category.UNCATEGORIZED

    private fun mergedNotes(contacts: List<Contact>): String? {
        val distinct = contacts.mapNotNull { it.notes?.trim()?.takeIf(String::isNotEmpty) }.distinct()
        return when (distinct.size) {
            0 -> null
            1 -> distinct.first()
            else -> distinct.joinToString(NOTES_SEPARATOR)
        }
    }
}
