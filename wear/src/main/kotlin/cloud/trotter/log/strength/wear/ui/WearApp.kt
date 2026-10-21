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
import androidx.compose.runtime.saveable.mapSaver
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
import cloud.trotter.log.strength.domain.sync.ExerciseSwapDelta
import cloud.trotter.log.strength.domain.sync.CardioDelta
import cloud.trotter.log.strength.domain.generator.CardioIntervals
import cloud.trotter.log.strength.domain.sync.SetEditDelta
import cloud.trotter.log.strength.domain.sync.WatchAlternate
import cloud.trotter.log.strength.domain.sync.WatchSnapshot
import cloud.trotter.log.strength.wear.OngoingWorkoutChip
import cloud.trotter.log.strength.wear.R
import cloud.trotter.log.strength.wear.data.WatchTrackerClient
import cloud.trotter.log.strength.wear.data.CompanionDetector
import cloud.trotter.log.strength.wear.theme.Background
import cloud.trotter.log.strength.wear.theme.WearTrackerTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** No rest, and no rest just ended — the sentinel for both saveable ints. */
private const val NO_REST = -1

/** The crown hasn't picked an exercise; the dial follows the day's own order. */
private const val NO_SELECTION = -1

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
    var localSessionStartedAtMillis by rememberSaveable(snapshot?.planId, dayId) { mutableLongStateOf(0L) }
    val context = LocalContext.current
    // The exact-alarm listener lives at the root so it outlives the ambient swap.
    val restController = remember(context) { restTimerController(context.applicationContext) }
    val rootScope = rememberCoroutineScope()
    // Cardio anchors are ROOT state: ambient removes WorkoutDial from the
    // composition, and a running block must survive that swap (review H1).
    val cardioDayId = snapshot?.day?.dayId
    var cardioStartedAtWall by rememberSaveable(cardioDayId) { mutableLongStateOf(0L) }
    var cardioStartedAtElapsed by rememberSaveable(cardioDayId) { mutableLongStateOf(0L) }
    var cardioStartedAtBoot by rememberSaveable(cardioDayId) { mutableIntStateOf(0) }
    var cardioPlanId by rememberSaveable(cardioDayId) { mutableStateOf<Long?>(null) }
    val cardioAnchors = cardioStartedAtElapsed.takeIf { it > 0L }?.let {
        CardioAnchors(cardioStartedAtWall, it, cardioStartedAtBoot, cardioPlanId)
    }
    var savedTimedRep by rememberSaveable(stateSaver = timedRepStateSaver) {
        mutableStateOf<TimedRepState?>(null)
    }
    // Do not discard a restored attempt while the initial snapshot is loading.
    val timedRep = snapshot?.let { savedTimedRep?.reconcile(it)?.takeIf { cardioAnchors == null } }
    LaunchedEffect(snapshot, timedRep) {
        if (snapshot != null && savedTimedRep != timedRep) savedTimedRep = timedRep
    }
    val rootView = LocalView.current
    fun bootCountNow(): Int = android.provider.Settings.Global.getInt(
        context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 0,
    )

    fun startCardio() {
        if (timedRep != null) return
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

    // Boundary buzzes are armed at the ROOT so ambient keeps them, and the
    // finally-disarm covers every vanish path — day change, dismiss, restore
    // past the end — not just the explicit stop (review M3).
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
    TimedRepScreenAwake(timedRep?.phase == TimedRepPhase.RUNNING)

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
                // not show LoadingDial's animated accent sweep (#161).
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
                    repWindowDeadlineMillis = timedRep?.takeIf { it.phase == TimedRepPhase.RUNNING }?.deadline(
                        SystemClock.elapsedRealtime(), System.currentTimeMillis(), bootCountNow(),
                    ),
                    burnInProtectionRequired = burnInProtectionRequired,
                    deviceHasLowBitAmbient = deviceHasLowBitAmbient,
                )
                else -> WorkoutDial(
                    snap = snap,
                    cardioAnchors = cardioAnchors,
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
                    // Activity going away mid-enqueue (#174).
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
    sendCardio: (CardioDelta) -> Unit,
    onSessionStarted: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val view = LocalView.current
    val dayId = snap.day.dayId
    val saved = rememberSaveable(snap.planId, dayId, saver = workoutDialStateSaver(snap.planId, dayId)) {
        WorkoutDialState(snap.planId, dayId, impliedFace(snap))
    }
    var face by saved::face
    var lifting by saved::lifting
    var startedAtWallMillis by saved::startedAtWallMillis
    var startedAtElapsedMillis by saved::startedAtElapsedMillis
    var restDeadlineMillis by saved::restDeadlineMillis
    var restTotalSeconds by saved::restTotalSeconds
    var restBetweenExercises by saved::restBetweenExercises
    var restedSeconds by saved::restedSeconds
    var sessionStartedAtMillis by saved::sessionStartedAtMillis
    var sessionEndedAtMillis by saved::sessionEndedAtMillis
    var selectedExercise by saved::selectedExercise
    var tickMemory by saved::tickMemory
    var peek by remember(snap.planId, dayId) { mutableStateOf<PeekState?>(null) }
    var browseDay by remember(snap.planId, dayId) { mutableStateOf<Int?>(null) }
    var nowElapsedMillis by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    val exerciseIndex = timedRep?.owner?.programExerciseId?.let { id ->
        snap.day.exercises.indexOfFirst { it.programExerciseId == id }.takeIf { it >= 0 }
    } ?: ExerciseSelection.resolve(snap, selectedExercise.takeIf { it >= 0 })
    val exercise = snap.day.exercises.getOrNull(exerciseIndex)
    val roundIndex = exercise?.let(::currentRoundIndex) ?: 0
    val holdGoal = exercise?.let { holdGoalSeconds(it, roundIndex) } ?: 0

    // A swap being considered is a glance like the peek — never restored through a
    // process death, and keyed on the lift as well as the day so that leaving the
    // lift forgets the half-made decision rather than carrying it onto the next one.
    var swapPreview by remember(snap.planId, dayId, exerciseIndex) { mutableStateOf<SwapPreview?>(null) }

    fun startRest(seconds: Int, betweenExercises: Boolean) {
        restTotalSeconds = seconds
        restBetweenExercises = betweenExercises
        restDeadlineMillis = RestTimer.deadlineFrom(SystemClock.elapsedRealtime(), seconds)
    }

    fun clearRest() {
        restDeadlineMillis = 0L
        restTotalSeconds = 0
        restBetweenExercises = false
    }

    fun startSet() {
        if (exercise?.repWindowSeconds?.let { it > 0 } == true) {
            val attempt = startTimedRep(
                snap, exerciseIndex, cardioAnchors != null, timedRep,
                System.currentTimeMillis(), SystemClock.elapsedRealtime(), bootCount,
            ) ?: return
            clearRest()
            lifting = false
            startedAtWallMillis = 0L
            startedAtElapsedMillis = 0L
            restedSeconds = NO_REST
            nowElapsedMillis = SystemClock.elapsedRealtime()
            onTimedRepChanged(attempt)
            if (sessionStartedAtMillis == 0L) {
                sessionStartedAtMillis = attempt.startedAtWallMillis
                onSessionStarted(attempt.startedAtWallMillis)
            }
            DialHaptics.perform(view, DialHaptics.Cue.START)
            return
        }
        if (cardioAnchors != null || timedRep != null) return
        lifting = true
        restedSeconds = NO_REST
        val wallNow = System.currentTimeMillis()
        startedAtWallMillis = wallNow
        startedAtElapsedMillis = SystemClock.elapsedRealtime()
        if (sessionStartedAtMillis == 0L) {
            sessionStartedAtMillis = wallNow
            onSessionStarted(wallNow)
        }
        nowElapsedMillis = SystemClock.elapsedRealtime()
        DialHaptics.perform(view, DialHaptics.Cue.START)
    }

    fun startCardio() {
        face = DialFace.WORKOUT
        nowElapsedMillis = SystemClock.elapsedRealtime()
        onStartCardio()
    }

    fun stopCardio() {
        onStopCardio()
    }

    fun tick() {
        val ex = exercise ?: return
        val set = ex.sets.getOrNull(roundIndex) ?: return
        if (set.done) return
        val completedAtMillis = System.currentTimeMillis()
        // A timed hold's goal buzz belongs to the set that is ending; never let it
        // land during the rest that follows.
        restController.disarm()
        val timedDelta = timedRep?.let { buildTimedRepDelta(it, snap, completedAtMillis) }
        if (timedRep != null && timedDelta == null) return
        sendEdit(
            timedDelta ?: buildTickDelta(
                dayId = dayId,
                programExerciseId = ex.programExerciseId,
                setIndex = roundIndex,
                startedAtMillis = startedAtWallMillis,
                completedAtMillis = completedAtMillis,
                snapshot = snap,
            ),
        )
        DialHaptics.perform(view, DialHaptics.Cue.CONFIRM_TICK)
        // Remembered on the wrist only — the wire never echoes the stamps back, so
        // this is the peek's only source for TOOK (§6) and the undo's only source of
        // chronology. Recorded even when no start was seen: a set the watch didn't
        // time still happened, and the undo has to be able to reach it.
        val tickStartedAt = timedRep?.startedAtWallMillis ?: startedAtWallMillis
        tickMemory = tickMemory.record(
            programExerciseId = ex.programExerciseId,
            roundIndex = roundIndex,
            durationSeconds = if (tickStartedAt > 0L) {
                ((completedAtMillis - tickStartedAt) / 1000L).toInt()
            } else {
                0
            },
        )
        onTimedRepChanged(null)
        lifting = false
        startedAtWallMillis = 0L
        startedAtElapsedMillis = 0L
        restedSeconds = NO_REST
        if (sessionStartedAtMillis == 0L) sessionStartedAtMillis = completedAtMillis
        sessionEndedAtMillis = completedAtMillis

        // Post-tick flags for this exercise (optimistic: the tick just marked this
        // round done) and for the day, so the advance decision matches what the
        // snapshot is about to say.
        val postTickFlags = ex.sets.mapIndexed { i, s -> i == roundIndex || s.done }
        val othersDone = snap.day.exercises.all { other ->
            other.programExerciseId == ex.programExerciseId || other.sets.all { it.done }
        }
        val advance = decideStreamAdvance(postTickFlags, postTickFlags.all { it } && othersDone)
        if (advance == StreamAdvance.DayDone && snap.cardio?.loggedStartedAt == null) {
            // The optional finisher is offered from the overview after the last lift.
            face = DialFace.OVERVIEW
        }
        when {
            RestTimer.shouldRest(advance, set.restAfterSeconds) ->
                startRest(seconds = set.restAfterSeconds, betweenExercises = false)
            RestTimer.shouldRestAfterExercise(advance, set.restAfterSeconds) ->
                startRest(seconds = set.restAfterSeconds, betweenExercises = true)
        }
    }

    /**
     * The deliberate way back out of a tick (§6): send the untick, forget the time
     * it claimed, and put the dial back on the round it just reopened. Whatever the
     * tick set in motion goes with it — the rest it started was rest between two
     * sets, one of which no longer exists.
     *
     * [target] is the day's most recent tick and so need not be a round of the lift
     * on screen, which is the whole point: the sets that are hardest to take back
     * are the ones whose tick moved the dial away from them. Pointing the crown's
     * own selection at the target is what walks the dial back — the derived rule
     * would go to the *first* lift with work left, which after undoing a set the
     * lifter deliberately skipped ahead to is not where they are looking.
     */
    fun undo(target: UndoTarget) {
        val ex = snap.day.exercises.getOrNull(target.exerciseIndex) ?: return
        restController.disarm()
        clearRest()
        restedSeconds = NO_REST
        lifting = false
        startedAtWallMillis = 0L
        startedAtElapsedMillis = 0L
        sendEdit(
            buildUndoDelta(
                dayId = dayId,
                programExerciseId = ex.programExerciseId,
                setIndex = target.roundIndex,
                editedAtMillis = System.currentTimeMillis(),
                snapshot = snap,
            ),
        )
        tickMemory = tickMemory.forget(ex.programExerciseId, target.roundIndex)
        selectedExercise = target.exerciseIndex
        DialHaptics.perform(view, DialHaptics.Cue.UNDO)
    }

    /**
     * Sends the swap and lets the phone answer (#90). The optimistic half is the
     * client's (the name changes at once); everything the *phone* is about to clear
     * is dropped here: this watch's recollection of ticks against the slot, because
     * the phone's §8.3 swap clears the slot's log and a memory of work that no longer
     * exists is a memory the undo must not be offered.
     *
     * The dial only reaches this on a lift with nothing logged, so there is normally
     * nothing to forget — the call is here for the one case there can be: a round
     * this watch ticked and the phone later untook.
     */
    fun confirmSwap(alternate: WatchAlternate) {
        val ex = exercise ?: return
        swapPreview = null
        sendSwap(
            buildSwapDelta(
                snapshot = snap,
                programExerciseId = ex.programExerciseId,
                alternate = alternate,
                editedAtMillis = System.currentTimeMillis(),
            ),
        )
        tickMemory = tickMemory.forgetExercise(ex.programExerciseId)
        DialHaptics.perform(view, DialHaptics.Cue.CONFIRM_TICK)
    }

    val inputs = DialInputs(
        snapshot = snap,
        exerciseIndex = exerciseIndex,
        phase = if (lifting) SetPhase.LIFTING else SetPhase.READY,
        face = face,
        pendingCount = pendingCount,
        rest = if (restDeadlineMillis > 0L) {
            RestState(restDeadlineMillis, restTotalSeconds, restBetweenExercises)
        } else {
            null
        },
        restedSeconds = restedSeconds.takeIf { it != NO_REST },
        liftingElapsedMillis = if (lifting) {
            (nowElapsedMillis - startedAtElapsedMillis).coerceAtLeast(0L)
        } else {
            0L
        },
        nowElapsedMillis = nowElapsedMillis,
        nowWallMillis = System.currentTimeMillis(),
        session = SessionStamps(sessionStartedAtMillis, sessionEndedAtMillis),
        browseDayIndex = browseDay,
        peekRoundIndex = peek?.roundIndex,
        swapAlternateIndex = swapPreview?.alternateIndex,
        pendingSwapExerciseIds = pendingSwaps,
        tickMemory = tickMemory,
        cardioAnchors = cardioAnchors,
        timedRep = timedRep,
        timedRepDeadlineMillis = timedRep?.deadline(nowElapsedMillis, System.currentTimeMillis(), bootCount) ?: 0L,
    )
    val copy = rememberDialCopy()
    val state = dialUiState(inputs, copy)

    // Repaint ticker: only while something on screen is counting.
    LaunchedEffect(state.screen) {
        if (state.screen !in LIVE_SCREENS) return@LaunchedEffect
        val cadence = if (state.screen == DialScreen.LIFTING) LIFTING_TICK_MILLIS else LIVE_TICK_MILLIS
        while (true) {
            nowElapsedMillis = SystemClock.elapsedRealtime()
            delay(cadence)
        }
    }

    // The crown's choice is *forgotten*, not merely ignored, once the day stops
    // honouring it — but only once nothing of ours is still in flight against that
    // lift ([ExerciseSelection.shouldForget]). Both keys matter: a snapshot can carry
    // a newer revision and still predate our undo, and the queue draining is the
    // other moment the answer can change.
    LaunchedEffect(snap.revision, pendingExercises) {
        if (ExerciseSelection.shouldForget(snap, selectedExercise.takeIf { it >= 0 }, pendingExercises)) {
            selectedExercise = NO_SELECTION
        }
    }

    // The rest. The controller owns the single buzz; this effect only re-segments
    // the dial afterwards.
    // Keyed on the deadline so it re-arms from the restored value after process
    // death — and if that value is already past, clears the stale rest at once.
    // A peek ends when the crown stops turning: a rotary crown has no release
    // event, so the brief's "↺ RELEASE TO RETURN" is read as stopping. The effect
    // re-arms on every turn, so this delay is the idle timeout itself.
    LaunchedEffect(peek) {
        val current = peek ?: return@LaunchedEffect
        delay(PeekScrub.IDLE_TIMEOUT_MILLIS)
        if (PeekScrub.expired(current, SystemClock.elapsedRealtime())) peek = null
    }

    // A swap under consideration puts the lift back the same way, on its own longer
    // clock — see SwapPicker. Nothing else cancels it; the tap is the only commit.
    LaunchedEffect(swapPreview) {
        val current = swapPreview ?: return@LaunchedEffect
        delay(SwapPicker.IDLE_TIMEOUT_MILLIS)
        if (SwapPicker.expired(current, SystemClock.elapsedRealtime())) swapPreview = null
    }

    LaunchedEffect(restDeadlineMillis, timedRep?.owner, cardioAnchors) {
        if (restDeadlineMillis <= 0L || timedRep != null || cardioAnchors != null) return@LaunchedEffect
        restController.arm(restDeadlineMillis, restTotalSeconds)
        while (!RestTimer.isExpired(restDeadlineMillis, SystemClock.elapsedRealtime())) {
            delay(LIVE_TICK_MILLIS)
        }
        // A rest between exercises hands straight over to the next exercise's Ready;
        // a rest between sets earns the "✓ RESTED" badge and the halo bloom (§5.5).
        restedSeconds = if (restBetweenExercises) NO_REST else restTotalSeconds
        clearRest()
        nowElapsedMillis = SystemClock.elapsedRealtime()
    }

    // A timed hold buzzes once at its goal and ticks itself (§5.6). Same controller,
    // so it is the same single-buzz guarantee, and a manual tick cancels it.
    val latestTick by rememberUpdatedState({ tick() })
    LaunchedEffect(lifting, startedAtElapsedMillis, holdGoal, timedRep?.owner, cardioAnchors) {
        if (timedRep != null || cardioAnchors != null || !lifting || holdGoal <= 0 || startedAtElapsedMillis <= 0L) return@LaunchedEffect
        val goalDeadline = RestTimer.deadlineFrom(startedAtElapsedMillis, holdGoal)
        restController.arm(goalDeadline, holdGoal) { firing ->
            scheduleTimedHoldTick(firing, latestTick)
        }
        while (!RestTimer.isExpired(goalDeadline, SystemClock.elapsedRealtime())) {
            delay(LIVE_TICK_MILLIS)
        }
    }


    // The crown reads the screen it's on: exercises on the overview, rounds in a
    // session, nothing where there is nothing to look through (§6).
    val crown = rememberCrownModifier(enabled = state.crown != DialCrown.NONE) { detents ->
        var moved = 0
        when (state.crown) {
            DialCrown.SELECT_EXERCISE -> {
                val next = ExerciseSelection.move(exerciseIndex, detents, snap.day.exercises.size)
                moved = next - exerciseIndex
                selectedExercise = next
            }
            DialCrown.PEEK -> {
                val from = peek?.roundIndex ?: roundIndex
                val next = PeekScrub.turn(
                    current = peek,
                    currentRoundIndex = roundIndex,
                    detents = detents,
                    roundCount = exercise?.sets?.size ?: 0,
                    nowElapsedMillis = SystemClock.elapsedRealtime(),
                )
                moved = (next?.roundIndex ?: from) - from
                peek = next
            }
            DialCrown.SELECT_ALTERNATE -> {
                val from = swapPreview?.alternateIndex ?: -1
                val next = SwapPicker.turn(
                    current = swapPreview,
                    detents = detents,
                    alternateCount = exercise?.alternates?.size ?: 0,
                    nowElapsedMillis = SystemClock.elapsedRealtime(),
                )
                moved = (next?.alternateIndex ?: -1) - from
                swapPreview = next
            }
            DialCrown.REP_COUNT -> {
                val attempt = timedRep ?: return@rememberCrownModifier
                val next = attempt.turn(detents)
                moved = next.count - attempt.count
                onTimedRepChanged(next)
            }
            DialCrown.NONE -> Unit
        }
        repeat(kotlin.math.abs(moved)) {
            DialHaptics.perform(view, DialHaptics.Cue.ROTARY_DETENT)
        }
        if (moved != detents) DialHaptics.perform(view, DialHaptics.Cue.BOUNDARY)
    }

    // Leftward is contextual and only where nothing is under way (v3 §3); rightward
    // is the platform's, below.
    val swipeLeft = crown.swipeLeft(enabled = state.swipe != DialSwipe.NONE) {
        when (state.swipe) {
            DialSwipe.NEXT_EXERCISE -> selectedExercise = ExerciseSelection.next(
                fromIndex = exerciseIndex,
                hasWorkLeft = snap.day.exercises.map { ex -> ex.sets.any { !it.done } },
            )
            DialSwipe.BROWSE_DAY -> browseDay = CycleBrowse.next(browseDay, snap)
            DialSwipe.NONE -> Unit
        }
    }

    fun returnToOverview() {
        face = DialFace.OVERVIEW
    }

    // Two-level dismiss: out of the workout face to the overview, then out of the
    // app. DONE is terminal — a swipe or back there means the same thing its tap
    // does, so both are handed back to the system.
    val backToOverview = systemBackTarget(face, state.screen) != null
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
            modifier = Modifier.fillMaxSize().background(Background).then(swipeLeft),
            onTap = {
                when (state.tap) {
                    DialTap.OPEN_WORKOUT -> face = DialFace.WORKOUT
                    DialTap.START_SET -> startSet()
                    DialTap.TICK -> tick()
                    DialTap.SKIP_REST -> {
                        // Skipping is silent by design: cancel the pending buzz, drop
                        // straight to the next set. No "rested" badge — nothing rested.
                        restController.disarm()
                        clearRest()
                        restedSeconds = NO_REST
                    }
                    // Resolved, not indexed raw: the prescription can shrink under a
                    // live preview, and the confirm must act on the alternate the
                    // disc is naming (SwapPicker.resolve) rather than silently do
                    // nothing because the old index fell off the end.
                    DialTap.CONFIRM_SWAP -> exercise?.let { ex ->
                        SwapPicker.resolve(swapPreview?.alternateIndex, ex.alternates.size)
                            ?.let { confirmSwap(ex.alternates[it]) }
                    }
                    DialTap.DISMISS -> onDismiss()
                    DialTap.START_CARDIO -> startCardio()
                    DialTap.NONE -> Unit
                }
            },
            onHoldComplete = { hold ->
                when (hold.action) {
                    DialHoldAction.UNDO -> hold.target?.let(::undo)
                    DialHoldAction.STOP_CARDIO -> stopCardio()
                    DialHoldAction.STOP_TIMED_REP -> {
                        restController.disarm()
                        onTimedRepChanged(null)
                        face = DialFace.WORKOUT
                        DialHaptics.perform(view, DialHaptics.Cue.START)
                    }
                }
            },
        )
    }
}

/** Local dial state and its owner travel together through process death. */
internal class WorkoutDialState(val planId: Long?, val dayId: String, face: DialFace) {
    var face by mutableStateOf(face)
    var lifting by mutableStateOf(false)
    var startedAtWallMillis by mutableLongStateOf(0L)
    var startedAtElapsedMillis by mutableLongStateOf(0L)
    var restDeadlineMillis by mutableLongStateOf(0L)
    var restTotalSeconds by mutableIntStateOf(0)
    var restBetweenExercises by mutableStateOf(false)
    var restedSeconds by mutableIntStateOf(NO_REST)
    var sessionStartedAtMillis by mutableLongStateOf(0L)
    var sessionEndedAtMillis by mutableLongStateOf(0L)
    var selectedExercise by mutableIntStateOf(NO_SELECTION)
    var tickMemory by mutableStateOf(TickMemory.EMPTY)
}

// rememberSaveable inputs reset a live plan/day switch, but do not validate a
// restored bundle. The first available snapshot must own the saved state too.
internal fun workoutDialStateSaver(planId: Long?, dayId: String) = mapSaver(
    save = { state: WorkoutDialState ->
        mapOf(
            "planId" to state.planId,
            "dayId" to state.dayId,
            "face" to state.face.name,
            "lifting" to state.lifting,
            "startedAtWallMillis" to state.startedAtWallMillis,
            "startedAtElapsedMillis" to state.startedAtElapsedMillis,
            "restDeadlineMillis" to state.restDeadlineMillis,
            "restTotalSeconds" to state.restTotalSeconds,
            "restBetweenExercises" to state.restBetweenExercises,
            "restedSeconds" to state.restedSeconds,
            "sessionStartedAtMillis" to state.sessionStartedAtMillis,
            "sessionEndedAtMillis" to state.sessionEndedAtMillis,
            "selectedExercise" to state.selectedExercise,
            "tickMemory" to state.tickMemory.encode(),
        )
    },
    restore = { saved ->
        if (saved["planId"] != planId || saved["dayId"] != dayId) {
            null
        } else {
            WorkoutDialState(planId, dayId, DialFace.valueOf(saved["face"] as String)).apply {
                lifting = saved["lifting"] as Boolean
                startedAtWallMillis = saved["startedAtWallMillis"] as Long
                startedAtElapsedMillis = saved["startedAtElapsedMillis"] as Long
                restDeadlineMillis = saved["restDeadlineMillis"] as Long
                restTotalSeconds = saved["restTotalSeconds"] as Int
                restBetweenExercises = saved["restBetweenExercises"] as Boolean
                restedSeconds = saved["restedSeconds"] as Int
                sessionStartedAtMillis = saved["sessionStartedAtMillis"] as Long
                sessionEndedAtMillis = saved["sessionEndedAtMillis"] as Long
                selectedExercise = saved["selectedExercise"] as Int
                tickMemory = TickMemory.decode(saved["tickMemory"] as String)
            }
        }
    },
)

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
private fun rememberDialCopy(): DialCopy {
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
    val noProgram = stringResource(R.string.dial_no_program)
    val setUpOnPhone = stringResource(R.string.dial_set_up_on_phone)
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
            noProgram = noProgram,
            setUpOnPhone = setUpOnPhone,
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
