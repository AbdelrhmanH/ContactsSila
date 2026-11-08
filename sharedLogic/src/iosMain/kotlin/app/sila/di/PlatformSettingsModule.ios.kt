package app.sila.di

import app.sila.feature.settings.data.IosBackupCryptoEngine
import app.sila.feature.settings.domain.BackupCryptoEngine
import app.sila.feature.settings.domain.PlatformRingtoneSource
import app.sila.settings.ringtone.IosRingtoneSource
import org.koin.core.module.Module
import org.koin.dsl.module

internal actual val platformSettingsModule: Module = module {
    single<BackupCryptoEngine> { IosBackupCryptoEngine() }
    single<PlatformRingtoneSource> { IosRingtoneSource() }
}
