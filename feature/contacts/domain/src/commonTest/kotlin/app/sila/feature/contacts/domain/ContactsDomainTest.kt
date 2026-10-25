package app.sila.feature.contacts.domain

import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.Phone
import kotlin.test.Test
import kotlin.test.assertEquals

class ContactsDomainTest {

    private val smartCategorize = SmartCategorizeContactUseCase()
    private val detectDuplicates = DetectDuplicatesUseCase()

    @Test
    fun smartCategorize_correctlyClassifiesFamilyServicesEmergencyAndWork() {
        assertEquals(Category.FAMILY, smartCategorize("خالتي منى", "01012345678"))
        assertEquals(Category.SERVICES, smartCategorize("سباك المعادي", "01112345678"))
        assertEquals(Category.SERVICES, smartCategorize("مطعم البركة", "19011"))
        assertEquals(Category.EMERGENCY, smartCategorize("الإسعاف", "123"))
        assertEquals(Category.WORK, smartCategorize("م. طارق علي", "01212345678", hasCompany = true))
    }

    @Test
    fun openClosedPrinciple_customCategorizationRuleExtendsBehaviorWithoutModifyingUseCase() {
        val universityFriendsRule = ContactCategorizationRule { normalizedName, _, _, _ ->
            if (normalizedName.contains("دفعه")) Category.FRIENDS else null
        }
        val extendedUseCase = SmartCategorizeContactUseCase(
            rules = SmartCategorizeContactUseCase.defaultRules() + universityFriendsRule
        )

        assertEquals(Category.FRIENDS, extendedUseCase("زياد دفعة هندسة", "01099998888"))
    }

    @Test
    fun detectDuplicates_findsContactsWithSameE164OrNormalizedArabicName() {
        val contacts = listOf(
            Contact(
                id = 1L,
                systemContactId = "1",
                name = ContactName("أحمد إبراهيم"),
                phones = listOf(Phone(raw = "010 1234 5678", e164 = "+201012345678"))
            ),
            Contact(
                id = 2L,
                systemContactId = "2",
                name = ContactName("احمد ابراهيم"),
                phones = listOf(Phone(raw = "+201012345678", e164 = "+201012345678"))
            )
        )

        val groups = detectDuplicates(contacts)
        assertEquals(1, groups.size)
        assertEquals(2, groups.first().contacts.size)
    }
}
