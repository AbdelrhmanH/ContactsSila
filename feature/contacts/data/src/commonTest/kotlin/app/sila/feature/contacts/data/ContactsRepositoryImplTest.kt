package app.sila.feature.contacts.data

import app.sila.core.database.dao.LocalSilaDatabase
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.Phone
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ContactsRepositoryImplTest {

    @Test
    fun contactsRepository_upsertUpdateCategoryAndDelete_persistsThroughDao() = runTest {
        val db = LocalSilaDatabase()
        try {
            val repo = ContactsRepositoryImpl(db)

            val newContact = Contact(
                id = 500L,
                systemContactId = "sys_500",
                name = ContactName(displayName = "د. طارق نبيل"),
                phones = listOf(Phone(raw = "01055554444", e164 = "+201055554444", isPrimary = true)),
                category = Category.UNCATEGORIZED
            )
            repo.upsertContact(newContact)

            val fetched = repo.getContactById(500L)
            assertNotNull(fetched)
            assertEquals(Category.UNCATEGORIZED, fetched.category)

            repo.updateCategory(500L, Category.SERVICES)
            assertEquals(Category.SERVICES, repo.getContactById(500L)?.category)

            val servicesContacts = repo.observeContacts(category = Category.SERVICES).first()
            assertEquals(true, servicesContacts.any { it.id == 500L })

            repo.deleteContact(500L)
            assertNull(repo.getContactById(500L))
        } finally {
            db.closeConnection()
        }
    }
}
