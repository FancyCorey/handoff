package dev.handoff.core.update

import dev.handoff.core.mesh.security.Crypto

/**
 * Public half of the Handoff release key (ECDSA P-256, X.509). Updates are installed only if
 * `update.json` is signed by the matching private key, which never leaves the maintainer's
 * machine (see CONTRIBUTING.md, "Releases").
 */
object UpdateKeys {
    private const val RELEASE_PUBLIC_KEY =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEiCEigu0B5LbpMP7zsoLgfIptU5hCKPYbzw8fKboPTcAS6GDmN+st57Ie4S5cwUSgiEu8g3l9wXWQ38HeeyvuXA=="

    val releasePublicKey: ByteArray get() = Crypto.unb64(RELEASE_PUBLIC_KEY)
}
