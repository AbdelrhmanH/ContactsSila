package app.sila.di

import android.content.Context
import app.sila.core.model.Category
import app.sila.feature.settings.data.AndroidBackupCryptoEngine
import app.sila.feature.settings.data.ringtone.AndroidRingtoneSource
import app.sila.feature.settings.domain.BackupCryptoEngine
import app.sila.feature.settings.domain.AppLocalePort
import app.sila.feature.settings.domain.PlatformRingtoneSource
import app.sila.feature.settings.domain.RingtoneItem
import org.koin.core.module.Module
import org.koin.dsl.module

internal actual val platformSettingsModule: Module = module {
    single<BackupCryptoEngine> { AndroidBackupCryptoEngine() }
    single<PlatformRingtoneSource> {
        val context = getOrNull<Context>()
        if (context != null) {
            AndroidRingtoneSource(context = context)
        } else {
            HostAbsentPlatformRingtoneSource
        }
    }
    // S2-14 (تذكرة #68): per-app locales عبر AppCompatDelegate — نيّة SetLanguage
    // تصير هي منبع لغة المنصة الفعلية فتتبع الموارد كلها لا شاشة الإعدادات وحدها.
    single<AppLocalePort> { AndroidAppLocalePort() }
}

private data object HostAbsentPlatformRingtoneSource : PlatformRingtoneSource {
    override suspend fun applyCategoryRingtone(category: Category, ringtoneUri: String?) {}
    override fun getSystemDefaultRingtoneUri(): String = "content://settings/system/ringtone"
    override fun getAvailableRingtones(): List<RingtoneItem> = emptyList()
}
