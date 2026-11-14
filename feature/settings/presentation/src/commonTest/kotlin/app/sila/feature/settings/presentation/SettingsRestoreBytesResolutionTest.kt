package app.sila.feature.settings.presentation

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

/**
 * عقد قرار بايتات الاستعادة الموحّد (DEF-04 · مسار SAF):
 *
 * العطل: حوار المعاينة يعرض دائماً `state.importBackupBytes ?: restoreBytesBuffer ?:
 * lastExportedBytes` بينما `restoreReady` و`onRestore` كانا يأخذان مصدرين فقط
 * (`restoreBytesBuffer`/`lastExportedBytes`) — فمسار «اختيار ملف من الجهاز» (SAF عبر
 * `SetImportBackupBytes`) يموت عند المعاينة: زر «بدء الاستعادة» معطّل دائماً، ولو مُكِّن
 * لما أرسل البايتات المعاينة نفسها.
 *
 * العقد: قرار واحد نقي يُستدعى حرفياً في الموضعين (العرض والتأكيد) — البايتات
 * المُستعادَة = البايتات المُعاينة، والأولوية لأحدث اختيار للمستخدم (ملف الجهاز).
 */
class SettingsRestoreBytesResolutionTest {

    @Test
    fun safImportedBytesTakePrecedenceOverSessionSources() {
        val safBytes = byteArrayOf(1, 2, 3)
        val sessionBuffer = byteArrayOf(9, 9)
        val exportedBytes = byteArrayOf(7, 7, 7)
        val resolved = resolvedRestoreBytes(
            state = SettingsUiState(importBackupBytes = safBytes),
            restoreBytesBuffer = sessionBuffer,
            lastExportedBytes = exportedBytes
        )
        assertContentEquals(safBytes, resolved)
    }

    @Test
    fun sessionRestoreBufferWinsWhenNoSafBytesAdopted() {
        val sessionBuffer = byteArrayOf(4, 5)
        val resolved = resolvedRestoreBytes(
            state = SettingsUiState(importBackupBytes = null),
            restoreBytesBuffer = sessionBuffer,
            lastExportedBytes = byteArrayOf(8, 8, 8)
        )
        assertContentEquals(sessionBuffer, resolved)
    }

    @Test
    fun lastExportedBytesIsTheFinalFallback() {
        val exportedBytes = byteArrayOf(6)
        val resolved = resolvedRestoreBytes(
            state = SettingsUiState(),
            restoreBytesBuffer = null,
            lastExportedBytes = exportedBytes
        )
        assertContentEquals(exportedBytes, resolved)
    }

    @Test
    fun noSourcesMeansResolutionNullAndRestoreGateClosed() {
        val resolved = resolvedRestoreBytes(
            state = SettingsUiState(),
            restoreBytesBuffer = null,
            lastExportedBytes = null
        )
        assertNull(resolved)
    }
}
