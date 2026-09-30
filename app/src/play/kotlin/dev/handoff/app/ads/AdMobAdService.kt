package dev.handoff.app.ads

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import dev.handoff.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Google Play edition: Google's consent form (UMP) and the Google Mobile Ads SDK (next-gen).
 *
 * Nothing starts until the home screen is shown ([start]); setup, linking, transfers and
 * background mode never touch this class. Ads wait for consent; Handoff never does. Any
 * failure (offline, no consent, SDK error) just means no banner.
 *
 * [adsRemoved] is where a future one-time "Remove ads" purchase plugs in: when it is true, no
 * space is reserved and no ad is requested. Nothing else in the app changes with it.
 */
// Koin injects the Application context, which cannot leak an Activity.
@SuppressLint("StaticFieldLeak")
class AdMobAdService(
    private val context: Context,
    private val scope: CoroutineScope,
    adsRemoved: StateFlow<Boolean> = MutableStateFlow(false),
) : AdService {
    private val consent: ConsentInformation = UserMessagingPlatform.getConsentInformation(context)
    private val started = AtomicBoolean(false)
    private val initializing = AtomicBoolean(false)
    private val sdkReady = MutableStateFlow(false)
    private val ruledOut = MutableStateFlow(false)
    private val _privacyOptionsRequired = MutableStateFlow(false)

    override val canShowAds: StateFlow<Boolean> =
        combine(sdkReady, adsRemoved) { ready, removed -> ready && !removed }.stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * Whether the home screen keeps room for the banner. True from the start, so a banner that
     * arrives later never pushes anything; false once ads are ruled out for this session.
     */
    val reserveSpace: StateFlow<Boolean> =
        combine(ruledOut, adsRemoved) { out, removed -> !out && !removed }.stateIn(scope, SharingStarted.Eagerly, true)

    /** True where the law requires a way to change the ad consent (Settings shows the entry). */
    val privacyOptionsRequired: StateFlow<Boolean> = _privacyOptionsRequired.asStateFlow()

    /** Refresh consent (showing Google's form only where required), then start the SDK. Once per process. */
    fun start(activity: Activity) {
        if (!started.compareAndSet(false, true)) return
        try {
            consent.requestConsentInfoUpdate(
                activity,
                ConsentRequestParameters.Builder().build(),
                { UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { onConsentKnown() } },
                { onConsentKnown() },
            )
        } catch (_: RuntimeException) {
            onConsentKnown()
        }
        // Consent given in an earlier session allows ads without waiting for the refresh.
        if (consent.canRequestAds()) initializeSdk()
    }

    /** Google's form for changing the ad consent later. */
    fun showPrivacyOptions(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { onConsentKnown() }
    }

    private fun onConsentKnown() {
        _privacyOptionsRequired.value =
            consent.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
        if (consent.canRequestAds()) initializeSdk() else if (!sdkReady.value) ruledOut.value = true
    }

    private fun initializeSdk() {
        if (!initializing.compareAndSet(false, true)) return
        // Google requires a background thread here; it can take a moment.
        scope.launch(Dispatchers.IO) {
            try {
                val config = InitializationConfig.Builder(BuildConfig.ADMOB_APP_ID)
                    .disableSdkCrashReporting()
                    .build()
                MobileAds.initialize(context, config) { sdkReady.value = true }
            } catch (_: Exception) {
                ruledOut.value = true
            }
        }
    }

    companion object {
        /** Google's test banner. Debug builds always use it, so development never shows live ads. */
        private const val TEST_BANNER_ID = "ca-app-pub-3940256099942544/9214589741"

        val bannerUnitId: String get() = if (BuildConfig.DEBUG) TEST_BANNER_ID else BuildConfig.ADMOB_BANNER_ID
    }
}
