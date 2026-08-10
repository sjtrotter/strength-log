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
import androidx.compose.runtime.saveable.Saver
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
private val LIVE_SCREENS = setOf(DialScreen.LIFTING, DialScreen.TIMED_HOLD, DialScreen.REST)

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
    var localSessionStartedAtMillis by rememberSaveable(dayId) { mutableLongStateOf(0L) }
    val context = LocalContext.current
    // The exact-alarm listener lives at the root so it outlives the ambient swap.
    val restController = remember(context) { restTimerController(context.applicationContext) }
    val rootScope = rememberCoroutineScope()
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
    // A rollover invalidates a fired hold as well as an armed one. The retained
    // latest-tick closure must never apply yesterday's exercise to today's snapshot.
    DisposableEffect(dayId, restController) {
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
                    burnInProtectionRequired = burnInProtectionRequired,
                    deviceHasLowBitAmbient = deviceHasLowBitAmbient,
                )
                else -> WorkoutDial(
                    snap = snap,
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
    pendingCount: Int,
    pendingExercises: Set<Long>,
    pendingSwaps: Set<Long>,
    restController: RestTimerController,
    scheduleTimedHoldTick: (RestTimerController.Firing, () -> Unit) -> Unit,
    sendEdit: (SetEditDelta) -> Unit,
    sendSwap: (ExerciseSwapDelta) -> Unit,
    onSessionStarted: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val view = LocalView.current
    // Keyed on the day: a rollover to tomorrow's workout starts a fresh session
    // rather than inheriting yesterday's stamps and a rest nobody is taking.
    val dayId = snap.day.dayId
    var face by rememberSaveable(dayId) { mutableStateOf(impliedFace(snap)) }
    var lifting by rememberSaveable(dayId) { mutableStateOf(false) }
    var startedAtWallMillis by rememberSaveable(dayId) { mutableLongStateOf(0L) }
    var startedAtElapsedMillis by rememberSaveable(dayId) { mutableLongStateOf(0L) }
    var restDeadlineMillis by rememberSaveable(dayId) { mutableLongStateOf(0L) }
    var restTotalSeconds by rememberSaveable(dayId) { mutableIntStateOf(0) }
    var restBetweenExercises by rememberSaveable(dayId) { mutableStateOf(false) }
    var restedSeconds by rememberSaveable(dayId) { mutableIntStateOf(NO_REST) }
    var sessionStartedAtMillis by rememberSaveable(dayId) { mutableLongStateOf(0L) }
    var sessionEndedAtMillis by rememberSaveable(dayId) { mutableLongStateOf(0L) }
    var selectedExercise by rememberSaveable(dayId) { mutableIntStateOf(NO_SELECTION) }
    var tickMemory by rememberSaveable(dayId, stateSaver = TickMemorySaver) {
        mutableStateOf(TickMemory.EMPTY)
    }
    var peek by remember(dayId) { mutableStateOf<PeekState?>(null) }
    var browseDay by remember(dayId) { mutableStateOf<Int?>(null) }
    var nowElapsedMillis by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    val exerciseIndex = ExerciseSelection.resolve(snap, selectedExercise.takeIf { it >= 0 })
    val exercise = snap.day.exercises.getOrNull(exerciseIndex)
    val roundIndex = exercise?.let(::currentRoundIndex) ?: 0
    val holdGoal = exercise?.let { holdGoalSeconds(it, roundIndex) } ?: 0

    // A swap being considered is a glance like the peek — never restored through a
    // process death, and keyed on the lift as well as the day so that leaving the
    // lift forgets the half-made decision rather than carrying it onto the next one.
    var swapPreview by remember(dayId, exerciseIndex) { mutableStateOf<SwapPreview?>(null) }

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

    fun tick() {
        val ex = exercise ?: return
        val set = ex.sets.getOrNull(roundIndex) ?: return
        if (set.done) return
        val completedAtMillis = System.currentTimeMillis()
        // A timed hold's goal buzz belongs to the set that is ending; never let it
        // land during the rest that follows.
        restController.disarm()
        sendEdit(
            buildTickDelta(
                dayId = dayId,
                programExerciseId = ex.programExerciseId,
                setIndex = roundIndex,
                startedAtMillis = startedAtWallMillis,
                completedAtMillis = completedAtMillis,
            ),
        )
        DialHaptics.perform(view, DialHaptics.Cue.CONFIRM_TICK)
        // Remembered on the wrist only — the wire never echoes the stamps back, so
        // this is the peek's only source for TOOK (§6) and the undo's only source of
        // chronology. Recorded even when no start was seen: a set the watch didn't
        // time still happened, and the undo has to be able to reach it.
        tickMemory = tickMemory.record(
            programExerciseId = ex.programExerciseId,
            roundIndex = roundIndex,
            durationSeconds = if (startedAtWallMillis > 0L) {
                ((completedAtMillis - startedAtWallMillis) / 1000L).toInt()
            } else {
                0
            },
        )
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
            ExerciseSwapDelta(
                dayId = dayId,
                programExerciseId = ex.programExerciseId,
                exerciseId = alternate.exerciseId,
                exerciseName = alternate.name,
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
        session = SessionStamps(sessionStartedAtMillis, sessionEndedAtMillis),
        browseDayIndex = browseDay,
        peekRoundIndex = peek?.roundIndex,
        swapAlternateIndex = swapPreview?.alternateIndex,
        pendingSwapExerciseIds = pendingSwaps,
        tickMemory = tickMemory,
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

    LaunchedEffect(restDeadlineMillis) {
        if (restDeadlineMillis <= 0L) return@LaunchedEffect
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
    LaunchedEffect(lifting, startedAtElapsedMillis, holdGoal) {
        if (!lifting || holdGoal <= 0 || startedAtElapsedMillis <= 0L) return@LaunchedEffect
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
                    DialTap.NONE -> Unit
                }
            },
            onHoldComplete = { target -> undo(target) },
        )
    }
}

/** [TickMemory] rides a saved-instance bundle as its own one-line encoding. */
private val TickMemorySaver = Saver<TickMemory, String>(
    save = { it.encode() },
    restore = { TickMemory.decode(it) },
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
    val rampLabel = stringResource(R.string.dial_ramp_label)
    val topLabel = stringResource(R.string.dial_top_label)
    val backoffLabel = stringResource(R.string.dial_backoff_label)
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
            rampLabel = { rampLabel.format(it) },
            topLabel = topLabel,
            backoffLabel = backoffLabel,
        )
    }
}
