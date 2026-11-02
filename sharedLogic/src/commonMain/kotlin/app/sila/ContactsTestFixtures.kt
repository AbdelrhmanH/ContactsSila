package app.sila

import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.feature.contacts.domain.ContactsSyncState
import app.sila.feature.contacts.presentation.ContactsUiState

/**
 * واجهة توافق Swift محدودة (T4 — Issue #25) — **للاختبارات حصراً، ليست جزءاً من API منطق التطبيق.**
 *
 * لماذا توجد أصلاً؟ تصدير Objective-C من Kotlin/Native لا يُصدِّر المُنشئات المُعامَلة
 * للفئات التي يحتوي مُنشئها الأولي معاملات افتراضية (دليل CI: الـ run 41483416994 —
 * «argument passed to call that takes no arguments» لـ Contact/ContactName/ContactsUiState)،
 * لذا لا يستطيع Swift إنشاء هذه الأنواع مطلقاً. دوال المصنع هذه ذات معاملات صريحة كاملة
 * (بلا معاملات افتراضية) لتكون قابلة للاستدعاء عبر SKIE، وتوفر نقطة إنشاء وحيدة لبيانات
 * اختبار XCTest لربط جهات الاتصال. لا منطق أعمال هنا — تفويض حرفي للمُنشئات الحقيقية.
 */
object ContactsTestFixtures {

    fun createContactName(displayName: String): ContactName = ContactName(displayName = displayName)

    fun createContact(id: Long, systemContactId: String, name: ContactName): Contact =
        Contact(id = id, systemContactId = systemContactId, name = name)

    fun createContactsUiState(
        isLoading: Boolean,
        searchQuery: String,
        contacts: List<Contact>,
        syncState: ContactsSyncState,
    ): ContactsUiState = ContactsUiState(
        isLoading = isLoading,
        searchQuery = searchQuery,
        contacts = contacts,
        syncState = syncState,
    )
}
