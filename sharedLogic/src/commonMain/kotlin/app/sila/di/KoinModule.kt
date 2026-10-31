package app.sila.di

import app.sila.core.common.AppClock
import app.sila.core.common.AppDispatchers
import app.sila.core.common.DefaultAppDispatchers
import app.sila.core.common.SystemAppClock
import app.sila.core.database.SilaRoomDatabase
import app.sila.core.database.dao.ArhamDao
import app.sila.core.database.dao.ContactDao
import app.sila.core.database.dao.DataRetentionCleanupDao
import app.sila.core.database.dao.LocalSilaDatabase
import app.sila.core.database.dao.ServiceDao
import app.sila.core.datastore.SilaPreferencesDataSource
import app.sila.core.datastore.UserPreferencesDataSource
import app.sila.feature.arham.data.ArhamRepositoryImpl
import app.sila.feature.arham.domain.ArhamRepository
import app.sila.feature.arham.domain.GetArhamDashboardStatsUseCase
import app.sila.feature.arham.domain.GetArhamPriorityListUseCase
import app.sila.feature.arham.domain.RecordArhamCallUseCase
import app.sila.feature.arham.presentation.ArhamViewModel
import app.sila.feature.contacts.data.ContactsRepositoryImpl
import app.sila.feature.contacts.domain.ContactsRepository
import app.sila.feature.contacts.domain.DeleteContactUseCase
import app.sila.feature.contacts.domain.DetectDuplicatesUseCase
import app.sila.feature.contacts.domain.ObserveCategoryCountsUseCase
import app.sila.feature.contacts.domain.ObserveContactsUseCase
import app.sila.feature.contacts.domain.ObserveFavoriteContactsUseCase
import app.sila.feature.contacts.domain.SmartCategorizeContactUseCase
import app.sila.feature.contacts.domain.UpdateContactCategoryUseCase
import app.sila.feature.contacts.presentation.ContactsViewModel
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
import org.koin.core.Koin
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools

/**
 * وحدة حقن التبعيات المشتركة لتطبيق صِلَة (ARCHITECTURE.md §1-2 & ADR-0007).
 * تطبق مبادئ SOLID:
 * - SRP & ISP: ربط كل واجهة DAO بكلاس مستقل أحادي المسؤولية (SqliteContactDao, SqliteArhamDao, SqliteServiceDao, SqliteDataRetentionCleanupDao).
 * - SRP: تسجيل كل حالة استخدام (UseCase) بمسؤولية واحدة.
 * - DIP: حقن التجريدات (Interfaces & UseCases) في الـ ViewModels عبر Koin.
 */
fun createSilaSharedModule(storageDirPath: String? = null): Module = module {
    single<AppClock> { SystemAppClock }
    single<AppDispatchers> { DefaultAppDispatchers() }

    // مصدر جهات الاتصال من المنصة (Issue #22 · ADR-0009 §3) — actuals per-platform.
    includes(platformContactsSourceModule)

    // Core Room KMP Database + Segregated DAOs (SRP & ISP)
    single {
        val dbPath = storageDirPath?.let { "$it/sila_local.db" } ?: ":memory:"
        LocalSilaDatabase(dbFilePath = dbPath, dispatchers = get())
    }
    single<SilaRoomDatabase> { get<LocalSilaDatabase>() }
    single<ContactDao> { get<SilaRoomDatabase>().contactDao() }
    single<ArhamDao> { get<SilaRoomDatabase>().arhamDao() }
    single<ServiceDao> { get<SilaRoomDatabase>().serviceDao() }
    single<DataRetentionCleanupDao> { get<SilaRoomDatabase>().dataRetentionCleanupDao() }

    // Core DataStore (Preferences Persistence Singleton)
    single<UserPreferencesDataSource> {
        val prefsPath = storageDirPath?.let { "$it/${SilaPreferencesDataSource.DATASTORE_FILE_NAME}" }
        SilaPreferencesDataSource(storageFilePath = prefsPath)
    }

    // :feature:contacts (Domain UseCases + Data Repository + Presentation ViewModel)
    single<ContactsRepository> { ContactsRepositoryImpl(contactDao = get()) }
    factory { ObserveContactsUseCase(repository = get()) }
    factory { ObserveFavoriteContactsUseCase(repository = get()) }
    factory { ObserveCategoryCountsUseCase(repository = get()) }
    factory { UpdateContactCategoryUseCase(repository = get()) }
    factory { DeleteContactUseCase(repository = get()) }
    factory { SmartCategorizeContactUseCase() }
    factory { DetectDuplicatesUseCase() }
    single {
        ContactsViewModel(
            observeContacts = get(),
            observeFavorites = get(),
            observeCategoryCounts = get(),
            updateContactCategory = get(),
            deleteContact = get(),
            detectDuplicates = get()
        )
    }

    // :feature:arham (Domain UseCases + Data Repository + Presentation ViewModel)
    single<ArhamRepository> { ArhamRepositoryImpl(arhamDao = get()) }
    factory { GetArhamPriorityListUseCase(repository = get(), clock = get()) }
    factory { GetArhamDashboardStatsUseCase(getPriorityList = get()) }
    factory { RecordArhamCallUseCase(repository = get(), clock = get()) }
    single {
        ArhamViewModel(
            getPriorityList = get(),
            getDashboardStats = get(),
            recordArhamCall = get()
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
            arhamRepository = get(),
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
