package dev.handoff.core.update

import dev.handoff.core.mesh.security.Crypto
import java.io.File
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import kotlin.system.exitProcess

/**
 * Maintainer tool used by `tools/release.ps1`, never by the apps:
 *
 * - `keygen <dir>` creates the update signing key pair (`update-signing.pk8` stays private;
 *   the printed public key goes into [UpdateKeys]).
 * - `sign <releaseDir> <version> <notesFile> <keyFile>` writes `update.json`, its signature and
 *   `SHA256SUMS.txt` for the files in the release folder.
 */
object ReleaseTool {
    @JvmStatic
    fun main(args: Array<String>) {
        when (args.firstOrNull()) {
            "keygen" -> keygen(File(args[1]))
            "sign" -> sign(File(args[1]), args[2], File(args[3]), File(args[4]))
            else -> {
                System.err.println("usage: keygen <dir> | sign <releaseDir> <version> <notesFile> <keyFile>")
                exitProcess(2)
            }
        }
    }

    private fun keygen(dir: File) {
        dir.mkdirs()
        val key = File(dir, "update-signing.pk8")
        require(!key.exists()) { "$key already exists; refusing to overwrite a release key" }
        val pair = Crypto.generateEcKeyPair()
        key.writeBytes(pair.private.encoded)
        println(Crypto.b64(pair.public.encoded))
    }

    private fun sign(releaseDir: File, version: String, notes: File, keyFile: File) {
        val tag = "v$version"
        val files = releaseDir.listFiles().orEmpty().filter { it.isFile && platformOf(it.name) != null }.sortedBy { it.name }
        require(files.isNotEmpty()) { "no release files in $releaseDir" }
        val manifest = UpdateManifest(
            version = version,
            publishedAtMs = System.currentTimeMillis(),
            notes = plainText(notes.readText()),
            releaseUrl = "${UpdateChecker.RELEASE_PAGE_PREFIX}tag/$tag",
            assets = files.map {
                UpdateAsset(platformOf(it.name)!!, it.name, "${UpdateChecker.DOWNLOAD_PREFIX}$tag/${it.name}", UpdateChecker.sha256(it), it.length())
            },
        )
        val body = UpdateChecker.json.encodeToString(UpdateManifest.serializer(), manifest).toByteArray()
        val privateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
        val signature = Crypto.signWith(privateKey, UpdateChecker.signedBytes(body))
        File(releaseDir, "update.json").writeBytes(body)
        File(releaseDir, "update.json.sig").writeText(Crypto.b64(signature))
        File(releaseDir, "SHA256SUMS.txt").writeText(manifest.assets.joinToString("") { "${it.sha256}  ${it.name}\n" })
        require(UpdateChecker(version).verify(body, signature) != null) { "signature does not verify with the built-in public key" }
        println("signed ${manifest.assets.size} files for $tag")
    }

    /**
     * The apps show release notes as plain text, so the Markdown written for the GitHub release
     * page is simplified: formatting marks and tables are dropped and links keep only their text.
     */
    internal fun plainText(markdown: String): String {
        val lines = markdown.replace("\r\n", "\n").lines().filterNot { it.trimStart().startsWith("|") }
        val heading = Regex("""^#{1,6}\s*""")
        val kept = lines.filterIndexed { i, line ->
            // A heading whose section was only a table would be left empty: drop it.
            !heading.containsMatchIn(line) ||
                lines.drop(i + 1).firstOrNull { it.isNotBlank() }?.let { !heading.containsMatchIn(it) } ?: false
        }
        return kept.joinToString("\n") { line ->
            line.replace(heading, "")
                .replace(Regex("""^\s*[-*]\s+"""), "• ")
                .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
                .replace("**", "")
                .replace(Regex("""(?<![\w*])\*([^*\n]+)\*(?![\w*])"""), "$1")
                .replace("`", "")
                .trimEnd()
        }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun platformOf(name: String): String? = when {
        name.endsWith(".apk") -> UpdatePlatform.ANDROID
        name.endsWith(".msi") -> UpdatePlatform.WINDOWS_MSI
        name.endsWith("-windows-portable.zip") -> UpdatePlatform.WINDOWS_PORTABLE
        name.endsWith("-setup.exe") -> UpdatePlatform.WINDOWS_SETUP
        else -> null
    }
}
