package dev.handoff.app.identity

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import dev.handoff.core.mesh.security.Crypto
import dev.handoff.core.mesh.security.Handshake
import dev.handoff.core.mesh.security.IdentityProvider
import dev.handoff.core.model.DeviceIdentity
import dev.handoff.core.model.PeerId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * This installation's identity.
 *
 * - The P-256 signing key is generated inside the Android Keystore (hardware-backed where the
 *   device supports it) and is non-exportable: only [sign] can use it.
 * - The peer id and display name are small, non-secret values in SharedPreferences.
 * - If the Keystore key is ever lost, a fresh peer id is minted with the new key so linked
 *   peers see a new, untrusted device instead of a known id with a mismatching key.
 */
class KeystoreIdentityProvider(context: Context) : IdentityProvider {
    private val prefs = context.applicationContext.getSharedPreferences("identity", Context.MODE_PRIVATE)
    private val resolver = context.applicationContext.contentResolver
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private val publicKey: ByteArray
    private val peerId: PeerId
    private val _name: MutableStateFlow<String>
    val displayName: StateFlow<String>

    init {
        val existingKey = keyStore.getCertificate(KEY_ALIAS)?.publicKey?.encoded
        val storedId = prefs.getString(PREF_PEER_ID, null)
        if (existingKey != null && storedId != null) {
            publicKey = existingKey
            peerId = PeerId(storedId)
        } else {
            publicKey = generateKey()
            peerId = PeerId.random()
            prefs.edit { putString(PREF_PEER_ID, peerId.value) }
        }
        _name = MutableStateFlow(prefs.getString(PREF_NAME, null) ?: defaultName())
        displayName = _name.asStateFlow()
    }

    override fun identity(): DeviceIdentity = DeviceIdentity(peerId, _name.value, publicKey)

    override fun sign(data: ByteArray): ByteArray {
        val key = keyStore.getKey(KEY_ALIAS, null) as PrivateKey
        return Signature.getInstance(Crypto.SIGNATURE_ALGORITHM).run {
            initSign(key)
            update(data)
            sign()
        }
    }

    fun rename(name: String) {
        val clean = name.trim().take(Handshake.MAX_NAME_LENGTH).ifEmpty { defaultName() }
        prefs.edit { putString(PREF_NAME, clean) }
        _name.value = clean
    }

    /** Short, human-comparable fingerprint of the public identity key. */
    fun keyFingerprint(): String = Crypto.hashParts(publicKey).take(6).joinToString(":") { "%02X".format(it) }

    private fun defaultName(): String =
        Settings.Global.getString(resolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

    private fun generateKey(): ByteArray {
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build(),
        )
        return generator.generateKeyPair().public.encoded
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "handoff_identity_p256_v1"
        const val PREF_PEER_ID = "peer_id"
        const val PREF_NAME = "display_name"
    }
}
