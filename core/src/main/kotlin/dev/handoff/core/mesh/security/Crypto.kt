package dev.handoff.core.mesh.security

import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Standard JCA primitives only (available on the JVM and on every Android version we target):
 * ECDH/ECDSA on NIST P-256, HKDF-SHA256 (RFC 5869), AES-256-GCM.
 */
object Crypto {
    private const val CURVE = "secp256r1"
    private val random = SecureRandom()

    private val p256Params by lazy { (generateEcKeyPair().public as ECPublicKey).params }

    fun randomBytes(length: Int): ByteArray = ByteArray(length).also { random.nextBytes(it) }

    fun generateEcKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(CURVE), random) }
            .generateKeyPair()

    /** Decode an X.509 P-256 public key; rejects keys on any other curve. */
    fun decodeP256PublicKey(encoded: ByteArray): PublicKey {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
        val params = (key as? ECPublicKey)?.params
            ?: throw SecurityException("not an EC public key")
        if (params.order != p256Params.order || params.curve != p256Params.curve) {
            throw SecurityException("public key is not on P-256")
        }
        return key
    }

    fun ecdh(privateKey: PrivateKey, publicKey: PublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").run {
            init(privateKey)
            doPhase(publicKey, true)
            generateSecret()
        }

    fun signWith(privateKey: PrivateKey, data: ByteArray): ByteArray =
        Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(privateKey)
            update(data)
            sign()
        }

    /** Returns false (never throws) for malformed keys or signatures. */
    fun verify(publicKeyEncoded: ByteArray, data: ByteArray, signature: ByteArray): Boolean = try {
        Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initVerify(decodeP256PublicKey(publicKeyEncoded))
            update(data)
            verify(signature)
        }
    } catch (_: Exception) {
        false
    }

    /** SHA-256 over length-prefixed parts, so different splits can never collide. */
    fun hashParts(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (part in parts) {
            out.write(part.size ushr 24)
            out.write(part.size ushr 16)
            out.write(part.size ushr 8)
            out.write(part.size)
            out.write(part)
        }
        return MessageDigest.getInstance("SHA-256").digest(out.toByteArray())
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    /** HKDF-SHA256 extract-and-expand (RFC 5869). */
    fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
        require(length in 1..255 * 32)
        val prk = hmacSha256(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = ByteArrayOutputStream()
        var previous = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            previous = hmacSha256(prk, previous + info + byteArrayOf(counter.toByte()))
            out.write(previous)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    fun aesGcmEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(aad)
            doFinal(plaintext)
        }

    /** Throws [javax.crypto.AEADBadTagException] if the frame was tampered with. */
    fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(aad)
            doFinal(ciphertext)
        }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun unb64(text: String): ByteArray = try {
        Base64.getDecoder().decode(text)
    } catch (e: IllegalArgumentException) {
        throw SecurityException("invalid base64")
    }

    const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
}
