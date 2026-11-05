package cloud.trotter.log.strength.ui.day

import android.os.Parcel
import android.os.Parcelable
import androidx.lifecycle.SavedStateHandle
import kotlinx.parcelize.parcelableCreator
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainCardioParcelRestoreTest {
    private val run = CardioRun(
        planId = 7, planName = "Running", dayId = "A", dayIndex = 0,
        sourceBlockJson = "source", blockJson = "frozen", finishesDay = true,
        nextDayTitle = "Day B, Run", results = listOf(SavedCardioResult("FINISHED", 98, 400)),
        startedAt = 1_000, wallStart = 2_000, elapsedStart = 3_000, bootCount = 4,
        drafts = listOf(CardioDistanceDraft(0, false, "400", true, false, true, true)),
        completedAt = 99_000, sessionId = 5, saveFailed = true, runId = "first", showReceipt = false,
    )
    private val second = run.copy(
        dayId = "B", runId = "second", startedAt = null, wallStart = null, elapsedStart = null,
        bootCount = null, completedAt = null, sessionId = null, showReceipt = true,
    )
    private val receipt = SessionReceipt(
        sessionId = 5, dayId = "A", dayIndex = 0, completedAt = 99_000, minutes = 2,
        setCount = 1, completedSetCount = 1, totalSetCount = 1, newTopSets = emptyList(),
        nextDayLine = "Day B, Run", note = "Saved note", cardio = run,
    )

    @Test
    fun mainLayoutsLeaveFollowingRunsAndValuesUnread() {
        val parcel = Parcel.obtain()
        try {
            MainCardioRunParcel(run).writeToParcel(parcel, 0)
            MainCardioRunParcel(second).writeToParcel(parcel, 0)
            MainSessionReceiptParcel(receipt).writeToParcel(parcel, 0)
            MainSessionReceiptParcel(receipt.copy(minutes = null, cardio = null)).writeToParcel(parcel, 0)
            parcel.writeString("following saved value")
            parcel.writeInt(417)
            parcel.setDataPosition(0)

            assertEquals(run, parcelableCreator<CardioRun>().createFromParcel(parcel))
            assertEquals(second, parcelableCreator<CardioRun>().createFromParcel(parcel))
            assertEquals(receipt, parcelableCreator<SessionReceipt>().createFromParcel(parcel))
            assertEquals(receipt.copy(minutes = null, cardio = null), parcelableCreator<SessionReceipt>().createFromParcel(parcel))
            assertEquals("following saved value", parcel.readString())
            assertEquals(417, parcel.readInt())
            assertEquals(parcel.dataSize(), parcel.dataPosition())
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun runDistanceRoundTripsThroughParcelAndSavedStateWithFollowingValue() {
        val distance = CardioRunDistance("r", 0, "5000")
        val handle = SavedStateHandle(mapOf(
            "day_cardio_run_distance" to distance,
            "following_value" to "still saved",
        ))
        val parcel = Parcel.obtain()
        try {
            distance.writeToParcel(parcel, 0)
            parcel.writeBundle(handle.savedStateProvider().saveState())
            parcel.writeInt(417)
            parcel.setDataPosition(0)

            assertEquals(distance, parcelableCreator<CardioRunDistance>().createFromParcel(parcel))
            val restored = SavedStateHandle.createHandle(parcel.readBundle(CardioRunDistance::class.java.classLoader), null)
            assertEquals(distance, restored.get<CardioRunDistance>("day_cardio_run_distance"))
            assertEquals("still saved", restored.get<String>("following_value"))
            assertEquals(417, parcel.readInt())
            assertEquals(parcel.dataSize(), parcel.dataPosition())
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun mainSavedStateBundleRestoresReceiptAndMultiplePendingRunsWithFollowingValue() {
        val handle = SavedStateHandle(mapOf(
            "day_cardio_run" to MainCardioRunParcel(second),
            "day_cardio_distances" to arrayListOf(MainCardioRunParcel(run), MainCardioRunParcel(second)),
            "day_deferred_cardio_receipt" to MainCardioRunParcel(run),
            "day_session_receipt" to MainSessionReceiptParcel(receipt),
            "following_value" to "still saved",
        ))
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(handle.savedStateProvider().saveState())
            parcel.writeInt(417)
            parcel.setDataPosition(0)
            val restored = SavedStateHandle.createHandle(parcel.readBundle(CardioRun::class.java.classLoader), null)

            assertEquals(second, restored.get<CardioRun>("day_cardio_run"))
            assertEquals(listOf(run, second), restored.get<ArrayList<CardioRun>>("day_cardio_distances"))
            assertEquals(run, restored.get<CardioRun>("day_deferred_cardio_receipt"))
            assertEquals(receipt, restored.get<SessionReceipt>("day_session_receipt"))
            assertEquals("still saved", restored.get<String>("following_value"))
            assertEquals(417, parcel.readInt())
        } finally {
            parcel.recycle()
        }
    }
}

// Independent writers for origin/main's field order and null/list markers. Their
// CREATORs use the production readers so Bundle restoration exercises old bytes,
// rather than round-tripping the current production writer and reader together.
internal class MainCardioRunParcel(private val run: CardioRun) : Parcelable {
    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeLong(run.planId)
        dest.writeString(run.planName)
        dest.writeString(run.dayId)
        dest.writeInt(run.dayIndex)
        dest.writeString(run.sourceBlockJson)
        dest.writeString(run.blockJson)
        dest.writeInt(if (run.finishesDay) 1 else 0)
        dest.writeString(run.nextDayTitle)
        dest.writeInt(run.results.size)
        run.results.forEach { it.writeToParcel(dest, flags) }
        dest.writeMainNullableLong(run.startedAt)
        dest.writeMainNullableLong(run.wallStart)
        dest.writeMainNullableLong(run.elapsedStart)
        dest.writeMainNullableInt(run.bootCount)
        dest.writeInt(run.drafts.size)
        run.drafts.forEach { it.writeToParcel(dest, flags) }
        dest.writeMainNullableLong(run.completedAt)
        dest.writeMainNullableLong(run.sessionId)
        dest.writeInt(if (run.saveFailed) 1 else 0)
        dest.writeString(run.runId)
        dest.writeInt(if (run.showReceipt) 1 else 0)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<CardioRun> = parcelableCreator<CardioRun>()
    }
}

internal class MainSessionReceiptParcel(private val receipt: SessionReceipt) : Parcelable {
    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeLong(receipt.sessionId)
        dest.writeString(receipt.dayId)
        dest.writeInt(receipt.dayIndex)
        dest.writeLong(receipt.completedAt)
        dest.writeMainNullableInt(receipt.minutes)
        dest.writeInt(receipt.setCount)
        dest.writeInt(receipt.completedSetCount)
        dest.writeInt(receipt.totalSetCount)
        dest.writeInt(receipt.newTopSets.size)
        receipt.newTopSets.forEach { it.writeToParcel(dest, flags) }
        dest.writeString(receipt.nextDayLine)
        dest.writeString(receipt.note)
        val cardio = receipt.cardio
        dest.writeInt(if (cardio == null) 0 else 1)
        if (cardio != null) MainCardioRunParcel(cardio).writeToParcel(dest, flags)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<SessionReceipt> = parcelableCreator<SessionReceipt>()
    }
}

private fun Parcel.writeMainNullableLong(value: Long?) {
    writeInt(if (value == null) 0 else 1)
    if (value != null) writeLong(value)
}

private fun Parcel.writeMainNullableInt(value: Int?) {
    writeInt(if (value == null) 0 else 1)
    if (value != null) writeInt(value)
}
