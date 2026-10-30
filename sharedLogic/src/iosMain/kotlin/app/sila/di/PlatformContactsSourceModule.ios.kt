package app.sila.di

import app.sila.feature.contacts.data.IosContactsPlatformSource
import app.sila.feature.contacts.domain.ContactsPlatformSource
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * ربط iOS لمصدر جهات الاتصال من المنصة (Issue #22 · ADR-0009 §3):
 * يوفر [IosContactsPlatformSource] (قراءة CNContactStore فقط — لا كتابة ولا شبكة).
 */
internal actual val platformContactsSourceModule: Module = module {
    single<ContactsPlatformSource> { IosContactsPlatformSource() }
}
