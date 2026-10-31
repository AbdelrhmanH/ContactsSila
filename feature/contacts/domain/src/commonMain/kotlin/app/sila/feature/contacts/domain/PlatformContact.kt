package app.sila.feature.contacts.domain

import app.sila.core.model.ContactAccount
import app.sila.core.model.ContactName
import app.sila.core.model.Email
import app.sila.core.model.Organization
import app.sila.core.model.Phone
import kotlinx.serialization.Serializable

/**
 * جهة اتصال كما تظهر من دفتر هاتف المستخدم (Contacts Provider على Android / CNContactStore على iOS).
 *
 * دلالات الهوية (ADR-0009):
 * - **Android:** `systemContactId` يحمل قيمة `ContactsContract.Contacts.LOOKUP_KEY`،
 *   و[lookupKey] مرآة لنفس القيمة (كي يُبنى `CONTENT_LOOKUP_URI` لاحقاً)؛
 *   أما `Contacts._ID` فلا يُخزَّن ولا يُستخدم لهوية.
 * - **iOS:** `systemContactId` يحمل قيمة `CNContact.identifier` وتبقى [lookupKey] فارغاً.
 *
 * تعيد استخدام أنواع :core:model طبيعياً (اسم، أرقام، بريد، مؤسسة، حساب).
 */
@Serializable
data class PlatformContact(
    /**
     * الهوية الدائمة الموحّدة بين المنصتين: مقابلة لعمود `contacts.systemContactId` (ADR-0009).
     */
    val systemContactId: String,
    /** Android: mirror of `ContactsContract.LOOKUP_KEY` — null on iOS (ADR-0009 §4). */
    val lookupKey: String? = null,
    val name: ContactName,
    val photoUri: String? = null,
    val phones: List<Phone> = emptyList(),
    val emails: List<Email> = emptyList(),
    val organization: Organization? = null,
    val websites: List<String> = emptyList(),
    val notes: String? = null,
    val account: ContactAccount? = null,
)
