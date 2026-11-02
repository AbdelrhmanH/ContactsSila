package app.sila.di

import app.sila.feature.contacts.data.IosConnectedAppsDetector
import app.sila.feature.contacts.data.IosContactImageProcessor
import app.sila.feature.contacts.data.IosContactsPlatformWriter
import app.sila.feature.contacts.domain.ConnectedAppsDetector
import app.sila.feature.contacts.domain.ContactImageProcessor
import app.sila.feature.contacts.domain.ContactsPlatformWriter
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * ربط iOS لوحدة كتابة جهات الاتصال من المنصة (T6 · Issue #27 · ADR-0012):
 * يوفر [IosContactsPlatformWriter] (كتابة CNContactStore — طفير ضيق حصراً بمفاتيح
 * مُدارة)، [IosContactImageProcessor] (قصّ مركزي + تصغير + JPEG)، و[IosConnectedAppsDetector]
 * (canOpenURL بمخططات مصرَّح بها في Info.plist).
 */
internal actual val platformContactsWriterModule: Module = module {
    single<ContactsPlatformWriter> { IosContactsPlatformWriter() }
    single<ContactImageProcessor> { IosContactImageProcessor() }
    single<ConnectedAppsDetector> { IosConnectedAppsDetector() }
}
