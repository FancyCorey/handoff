package dev.handoff.core.update

import dev.handoff.core.mesh.security.Crypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/** One downloadable file of a release. */
@Serializable
data class UpdateAsset(
    /** [UpdatePlatform] value, e.g. "android". */
    val platform: String,
    val name: String,
    val url: String,
    val sha256: String,
    val size: Long,
)

/**
 * `update.json`, published with every GitHub release next to `update.json.sig`: an ECDSA P-256
 * signature by the release key whose public half is built into the apps ([UpdateKeys]).
 */
@Serializable
data class UpdateManifest(
    val format: Int = 1,
    val version: String,
    val publishedAtMs: Long,
    val notes: String,
    val releaseUrl: String,
    val assets: List<UpdateAsset>,
) {
    fun asset(platform: String): UpdateAsset? = assets.firstOrNull { it.platform == platform }
}

object UpdatePlatform {
    const val ANDROID = "android"
    const val WINDOWS_MSI = "windows-msi"
    const val WINDOWS_PORTABLE = "windows-portable"
    const val WINDOWS_SETUP = "windows-setup"
}

sealed interface UpdateCheck {
    data class UpToDate(val latest: String) : UpdateCheck
    data class Available(val manifest: UpdateManifest) : UpdateCheck

    /** Nothing was changed; [reason] is shown to the user. */
    data class Failed(val reason: String) : UpdateCheck
}

/**
 * Checks GitHub Releases for a newer Handoff and downloads it safely.
 *
 * Nothing is trusted because it came from GitHub: the manifest must carry a valid signature from
 * the Handoff release key, every download must match the size and SHA-256 in that manifest, and
 * downloads may only come from this project's release pages over HTTPS. A compromised account
 * or a tampered download is therefore rejected, not installed.
 *
 * Only contacts GitHub when asked (a button, or the opt-in daily check).
 */
class UpdateChecker(
    private val currentVersion: String,
    private val publicKey: ByteArray = UpdateKeys.releasePublicKey,
    private val fetch: (url: String, maxBytes: Long) -> ByteArray = ::httpGet,
    private val manifestUrl: String = MANIFEST_URL,
) {
    suspend fun check(): UpdateCheck = withContext(Dispatchers.IO) {
        try {
            val body = fetch(manifestUrl, MAX_MANIFEST_BYTES)
            val signature = Crypto.unb64(fetch("$manifestUrl.sig", MAX_SIGNATURE_BYTES).toString(Charsets.UTF_8).trim())
            val manifest = verify(body, signature) ?: return@withContext UpdateCheck.Failed("The update information isn't signed by Handoff.")
            if (isNewer(manifest.version, currentVersion)) UpdateCheck.Available(manifest) else UpdateCheck.UpToDate(manifest.version)
        } catch (e: FileNotFoundException) {
            UpdateCheck.Failed("No release has been published yet.")
        } catch (e: IOException) {
            UpdateCheck.Failed("Couldn't reach GitHub. Check the internet connection.")
        } catch (e: Exception) {
            UpdateCheck.Failed("Couldn't check for updates (${e.javaClass.simpleName}).")
        }
    }

    /** The parsed manifest if [signature] is valid for [body] and every URL is a Handoff release. */
    fun verify(body: ByteArray, signature: ByteArray): UpdateManifest? {
        if (!Crypto.verify(publicKey, signedBytes(body), signature)) return null
        val manifest = runCatching { json.decodeFromString(UpdateManifest.serializer(), body.toString(Charsets.UTF_8)) }.getOrNull() ?: return null
        if (manifest.format != 1 || !manifest.releaseUrl.startsWith(RELEASE_PAGE_PREFIX)) return null
        if (manifest.assets.any { !it.url.startsWith(DOWNLOAD_PREFIX) || it.sha256.length != 64 || it.size <= 0 }) return null
        return manifest
    }

    /**
     * Downloads [asset] to [target] and returns true only if size and SHA-256 match the signed
     * manifest; otherwise the file is deleted.
     */
    suspend fun download(asset: UpdateAsset, target: File, onProgress: (Float) -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        if (!asset.url.startsWith(DOWNLOAD_PREFIX)) return@withContext false
        target.parentFile?.mkdirs()
        val ok = try {
            openFollowingRedirects(asset.url).use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                target.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > asset.size) return@use
                        digest.update(buffer, 0, n)
                        out.write(buffer, 0, n)
                        onProgress(total.toFloat() / asset.size)
                    }
                }
                total == asset.size && hex(digest.digest()).equals(asset.sha256, ignoreCase = true)
            }
        } catch (_: Exception) {
            false
        }
        if (!ok) target.delete()
        ok
    }

    companion object {
        const val REPO = "FancyCorey/handoff"
        const val RELEASE_PAGE_PREFIX = "https://github.com/$REPO/releases/"
        const val DOWNLOAD_PREFIX = "https://github.com/$REPO/releases/download/"
        const val MANIFEST_URL = "https://github.com/$REPO/releases/latest/download/update.json"
        private const val MAX_MANIFEST_BYTES = 64L * 1024
        private const val MAX_SIGNATURE_BYTES = 1024L
        private const val SIGNATURE_DOMAIN = "handoff-update-v1\n"

        /** Hosts GitHub serves release downloads from; redirects anywhere else are refused. */
        private val TRUSTED_HOSTS = setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")

        internal val json = Json { ignoreUnknownKeys = true }

        /** What the release key signs: a fixed prefix plus the exact manifest bytes. */
        fun signedBytes(body: ByteArray): ByteArray = SIGNATURE_DOMAIN.toByteArray() + body

        /** "0.10.0" > "0.9.3"; suffixes like "-debug" are ignored. */
        fun isNewer(candidate: String, current: String): Boolean {
            val a = numbers(candidate)
            val b = numbers(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }

        private fun numbers(version: String) = version.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }

        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

        fun sha256(file: File): String = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
            hex(digest.digest())
        }

        private fun httpGet(url: String, maxBytes: Long): ByteArray = openFollowingRedirects(url).use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > maxBytes) throw IOException("response too large")
            }
            out.toByteArray()
        }

        /** HTTPS only, and only GitHub's release hosts, including every redirect. */
        private fun openFollowingRedirects(start: String): InputStream {
            var url = start
            repeat(MAX_REDIRECTS) {
                val uri = URI(url)
                if (uri.scheme != "https" || uri.host !in TRUSTED_HOSTS) throw IOException("untrusted download location")
                val connection = (uri.toURL().openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 10_000
                    readTimeout = 30_000
                    setRequestProperty("User-Agent", "Handoff-updater")
                }
                when (val code = connection.responseCode) {
                    in 200..299 -> return connection.inputStream
                    301, 302, 303, 307, 308 -> {
                        url = uri.resolve(connection.getHeaderField("Location") ?: throw IOException("redirect without location")).toString()
                        connection.disconnect()
                    }
                    404 -> throw FileNotFoundException(url)
                    else -> throw IOException("HTTP $code")
                }
            }
            throw IOException("too many redirects")
        }

        private const val MAX_REDIRECTS = 5
    }
}
