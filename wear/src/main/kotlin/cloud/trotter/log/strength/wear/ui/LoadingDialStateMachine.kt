package cloud.trotter.log.strength.wear.ui

import cloud.trotter.log.strength.wear.data.CompanionDetector
import cloud.trotter.log.strength.wear.data.CompanionPresence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal enum class LoadingDialState {
    LOADING,
    OPEN_PHONE_APP,
    INSTALL_NEEDED,
    PHONE_UNREACHABLE,
    ERROR,
    SNAPSHOT_READY,
}

/** Pure loading-state coordinator; Data Layer snapshot collection remains the authority. */
internal class LoadingDialStateMachine(
    private val detector: CompanionDetector,
    private val scope: CoroutineScope,
    private val timeoutMillis: Long = LOADING_TIMEOUT_MILLIS,
) {
    private val mutableState = MutableStateFlow(LoadingDialState.LOADING)
    val state: StateFlow<LoadingDialState> = mutableState
    private var check: Job? = null

    fun start() {
        if (check != null || mutableState.value == LoadingDialState.SNAPSHOT_READY) return
        check = scope.launch {
            delay(timeoutMillis)
            detect()
        }
    }

    fun retry() {
        if (mutableState.value == LoadingDialState.SNAPSHOT_READY) return
        check?.cancel()
        mutableState.value = LoadingDialState.LOADING
        check = scope.launch { detect() }
    }

    /** The install hand-off to the phone could not be sent; retry is the way out. */
    fun remoteLaunchFailed() {
        if (mutableState.value == LoadingDialState.SNAPSHOT_READY) return
        mutableState.value = LoadingDialState.ERROR
    }

    fun snapshotArrived() {
        check?.cancel()
        mutableState.value = LoadingDialState.SNAPSHOT_READY
    }

    private suspend fun detect() {
        try {
            mutableState.value = when (detector.detect()) {
                CompanionPresence.INSTALLED -> LoadingDialState.OPEN_PHONE_APP
                CompanionPresence.INSTALL_NEEDED -> LoadingDialState.INSTALL_NEEDED
                CompanionPresence.UNREACHABLE -> LoadingDialState.PHONE_UNREACHABLE
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            mutableState.value = LoadingDialState.ERROR
        }
    }
}

internal const val LOADING_TIMEOUT_MILLIS = 10_000L
