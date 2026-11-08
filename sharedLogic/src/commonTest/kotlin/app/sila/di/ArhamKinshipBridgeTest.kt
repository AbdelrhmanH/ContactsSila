package app.sila.di

import app.sila.core.common.AppClock
import app.sila.core.common.AppDispatchers
import app.sila.core.common.AppResult
import app.sila.core.database.dao.LocalSilaDatabase
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.Relation
import app.sila.feature.arham.data.ArhamRepositoryImpl
import app.sila.feature.contacts.domain.KinshipDefaultFrequency
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * جسر ربط القرابة (T7 · agy F5): نداء **مباشر** على مستودع أرحام (لا UpdateKinshipUseCase
 * الذي يُخفق NotFound على المُصنَّف للتو) — فوق مستودع حقيقي فوق قاعدة In-Memory
 * (نمط MainViewModelTest)، مع وتيرة افتراضية من [KinshipDefaultFrequency] لكل قرابة.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArhamKinshipBridgeTest {

    private val unconfinedDispatchers = object : AppDispatchers {
        override val main = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
    }
    private val fixedClock = object : AppClock {
        override fun now(): kotlinx.datetime.Instant =
            kotlinx.datetime.Instant.fromEpochMilliseconds(1_760_000_000_000L)

        override fun nowEpochMillis(): Long = 1_760_000_000_000L
    }

    private lateinit var db: LocalSilaDatabase

    @BeforeTest
    fun setUp() {
        db = LocalSilaDatabase(dispatchers = unconfinedDispatchers, seedDemoData = false)
    }

    @AfterTest
    fun tearDown() {
        db.closeConnection()
    }

    @Test
    fun link_writesArhamRowDirectly_withDefaultFrequency() = runTest {
        val repository = ArhamRepositoryImpl(db)
        val bridge = ArhamBridgeKinshipLinker(arhamRepository = repository)
        // جهة موجودة في Room (بلا صف أرحام) — الحالة الحرفية للمرشّح المُصنَّف للتو
        db.upsertContact(app.sila.core.model.Contact(
            id = 5L,
            systemContactId = "sys_5",
            name = app.sila.core.model.ContactName(displayName = "خوي خالد"),
        ))

        val result = bridge.link(contactId = 5L, relation = Relation.BROTHER)

        assertTrue(result is AppResult.Success, "الربط المباشر ينجح — لا فخ NotFound (agy F5)")
        val linked = repository.observeArhamContacts().first().first { it.id == 5L }
        assertEquals(Relation.BROTHER, linked.arham?.relation, "صف أرحام كُتب مباشرة للجهة")
        assertEquals(
            KinshipDefaultFrequency.of(Relation.BROTHER),
            linked.arham?.frequency,
            "وتيرة افتراضية غير-صفرية من الجسر",
        )
        assertEquals(ArhamFrequency.WEEKLY, linked.arham?.frequency)
    }
}
