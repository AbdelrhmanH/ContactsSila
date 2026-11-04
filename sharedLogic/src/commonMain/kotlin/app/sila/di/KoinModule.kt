package app.sila.di

import app.sila.core.common.AppClock
import app.sila.core.common.AppDispatchers
import app.sila.core.common.DefaultAppDispatchers
import app.sila.core.common.SystemAppClock
import app.sila.core.database.SilaRoomDatabase
import app.sila.core.database.dao.ArhamDao
import app.sila.core.database.dao.ContactDao
import app.sila.core.database.dao.ContactsSyncDao
import app.sila.core.database.dao.DataRetentionCleanupDao
import app.sila.core.database.dao.LocalSilaDatabase
import app.sila.core.database.dao.ServiceDao
import app.sila.core.datastore.SilaPreferencesDataSource
import app.sila.core.datastore.UserPreferencesDataSource
import app.sila.feature.arham.data.ArhamCallEventRepositoryImpl
import app.sila.feature.arham.data.ArhamRepositoryImpl
import app.sila.feature.arham.domain.ArhamCallEventRepository
import app.sila.feature.arham.domain.ArhamCelebrationPolicy
import app.sila.feature.arham.domain.ArhamRepository
import app.sila.feature.arham.domain.BuildArhamFamilyTreeUseCase
import app.sila.feature.arham.domain.CallEventStateMachine
import app.sila.feature.arham.domain.CelebrateTransitionUseCase
import app.sila.feature.arham.domain.GetArhamDashboardStatsUseCase
import app.sila.feature.arham.domain.GetArhamPriorityListUseCase
import app.sila.feature.arham.domain.IngestCallEventUseCase
import app.sila.feature.arham.domain.ObservePendingConfirmationsUseCase
import app.sila.feature.arham.domain.ObserveUpcomingOccasionsUseCase
import app.sila.feature.arham.domain.PlanArhamDailyNotificationUseCase
import app.sila.feature.arham.domain.RecordDialAttemptUseCase
import app.sila.feature.arham.domain.RecordManualContactUseCase
import app.sila.feature.arham.domain.ResolveConfirmationUseCase
import app.sila.feature.arham.presentation.ArhamViewModel
import app.sila.feature.contacts.data.ContactsListPreferencesRepositoryImpl
import app.sila.feature.contacts.data.ContactsRepositoryImpl
import app.sila.feature.contacts.data.ContactsSyncEngine
import app.sila.feature.contacts.data.OnboardingRepositoryImpl
import app.sila.feature.contacts.data.SyncStateSourceAdapter
import app.sila.feature.contacts.domain.ArhamOccasionRecorder
import app.sila.feature.contacts.domain.BulkCategorizeUseCase
import app.sila.feature.contacts.domain.BulkSetFavoriteUseCase
import app.sila.feature.contacts.domain.ContactsListPreferencesRepository
import app.sila.feature.contacts.domain.ContactsRepository
import app.sila.feature.contacts.domain.CreateArhamOccasionUseCase
import app.sila.feature.contacts.domain.DeleteContactUseCase
import app.sila.feature.contacts.domain.DeleteContactWithUndoUseCase
import app.sila.feature.contacts.domain.FindDuplicateContactUseCase
import app.sila.feature.contacts.domain.DeleteContactsUseCase
import app.sila.feature.contacts.domain.DetectDuplicatesUseCase
import app.sila.feature.contacts.domain.ObserveCategoryCountsUseCase
import app.sila.feature.contacts.domain.ObserveContactDetailsUseCase
import app.sila.feature.contacts.domain.ObserveContactsPagedUseCase
import app.sila.feature.contacts.domain.ObserveContactsUseCase
import app.sila.feature.contacts.domain.ObserveFavoriteContactsUseCase
import app.sila.feature.contacts.domain.ObserveSuggestionsUseCase
import app.sila.feature.contacts.domain.RecordInAppCallUseCase
import app.sila.feature.contacts.domain.SaveContactUseCase
import app.sila.feature.contacts.domain.SearchContactsUseCase
import app.sila.feature.contacts.domain.SmartCategorizeContactUseCase
import app.sila.feature.contacts.domain.SyncStateSource
import app.sila.feature.contacts.domain.ToggleFavoriteUseCase
import app.sila.feature.contacts.domain.UpdateContactCategoryUseCase
import app.sila.feature.contacts.domain.onboarding.CompleteOnboardingStepUseCase
import app.sila.feature.contacts.domain.onboarding.CompleteOnboardingUseCase
import app.sila.feature.contacts.domain.onboarding.ObserveOnboardingStateUseCase
import app.sila.feature.contacts.domain.onboarding.OnboardingRepository
import app.sila.feature.contacts.domain.onboarding.SaveTopFiveSelectionUseCase
import app.sila.feature.contacts.domain.onboarding.SetOnboardingPermissionsResultUseCase
import app.sila.feature.contacts.presentation.ContactDetailsViewModel
import app.sila.feature.contacts.presentation.ContactEditViewModel
import app.sila.feature.contacts.presentation.ContactsViewModel
import app.sila.feature.contacts.presentation.onboarding.OnboardingViewModel
import app.sila.feature.main.MainViewModel
import app.sila.feature.services.data.ServicesRepositoryImpl
import app.sila.feature.services.domain.LookupServiceByPhoneUseCase
import app.sila.feature.services.domain.ObserveServicesDirectoryUseCase
import app.sila.feature.services.domain.ServicesRepository
import app.sila.feature.services.presentation.ServicesViewModel
import app.sila.feature.settings.data.SettingsRepositoryImpl
import app.sila.feature.settings.domain.ObserveUserPreferencesUseCase
import app.sila.feature.settings.domain.SetArhamNotificationsUseCase
import app.sila.feature.settings.domain.SetLanguageUseCase
import app.sila.feature.settings.domain.SetSmartFilterUseCase
import app.sila.feature.settings.domain.SetThemeModeUseCase
import app.sila.feature.settings.domain.SettingsRepository
import app.sila.feature.settings.presentation.SettingsViewModel
import app.sila.maintenance.RetentionCleanupCoordinator
import org.koin.core.Koin
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module
import org.koin.dsl.onClose
import org.koin.mp.KoinPlatformTools

/**
 * وحدة حقن التبعيات المشتركة لتطبيق صِلَة (ARCHITECTURE.md §1-2 & ADR-0007).
 * تطبق مبادئ SOLID:
 * - SRP & ISP: واجهات DAO مجزّأة لكل نطاق (ContactDao, ArhamDao, ServiceDao, DataRetentionCleanupDao, ContactsSyncDao) تنفّذها [LocalSilaDatabase] مباشرة (PR B · ADR-0011).
 * - SRP: تسجيل كل حالة استخدام (UseCase) بمسؤولية واحدة.
 * - DIP: حقن التجريدات (Interfaces & UseCases) في الـ ViewModels عبر Koin.
 */
fun createSilaSharedModule(storageDirPath: String? = null): Module = module {
    single<AppClock> { SystemAppClock }
    single<AppDispatchers> { DefaultAppDispatchers() }

    // مصدر جهات الاتصال من المنصة (Issue #22 · ADR-0009 §3) — actuals per-platform.
    includes(platformContactsSourceModule)

    // كتابة جهات الاتصال إلى المنصة (T6 · Issue #27 · ADR-0012) — actuals per-platform:
    // الكاتب الضيق + معالج الصورة + كاشف التطبيقات المتصلة.
    includes(platformContactsWriterModule)

    // جدولة إشعارات أرحام على المنصة (T8-08 · D2) — actuals per-platform:
    // أندرويد فوق AlarmManager (بلا دقة — D2)، وiOS فارغة حتى T8-12.
    includes(platformArhamNotificationModule)

    // قاعدة Room KMP الوحيدة (PR B · ADR-0011): [LocalSilaDatabase] هي واجهة الوصول
    // الموحّدة — تنفّذ واجهات DAO الخمس مباشرة (كتابات مركّبة بـ SQL خام داخل معاملات
    // Room، وقراءات عبر DAOs الكيانات المولَّدة)، و[SilaRoomDatabase] صاحبة المخطط.
    // F-01: بلا بذرة تجريبية في الإنتاج — `seedDemoData` غير مُمرَّرة (افتراضياً `false` حصراً).
    // `":memory:"` عند غياب المسار حاضنة اختبارات فقط؛ مهيّئا الإنتاج
    // (SilaApplication / IosKoinBootstrap) يمرّران مساراً ملفياً حقيقياً دائماً.
    single {
        val dbPath = storageDirPath?.let { "$it/sila_local.db" } ?: ":memory:"
        LocalSilaDatabase(dbFilePath = dbPath, dispatchers = get())
    } onClose {
        it?.closeConnection()
    }
    single<SilaRoomDatabase> { get<LocalSilaDatabase>().room }
    single<ContactDao> { get<LocalSilaDatabase>() }
    single<ArhamDao> { get<LocalSilaDatabase>() }
    single<ServiceDao> { get<LocalSilaDatabase>() }
    single<DataRetentionCleanupDao> { get<LocalSilaDatabase>() }
    single<ContactsSyncDao> { get<LocalSilaDatabase>() }

    // محرك المزامنة التزايدية لجهات الاتصال (Issue #23 · ADR-0010) — يُشغَّل من مهيئات المنصات
    // (SilaApplication / IosKoinBootstrap) عبر start(scope) بمعرّف idempotent.
    single {
        ContactsSyncEngine(
            platformSource = get(),
            syncDao = get(),
            clock = get()
        )
    }

    // F-02: مشغِّل صيانة الاستبقاء (تنظيف 30/90/365 يوماً) — يُطلق من مهيئات المنصات
    // على نطاق عمر التطبيق، مرة واحدة لكل عملية (single-flight — انظر الكلاس).
    single { RetentionCleanupCoordinator(cleanup = get(), clock = get()) }

    // Core DataStore (Preferences Persistence Singleton)
    single<UserPreferencesDataSource> {
        val prefsPath = storageDirPath?.let { "$it/${SilaPreferencesDataSource.DATASTORE_FILE_NAME}" }
        SilaPreferencesDataSource(storageFilePath = prefsPath)
    }

    // :feature:contacts (Domain UseCases + Data Repository + Presentation ViewModel)
    single<ContactsRepository> { ContactsRepositoryImpl(contactDao = get()) }

    // T3 (Issue #24): تفضيلات القائمة (استبعاد الاقتراحات + أفعال السحب) ومصدر حالة المزامنة.
    single<ContactsListPreferencesRepository> { ContactsListPreferencesRepositoryImpl(dataSource = get()) }
    single<SyncStateSource> { SyncStateSourceAdapter(engine = get()) }

    factory { ObserveContactsUseCase(repository = get()) }
    factory { ObserveFavoriteContactsUseCase(repository = get()) }
    factory { ObserveCategoryCountsUseCase(repository = get()) }
    factory { SearchContactsUseCase(repository = get()) }
    factory { ObserveContactsPagedUseCase(repository = get()) }
    factory { ObserveSuggestionsUseCase(repository = get(), listPreferences = get()) }
    factory { UpdateContactCategoryUseCase(repository = get()) }
    factory { DeleteContactUseCase(repository = get()) }
    factory { SmartCategorizeContactUseCase() }
    factory { DetectDuplicatesUseCase() }
    // T3 (Issue #24): أفعال الاختيار المتعدد + تسجيل محاولات الاتصال IN_APP.
    factory { BulkCategorizeUseCase(repository = get()) }
    factory { BulkSetFavoriteUseCase(repository = get()) }
    factory { DeleteContactsUseCase(repository = get()) }
    factory { ToggleFavoriteUseCase(repository = get()) }
    factory { RecordInAppCallUseCase(repository = get(), clock = get()) }
    // T6 (Issue #27 · ADR-0012): تفاصيل/تحرير جهة — ميثاق الاتساق J-1 يعيش في SaveContactUseCase،
    // وجسر أرحام J-4 يُنفَّذ في جذر التركيب (ArhamBridgeOccasionRecorder) بعكس التبعية.
    single<ArhamOccasionRecorder> {
        ArhamBridgeOccasionRecorder(arhamRepository = get())
    }
    factory { SaveContactUseCase(writer = get(), repository = get(), imageProcessor = get(), clock = get()) }
    factory { ObserveContactDetailsUseCase(repository = get()) }
    factory { DeleteContactWithUndoUseCase(writer = get(), repository = get(), clock = get()) }
    factory { CreateArhamOccasionUseCase(recorder = get()) }
    factory { FindDuplicateContactUseCase(repository = get()) }
    // T6 — ViewModels التفاصيل/التحرير (معرفات تُمرَّر من التنقل عبر parametersOf).
    factory { (contactId: Long) ->
        ContactDetailsViewModel(
            contactId = contactId,
            observeDetails = get(),
            deleteWithUndo = get(),
            toggleFavorite = get(),
            changeCategory = get(),
            recordInAppCall = get(),
            connectedAppsDetector = get(),
            appClock = get(),
        )
    }
    factory { (contactId: Long?) ->
        ContactEditViewModel(
            contactId = contactId,
            observeDetails = get(),
            saveContact = get(),
            createArhamOccasion = get(),
            findDuplicate = get(),
        )
    }
    single {
        ContactsViewModel(
            observeContactsPaged = get(),
            observeFavorites = get(),
            observeCategoryCounts = get(),
            changeContactCategory = get(),
            bulkCategorize = get(),
            deleteContact = get(),
            deleteContacts = get(),
            toggleFavorite = get(),
            bulkSetFavorite = get(),
            recordInAppCall = get(),
            observeSuggestions = get(),
            listPreferences = get(),
            syncStateSource = get()
        )
    }

    // :feature:contacts — التهيئة الأولى للمضيف (Guest onboarding) (Issue #26 — T5)
    single<OnboardingRepository> { OnboardingRepositoryImpl(dataSource = get()) }
    factory { ObserveOnboardingStateUseCase(repository = get()) }
    factory { CompleteOnboardingStepUseCase(repository = get()) }
    factory { SetOnboardingPermissionsResultUseCase(repository = get()) }
    factory { SaveTopFiveSelectionUseCase(repository = get()) }
    factory { CompleteOnboardingUseCase(repository = get()) }
    single {
        OnboardingViewModel(
            observeOnboardingState = get(),
            completeStep = get(),
            setPermissionsResult = get(),
            saveTopFiveSelection = get(),
            finishOnboarding = get(),
            observeContacts = get()
        )
    }

    // :feature:arham (Domain UseCases + Data Repository + Presentation ViewModel)
    single<ArhamRepository> { ArhamRepositoryImpl(arhamDao = get()) }

    // T8-06 (Issue #29): استمرارية أحداث المكالمات فوق آلة الحالات (ADR-0006) —
    // جذر التركيب هو المكان الوحيد الذي يعرف المستودع والآلة معاً (DIP). المكوّنات
    // الإدخالية كانت غير مسجَّلة قبل T8-06 (خلل D.3-4): مسار زر الاتصال كان يكتب
    // آخر تواصل بلا بوابة AC-8.
    single<ArhamCallEventRepository> { ArhamCallEventRepositoryImpl(arhamDao = get()) }
    single { CallEventStateMachine() }
    factory { IngestCallEventUseCase(repository = get(), stateMachine = get()) }
    factory { RecordDialAttemptUseCase(ingest = get(), stateMachine = get()) }
    factory { ResolveConfirmationUseCase(repository = get(), stateMachine = get()) }
    factory { RecordManualContactUseCase(repository = get(), stateMachine = get()) }
    factory { ObservePendingConfirmationsUseCase(repository = get()) }
    single<ArhamCelebrationPolicy> { ArhamCelebrationPolicyBridge(userPreferencesDataSource = get()) }
    factory { CelebrateTransitionUseCase(policy = get(), clock = get()) }

    factory { GetArhamPriorityListUseCase(repository = get(), clock = get()) }

    // T8-08 (Issue #29): اشتعال مناسبات أرحام القادمة (D1 مشتق — تسجيل Koin الأول، O-4)
    // وتخطيط التذكير اليومي (§M): موعد + محتوى قرارٌ حتّي مشترك، والمنصة تُنفّذ.
    factory { ObserveUpcomingOccasionsUseCase(repository = get(), clock = get()) }
    factory {
        PlanArhamDailyNotificationUseCase(
            getPriorityList = get(),
            observeUpcomingOccasions = get(),
            clock = get(),
        )
    }

    // T8-07 (Issue #29): تجميع اللوحة من أحداث حقيقية فوق قاعدة AC-8 (آلة الحالات مصدر
    // القاعدة الوحيد) + بناء شجرة العائلة من الطبقات/الآباء.
    factory {
        GetArhamDashboardStatsUseCase(
            arhamRepository = get(),
            callEventRepository = get(),
            clock = get(),
            stateMachine = get(),
        )
    }
    factory { BuildArhamFamilyTreeUseCase(repository = get(), clock = get()) }
    single {
        ArhamViewModel(
            getPriorityList = get(),
            getDashboardStats = get(),
            recordDialAttempt = get(),
            celebrateTransition = get(),
            clock = get()
        )
    }

    // :feature:services (Domain UseCases + Data Repository + Presentation ViewModel)
    single<ServicesRepository> { ServicesRepositoryImpl(serviceDao = get()) }
    factory { ObserveServicesDirectoryUseCase(repository = get()) }
    factory { LookupServiceByPhoneUseCase(repository = get()) }
    single { ServicesViewModel(observeServicesDirectory = get()) }

    // :feature:settings (Domain UseCases + Data Repository + Presentation ViewModel)
    single<SettingsRepository> { SettingsRepositoryImpl(dataSource = get()) }
    factory { ObserveUserPreferencesUseCase(repository = get()) }
    factory { SetThemeModeUseCase(repository = get()) }
    factory { SetLanguageUseCase(repository = get()) }
    factory { SetArhamNotificationsUseCase(repository = get()) }
    factory { SetSmartFilterUseCase(repository = get()) }
    single {
        SettingsViewModel(
            observePreferences = get(),
            setThemeMode = get(),
            setLanguage = get(),
            setArhamNotifications = get(),
            setSmartFilter = get()
        )
    }

    // Umbrella Shell Coordinator ViewModel
    single {
        MainViewModel(
            contactsRepository = get(),
            recordDialAttempt = get(),
            servicesRepository = get(),
            getArhamPriorityList = get(),
            getArhamStats = get(),
            settingsRepository = get(),
            clock = get()
        )
    }
}

val silaSharedModule: Module = createSilaSharedModule(storageDirPath = null)

fun initKoin(
    storageDirPath: String? = null,
    appDeclaration: KoinAppDeclaration = {},
): KoinApplication = startKoin {
    appDeclaration()
    modules(createSilaSharedModule(storageDirPath))
}

/**
 * تضمن تشغيل Koin مرة واحدة فقط في دورة حياة التطبيق (Idempotent).
 * [appDeclaration] يُمرَّر لـ [initKoin] (على Android يستخدمه SilaApplication لتزويد androidContext).
 */
fun ensureKoinStarted(
    storageDirPath: String? = null,
    appDeclaration: KoinAppDeclaration = {},
): Koin = KoinPlatformTools.defaultContext().getOrNull()
    ?: initKoin(storageDirPath = storageDirPath, appDeclaration = appDeclaration).koin
