package app.sila.di

import org.koin.core.module.Module

/**
 * وحدة إعدادات المنصة (T9 · Issue #30):
 * expect/actual يربط [app.sila.feature.settings.domain.BackupCryptoEngine]
 * و [app.sila.feature.settings.domain.PlatformRingtoneSource] لكل منصة.
 */
internal expect val platformSettingsModule: Module
