package cloud.trotter.log.strength.icon

import android.content.pm.PackageManager
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
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

private class RecordingAliasStates(initial: Map<Char, Int>) : AliasStates {
    val states = ('A'..'G').associateWith { initial[it] ?: PackageManager.COMPONENT_ENABLED_STATE_DISABLED }.toMutableMap()
    val writes = mutableListOf<Pair<Char, Int>>()
    val targetEnabledBeforeDisables = mutableListOf<Boolean>()
    var target: Char? = null

    fun effectivelyEnabled(letter: Char): Boolean = states.getValue(letter).let { state ->
        state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
            (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && letter == 'A')
    }

    override fun get(letter: Char): Int = states.getValue(letter)

    override fun set(letter: Char, state: Int) {
        target?.let { selected ->
            if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) targetEnabledBeforeDisables += effectivelyEnabled(selected)
        }
        writes += letter to state
        states[letter] = state
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LauncherAliasMigrationTest {

    private fun checkMigration(initial: Map<Char, Int>, expected: Char, verifyOnce: Boolean = false, expectNoWrites: Boolean = false) = runTest {
        val states = RecordingAliasStates(initial).apply { target = expected }
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val store = PreferenceDataStoreFactory.create(scope = scope) { File.createTempFile("aliases", ".preferences_pb") }
        try {
            LauncherAliasMigration(states, store).runOnce()
            assertEquals(listOf(expected), ('A'..'G').filter(states::effectivelyEnabled))
            assertTrue("the target must be enabled before disabling another alias", states.targetEnabledBeforeDisables.all { it })
            if (initial.values.none { it == PackageManager.COMPONENT_ENABLED_STATE_ENABLED } &&
                initial['A'] != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            ) {
                assertEquals('A' to PackageManager.COMPONENT_ENABLED_STATE_ENABLED, states.writes.first())
            }
            if (expectNoWrites) assertTrue(states.writes.isEmpty())
            assertTrue(store.data.first()[booleanPreferencesKey("normalized")] == true)
            if (verifyOnce) {
                states.states['G'] = PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                states.writes.clear()
                // A fresh migration still honors the persisted flag.
                LauncherAliasMigration(states, store).runOnce()
                assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, states.states['G'])
                assertTrue(states.writes.isEmpty())
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

    @Test fun freshInstallDoesNotWriteAliasStates() = checkMigration(
        ('A'..'G').associateWith { PackageManager.COMPONENT_ENABLED_STATE_DEFAULT }, 'A', expectNoWrites = true,
    )

    @Test fun dayAOnlyDoesNotWriteAliasStates() = checkMigration(
        mapOf('A' to PackageManager.COMPONENT_ENABLED_STATE_ENABLED), 'A', expectNoWrites = true,
    )
}
