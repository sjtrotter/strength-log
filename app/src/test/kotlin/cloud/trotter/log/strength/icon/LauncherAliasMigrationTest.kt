package cloud.trotter.log.strength.icon

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class LauncherAliasMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val pm get() = context.packageManager
    private fun alias(letter: Char) = ComponentName(context.packageName, "${context.packageName}.Launcher_Day$letter")
    private fun set(letter: Char, state: Int) = pm.setComponentEnabledSetting(alias(letter), state, PackageManager.DONT_KILL_APP)

    private fun checkMigration(initial: Map<Char, Int>, expected: Char, verifyOnce: Boolean = false) = runTest {
        ('A'..'G').forEach { set(it, initial[it] ?: PackageManager.COMPONENT_ENABLED_STATE_DISABLED) }
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val store = PreferenceDataStoreFactory.create(scope = scope) { File.createTempFile("aliases", ".preferences_pb") }
        try {
            LauncherAliasMigration(context, store).runOnce()
            assertEquals(listOf(expected), ('A'..'G').filter { pm.getComponentEnabledSetting(alias(it)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED })
            assertTrue(store.data.first()[booleanPreferencesKey("normalized")] == true)
            if (verifyOnce) {
                set('G', PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
                // A fresh manager still honors the persisted flag.
                LauncherAliasMigration(context, store).runOnce()
                assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, pm.getComponentEnabledSetting(alias('G')))
            }
        } finally {
            scope.cancel()
        }
    }

    @Test fun defaultDayAAndExplicitDayCLeaveOnlyDayA() = checkMigration(
        mapOf('A' to PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, 'C' to PackageManager.COMPONENT_ENABLED_STATE_ENABLED), 'A',
    )

    @Test fun twoExplicitAliasesKeepTheFirst() = checkMigration(
        mapOf('B' to PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 'C' to PackageManager.COMPONENT_ENABLED_STATE_ENABLED), 'B',
    )

    @Test fun existingDayCRemainsEnabled() = checkMigration(mapOf('C' to PackageManager.COMPONENT_ENABLED_STATE_ENABLED), 'C')

    @Test fun noEnabledAliasFallsBackToDayA() = checkMigration(mapOf('B' to PackageManager.COMPONENT_ENABLED_STATE_DEFAULT), 'A')

    @Test fun normalizationRunsOnce() = checkMigration(mapOf('C' to PackageManager.COMPONENT_ENABLED_STATE_ENABLED), 'C', verifyOnce = true)
}
