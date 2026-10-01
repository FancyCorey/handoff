import com.android.build.api.artifact.SingleArtifact
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Release signing lives outside the repository (default ~/.handoff-release/signing.properties,
// or -Phandoff.signing=<file>). Without it, release builds are simply unsigned.
fun signingProperties(property: String, fileName: String): Properties? =
    ((findProperty(property) as String?) ?: "${System.getProperty("user.home")}/.handoff-release/$fileName")
        .let(::file)
        .takeIf { it.exists() }
        ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

val releaseSigning: Properties? = signingProperties("handoff.signing", "signing.properties")

// The Google Play upload key (-Phandoff.playSigning=<file>, default play-signing.properties next to
// the file above). Without it the Play bundle is signed with the GitHub release key, or left unsigned.
val playSigning: Properties? = signingProperties("handoff.playSigning", "play-signing.properties") ?: releaseSigning

// Where the privacy policy is published; Settings > Privacy policy opens this address.
val privacyPolicyUrl: String =
    (findProperty("handoff.privacyUrl") as String?) ?: "https://github.com/FancyCorey/handoff/blob/main/PRIVACY.md"

// AdMob identifiers for the Google Play edition. Without these properties the build uses Google's
// published test IDs, which only ever show test ads. See docs/PLAY_STORE.md, "AdMob".
val admobAppId: String = (findProperty("handoff.admobAppId") as String?) ?: "ca-app-pub-3940256099942544~3347511713"
val admobBannerId: String = (findProperty("handoff.admobBannerId") as String?) ?: "ca-app-pub-3940256099942544/9214589741"

android {
    namespace = "dev.handoff.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.handoff.app"
        minSdk = 31
        targetSdk = 36
        versionCode = 15
        versionName = "0.6.2"
        buildConfigField("String", "PRIVACY_POLICY_URL", "\"$privacyPolicyUrl\"")
    }

    signingConfigs {
        fun key(name: String, p: Properties) = create(name) {
            storeFile = file(p.getProperty("storeFile"))
            storePassword = p.getProperty("storePassword")
            keyAlias = p.getProperty("keyAlias")
            keyPassword = p.getProperty("keyPassword")
        }
        releaseSigning?.let { key("release", it) }
        playSigning?.let { key("play", it) }
    }

    // Two editions of the same app, built from the same code. Only what differs lives in
    // src/github and src/play: how updates arrive, and (Play only) the advertising slot.
    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            buildConfigField("String", "EDITION", "\"github\"")
            signingConfig = signingConfigs.findByName("release")
        }
        create("play") {
            dimension = "distribution"
            buildConfigField("String", "EDITION", "\"play\"")
            buildConfigField("String", "ADMOB_APP_ID", "\"$admobAppId\"")
            buildConfigField("String", "ADMOB_BANNER_ID", "\"$admobBannerId\"")
            manifestPlaceholders["admobAppId"] = admobAppId
            signingConfig = signingConfigs.findByName("play")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // Kept off until keep-rules are validated on hardware; behaviour then matches debug.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// Output files are named after the app and version, e.g. Handoff-0.6.0-github-release.apk.
base {
    archivesName.set("Handoff-${android.defaultConfig.versionName}")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":bluetooth"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.zxing.core)
    implementation(libs.zxing.embedded)

    // Google Play edition only: one banner on the home screen, and Google's consent form.
    "playImplementation"(libs.gma.nextgen)
    "playImplementation"(libs.ump)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Guards for the two editions, checked before anything is packaged:
//  - the Play edition must not be able to install APKs itself (no REQUEST_INSTALL_PACKAGES, no
//    update FileProvider);
//  - the GitHub edition must not contain an advertising SDK or the advertising-ID permission.
abstract class VerifyEditionManifest : DefaultTask() {
    @get:InputFile
    abstract val manifest: RegularFileProperty

    @get:Input
    abstract val forbidden: ListProperty<String>

    @TaskAction
    fun verify() {
        val text = manifest.get().asFile.readText()
        val found = forbidden.get().filter { it in text }
        check(found.isEmpty()) { "${manifest.get().asFile}: must not contain $found" }
    }
}

val verifyEditions = tasks.register("verifyEditions") {
    group = "verification"
    description = "Checks that the Play edition cannot self-install and the GitHub edition has no advertising."
}

val adLibraryGroups = listOf("com.google.android.gms", "com.google.android.libraries.ads", "com.google.android.ump")

androidComponents {
    onVariants { variant ->
        val play = variant.flavorName == "play"
        val name = variant.name.replaceFirstChar(Char::uppercase)
        val verify = tasks.register<VerifyEditionManifest>("verify${name}Edition") {
            manifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            forbidden.set(
                if (play) {
                    listOf("android.permission.REQUEST_INSTALL_PACKAGES", "update_paths")
                } else {
                    listOf("com.google.android.gms.permission.AD_ID", "com.google.android.gms.ads", "ACCESS_ADSERVICES")
                },
            )
            if (!play) {
                val classpath = configurations.named("${variant.name}RuntimeClasspath")
                doFirst {
                    val ads = classpath.get().incoming.resolutionResult.allComponents
                        .map { it.moduleVersion?.group.orEmpty() }
                        .filter { group -> adLibraryGroups.any { group.startsWith(it) } }
                    check(ads.isEmpty()) { "${variant.name} must not depend on advertising libraries: ${ads.distinct()}" }
                }
            }
        }
        verifyEditions.configure { dependsOn(verify) }
        tasks.matching { it.name == "assemble$name" || it.name == "bundle$name" }.configureEach { dependsOn(verify) }
    }
}
