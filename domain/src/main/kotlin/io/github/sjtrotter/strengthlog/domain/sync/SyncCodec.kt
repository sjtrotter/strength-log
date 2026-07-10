package io.github.sjtrotter.strengthlog.domain.sync

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

    fun encodeSnapshot(snapshot: WatchSnapshot): ByteArray =
        json.encodeToString(WatchSnapshot.serializer(), snapshot).encodeToByteArray()

    fun decodeSnapshot(bytes: ByteArray): WatchSnapshot =
        json.decodeFromString(WatchSnapshot.serializer(), bytes.decodeToString())

    fun encodeDelta(delta: SetEditDelta): ByteArray =
        json.encodeToString(SetEditDelta.serializer(), delta).encodeToByteArray()

    fun decodeDelta(bytes: ByteArray): SetEditDelta =
        json.decodeFromString(SetEditDelta.serializer(), bytes.decodeToString())

    /** The watch persists its unacked outbound deltas as one JSON array (queue). */
    fun encodeDeltaQueue(deltas: List<SetEditDelta>): String =
        json.encodeToString(deltaListSerializer, deltas)

    fun decodeDeltaQueue(text: String): List<SetEditDelta> =
        if (text.isBlank()) emptyList() else json.decodeFromString(deltaListSerializer, text)
}
