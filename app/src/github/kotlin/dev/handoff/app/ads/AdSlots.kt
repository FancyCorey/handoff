package dev.handoff.app.ads

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** The GitHub edition has no ads: nothing is drawn and no space is reserved. */
@Composable
@Suppress("UNUSED_PARAMETER")
fun HomeAdSlot(modifier: Modifier = Modifier) = Unit

/** No advertising settings in the GitHub edition. */
@Composable
fun AdPrivacySettings() = Unit
