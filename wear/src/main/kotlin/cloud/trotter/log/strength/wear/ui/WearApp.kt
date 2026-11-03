package cloud.trotter.log.strength.wear.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.BasicSwipeToDismissBox
import androidx.wear.remote.interactions.RemoteActivityHelper
import cloud.trotter.log.strength.domain.sync.CardioDelta
import cloud.trotter.log.strength.domain.sync.ExerciseSwapDelta
import cloud.trotter.log.strength.domain.generator.CardioIntervals
import cloud.trotter.log.strength.domain.sync.SetEditDelta
import cloud.trotter.log.strength.domain.sync.WatchSnapshot
import cloud.trotter.log.strength.wear.OngoingWorkoutChip
import cloud.trotter.log.strength.wear.R
import cloud.trotter.log.strength.wear.data.WatchTrackerClient
import cloud.trotter.log.strength.wear.data.CompanionDetector
import cloud.trotter.log.strength.wear.theme.Background
import cloud.trotter.log.strength.wear.theme.WearTrackerTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How often a live screen re-reads the clock: fine enough for a draining arc,
 *  coarse enough to be free. The lifting elapsed timer only needs seconds. */
private const val LIVE_TICK_MILLIS = 200L
private const val LIFTING_TICK_MILLIS = 1_000L

/** Room for the goal buzz to be heard before the auto-tick follows it. */
private const val GOAL_BUZZ_SETTLE_MILLIS = 250L

/** The screens with a clock on them — the only ones that need a repaint ticker. */
private val LIVE_SCREENS = setOf(
    DialScreen.LIFTING, DialScreen.TIMED_HOLD, DialScreen.REST,
    DialScreen.CARDIO_EXECUTING, DialScreen.CARDIO_OVERRUN, DialScreen.TIMED_REP_RUNNING,
)

/**
 * Root composable: swaps in [AmbientDial] while the system reports ambient mode,
 * and otherwise shows the dial — the one
 * screen the whole workout happens on (brief §1).
 *
 * Every state the watch can be in is that same dial: waiting for the phone
 * ([LoadingDial]), a day with no program yet (the dashed disc [dialUiState]
 * already produces), the workout, and ambient. There are no lists and no
 * destinations — the dial re-renders in place — but there are two faces (v3 §3),
 * and the platform's own dismiss gesture is what moves between them: back out of
 * the workout face, then out of the app.
 */
@Composable
fun WearApp(
    client: WatchTrackerClient,
    companionDetector: CompanionDetector,
    isAmbient: Boolean,
    ambientTick: Int = 0,
    burnInProtectionRequired: Boolean = false,
    deviceHasLowBitAmbient: Boolean = false,
    onDismiss: () -> Unit = {},
) {
    val snapshot by client.snapshotFlow().collectAsState(initial = null)
    val pendingCount by client.pendingCountFlow().collectAsState(initial = 0)
    val pendingExercises by client.pendingExercisesFlow().collectAsState(initial = emptySet())
    val pendingSwaps by client.pendingSwapsFlow().collectAsState(initial = emptySet())
    val dayId = snapshot?.day?.dayId
    var localSessionStartedAtMillis by rememberSaveable(snapshot?.planId, dayId, snapshot?.runId) { mutableLongStateOf(0L) }
    val context = LocalContext.current
    // The exact-alarm listener lives at the root so it outlives the ambient swap.
    val restController = remember(context) { restTimerController(context.applicationContext) }
    val rootScope = rememberCoroutineScope()
    // Cardio anchors are ROOT state: ambient removes WorkoutDial from the
    // composition, and a running block must survive that swap.
    val cardioDayId = snapshot?.day?.dayId
    var cardioStartedAtWall by rememberSaveable(cardioDayId) { mutableLongStateOf(0L) }
    var cardioStartedAtElapsed by rememberSaveable(cardioDayId) { mutableLongStateOf(0L) }
    var cardioStartedAtBoot by rememberSaveable(cardioDayId) { mutableIntStateOf(0) }
    var cardioPlanId by rememberSaveable(cardioDayId) { mutableStateOf<Long?>(null) }
    val cardioAnchors = cardioStartedAtElapsed.takeIf { it > 0L }?.let {
        CardioAnchors(cardioStartedAtWall, it, cardioStartedAtBoot, cardioPlanId)
    }
    var savedCardioSets by rememberSaveable(stateSaver = cardioSetStateSaver) {
        mutableStateOf<CardioSetState?>(null)
    }
    // Reconciliation retains started work from another plan/day, so its frozen
    // cardio face and execution ownership outlive the phone's selection changes.
    val cardioSets = snapshot?.let { savedCardioSets?.reconcile(it) }
    LaunchedEffect(snapshot, cardioSets) {
        if (snapshot != null && savedCardioSets != cardioSets) savedCardioSets = cardioSets
    }
    var savedTimedRep by rememberSaveable(stateSaver = timedRepStateSaver) {
        mutableStateOf<TimedRepState?>(null)
    }
    // Do not discard a restored attempt while the initial snapshot is loading.
    val timedRep = snapshot?.let { savedTimedRep?.reconcile(it)?.takeIf { cardioAnchors == null && cardioSets?.ownsExecution != true } }
    LaunchedEffect(snapshot, timedRep) {
        if (snapshot != null && savedTimedRep != timedRep) savedTimedRep = timedRep
    }
    val rootView = LocalView.current
    fun bootCountNow(): Int = android.provider.Settings.Global.getInt(
        context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 0,
    )

    fun startCardio() {
        if (timedRep != null || cardioSets?.ownsExecution == true) return
        cardioPlanId = snapshot?.planId
        cardioStartedAtWall = System.currentTimeMillis()
        cardioStartedAtElapsed = SystemClock.elapsedRealtime()
        cardioStartedAtBoot = bootCountNow()
        restController.disarm()
        DialHaptics.perform(rootView, DialHaptics.Cue.START)
    }

    fun stopCardio() {
        val suggestion = snapshot?.cardio
        val anchors = cardioAnchors
        val dayId = cardioDayId
        if (suggestion != null && anchors != null && dayId != null) {
            val progress = cardioProgress(
                CardioIntervals.plan(suggestion, suggestion.fiveK ?: false), anchors,
                SystemClock.elapsedRealtime(), System.currentTimeMillis(), bootCountNow(),
            )
            // A null mode can only mean a phone downgrade mid-run: discard rather
            // than log a guessed mode or leave the hold dead.
            suggestion.mode?.let { mode ->
                buildCardioDelta(
                    dayId, mode, suggestion.hard, suggestion.label, anchors, progress,
                    System.currentTimeMillis(),
                )?.let(client::sendCardio)
            }
        }
        cardioPlanId = null
        cardioStartedAtWall = 0L
        cardioStartedAtElapsed = 0L
        cardioStartedAtBoot = 0
        restController.disarm()
        DialHaptics.perform(rootView, DialHaptics.Cue.CONFIRM_TICK)
    }

    fun localCardioSets(): CardioSetState? {
        if (timedRep != null || cardioAnchors != null) return null
        cardioSets?.let { return it }
        val snap = snapshot ?: return null
        return newCardioSetState(snap, timedRep = null, legacyCardioRunning = false)
    }

    fun tapCardioSet() {
        val run = localCardioSets() ?: return
        val next = run.tap(System.currentTimeMillis(), SystemClock.elapsedRealtime(), bootCountNow(),
            finishesDay = snapshot?.day?.exercises?.isEmpty() == true)
        if (cardioSets == null || next.active != run.active) restController.disarm()
        savedCardioSets = next
    }

    fun holdCardioSet(hold: DialHold) {
        val run = localCardioSets() ?: return
        val pressed = hold.cardioSetAttempt ?: return
        if (pressed.planId != run.planId || pressed.dayId != run.dayId || pressed.block != run.block ||
            pressed.position != run.position || pressed.active != run.active) return
        if (hold.action == DialHoldAction.STOP_CARDIO_SET) restController.disarm()
        savedCardioSets = when (hold.action) {
            DialHoldAction.STOP_CARDIO_SET -> run.stop(System.currentTimeMillis(), SystemClock.elapsedRealtime(), bootCountNow())
            DialHoldAction.CARDIO_SET_OPTIONS -> run.openOptions()
            else -> run
        }
    }

    LaunchedEffect(cardioSets?.peekIndex) {
        if (cardioSets?.peekIndex != null) {
            delay(PeekScrub.IDLE_TIMEOUT_MILLIS)
            savedCardioSets = savedCardioSets?.copy(peekIndex = null)
        }
    }

    // Uses the existing single exact-alarm adapter above the ambient swap. Restored
    // past boundaries are reconciled before arming, without replaying their buzzes.
    LaunchedEffect(cardioSets?.active, cardioSets?.position) {
        val run = cardioSets ?: return@LaunchedEffect
        if (run.active == null) return@LaunchedEffect
        val wall = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val resumed = run.resume(wall, elapsed, bootCountNow())
        if (resumed != run) {
            savedCardioSets = resumed
            return@LaunchedEffect
        }
        val deadline = run.deadline(elapsed, wall, bootCountNow()) ?: return@LaunchedEffect
        try {
            val spec = run.block.sets[run.position]
            restController.arm(deadline, spec.endValue, ambientLabel = cardioActivityLabel(run.block.activity)) {
                val current = savedCardioSets
                if (current != null && current.active == run.active && current.position == run.position) {
                    savedCardioSets = current.resume(System.currentTimeMillis(), SystemClock.elapsedRealtime(), bootCountNow())
                }
            }
            while (!RestTimer.isExpired(deadline, SystemClock.elapsedRealtime())) delay(LIVE_TICK_MILLIS)
            restController.completeIfExpired()
            val current = savedCardioSets
            if (current != null && current.active == run.active && current.position == run.position) {
                savedCardioSets = current.resume(System.currentTimeMillis(), SystemClock.elapsedRealtime(), bootCountNow())
            }
        } finally {
            restController.disarm()
        }
    }

    val cardioDelta = cardioSets?.takeIf { it.queuedStamp == null }?.delta()
    LaunchedEffect(cardioDelta, cardioSets?.saveFailed) {
        val delta = cardioDelta ?: return@LaunchedEffect
        if (cardioSets.saveFailed) return@LaunchedEffect
        client.sendCardio(delta, onQueued = { stamp ->
            val current = savedCardioSets
            if (current != null && current.planId == delta.planId && current.dayId == delta.dayId && current.startedAt == delta.startedAt) {
                savedCardioSets = current.copy(queuedStamp = stamp, saveFailed = false)
            }
        }, onFailure = {
            val current = savedCardioSets
            if (current != null && current.planId == delta.planId && current.dayId == delta.dayId && current.startedAt == delta.startedAt) {
                savedCardioSets = current.copy(saveFailed = true)
            }
        })
    }

    // Boundary buzzes are armed at the ROOT so ambient keeps them, and the
    // finally-disarm covers every vanish path — day change, dismiss, restore
    // past the end — not just the explicit stop.
    val cardioPlanRoot = snapshot?.cardio?.let { CardioIntervals.plan(it, it.fiveK ?: false) }
    LaunchedEffect(snapshot?.planId, cardioAnchors, cardioPlanRoot) {
        if (cardioAnchors == null || cardioPlanRoot == null) return@LaunchedEffect
        try {
            while (true) {
                val progress = cardioProgress(
                    cardioPlanRoot, cardioAnchors,
                    SystemClock.elapsedRealtime(), System.currentTimeMillis(), bootCountNow(),
                )
                val deadline = progress.nextBoundaryElapsedMillis ?: break
                val step = cardioPlanRoot.steps.getOrNull(progress.stepIndex)
                restController.arm(deadline, step?.seconds ?: 0, ambientLabel = step?.label)
                delay((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(LIVE_TICK_MILLIS))
            }
            restController.disarm()
        } finally {
            restController.disarm()
        }
    }

    // Like cardio, the one end alarm belongs above the ambient/interactive swap.
    // An expired restore advances silently; only a timer armed in this process buzzes.
    LaunchedEffect(timedRep) {
        val attempt = timedRep ?: return@LaunchedEffect
        if (attempt.phase != TimedRepPhase.RUNNING) return@LaunchedEffect
        val installed = snapshot ?: return@LaunchedEffect
        val resumed = attempt.resume(
            snap = installed,
            nowElapsedMillis = SystemClock.elapsedRealtime(),
            nowWallMillis = System.currentTimeMillis(),
            nowBootCount = bootCountNow(),
        )
        if (resumed != attempt) {
            savedTimedRep = resumed
            return@LaunchedEffect
        }
        val deadline = attempt.deadline(SystemClock.elapsedRealtime(), System.currentTimeMillis(), bootCountNow())
        try {
            val label = installed.day.exercises.find {
                it.programExerciseId == attempt.owner.programExerciseId
            }?.name
            restController.arm(deadline, attempt.totalSeconds, ambientLabel = label) {
                if (savedTimedRep == attempt) savedTimedRep = attempt.timeUp()
            }
            // The deadline can pass between resume and arm; arm deliberately
            // ignores an expired boundary, so finish that restore silently too.
            if (restController.activeRest == null) {
                if (savedTimedRep == attempt) savedTimedRep = attempt.timeUp()
                return@LaunchedEffect
            }
            while (!RestTimer.isExpired(deadline, SystemClock.elapsedRealtime())) {
                delay((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L))
            }
            restController.completeIfExpired()
        } finally {
            restController.disarm()
        }
    }

    val loadingMachine = remember(companionDetector, rootScope) {
        LoadingDialStateMachine(companionDetector, rootScope)
    }
    val loadingState by loadingMachine.state.collectAsState()
    val remoteActivity = remember(context) {
        RemoteActivityHelper(context.applicationContext, ContextCompat.getMainExecutor(context))
    }
    LaunchedEffect(Unit) { loadingMachine.start() }
    LaunchedEffect(snapshot) {
        if (snapshot != null) loadingMachine.snapshotArrived()
    }
    DisposableEffect(restController) {
        onDispose(restController::close)
    }
    // A plan switch or rollover invalidates a fired hold as well as an armed one.
    // The retained tick closure must only apply to the workout it came from.
    DisposableEffect(snapshot?.planId, dayId, restController) {
        onDispose(restController::disarm)
    }
    LaunchedEffect(snapshot?.epoch, snapshot?.revision) {
        // The dial's retained lambda is current through its last composition. If a
        // phone update lands while ambient has removed it, cancel the settle rather
        // than applying that closure to a snapshot it has never observed.
        restController.invalidateFiring()
    }
    // Runs on every screen (loading/ambient/interactive) so the chip reconciles
    // even when the app relaunches straight into ambient — see OngoingSessionChip.
    OngoingSessionChip(snapshot, localSessionStartedAtMillis)

    WearTrackerTheme {
        Box(Modifier.fillMaxSize().background(Background)) {
            val snap = snapshot
            when {
                // Ambient outranks loading: a relaunch straight into ambient must
                // not show LoadingDial's animated accent sweep.
                isAmbient && snap == null -> AmbientLoadingDial(
                    ambientTick = ambientTick,
                    burnInProtectionRequired = burnInProtectionRequired,
                    deviceHasLowBitAmbient = deviceHasLowBitAmbient,
                )
                snap == null -> {
                    val loadingCopy = rememberLoadingDialCopy()
                    LoadingDial(
                        state = loadingState,
                        copy = loadingCopy,
                        onAction = {
                            if (loadingState == LoadingDialState.INSTALL_NEEDED) {
                                val send = remoteActivity.startRemoteActivity(
                                    Intent(Intent.ACTION_VIEW)
                                        .setData(Uri.parse(PHONE_PLAY_LISTING))
                                        .addCategory(Intent.CATEGORY_BROWSABLE),
                                )
                                // A hand-off that can't be delivered must not leave the
                                // dial silently claiming an install is on its way.
                                send.addListener({
                                    val failed = runCatching { send.get() }.isFailure
                                    if (failed) loadingMachine.remoteLaunchFailed()
                                }, ContextCompat.getMainExecutor(context))
                            } else {
                                loadingMachine.retry()
                            }
                        },
                    )
                }
                isAmbient -> AmbientDial(
                    snapshot = snap,
                    ambientTick = ambientTick,
                    rest = restController.activeRest,
                    burnInProtectionRequired = burnInProtectionRequired,
                    deviceHasLowBitAmbient = deviceHasLowBitAmbient,
                )
                else -> WorkoutDial(
                    snap = snap,
                    cardioAnchors = cardioAnchors,
                    cardioSets = cardioSets,
                    onCardioSetTap = ::tapCardioSet,
                    onCardioSetDismiss = { savedCardioSets = null },
                    onCardioSetHold = ::holdCardioSet,
                    onCardioSetTurn = { detents ->
                        val run = localCardioSets()
                        val next = run?.turn(detents)
                        savedCardioSets = next
                        if (run == null || next == null) 0 else next.crownPosition() - run.crownPosition()
                    },
                    timedRep = timedRep,
                    onTimedRepChanged = { savedTimedRep = it },
                    bootCount = bootCountNow(),
                    onStartCardio = ::startCardio,
                    onStopCardio = ::stopCardio,
                    pendingCount = pendingCount,
                    pendingExercises = pendingExercises,
                    pendingSwaps = pendingSwaps,
                    restController = restController,
                    scheduleTimedHoldTick = { firing, tick ->
                        rootScope.launch {
                            // Let the long goal buzz land before tick's confirm haptic.
                            delay(GOAL_BUZZ_SETTLE_MILLIS)
                            if (firing.isCurrent()) tick()
                        }
                    },
                    // Straight through, no composition scope in the middle: the client
                    // owns the coroutine, on a scope that outlives this Activity. A
                    // tick the lifter already felt must not be abortable by the
                    // Activity going away mid-enqueue.
                    sendEdit = client::sendEdit,
                    sendSwap = client::sendSwap,
                    sendCardio = client::sendCardio,
                    onSessionStarted = { localSessionStartedAtMillis = it },
                    onDismiss = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun rememberLoadingDialCopy() = LoadingDialCopy(
    phoneAppNeeded = stringResource(R.string.dial_phone_app_needed),
    openPhoneApp = stringResource(R.string.dial_open_phone_app),
    installPhone = stringResource(R.string.dial_install_phone),
    installAction = stringResource(R.string.dial_install_phone_action),
    phoneUnreachable = stringResource(R.string.dial_phone_unreachable),
    syncError = stringResource(R.string.dial_sync_error),
    retry = stringResource(R.string.dial_retry),
    retryAction = stringResource(R.string.dial_retry_action),
)

private const val PHONE_PLAY_LISTING =
    "https://play.google.com/store/apps/details?id=cloud.trotter.log.strength"

/**
 * The workout, as local state over the snapshot. Which exercise and which round
 * the lifter is on are *derived* (the client echoes a tick optimistically, so the
 * ring moves the moment it is tapped); only what the phone cannot know is held
 * here — has this set been started, is a rest running, when did the session begin,
 * which exercise did the crown pick, and which rounds this watch ticked, in order.
 *
 * All of it is [rememberSaveable] and deadline-anchored, so a process death
 * mid-set or mid-rest restores to exactly the same dial (write-on-mutation) — the
 * face included, seeded from what the day itself implies ([impliedFace]) on a cold
 * launch. The deliberate exceptions are the two glances: the crown's peek at
 * another round, and the overview's preview of another day. Neither is a place the
 * watch should ever restore into.
 */
@Composable
private fun WorkoutDial(
    snap: WatchSnapshot,
    cardioAnchors: CardioAnchors?,
    cardioSets: CardioSetState?,
    onCardioSetTap: () -> Unit,
    onCardioSetDismiss: () -> Unit,
    onCardioSetHold: (DialHold) -> Unit,
    onCardioSetTurn: (Int) -> Int,
    timedRep: TimedRepState?,
    onTimedRepChanged: (TimedRepState?) -> Unit,
    bootCount: Int,
    onStartCardio: () -> Unit,
    onStopCardio: () -> Unit,
    pendingCount: Int,
    pendingExercises: Set<Long>,
    pendingSwaps: Set<Long>,
    restController: RestTimerController,
    scheduleTimedHoldTick: (RestTimerController.Firing, () -> Unit) -> Unit,
    sendEdit: (SetEditDelta) -> Unit,
    sendSwap: (ExerciseSwapDelta) -> Unit,
    // The original signature anchors rememberSaveable's compiler-generated registry key.
    @Suppress("UNUSED_PARAMETER") sendCardio: (CardioDelta) -> Unit,
    onSessionStarted: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val view = LocalView.current
    val dayId = snap.day.dayId
    var saved by rememberSaveable(snap.planId, dayId, snap.runId,
        saver = workoutDialStateHolderSaver(snap.planId, dayId, snap.runId)) {
        mutableStateOf(WorkoutDialState(snap.planId, dayId, snap.runId, impliedFace(snap)))
    }
    fun deviceTime() = DeviceTime(System.currentTimeMillis(), SystemClock.elapsedRealtime(), bootCount)
    var now by remember { mutableStateOf(deviceTime()) }
    val workoutInputs = WorkoutInputs(snap, timedRep, cardioAnchors != null || cardioSets?.ownsExecution == true)
    val latestWorkoutInputs by rememberUpdatedState(workoutInputs)

    fun dispatch(event: WorkoutEvent) {
        val time = deviceTime()
        val transition = saved.reduce(event, time, latestWorkoutInputs)
        saved = transition.state
        now = time
        transition.effects.forEach { effect ->
            when (effect) {
                is WorkoutEffect.SendEdit -> sendEdit(effect.delta)
                is WorkoutEffect.SendSwap -> sendSwap(effect.delta)
                is WorkoutEffect.Haptic -> DialHaptics.perform(view, effect.cue)
                is WorkoutEffect.TimedRepChanged -> onTimedRepChanged(effect.state)
                is WorkoutEffect.SessionStarted -> onSessionStarted(effect.wallMillis)
                is WorkoutEffect.StartRest -> dispatch(WorkoutEvent.StartRest(effect.seconds, effect.betweenExercises))
                WorkoutEffect.DisarmTimer -> restController.disarm()
                is WorkoutEffect.ArmRest -> restController.arm(effect.deadlineMillis, effect.totalSeconds)
                is WorkoutEffect.ArmTimedHold -> restController.arm(effect.deadlineMillis, effect.totalSeconds) { firing ->
                    val tick = WorkoutEvent.Tick(saved.tickTarget(latestWorkoutInputs))
                    scheduleTimedHoldTick(firing) { dispatch(tick) }
                }
                WorkoutEffect.StartCardio -> onStartCardio()
                WorkoutEffect.StopCardio -> onStopCardio()
            }
        }
    }

    val exerciseIndex = saved.exerciseIndex(workoutInputs)
    val exercise = snap.day.exercises.getOrNull(exerciseIndex)
    val roundIndex = exercise?.let(::currentRoundIndex) ?: 0
    val holdGoal = exercise?.let { holdGoalSeconds(it, roundIndex) } ?: 0
    // A preview must be forgotten in the composition that leaves its exercise.
    if (saved.swapExerciseIndex != exerciseIndex) dispatch(WorkoutEvent.ExerciseChanged)
    val swapPreview = saved.swapPreviewFor(exerciseIndex)

    val inputs = DialInputs(
        snapshot = snap,
        exerciseIndex = exerciseIndex,
        phase = if (saved.lifting) SetPhase.LIFTING else SetPhase.READY,
        face = saved.face,
        pendingCount = pendingCount,
        rest = if (saved.restDeadlineMillis > 0L) {
            RestState(saved.restDeadlineMillis, saved.restTotalSeconds, saved.restBetweenExercises)
        } else {
            null
        },
        restedSeconds = saved.restedSeconds.takeIf { it != NO_REST },
        liftingElapsedMillis = if (saved.lifting) {
            (now.elapsedMillis - saved.startedAtElapsedMillis).coerceAtLeast(0L)
        } else {
            0L
        },
        nowElapsedMillis = now.elapsedMillis,
        nowWallMillis = now.wallMillis,
        session = SessionStamps(saved.sessionStartedAtMillis, saved.sessionEndedAtMillis),
        browseDayIndex = saved.browseDay,
        peekRoundIndex = saved.peek?.roundIndex,
        swapAlternateIndex = swapPreview?.alternateIndex,
        pendingSwapExerciseIds = pendingSwaps,
        tickMemory = saved.tickMemory,
        cardioAnchors = cardioAnchors,
        cardioSets = cardioSets,
        bootCount = bootCount,
        timedRep = timedRep,
        timedRepDeadlineMillis = timedRep?.deadline(now.elapsedMillis, now.wallMillis, bootCount) ?: 0L,
    )
    val copy = rememberDialCopy()
    val state = dialUiState(inputs, copy)

    // Repaint ticker: only while something on screen is counting.
    LaunchedEffect(state.screen) {
        if (state.screen !in LIVE_SCREENS) return@LaunchedEffect
        val cadence = if (state.screen == DialScreen.LIFTING) LIFTING_TICK_MILLIS else LIVE_TICK_MILLIS
        while (true) {
            now = deviceTime()
            delay(cadence)
        }
    }

    // The crown's choice is *forgotten*, not merely ignored, once the day stops
    // honouring it — but only once nothing of ours is still in flight against that
    // lift ([ExerciseSelection.shouldForget]). Both keys matter: a snapshot can carry
    // a newer revision and still predate our undo, and the queue draining is the
    // other moment the answer can change.
    LaunchedEffect(snap.revision, pendingExercises) {
        dispatch(WorkoutEvent.ReconcileSelection(pendingExercises))
    }

    // A peek ends when the crown stops turning: a rotary crown has no release
    // event, so the brief's "↺ RELEASE TO RETURN" is read as stopping. The effect
    // re-arms on every turn, so this delay is the idle timeout itself.
    LaunchedEffect(saved.peek) {
        if (saved.peek == null) return@LaunchedEffect
        delay(PeekScrub.IDLE_TIMEOUT_MILLIS)
        dispatch(WorkoutEvent.PeekTimeout)
    }

    // A swap under consideration puts the lift back the same way, on its own longer
    // clock — see SwapPicker. Nothing else cancels it; the tap is the only commit.
    LaunchedEffect(swapPreview) {
        if (swapPreview == null) return@LaunchedEffect
        delay(SwapPicker.IDLE_TIMEOUT_MILLIS)
        dispatch(WorkoutEvent.SwapTimeout)
    }

    // Re-arm restored deadlines; past deadlines clear without replaying the buzz.
    LaunchedEffect(saved.restDeadlineMillis, timedRep?.owner, cardioAnchors, cardioSets?.ownsExecution) {
        if (saved.restDeadlineMillis <= 0L || timedRep != null || cardioAnchors != null || cardioSets?.ownsExecution == true) return@LaunchedEffect
        val deadline = saved.restDeadlineMillis
        dispatch(WorkoutEvent.ResumeRest)
        while (!RestTimer.isExpired(deadline, SystemClock.elapsedRealtime())) {
            delay(LIVE_TICK_MILLIS)
        }
        dispatch(WorkoutEvent.RestExpired(deadline))
    }

    // A timed hold buzzes once at its goal and ticks itself (§5.6). Same controller,
    // so it is the same single-buzz guarantee, and a manual tick cancels it.
    LaunchedEffect(saved.lifting, saved.startedAtElapsedMillis, holdGoal, timedRep?.owner, cardioAnchors, cardioSets?.ownsExecution) {
        if (timedRep != null || cardioAnchors != null || cardioSets?.ownsExecution == true || !saved.lifting || holdGoal <= 0 || saved.startedAtElapsedMillis <= 0L) return@LaunchedEffect
        val goalDeadline = RestTimer.deadlineFrom(saved.startedAtElapsedMillis, holdGoal)
        dispatch(WorkoutEvent.ResumeTimedHold)
        while (!RestTimer.isExpired(goalDeadline, SystemClock.elapsedRealtime())) {
            delay(LIVE_TICK_MILLIS)
        }
    }

    val crown = rememberCrownModifier(enabled = state.crown != DialCrown.NONE) { detents ->
        val cardioMovement = if (state.crown == DialCrown.CARDIO_SET) onCardioSetTurn(detents) else 0
        dispatch(WorkoutEvent.TurnCrown(state.crown, detents, cardioMovement))
    }

    // Leftward is contextual and only where nothing is under way (v3 §3); rightward
    // is the platform's, below.
    val swipeLeft = crown.swipeLeft(enabled = state.swipe != DialSwipe.NONE) {
        dispatch(WorkoutEvent.Swipe(state.swipe))
    }

    fun returnToOverview() {
        dispatch(WorkoutEvent.ShowFace(DialFace.OVERVIEW))
    }

    // Two-level dismiss: out of the workout face to the overview, then out of the
    // app. DONE is terminal — a swipe or back there means the same thing its tap
    // does, so both are handed back to the system.
    val backToOverview = systemBackTarget(saved.face, state.screen) != null
    BackHandler(enabled = backToOverview, onBack = ::returnToOverview)
    BasicSwipeToDismissBox(
        onDismissed = ::returnToOverview,
        // Off on the overview: the gesture then belongs to the system, which is
        // how the second level (out of the app) stays the platform's own.
        userSwipeEnabled = backToOverview,
    ) { isBackground ->
        // What the swipe pulls the face off is the dial's own near-black, not a
        // second live dial: the overview arrives when the gesture lands, and a
        // whole dial composed underneath a rest would repaint five times a second
        // for something nobody sees.
        if (isBackground) {
            Box(Modifier.fillMaxSize().background(Background))
            return@BasicSwipeToDismissBox
        }
        Dial(
            state = state,
            onRepCountChange = { dispatch(WorkoutEvent.ChangeRepCount(it)) },
            onCardioSetTurn = { onCardioSetTurn(it) },
            modifier = Modifier.fillMaxSize().background(Background).then(swipeLeft),
            onTap = {
                when (state.tap) {
                    DialTap.OPEN_WORKOUT -> dispatch(WorkoutEvent.ShowFace(DialFace.WORKOUT))
                    DialTap.START_SET -> dispatch(WorkoutEvent.StartSet)
                    DialTap.TICK -> dispatch(WorkoutEvent.Tick())
                    DialTap.SKIP_REST -> dispatch(WorkoutEvent.SkipRest)
                    DialTap.CONFIRM_SWAP -> dispatch(WorkoutEvent.ConfirmSwap)
                    DialTap.DISMISS -> if (cardioSets?.complete == true) {
                        onCardioSetDismiss()
                        dispatch(WorkoutEvent.ShowFace(DialFace.OVERVIEW))
                    } else onDismiss()
                    DialTap.START_CARDIO -> dispatch(WorkoutEvent.StartCardio)
                    DialTap.CARDIO_SET -> onCardioSetTap()
                    DialTap.NONE -> Unit
                }
            },
            onHoldComplete = { hold ->
                when (hold.action) {
                    DialHoldAction.UNDO -> hold.target?.let { dispatch(WorkoutEvent.Undo(it)) }
                    DialHoldAction.STOP_CARDIO -> dispatch(WorkoutEvent.StopCardio)
                    DialHoldAction.STOP_CARDIO_SET, DialHoldAction.CARDIO_SET_OPTIONS -> onCardioSetHold(hold)
                    DialHoldAction.STOP_TIMED_REP -> dispatch(WorkoutEvent.StopTimedRep(hold.timedRepAttempt))
                }
            },
        )
    }
}

/**
 * Drives the OngoingActivity re-entry chip from reconciled snapshot and local
 * session state (redesign §1.4 / R6).
 *
 * [isSessionUnderway] posts immediately when the first set starts, before its
 * tick reaches the snapshot. Its snapshot half still **reconciles on launch**:
 * first composition (snapshot still loading ⇒ inactive) cancels any chip a
 * killed process left behind, and a finished day flips it back to `clear()`.
 *
 * [POST_NOTIFICATIONS][Manifest.permission.POST_NOTIFICATIONS] is requested
 * **contextually** — once, the moment a session first becomes active (API 33+
 * only). Denial is graceful: [OngoingWorkoutChip.show] no-ops without the grant,
 * we never re-ask, and logging is entirely unaffected (re-entry falls back to
 * the launcher).
 */
@Composable
private fun OngoingSessionChip(snapshot: WatchSnapshot?, localSessionStartedAtMillis: Long) {
    val context = LocalContext.current
    val chip = remember(context) { OngoingWorkoutChip(context) }
    val sessionActive = isSessionUnderway(snapshot, localSessionStartedAtMillis > 0L)

    var hasPermission by remember {
        mutableStateOf(OngoingWorkoutChip.hasPostNotificationsPermission(context))
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }
    // Once per install-session, not per day: a denial must stay denied across
    // DONE/undo and day turnover, or the prompt re-fires on every reactivation.
    var permissionRequested by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(sessionActive) {
        if (sessionActive && !hasPermission && !permissionRequested &&
            OngoingWorkoutChip.needsRuntimePermission()
        ) {
            permissionRequested = true
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(sessionActive, hasPermission) {
        if (sessionActive) chip.show(localSessionStartedAtMillis) else chip.clear()
    }
}

/** The dial recomposes every rest-tick second; copy changes only with the
 *  configuration, so the lambda bag is rebuilt on that key alone. Templates
 *  resolve through [stringResource], the configuration-aware read lint asks for. */
@Composable
internal fun rememberDialCopy(): DialCopy {
    val continueText = stringResource(R.string.dial_continue)
    val start = stringResource(R.string.dial_start)
    val day = stringResource(R.string.dial_day)
    val setOf = stringResource(R.string.dial_set_of)
    val exerciseKind = stringResource(R.string.dial_exercise_kind)
    val waitingOnPhone = stringResource(R.string.dial_waiting_on_phone)
    val swapping = stringResource(R.string.dial_swapping)
    val useThis = stringResource(R.string.dial_use_this)
    val alternateOf = stringResource(R.string.dial_alternate_of)
    val tapToLog = stringResource(R.string.dial_tap_to_log)
    val goal = stringResource(R.string.dial_goal)
    val rest = stringResource(R.string.dial_rest)
    val tapToSkip = stringResource(R.string.dial_tap_to_skip)
    val next = stringResource(R.string.dial_next)
    val rested = stringResource(R.string.dial_rested)
    val done = stringResource(R.string.dial_done)
    val minutes = stringResource(R.string.dial_minutes)
    val volume = stringResource(R.string.dial_volume)
    val sets = stringResource(R.string.dial_sets)
    val undo = stringResource(R.string.dial_undo)
    val set = stringResource(R.string.dial_set)
    val took = stringResource(R.string.dial_took)
    val thenPartner = stringResource(R.string.dial_then_partner)
    val releaseToReturn = stringResource(R.string.dial_release_to_return)
    val queued = stringResource(R.string.dial_queued)
    val nothingOnToday = stringResource(R.string.nothing_on_today)
    val noExercisesYet = stringResource(R.string.dial_no_exercises_yet)
    val rampLabel = stringResource(R.string.dial_ramp_label)
    val topLabel = stringResource(R.string.dial_top_label)
    val backoffLabel = stringResource(R.string.dial_backoff_label)
    val cardio = stringResource(R.string.dial_cardio)
    val cardioStart = stringResource(R.string.dial_cardio_start)
    val cardioHoldToStop = stringResource(R.string.dial_cardio_hold_to_stop)
    val cardioStop = stringResource(R.string.dial_cardio_stop)
    val timedRepStop = stringResource(R.string.dial_timed_rep_stop)
    val timesUp = stringResource(R.string.dial_times_up)
    val repAim = stringResource(R.string.dial_rep_aim)
    val configuration = LocalConfiguration.current
    return remember(configuration) {
        DialCopy(
            continueText = continueText,
            start = start,
            day = { day.format(it) },
            setOf = { current, total -> setOf.format(current, total) },
            exerciseKind = { exercise, kind -> exerciseKind.format(exercise, kind) },
            waitingOnPhone = waitingOnPhone,
            swapping = swapping,
            useThis = useThis,
            alternateOf = { current, total -> alternateOf.format(current, total) },
            tapToLog = tapToLog,
            goal = { goal.format(it) },
            rest = rest,
            tapToSkip = tapToSkip,
            next = { next.format(it) },
            rested = { rested.format(it) },
            done = done,
            minutes = { minutes.format(it) },
            volume = { value, unit -> volume.format(value, unit) },
            sets = { sets.format(it) },
            undo = undo,
            set = { set.format(it) },
            took = { took.format(it) },
            thenPartner = { name, summary -> thenPartner.format(name, summary) },
            releaseToReturn = releaseToReturn,
            queued = { queued.format(it) },
            nothingOnToday = nothingOnToday,
            noExercisesYet = noExercisesYet,
            rampLabel = { rampLabel.format(it) },
            topLabel = topLabel,
            backoffLabel = backoffLabel,
            cardio = cardio,
            cardioStart = cardioStart,
            cardioHoldToStop = cardioHoldToStop,
            cardioStop = cardioStop,
            timedRepStop = timedRepStop,
            timesUp = timesUp,
            repAim = { repAim.format(it) },
        )
    }
}
