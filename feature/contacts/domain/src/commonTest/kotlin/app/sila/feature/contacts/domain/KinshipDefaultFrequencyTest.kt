package app.sila.feature.contacts.domain

import app.sila.core.model.ArhamFrequency
import app.sila.core.model.Relation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * عقد الوتيرة الافتراضية لجسر أرحام (T7 · agy F5): **كل** قرابة تحمل وتيرة غير-صفرية —
 * الافتراضي الفارغ كان يُسقط في UseCase أرحام.
 */
class KinshipDefaultFrequencyTest {

    @Test
    fun everyRelation_hasADefaultFrequency() {
        Relation.entries.forEach { relation ->
            assertNotNull(
                KinshipDefaultFrequency.of(relation),
                "قرابة بلا وتيرة افتراضية: $relation",
            )
        }
    }

    @Test
    fun frequencyLadder_matchesTheDocumentedTable() {
        assertEquals(ArhamFrequency.DAILY, KinshipDefaultFrequency.of(Relation.FATHER))
        assertEquals(ArhamFrequency.DAILY, KinshipDefaultFrequency.of(Relation.MOTHER))
        assertEquals(ArhamFrequency.DAILY, KinshipDefaultFrequency.of(Relation.SPOUSE))
        assertEquals(ArhamFrequency.DAILY, KinshipDefaultFrequency.of(Relation.SON))
        assertEquals(ArhamFrequency.DAILY, KinshipDefaultFrequency.of(Relation.DAUGHTER))

        assertEquals(ArhamFrequency.WEEKLY, KinshipDefaultFrequency.of(Relation.GRANDFATHER))
        assertEquals(ArhamFrequency.WEEKLY, KinshipDefaultFrequency.of(Relation.GRANDMOTHER))
        assertEquals(ArhamFrequency.WEEKLY, KinshipDefaultFrequency.of(Relation.BROTHER))
        assertEquals(ArhamFrequency.WEEKLY, KinshipDefaultFrequency.of(Relation.SISTER))

        assertEquals(ArhamFrequency.BIWEEKLY, KinshipDefaultFrequency.of(Relation.UNCLE_PATERNAL))
        assertEquals(ArhamFrequency.BIWEEKLY, KinshipDefaultFrequency.of(Relation.AUNT_MATERNAL))

        assertEquals(ArhamFrequency.MONTHLY, KinshipDefaultFrequency.of(Relation.COUSIN_PATERNAL))
        assertEquals(ArhamFrequency.MONTHLY, KinshipDefaultFrequency.of(Relation.COUSIN_MATERNAL))

        assertEquals(ArhamFrequency.OCCASIONS, KinshipDefaultFrequency.of(Relation.IN_LAW))
        assertEquals(ArhamFrequency.OCCASIONS, KinshipDefaultFrequency.of(Relation.CLOSE_FRIEND))
        assertEquals(ArhamFrequency.OCCASIONS, KinshipDefaultFrequency.of(Relation.CUSTOM))
    }
}
