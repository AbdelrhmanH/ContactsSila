package app.sila.feature

import app.sila.core.database.dao.ContactDao
import app.sila.core.database.dao.ServiceDao
import app.sila.core.model.ArhamFrequency
import app.sila.core.model.ArhamInfo
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import app.sila.core.model.Phone
import app.sila.core.model.Relation
import app.sila.core.model.Service
import app.sila.core.model.ServiceVisibility
import app.sila.di.ensureKoinStarted
import app.sila.feature.arham.domain.TopFiveMaterializationRepository
import app.sila.feature.arham.presentation.ArhamViewModel
import app.sila.feature.contacts.presentation.ContactsViewModel
import app.sila.feature.services.presentation.ServicesViewModel
import app.sila.feature.settings.presentation.SettingsViewModel
import app.sila.maintenance.ArhamMaterializationCoordinator
import app.sila.maintenance.RetentionCleanupCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FeatureViewModelsTest {

    @BeforeTest
    fun setUp() {
        // عزل Koin العام على K/N (عدّاء الاختبارات عملية واحدة): صنفٌ سابق قد يترك
        // السياق العام مشدوداً — كل اختبار يبدأ من حاوية نظيفة (المرآة في tearDown).
        stopKoin()
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    /** ViewModels الحالية في الحاوية — تُسجَّل عند الحل (لا getOrNull متأخر يُنشئ
     * وحيدات جديدة عند فشلٍ سابق) ويُلغى نطاقها **بالانضمام** قبل stopKoin. */
    private val resolvedViewModels = mutableListOf<androidx.lifecycle.ViewModel>()

    @AfterTest
    fun tearDown() {
        // نطاقات ViewModels ملك لأصحابها — من حلّها في الاختبار يلغيها قبل إغلاق
        // الحاوية، **بالانضمام** (لا مجرد طلب إلغاء): جامعات Room على IO تخرج من
        // المُشغِّل فعلياً قبل stopKoin فلا تلمس تجمع اتصالات مغلقاً (تكامل دورة حياة
        // الموارد). runBlocking مقيد بمهلة في اختبار مشترك — سابقة المستودع.
        runBlocking {
            withTimeout(5_000) {
                resolvedViewModels.forEach { vm ->
                    runCatching {
                        vm.viewModelScope.coroutineContext[Job]!!.cancelAndJoin()
                    }
                }
            }
        }
        resolvedViewModels.clear()
        stopKoin()
        Dispatchers.resetMain()
    }

    /** تركيبة جهة صريحة لأرحام متأخرة (آخر تواصل قديم) — تُدرج عبر الـ DAO حصراً. */
    private fun arhamContactFixture(systemContactId: String, displayName: String) = Contact(
        id = 0L,
        systemContactId = systemContactId,
        name = ContactName(displayName = displayName),
        phones = listOf(Phone(raw = "01000000000", e164 = "+201000000000", isPrimary = true)),
        category = Category.FAMILY,
        arham = ArhamInfo(
            relation = Relation.UNCLE_PATERNAL,
            frequency = ArhamFrequency.WEEKLY,
            lastContactedAt = 1_700_000_000_000L
        ),
        normalizedName = displayName
    )

    @Test
    fun koinContainer_resolvesAllFeatureViewModelsAndSingletons() = runTest {
        val koin = ensureKoinStarted()

        // F-01: الإنتاج لا يبذر بيانات عرض — كل شاشة تُثبت بياناتها صراحةً عبر الـ DAOs.
        // (الاختبار يتحقق من حلّ ViewModels ومن تدفق الحالة فوق بيانات الاختبار نفسها.)
        koin.get<ContactDao>().upsertContact(arhamContactFixture("fvt_contact_1", "جهة أرحام الاختبار"))
        koin.get<ServiceDao>().upsertServices(
            listOf(
                Service(
                    cloudId = "fvt_srv_1",
                    name = "خدمة الاختبار",
                    professionId = "plumber",
                    professionNameAr = "سباك",
                    professionNameEn = "Plumber",
                    phoneE164 = "+201000000001",
                    visibility = ServiceVisibility.VERIFIED,
                    trustScore = 90.0,
                    uniqueCallers = 10,
                    retentionRate = 0.8,
                    ratingAvg = 2.5,
                    ratingCount = 5,
                    isHot = false,
                    city = "القاهرة",
                    isAvailableNow = true
                )
            )
        )

        // F-02: مشغِّل صيانة الاستبقاء مُسجَّل في حاوية الإنتاج (يُطلقه مهيئا المنصتين
        // SilaApplication / IosKoinBootstrap مرة واحدة لكل عملية) — إسقاط التسجيل يُفشل هنا.
        assertNotNull(koin.get<RetentionCleanupCoordinator>())

        // T8-15 (F-1): مشغِّل تجسيد «أهم 5» وربط مستودعه (فوق DAO+DataStore) مُسجَّلان
        // في حاوية الإنتاج — إسقاط التسجيل يُفشل هنا (مرآة F-02 أعلاه).
        assertNotNull(koin.get<ArhamMaterializationCoordinator>())
        assertNotNull(koin.get<TopFiveMaterializationRepository>())

        val contactsVm = koin.get<ContactsViewModel>()
        val arhamVm = koin.get<ArhamViewModel>()
        val servicesVm = koin.get<ServicesViewModel>()
        val settingsVm = koin.get<SettingsViewModel>()
        resolvedViewModels += listOf(contactsVm, arhamVm, servicesVm, settingsVm)

        assertNotNull(contactsVm)
        assertNotNull(arhamVm)
        assertNotNull(servicesVm)
        assertNotNull(settingsVm)

        val contactsState = contactsVm.state.first { it.contacts.isNotEmpty() }
        val arhamState = arhamVm.state.first { it.items.isNotEmpty() }
        val servicesState = servicesVm.state.first { it.services.isNotEmpty() }

        assertTrue(contactsState.contacts.any { it.systemContactId == "fvt_contact_1" })
        assertTrue(arhamState.items.isNotEmpty())
        assertTrue(servicesState.services.any { it.cloudId == "fvt_srv_1" })
    }
}
