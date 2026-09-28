package dev.handoff.desktop.store

import com.sun.jna.platform.win32.Crypt32Util
import dev.handoff.core.handoff.TransferHistory
import dev.handoff.core.handoff.TransferRecord
import dev.handoff.core.mesh.security.Crypto
import dev.handoff.core.mesh.security.Handshake
import dev.handoff.core.mesh.security.IdentityProvider
import dev.handoff.core.model.AudioDeviceKind
import dev.handoff.core.model.BluetoothDeviceId
import dev.handoff.core.model.DeviceIdentity
import dev.handoff.core.model.HostMapping
import dev.handoff.core.model.LogicalAudioDevice
import dev.handoff.core.model.LogicalDeviceId
import dev.handoff.core.model.PeerId
import dev.handoff.core.model.TrustedPeer
import dev.handoff.core.store.InMemoryLogicalDeviceRepository
import dev.handoff.core.store.InMemoryTrustedPeerRepository
import dev.handoff.core.store.LogicalDeviceRepository
import dev.handoff.core.store.TrustedPeerRepository
import dev.handoff.desktop.bluetooth.ReleasedServicesStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec

/** %APPDATA%\Handoff (override with -Dhandoff.dataDir=... for development and tests). */
fun defaultDataDir(): File {
    System.getProperty("handoff.dataDir")?.let { return File(it).apply { mkdirs() } }
    val base = System.getenv("APPDATA")?.let(::File) ?: File(System.getProperty("user.home"), ".config")
    return File(base, "Handoff").apply { mkdirs() }
}

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
}

/** A JSON file written atomically (temp file + move), so a crash never leaves it half-written. */
class JsonFile<T>(private val file: File, private val serializer: KSerializer<T>, private val default: () -> T) {
    fun load(): T = try {
        if (file.exists()) json.decodeFromString(serializer, file.readText()) else default()
    } catch (_: Exception) {
        default()
    }

    @Synchronized
    fun save(value: T) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(serializer, value))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

// ---- identity ------------------------------------------------------------------------------

@Serializable
private data class IdentityFile(val peerId: String, val displayName: String, val publicKey: String)

/**
 * This PC's Handoff identity. The P-256 private key is encrypted with Windows DPAPI
 * (CryptProtectData, bound to the current Windows user) and never written in plaintext.
 */
class DesktopIdentityProvider(dir: File) : IdentityProvider {
    private val meta = JsonFile(File(dir, "identity.json"), IdentityFile.serializer()) { IdentityFile("", "", "") }
    private val keyFile = File(dir, "identity.key")
    private val privateKey: PrivateKey
    private var current: IdentityFile

    private val _name: MutableStateFlow<String>
    val displayName: StateFlow<String>

    init {
        val loaded = meta.load()
        val key = if (loaded.peerId.isNotEmpty() && keyFile.exists()) runCatching { readKey() }.getOrNull() else null
        if (key != null) {
            privateKey = key
            current = loaded
        } else {
            val pair = Crypto.generateEcKeyPair()
            privateKey = pair.private
            keyFile.writeBytes(Crypt32Util.cryptProtectData(pair.private.encoded))
            current = IdentityFile(PeerId.random().value, defaultName(), Crypto.b64(pair.public.encoded))
            meta.save(current)
        }
        _name = MutableStateFlow(current.displayName)
        displayName = _name.asStateFlow()
    }

    override fun identity(): DeviceIdentity = DeviceIdentity(PeerId(current.peerId), current.displayName, Crypto.unb64(current.publicKey))

    override fun sign(data: ByteArray): ByteArray = Crypto.signWith(privateKey, data)

    fun rename(name: String) {
        val clean = name.trim().take(Handshake.MAX_NAME_LENGTH).ifEmpty { defaultName() }
        current = current.copy(displayName = clean)
        meta.save(current)
        _name.value = clean
    }

    fun keyFingerprint(): String = Crypto.hashParts(Crypto.unb64(current.publicKey)).take(6).joinToString(":") { "%02X".format(it) }

    private fun readKey(): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Crypt32Util.cryptUnprotectData(keyFile.readBytes())))

    private fun defaultName(): String = System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() }
        ?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Windows PC"
}

// ---- trust & logical devices ---------------------------------------------------------------

@Serializable
private data class StoredPeer(val peerId: String, val displayName: String, val publicKey: String, val pairedAtMs: Long)

class FileTrustedPeerRepository(dir: File) : TrustedPeerRepository {
    private val file = JsonFile(File(dir, "peers.json"), kotlinx.serialization.builtins.ListSerializer(StoredPeer.serializer())) { emptyList() }
    private val delegate = InMemoryTrustedPeerRepository(
        file.load().map { TrustedPeer(PeerId(it.peerId), it.displayName, Crypto.unb64(it.publicKey), it.pairedAtMs) },
    )
    private val lock = Mutex()
    override val peers: StateFlow<List<TrustedPeer>> = delegate.peers

    override suspend fun upsert(peer: TrustedPeer) = lock.withLock {
        delegate.upsert(peer)
        persist()
    }

    override suspend fun remove(peerId: PeerId) = lock.withLock {
        delegate.remove(peerId)
        persist()
    }

    private fun persist() = file.save(peers.value.map { StoredPeer(it.peerId.value, it.displayName, Crypto.b64(it.publicKey), it.pairedAtMs) })
}

@Serializable
private data class StoredHost(val hostId: String, val alias: String)

@Serializable
private data class StoredDevice(
    val logicalId: String,
    val displayName: String,
    val deviceType: String,
    val fingerprint: String?,
    val localAddress: String?,
    val multipoint: Boolean,
    val lastKnownOwner: String?,
    val ownershipGeneration: Long,
    val hosts: List<StoredHost>,
)

class FileLogicalDeviceRepository(dir: File) : LogicalDeviceRepository {
    private val file = JsonFile(File(dir, "headsets.json"), kotlinx.serialization.builtins.ListSerializer(StoredDevice.serializer())) { emptyList() }
    private val delegate = InMemoryLogicalDeviceRepository(file.load().map(::toModel))
    private val lock = Mutex()
    override val devices: StateFlow<List<LogicalAudioDevice>> = delegate.devices

    override suspend fun upsert(device: LogicalAudioDevice) = lock.withLock {
        delegate.upsert(device)
        persist()
    }

    override suspend fun remove(logicalId: LogicalDeviceId) = lock.withLock {
        delegate.remove(logicalId)
        persist()
    }

    override suspend fun rekey(from: LogicalDeviceId, to: LogicalDeviceId) = lock.withLock {
        delegate.rekey(from, to)
        persist()
    }

    override suspend fun applyOwnership(logicalId: LogicalDeviceId, owner: PeerId?, generation: Long): Boolean = lock.withLock {
        delegate.applyOwnership(logicalId, owner, generation).also { if (it) persist() }
    }

    private fun persist() = file.save(devices.value.map(::toStored))

    private fun toModel(d: StoredDevice) = LogicalAudioDevice(
        logicalId = LogicalDeviceId(d.logicalId),
        displayName = d.displayName,
        deviceType = runCatching { AudioDeviceKind.valueOf(d.deviceType) }.getOrDefault(AudioDeviceKind.UNKNOWN),
        fingerprint = d.fingerprint,
        localDeviceId = d.localAddress?.let(::BluetoothDeviceId),
        multipoint = d.multipoint,
        lastKnownOwner = d.lastKnownOwner?.let(::PeerId),
        ownershipGeneration = d.ownershipGeneration,
        hostMappings = d.hosts.map { HostMapping(PeerId(it.hostId), it.alias) },
    )

    private fun toStored(d: LogicalAudioDevice) = StoredDevice(
        logicalId = d.logicalId.value,
        displayName = d.displayName,
        deviceType = d.deviceType.name,
        fingerprint = d.fingerprint,
        localAddress = d.localDeviceId?.address,
        multipoint = d.multipoint,
        lastKnownOwner = d.lastKnownOwner?.value,
        ownershipGeneration = d.ownershipGeneration,
        hosts = d.hostMappings.map { StoredHost(it.hostId.value, it.alias) },
    )
}

// ---- settings & history --------------------------------------------------------------------

@Serializable
data class DesktopSettings(
    val preferredDevice: String? = null,
    val startWithWindows: Boolean = true,
    val welcomeSeen: Boolean = false,
    /** Headsets whose Windows audio services Handoff turned off when handing them away. */
    val releasedServices: Set<String> = emptySet(),
)

class SettingsStore(dir: File) : ReleasedServicesStore {
    private val file = JsonFile(File(dir, "settings.json"), DesktopSettings.serializer()) { DesktopSettings() }
    private val _settings = MutableStateFlow(file.load())
    val settings: StateFlow<DesktopSettings> = _settings.asStateFlow()

    private val _released = MutableStateFlow(_settings.value.releasedServices)
    override val released: StateFlow<Set<String>> = _released.asStateFlow()

    fun update(change: (DesktopSettings) -> DesktopSettings) {
        _settings.update(change)
        file.save(_settings.value)
    }

    override suspend fun setReleased(address: String, released: Boolean) {
        update { s -> s.copy(releasedServices = if (released) s.releasedServices + address.uppercase() else s.releasedServices - address.uppercase()) }
        _released.value = _settings.value.releasedServices
    }
}

class DesktopTransferHistory : TransferHistory {
    private val _records = MutableStateFlow<List<TransferRecord>>(emptyList())
    val records: StateFlow<List<TransferRecord>> = _records.asStateFlow()

    override suspend fun add(record: TransferRecord) {
        _records.update { (listOf(record) + it).take(50) }
    }
}
