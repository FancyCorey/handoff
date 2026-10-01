package dev.handoff.app.ads

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import org.koin.compose.koinInject

/**
 * The one ad in Handoff: an anchored adaptive banner below the home screen's list, never over
 * it. Its height is reserved before anything loads, so nothing moves under the user's finger,
 * and a divider and label separate it from the headset cards and their Move here buttons. If
 * Google has no ad to show, the slot closes; being below the list, that moves nothing either.
 *
 * The request carries only the ad unit and size: no keywords, content URL or extras, and
 * nothing about headsets, devices, networks or transfers.
 */
@Composable
fun HomeAdSlot(modifier: Modifier = Modifier) {
    val ads: AdMobAdService = koinInject()
    val activity = LocalActivity.current
    LaunchedEffect(activity) { activity?.let(ads::start) }
    val reserve by ads.reserveSpace.collectAsStateWithLifecycle()
    val canShow by ads.canShowAds.collectAsStateWithLifecycle()
    // No fill or a load error: give the space back for the rest of this screen's life.
    var failed by remember { mutableStateOf(false) }
    if (!reserve || failed) return

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val context = LocalContext.current
        val width = maxWidth.value.toInt()
        val size = remember(width) {
            runCatching { AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, width) }.getOrDefault(AdSize.BANNER)
        }
        var loaded by remember(size) { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                if (loaded) "Sponsored" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).height(16.dp),
            )
            Box(Modifier.fillMaxWidth().height(size.height.dp), contentAlignment = Alignment.Center) {
                if (canShow) {
                    key(size) {
                        AndroidView(
                            factory = { ctx ->
                                AdView(ctx).apply {
                                    loadAd(
                                        BannerAdRequest.Builder(AdMobAdService.bannerUnitId, size).build(),
                                        object : AdLoadCallback<BannerAd> {
                                            override fun onAdLoaded(ad: BannerAd) {
                                                post { loaded = true }
                                            }

                                            override fun onAdFailedToLoad(adError: LoadAdError) {
                                                post { failed = true }
                                            }
                                        },
                                    )
                                }
                            },
                            onRelease = { it.destroy() },
                        )
                    }
                }
            }
        }
    }
}

/** Settings > Privacy, Google Play edition: what the ad is, and Google's consent choices where required. */
@Composable
fun AdPrivacySettings() {
    val ads: AdMobAdService = koinInject()
    val required by ads.privacyOptionsRequired.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    ListItem(
        headlineContent = { Text("Advertising") },
        supportingContent = {
            Text(
                "This edition shows one banner from Google AdMob at the bottom of the home screen. Handoff gives it " +
                    "nothing about your headsets, devices or network. The GitHub edition has no ads.",
            )
        },
    )
    if (required) {
        ListItem(
            headlineContent = { Text("Ad privacy choices") },
            supportingContent = { Text("Review or change your consent for ads.") },
            modifier = Modifier.clickable { activity?.let(ads::showPrivacyOptions) },
        )
    }
}
