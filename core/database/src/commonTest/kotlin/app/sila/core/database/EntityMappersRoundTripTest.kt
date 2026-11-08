package app.sila.core.database

import app.sila.core.database.entity.toContactEntity
import app.sila.core.database.entity.toDomain
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * اختبار الذهاب-والعودة للمرسال بين `ContactEntity` ونموذج النطاق `Contact` (T7 — Issue #28).
 *
 * العيب المُصلَح: العمود `excludeFromSmartFilter` فيزيائي منذ v1 (defaultValue=0) والقراءة الخام
 * في `LocalSilaDatabase` تتعامل معه بولاء، لكن مسار المرسال كان يُسقطه قراءةً (`toDomain` لا يراه)
 * ويصفّره كتابةً (`toContactEntity` كان يرميه بـ false حرفياً) — فأي كتابة نطاقية كانت تمحو
 * الاستبعاد المحفوظ صمتاً. هذا الاختبار يثبت الولاء في الاتجاهين.
 */
class EntityMappersRoundTripTest {

    @Test
    fun excludeFromSmartFilter_survivesDomainRoundTrip_bothDirections() {
        val flagged = Contact(
            id = 7L,
            systemContactId = "t7_roundtrip_one",
            name = ContactName(displayName = "جهة مستثناة"),
            excludeFromSmartFilter = true,
        )

        val entity = flagged.toContactEntity()
        assertTrue(entity.excludeFromSmartFilter, "الكتابة نحو الكيان تحفظ الاستبعاد (كانت تُصفَّر حرفياً)")

        val restored = entity.toDomain(phones = emptyList(), arham = null)
        assertTrue(restored.excludeFromSmartFilter, "القراءة نحو النطاق تحفظ الاستبعاد (كانت مُسقَطة)")
    }

    @Test
    fun excludeFromSmartFilter_defaultsFalse_andRoundsTripClean() {
        val plain = Contact(
            id = 8L,
            systemContactId = "t7_roundtrip_two",
            name = ContactName(displayName = "جهة عادية"),
        )

        assertFalse(plain.excludeFromSmartFilter, "الافتراضي في النطاق غير مستبعد")
        val entity = plain.toContactEntity()
        assertFalse(entity.excludeFromSmartFilter)
        assertFalse(entity.toDomain(phones = emptyList(), arham = null).excludeFromSmartFilter)
    }
}
