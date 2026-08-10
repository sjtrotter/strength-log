package cloud.trotter.log.strength.domain.sync

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The one place the wear-sync DTOs are turned into (and read back from) their
 * on-the-wire bytes (m5-wear.md #20 "Serialization" requirement). Both transports
 * — the phone's DataClient publish and its MessageClient receive, plus the watch's
 * mirror of each — go through here so the leniency lives in exactly one decoder:
 *
 * `ignoreUnknownKeys = true` is the load-bearing setting. The DTOs carry a
 * [WatchSnapshot.schemaVersion]/[SetEditDelta.schemaVersion], but a version bump
 * that only *adds* a field can't stay backward-compatible unless the older side's
 * decoder tolerates the field it doesn't know — the DTO annotations alone can't
 * enforce that. Keeping this pure-Kotlin (no Android) lets the round-trip and
 * forward-migration tests run on the JVM.
 *
 * Payloads are UTF-8 JSON bytes: the Data Layer moves `ByteArray`s, and JSON keeps
 * the wire self-describing so a stray/omitted field degrades rather than corrupts.
 */
object SyncCodec {

    private val json = Json { ignoreUnknownKeys = true }

    private val deltaListSerializer = ListSerializer(SetEditDelta.serializer())

    private val swapListSerializer = ListSerializer(ExerciseSwapDelta.serializer())
    private val cardioListSerializer = ListSerializer(CardioDelta.serializer())

    fun encodeSnapshot(snapshot: WatchSnapshot): ByteArray =
        json.encodeToString(WatchSnapshot.serializer(), snapshot).encodeToByteArray()

    fun decodeSnapshot(bytes: ByteArray): WatchSnapshot =
        json.decodeFromString(WatchSnapshot.serializer(), bytes.decodeToString())

    fun encodeDelta(delta: SetEditDelta): ByteArray =
        json.encodeToString(SetEditDelta.serializer(), delta).encodeToByteArray()

    fun decodeDelta(bytes: ByteArray): SetEditDelta =
        json.decodeFromString(SetEditDelta.serializer(), bytes.decodeToString())

    fun encodeSwap(swap: ExerciseSwapDelta): ByteArray =
        json.encodeToString(ExerciseSwapDelta.serializer(), swap).encodeToByteArray()

    fun decodeSwap(bytes: ByteArray): ExerciseSwapDelta =
        json.decodeFromString(ExerciseSwapDelta.serializer(), bytes.decodeToString())

    fun encodeCardio(delta: CardioDelta): ByteArray =
        json.encodeToString(CardioDelta.serializer(), delta).encodeToByteArray()

    fun decodeCardio(bytes: ByteArray): CardioDelta =
        json.decodeFromString(CardioDelta.serializer(), bytes.decodeToString())

    /** The watch persists its unacked outbound deltas as one JSON array (queue). */
    fun encodeDeltaQueue(deltas: List<SetEditDelta>): String =
        json.encodeToString(deltaListSerializer, deltas)

    fun decodeDeltaQueue(text: String): List<SetEditDelta> =
        if (text.isBlank()) emptyList() else json.decodeFromString(deltaListSerializer, text)

    /** Swaps queue beside the set edits, in their own array — see [ExerciseSwapDelta]. */
    fun encodeSwapQueue(swaps: List<ExerciseSwapDelta>): String =
        json.encodeToString(swapListSerializer, swaps)

    fun decodeSwapQueue(text: String): List<ExerciseSwapDelta> =
        if (text.isBlank()) emptyList() else json.decodeFromString(swapListSerializer, text)

    fun encodeCardioQueue(deltas: List<CardioDelta>): String =
        json.encodeToString(cardioListSerializer, deltas)

    fun decodeCardioQueue(text: String): List<CardioDelta> =
        if (text.isBlank()) emptyList() else json.decodeFromString(cardioListSerializer, text)
}
