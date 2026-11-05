package cloud.trotter.log.strength.playtest

import android.content.Context
import android.os.SystemClock
import cloud.trotter.log.strength.di.CivilDay
import cloud.trotter.log.strength.di.HealthModule
import cloud.trotter.log.strength.di.TimeModule
import cloud.trotter.log.strength.time.CivilTime
import cloud.trotter.log.strength.time.CivilTimeSource
import cloud.trotter.log.strength.transfer.health.HealthConnectClientProvider
import cloud.trotter.log.strength.transfer.health.HealthConnectReader
import cloud.trotter.log.strength.transfer.health.SessionPublisher
import cloud.trotter.log.strength.ui.day.CardioAlarm
import cloud.trotter.log.strength.ui.day.CardioClock
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal class PlaytestTime {
    @Volatile private var now = Instant.parse("2026-11-09T23:30:00Z")
    @Volatile private var zone = ZoneId.of("America/Chicago")
    private val state = MutableStateFlow(CivilTime.now(now, zone))

    private fun clockIn(fixedZone: ZoneId?): Clock = object : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = fixedZone ?: zone
        override fun withZone(zone: ZoneId): Clock = clockIn(zone)
    }

    val clock: Clock = clockIn(null)
    val civil: CivilTimeSource = object : CivilTimeSource {
        override val civilTime: Flow<CivilTime> = state
        override fun refresh() {
            state.value = CivilTime.now(now, zone)
        }
    }
    val cardioClock: CardioClock = object : CardioClock {
        override fun wallMillis(): Long = now.toEpochMilli()
        override fun elapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime()
        override fun bootCount(): Int = 1
    }

    fun set(instant: Instant, zone: ZoneId) {
        now = instant
        this.zone = zone
        civil.refresh()
    }

    fun advance(duration: Duration) {
        now = now.plus(duration)
        civil.refresh()
    }
}

@Singleton
internal class PlaytestCardioAlarm @Inject constructor() : CardioAlarm {
    private data class Pending(val deadline: Long, val identity: String, val callback: () -> Unit)
    private var pending: Pending? = null

    override fun arm(deadlineElapsedMillis: Long, identity: String, onBoundary: () -> Unit) {
        if (pending?.let { it.deadline == deadlineElapsedMillis && it.identity == identity } == true) return
        pending = Pending(deadlineElapsedMillis, identity, onBoundary)
    }

    override fun cancel() {
        pending = null
    }

    fun fireDue(now: Long) {
        val due = pending?.takeIf { it.deadline <= now } ?: return
        pending = null
        due.callback()
    }
}

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [TimeModule::class])
internal object PlaytestTimeModule {
    @Provides @Singleton fun time(): PlaytestTime = PlaytestTime()
    @Provides @Singleton fun clock(time: PlaytestTime): Clock = time.clock
    @Provides @Singleton fun civil(time: PlaytestTime): CivilTimeSource = time.civil
    @Provides @Singleton @CivilDay
    fun civilDay(time: PlaytestTime): Flow<LocalDate> = time.civil.civilTime.map { it.date }.distinctUntilChanged()
    @Provides @Singleton fun cardioClock(time: PlaytestTime): CardioClock = time.cardioClock
    @Provides @Singleton fun cardioAlarm(alarm: PlaytestCardioAlarm): CardioAlarm = alarm
}

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [HealthModule::class])
internal object PlaytestHealthModule {
    @Provides @Singleton fun provider(): HealthConnectClientProvider = HealthConnectClientProvider { null }
    @Provides @Singleton fun publisher(): SessionPublisher = SessionPublisher.NoOp
    @Provides @Singleton
    fun reader(@ApplicationContext context: Context, provider: HealthConnectClientProvider): HealthConnectReader =
        HealthConnectReader(provider, ownPackageName = context.packageName)
}
