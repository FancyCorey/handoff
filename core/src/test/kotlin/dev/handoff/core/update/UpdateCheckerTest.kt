package dev.handoff.core.update

import dev.handoff.core.mesh.security.Crypto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

class UpdateCheckerTest {
    private val releaseKey = Crypto.generateEcKeyPair()
    private val prefix = UpdateChecker.DOWNLOAD_PREFIX

    private fun manifest(version: String, url: String = "${prefix}v$version/Handoff-$version.apk") = UpdateManifest(
        version = version,
        publishedAtMs = 1,
        notes = "Notes",
        releaseUrl = "${UpdateChecker.RELEASE_PAGE_PREFIX}tag/v$version",
        assets = listOf(UpdateAsset(UpdatePlatform.ANDROID, "Handoff-$version.apk", url, "a".repeat(64), 10)),
    )

    private fun published(m: UpdateManifest, signer: java.security.PrivateKey = releaseKey.private, tamper: Boolean = false): Map<String, ByteArray> {
        val body = UpdateChecker.json.encodeToString(UpdateManifest.serializer(), m).toByteArray()
        val signature = Crypto.b64(Crypto.signWith(signer, UpdateChecker.signedBytes(body))).toByteArray()
        val served = if (tamper) String(body).replace("Notes", "Evil!").toByteArray() else body
        return mapOf(UpdateChecker.MANIFEST_URL to served, UpdateChecker.MANIFEST_URL + ".sig" to signature)
    }

    private fun checker(current: String, files: Map<String, ByteArray>) =
        UpdateChecker(current, releaseKey.public.encoded, { url, _ -> files[url] ?: throw FileNotFoundException(url) })

    @Test
    fun `a signed newer release is offered`() = runBlocking {
        val result = checker("0.4.3-debug", published(manifest("0.5.0"))).check()
        assertTrue(result is UpdateCheck.Available)
        assertEquals("0.5.0", (result as UpdateCheck.Available).manifest.version)
    }

    @Test
    fun `the same or an older release means up to date`() = runBlocking {
        assertEquals(UpdateCheck.UpToDate("0.4.3"), checker("0.4.3", published(manifest("0.4.3"))).check())
        assertEquals(UpdateCheck.UpToDate("0.4.2"), checker("0.4.3", published(manifest("0.4.2"))).check())
    }

    @Test
    fun `tampered, foreign-signed or off-site manifests are rejected`() = runBlocking {
        assertTrue(checker("0.4.3", published(manifest("0.5.0"), tamper = true)).check() is UpdateCheck.Failed)
        assertTrue(checker("0.4.3", published(manifest("0.5.0"), signer = Crypto.generateEcKeyPair().private)).check() is UpdateCheck.Failed)
        assertTrue(checker("0.4.3", published(manifest("0.5.0", url = "https://evil.example/Handoff.apk"))).check() is UpdateCheck.Failed)
    }

    @Test
    fun `no release and no network are reported plainly`() = runBlocking {
        assertEquals(UpdateCheck.Failed("No release has been published yet."), checker("0.4.3", emptyMap()).check())
        val offline = UpdateChecker("0.4.3", releaseKey.public.encoded, { _, _ -> throw IOException("offline") })
        assertEquals(UpdateCheck.Failed("Couldn't reach GitHub. Check the internet connection."), offline.check())
    }

    @Test
    fun `downloads outside the project's releases are refused`() = runBlocking {
        val target = File.createTempFile("update", ".apk").apply { deleteOnExit() }
        val foreign = UpdateAsset(UpdatePlatform.ANDROID, "x.apk", "https://evil.example/x.apk", "a".repeat(64), 10)
        assertFalse(checker("0.4.3", emptyMap()).download(foreign, target))
    }

    @Test
    fun `version ordering`() {
        assertTrue(UpdateChecker.isNewer("0.10.0", "0.9.9"))
        assertTrue(UpdateChecker.isNewer("1.0", "0.9.9-debug"))
        assertFalse(UpdateChecker.isNewer("0.4.3", "0.4.3-debug"))
        assertNull(UpdateChecker("0.1", releaseKey.public.encoded).verify("{}".toByteArray(), ByteArray(8)))
    }

    @Test
    fun `the built-in release key is a valid P-256 key`() {
        Crypto.decodeP256PublicKey(UpdateKeys.releasePublicKey)
    }
}
