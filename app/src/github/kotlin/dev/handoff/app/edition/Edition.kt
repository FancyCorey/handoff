package dev.handoff.app.edition

/** What makes this build the GitHub edition. */
object Edition {
    const val NAME = "GitHub edition"

    /** One line for setup. True here: this edition contains no advertising or analytics code. */
    const val PRIVACY_SUMMARY = "No account, no cloud, no tracking. Everything stays on your network."

    /** Licenses of components only this edition contains, shown after the shared notices. */
    const val EXTRA_NOTICES = ""
}
