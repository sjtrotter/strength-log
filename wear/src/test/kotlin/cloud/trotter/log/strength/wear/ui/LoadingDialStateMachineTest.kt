package cloud.trotter.log.strength.wear.ui

import cloud.trotter.log.strength.wear.data.CompanionDetector
import cloud.trotter.log.strength.wear.data.CompanionPresence
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class LoadingDialStateMachineTest {

    @Test
    fun `authored loading sweep remains until timeout`() = runTest {
        val detector = FakeCompanionDetector(CompanionPresence.UNREACHABLE)
        val machine = LoadingDialStateMachine(detector, backgroundScope)

        machine.start()
        advanceTimeBy(LOADING_TIMEOUT_MILLIS - 1)
        runCurrent()

        assertEquals(LoadingDialState.LOADING, machine.state.value)
        assertEquals(0, detector.reads)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(LoadingDialState.PHONE_UNREACHABLE, machine.state.value)
    }

    @Test
    fun `reachable node without capability asks for phone install`() = runTest {
        val machine = LoadingDialStateMachine(
            FakeCompanionDetector(CompanionPresence.INSTALL_NEEDED),
            backgroundScope,
            timeoutMillis = 0,
        )

        machine.start()
        runCurrent()

        assertEquals(LoadingDialState.INSTALL_NEEDED, machine.state.value)
    }

    @Test
    fun `no connected node reports phone unreachable`() = runTest {
        val machine = LoadingDialStateMachine(
            FakeCompanionDetector(CompanionPresence.UNREACHABLE),
            backgroundScope,
            timeoutMillis = 0,
        )

        machine.start()
        runCurrent()

        assertEquals(LoadingDialState.PHONE_UNREACHABLE, machine.state.value)
    }

    @Test
    fun `retry reruns a transiently failed capability read`() = runTest {
        val detector = FakeCompanionDetector(
            IOException("Play services busy"),
            CompanionPresence.INSTALL_NEEDED,
        )
        val machine = LoadingDialStateMachine(detector, backgroundScope, timeoutMillis = 0)
        machine.start()
        runCurrent()
        assertEquals(LoadingDialState.ERROR, machine.state.value)

        machine.retry()
        runCurrent()

        assertEquals(2, detector.reads)
        assertEquals(LoadingDialState.INSTALL_NEEDED, machine.state.value)
    }

    @Test
    fun `snapshot preempts every waiting outcome`() = runTest {
        val beforeTimeout = FakeCompanionDetector(CompanionPresence.UNREACHABLE)
        val stillLoading = LoadingDialStateMachine(beforeTimeout, backgroundScope)
        stillLoading.start()
        stillLoading.snapshotArrived()
        advanceTimeBy(LOADING_TIMEOUT_MILLIS)
        runCurrent()
        assertEquals(LoadingDialState.SNAPSHOT_READY, stillLoading.state.value)
        assertEquals(0, beforeTimeout.reads)

        listOf(
            LoadingDialState.INSTALL_NEEDED,
            LoadingDialState.PHONE_UNREACHABLE,
            LoadingDialState.ERROR,
        ).forEach { outcome ->
            val detector = FakeCompanionDetector(
                when (outcome) {
                    LoadingDialState.INSTALL_NEEDED -> CompanionPresence.INSTALL_NEEDED
                    LoadingDialState.PHONE_UNREACHABLE -> CompanionPresence.UNREACHABLE
                    else -> IOException("transient")
                },
            )
            val machine = LoadingDialStateMachine(detector, backgroundScope, timeoutMillis = 0)
            machine.start()
            runCurrent()

            machine.snapshotArrived()

            assertEquals(LoadingDialState.SNAPSHOT_READY, machine.state.value)
        }
    }
}

private class FakeCompanionDetector(vararg outcomes: Any) : CompanionDetector {
    private val outcomes = ArrayDeque(outcomes.toList())
    var reads = 0
        private set

    override suspend fun detect(): CompanionPresence {
        reads++
        return when (val outcome = outcomes.removeFirst()) {
            is CompanionPresence -> outcome
            is Exception -> throw outcome
            else -> error("unsupported fake outcome")
        }
    }
}
