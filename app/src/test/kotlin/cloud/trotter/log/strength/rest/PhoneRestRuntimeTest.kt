package cloud.trotter.log.strength.rest

import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.StrengthLogApp
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.standards.PhoneRest
import cloud.trotter.log.strength.transfer.health.SessionPublisher
import cloud.trotter.log.strength.ui.day.DayViewModel
import cloud.trotter.log.strength.ui.day.FixedCardioClock
import cloud.trotter.log.strength.ui.day.InertCardioAlarm
import cloud.trotter.log.strength.ui.log.share.ShareCardService
import dagger.hilt.internal.GeneratedComponent
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A real receiver graph without starting the production app's background observers. */
class RestTestApplication : Application(), GeneratedComponent,
    StrengthLogApp.RepositoryEntryPoint, StrengthLogApp.RestRuntimeEntryPoint, StrengthLogApp.AppScopeEntryPoint {
    lateinit var repository: TrackerRepository
    lateinit var runtime: PhoneRestRuntime
    lateinit var scope: CoroutineScope
    override fun trackerRepository() = repository
    override fun phoneRestRuntime() = runtime
    override fun appScope() = scope
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = RestTestApplication::class)
class PhoneRestRuntimeTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var context: RestTestApplication
    private lateinit var db: StrengthDatabase
    private lateinit var repo: TrackerRepository
    private lateinit var runtime: PhoneRestRuntime
    private lateinit var scope: CoroutineScope
    private val models = mutableListOf<DayViewModel>()
    private val notifications get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val alarms get() = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        scope = CoroutineScope(dispatcher + Job())
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries().setQueryCoroutineContext(dispatcher).build()
        val store = PreferenceDataStoreFactory.create(scope = scope) { File.createTempFile("rest", ".preferences_pb") }
        repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), SettingsStore(store))
        runtime = PhoneRestRuntime(context)
        context.repository = repo
        context.runtime = runtime
        context.scope = scope
    }

    @After fun tearDown() {
        models.forEach { it.viewModelScope.cancel() }
        scope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun startRest(): PhoneRest {
        val rest = PhoneRest(System.currentTimeMillis() + 60_000, 60, "Barbell Back Squat 190 × 5")
        repo.setPhoneRest(rest)
        runtime.arm(rest)
        return rest
    }

    @Test fun runningNotificationHasCountdownNextLoadAndOnlyRestControls() = runTest(dispatcher) {
        val rest = startRest()
        val notification = shadowOf(notifications).getNotification(PhoneRestRuntime.RUNNING_ID)
        assertEquals("Rest", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Next: Barbell Back Squat 190 × 5", notification.extras.getString(Notification.EXTRA_TEXT))
        assertEquals(rest.deadlineEpochMillis, notification.`when`)
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
        assertEquals(PhoneRestRuntime.CHANNEL, notification.channelId)
        assertEquals(listOf("+15 s", "Skip rest"), notification.actions.map { it.title.toString() })
        assertEquals(listOf(RestActionReceiver.ADD, RestActionReceiver.SKIP), notification.actions.map { shadowOf(it.actionIntent).savedIntent.action })
    }

    @Test fun notificationActionsExtendAndSkipThroughTheReceiver() = runTest(dispatcher) {
        val original = startRest()
        val notification = shadowOf(notifications).getNotification(PhoneRestRuntime.RUNNING_ID)
        notification.actions[0].actionIntent.send()
        shadowOf(Looper.getMainLooper()).idle()
        advanceUntilIdle()
        assertEquals(original.deadlineEpochMillis + 15_000, repo.phoneRestFlow.first()!!.deadlineEpochMillis)
        assertEquals(1, alarms.scheduledAlarms.size)
        notification.actions[1].actionIntent.send()
        shadowOf(Looper.getMainLooper()).idle()
        advanceUntilIdle()
        assertCleared()
        assertEquals(0, repo.sessionsFlow.first().size)
    }

    @Test fun restOverUsesANewDefaultImportanceChannelWithSoundAndVibration() {
        val channel = notifications.getNotificationChannel(PhoneRestRuntime.OVER_CHANNEL)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertNotNull(channel.sound)
        assertTrue(channel.shouldVibrate())
        assertEquals(NotificationManager.IMPORTANCE_LOW, notifications.getNotificationChannel(PhoneRestRuntime.CHANNEL).importance)
        val notification = runtime.overNotification()
        assertEquals(PhoneRestRuntime.OVER_CHANNEL, notification.channelId)
        assertEquals("Rest over", notification.extras.getString(Notification.EXTRA_TITLE))
    }

    @Test fun alarmPostsRestOverAndClearsPersistedRestAndAlarm() = runTest(dispatcher) {
        val expired = PhoneRest(System.currentTimeMillis() - 1, 60, "Squat 190 × 5")
        repo.setPhoneRest(expired)
        runtime.arm(expired)
        context.sendBroadcast(Intent(context, RestAlarmReceiver::class.java))
        shadowOf(Looper.getMainLooper()).idle()
        advanceUntilIdle()
        assertNull(repo.phoneRestFlow.first())
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertNull(shadowOf(notifications).getNotification(PhoneRestRuntime.RUNNING_ID))
        assertEquals(PhoneRestRuntime.OVER_CHANNEL, shadowOf(notifications).getNotification(PhoneRestRuntime.OVER_ID).channelId)
    }

    @Test fun queuedAlarmCannotFinishAnExtendedOrCanceledRest() = runTest(dispatcher) {
        startRest()
        context.sendBroadcast(Intent(context, RestAlarmReceiver::class.java))
        shadowOf(Looper.getMainLooper()).idle()
        advanceUntilIdle()
        assertNotNull(repo.phoneRestFlow.first())
        assertNull(shadowOf(notifications).getNotification(PhoneRestRuntime.OVER_ID))
        PhoneRestActions.skip(repo, runtime)
        context.sendBroadcast(Intent(context, RestAlarmReceiver::class.java))
        shadowOf(Looper.getMainLooper()).idle()
        advanceUntilIdle()
        assertCleared()
        assertNull(shadowOf(notifications).getNotification(PhoneRestRuntime.OVER_ID))
    }

    @Test fun finishClearsRestAlarmAndNotification() = checkDayAction { it.completeDay() }
    @Test fun discardClearsRestAlarmAndNotification() = checkDayAction { it.discardWorkout() }
    @Test fun daySwitchClearsRestAlarmAndNotification() = checkDayAction { it.selectDay("B") }

    private fun checkDayAction(action: (DayViewModel) -> Unit) = runTest(dispatcher) {
        repo.replaceProgram(Program(listOf(
            ProgramDay("A", "Lower", "", listOf(ProgramExercise("bb_back_squat", isMain = true))),
            ProgramDay("B", "Upper", "", listOf(ProgramExercise("bb_bench", isMain = true))),
        )))
        val vm = DayViewModel(repo, SessionPublisher.NoOp, ShareCardService(context, repo), SavedStateHandle(),
            FixedCardioClock(), InertCardioAlarm, runtime).also { models += it }
        val collector = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        // Log with rest disabled, then arm it without collecting the foreground ticker.
        repo.setPhoneRestTimerEnabled(false)
        val slot = repo.daySlotsFlow("A").first().first()
        vm.toggleDone(slot.programExerciseId, 0, true, Slot.MAIN)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.runOpen)
        collector.cancel()
        advanceUntilIdle()
        startRest()
        assertFalse(alarms.scheduledAlarms.isEmpty())
        assertNotNull(shadowOf(notifications).getNotification(PhoneRestRuntime.RUNNING_ID))
        action(vm)
        advanceUntilIdle()
        assertCleared()
    }

    private suspend fun assertCleared() {
        assertNull(repo.phoneRestFlow.first())
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertNull(shadowOf(notifications).getNotification(PhoneRestRuntime.RUNNING_ID))
        assertNull(shadowOf(notifications).getNotification(PhoneRestRuntime.OVER_ID))
    }
}
