package app.sila.di

import app.sila.feature.contacts.data.IosContactsMergeWriter
import app.sila.feature.contacts.domain.ContactsMergeWriter
import org.koin.core.module.Module
import org.koin.dsl.module

/** ربط iOS لوحدة الدمج المنصوي (T7 · ADR-0013): [IosContactsMergeWriter]. */
internal actual val platformContactsMergeModule: Module = module {
    single<ContactsMergeWriter> { IosContactsMergeWriter() }
}
