package dev.handoff.core.mesh.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** JSON encoding of [PeerMessage] with a `type` discriminator matching the protocol names. */
object ProtocolCodec {
    val json: Json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(message: PeerMessage): ByteArray =
        json.encodeToString(PeerMessage.serializer(), message).toByteArray(Charsets.UTF_8)

    fun encodeToString(message: PeerMessage): String =
        json.encodeToString(PeerMessage.serializer(), message)

    /** Throws [MalformedMessageException] for anything that is not a known, valid message. */
    fun decode(bytes: ByteArray): PeerMessage = decode(bytes.toString(Charsets.UTF_8))

    fun decode(text: String): PeerMessage = try {
        json.decodeFromString(PeerMessage.serializer(), text)
    } catch (e: SerializationException) {
        throw MalformedMessageException(e.message ?: "undecodable message")
    } catch (e: IllegalArgumentException) {
        throw MalformedMessageException(e.message ?: "invalid message")
    }
}

class MalformedMessageException(message: String) : Exception(message)
