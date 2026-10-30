package app.sila.di

import android.content.Context
import app.sila.feature.contacts.data.AndroidContactsPlatformSource
import app.sila.feature.contacts.domain.ContactsPlatformSource
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * ربط Android لمصدر جهات الاتصال من المنصة (Issue #22 · ADR-0009 §3):
 * يوفر [AndroidContactsPlatformSource] (قراءة ContactsContract فقط — لا كتابة ولا شبكة)
 * بدون أي منصات أعلى، وهو قابل للتجميع مع Host Test عبر التحققات (JVM قابل للاختبار).
 */
internal actual val platformContactsSourceModule: Module = module {
    single<ContactsPlatformSource> {
        val context = get<Context>()
        AndroidContactsPlatformSource(
            contentResolver = context.contentResolver,
            applicationContext = context
        )
    }
}
