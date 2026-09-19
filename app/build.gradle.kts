import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.zip.ZipException
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val appId: String = providers.gradleProperty("pikit.applicationId").get()
val termuxPrefix: String = providers.gradleProperty("pikit.termuxPrefix").get()
val termuxHome: String = providers.gradleProperty("pikit.termuxHome").get()

/**
 * The application id is not a free choice, so the build refuses to proceed with
 * one that cannot work.
 *
 * The bundled runtime is the official Termux bootstrap with
 * `/data/data/com.termux` rewritten to this app's id, and the rewrite is
 * byte-for-byte: every ELF in the image resolves its libraries through an
 * absolute `DT_RUNPATH`, so a replacement of a different length would move every
 * section offset and corrupt all 338 binaries. `com.termux` is 10 characters, so
 * the id has to be 10 characters too.
 *
 * Checked here rather than only in `tools/build-runtime-image.py`, because the
 * image and the APK are built separately: someone can change the id, rebuild
 * only the APK, and ship something that installs and then dies on first launch
 * with an exec error. This makes that a build failure with the reason attached.
 */
val expectedIdLength = "com.termux".length
check(appId.length == expectedIdLength) {
    "pikit.applicationId is '$appId' (${appId.length} characters), but the bundled " +
        "runtime requires exactly $expectedIdLength. The Termux prefix is relocated by " +
        "an equal-length byte rewrite, so a different length corrupts every ELF in the " +
        "image. Pick a $expectedIdLength-character id, for example 'pi.kit.mob'."
}
check(termuxPrefix == "/data/data/$appId/files/usr") {
    "pikit.termuxPrefix is '$termuxPrefix' but pikit.applicationId is '$appId'. " +
        "The prefix has to be /data/data/$appId/files/usr, because that is what the " +
        "runtime image was built for and what every binary in it links against."
}
check(termuxHome == "/data/data/$appId/files/home") {
    "pikit.termuxHome is '$termuxHome' but pikit.applicationId is '$appId'. " +
        "The home directory has to be /data/data/$appId/files/home."
}

/**
 * The version, read from `gradle.properties` rather than written here.
 *
 * One place to bump matters more than it sounds: the value reaches the About page,
 * the update check that compares it against the newest release's tag, the APK's own
 * manifest, and the release workflow that names the tag — so leaving it somewhere a
 * workflow has to guess is how a release ends up tagged `v0.1.0` and shipping
 * `0.1.1`.
 *
 * It is deliberately *not* what a shell sees: `TERMUX_VERSION` reports the bundled
 * environment's version (`env/BundledImage.kt`), because everything that reads that
 * variable is asking which Termux is hosting the shell, not which app.
 *
 * The shape is checked because the app *reports* it: a `versionName` that is not a
 * bare `major.minor.patch` is indistinguishable from the version plus a build note,
 * which is what `0.1.0-x64` was, and that made the version the app displays stop
 * being a version. It is also compared as a version by the update check, which is
 * why `0.10.0` has to be able to beat `0.9.0`. Which ABI you are running is shown on
 * the same settings page by the runtime revision, which is the fact that matters.
 */
val pikitVersionName: String = providers.gradleProperty("pikit.versionName").get()
val pikitVersionCode: Int = providers.gradleProperty("pikit.versionCode").get().toInt()

check(Regex("""\d+\.\d+\.\d+""").matches(pikitVersionName)) {
    "pikit.versionName is '$pikitVersionName'. It has to be a bare major.minor.patch " +
        "(for example 0.2.0): Settings → About PiKit shows this string to the user, and " +
        "a suffix like '-x64' or '+12' turns the version into a build note."
}
check(pikitVersionCode > 0) {
    "pikit.versionCode is $pikitVersionCode; it has to be a positive integer that goes up " +
        "for every published build."
}

/**
 * The repository the update check asks about, as `<owner>/<repo>`.
 *
 * A property rather than a constant in Kotlin so that moving the repository is one
 * line, and checked here because the value is pasted into a URL: an id GitHub would
 * reject produces a check that always fails, and the failure would look like the
 * network's fault rather than the build's.
 */
val pikitRepository: String = providers.gradleProperty("pikit.repository").get()

check(Regex("""[\w.-]+/[\w.-]+""").matches(pikitRepository)) {
    "pikit.repository is '$pikitRepository'. It has to be <owner>/<repo> — for example " +
        "nekooy/PiKit — because it is used verbatim in the update check's URL " +
        "(https://github.com/<owner>/<repo>/releases/latest)."
}

/**
 * A release keystore, when the build is given one.
 *
 * Release APKs are signed with the **debug** key until this is supplied, which is
 * the right default for an unreleased project — an unsigned release APK could not be
 * installed at all — and wrong for a published one: a release signed with the debug
 * key can never be upgraded by a properly signed one, because the signatures differ.
 *
 * All four values are required together. A *partial* configuration fails the build
 * instead of falling back to the debug key, because the failure it prevents is
 * silent and permanent: an APK signed with the wrong key installs fine on a phone
 * that has never seen PiKit, and is then the one build that no later release can
 * update. `docs/RELEASING.md` has the keytool command and the CI secrets.
 */
val keystoreFileProperty: String? = providers.gradleProperty("pikit.keystore.file").orNull
val keystoreAliasProperty: String? = providers.gradleProperty("pikit.keystore.alias").orNull
val keystoreStorePasswordProperty: String? =
    providers.gradleProperty("pikit.keystore.storePassword").orNull
val keystoreKeyPasswordProperty: String? =
    providers.gradleProperty("pikit.keystore.keyPassword").orNull

// Named individually rather than kept in a map, so that the four values below are
// `String` where they are used — `file()` takes `Any`, and a nullable one is a
// warning at configuration time for something `signingWithReleaseKey` already ruled
// out.
val keystoreValues = mapOf(
    "pikit.keystore.file" to keystoreFileProperty,
    "pikit.keystore.alias" to keystoreAliasProperty,
    "pikit.keystore.storePassword" to keystoreStorePasswordProperty,
    "pikit.keystore.keyPassword" to keystoreKeyPasswordProperty,
)
val configuredKeystore = keystoreValues.filterValues { it != null }
check(configuredKeystore.isEmpty() || configuredKeystore.size == keystoreValues.size) {
    "Release signing is configured with " +
        keystoreValues.filterValues { it == null }.keys.joinToString() +
        " missing. All of ${keystoreValues.keys.joinToString()} are needed together — " +
        "signing a release with some of them would silently produce an APK signed with the " +
        "debug key, which no properly signed release can ever upgrade."
}
val signingWithReleaseKey = configuredKeystore.isNotEmpty()

if (signingWithReleaseKey) {
    val keystoreFile = file(keystoreFileProperty!!)
    check(keystoreFile.isFile) {
        "pikit.keystore.file is '${keystoreFile.path}', which is not a file."
    }
}

android {
    namespace = "pi.kit.mob"
    compileSdk = providers.gradleProperty("pikit.compileSdk").get().toInt()

    defaultConfig {
        applicationId = appId
        minSdk = providers.gradleProperty("pikit.minSdk").get().toInt()
        targetSdk = providers.gradleProperty("pikit.targetSdk").get().toInt()
        versionCode = pikitVersionCode
        versionName = pikitVersionName

        // Only the prefix is read by the app — TermuxEnv checks it against the
        // directory the runtime was built for. The home path is not: `$HOME` is
        // `files/home` by construction, and the image does not name it.
        buildConfigField("String", "TERMUX_PREFIX", "\"$termuxPrefix\"")

        // Shown on the About page and asked about by its update check; see
        // `data/UpdateCheck.kt`.
        buildConfigField("String", "REPOSITORY", "\"$pikitRepository\"")
    }

    // One APK per CPU architecture. Each flavor only carries the runtime image
    // it actually needs, which keeps a fully offline build from doubling in size.
    flavorDimensions += "abi"
    productFlavors {
        create("arm64") {
            dimension = "abi"
            ndk { abiFilters.clear(); abiFilters += "arm64-v8a" }
        }
        create("x64") {
            dimension = "abi"
            ndk { abiFilters.clear(); abiFilters += "x86_64" }
        }
    }

    signingConfigs {
        if (signingWithReleaseKey) {
            create("release") {
                storeFile = file(keystoreFileProperty!!)
                storePassword = keystoreStorePasswordProperty
                keyAlias = keystoreAliasProperty
                keyPassword = keystoreKeyPasswordProperty
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // Deliberately no applicationIdSuffix: the bundled Termux image
            // hardcodes /data/data/<applicationId>/files/usr, so the installed
            // package name must not vary between build types.
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // The debug key when no keystore was supplied — see
            // `signingWithReleaseKey` above for why that is the default and why a
            // half-configured keystore is refused instead of falling back to it.
            signingConfig = signingConfigs.getByName(
                if (signingWithReleaseKey) "release" else "debug",
            )
        }
    }

    androidResources {
        // The bundled runtime images are already compressed archives. Storing
        // them uncompressed keeps `aapt2` fast and lets us stream-extract them.
        noCompress += listOf("zip", "tar", "xz", "zst", "gz", "tgz", "deb", "node")
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/*.kotlin_module",
            )
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // Termux-derived builds intentionally pin targetSdk 28 so the OS lets us
        // exec() binaries we extracted into our own data dir. That makes the
        // ExpiredTargetSdkVersion check fire; it is expected, not a defect.
        disable += setOf("ExpiredTargetSdkVersion", "GoogleAppIndexingWarning")
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okio)

    // Vendored Termux terminal stack (GPLv3): PTY via JNI + VT emulation + View.
    implementation(project(":terminal-view"))

    implementation(platform(libs.androidx.compose.bom))
    // Declared rather than inherited from material3: `AnimatedContent`, the
    // slide/fade specs and `SizeTransform` are all in this artifact, and leaning
    // on a transitive `api` dependency for a type named in our own source means
    // a material3 bump could break a file that never mentioned material3.
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Formula typesetting. JLaTeXMath carries the OpenType math metrics the platform's
    // text API does not expose, and it lays a formula out in **422 µs** where the KaTeX
    // metrics library this replaced took **21.7 ms** — measured on the same emulator at
    // the same size, and the whole of why a formula-heavy reply was slow. ARCHITECTURE §12.
    implementation(libs.jlatexmath)

    testImplementation(libs.junit)
}

// ---------------------------------------------------------------------------
// Bundled offline runtime image
// ---------------------------------------------------------------------------

/**
 * Each APK ships a prebuilt Termux environment, Node.js, ripgrep/fd and the pi
 * agent as stored (uncompressed) assets.
 *
 * Those assets are generated, not committed — they are ~110 MB per ABI — so a
 * fresh checkout can produce an APK that installs fine and then fails on first
 * launch. Warn during configuration so the cause is obvious, and expose a task
 * that turns it into a hard failure for release builds.
 */
val runtimeImagesByFlavor = mapOf("arm64" to "arm64-v8a", "x64" to "x86_64")

runtimeImagesByFlavor.forEach { (flavor, abi) ->
    val imageDir = layout.projectDirectory.dir("src/$flavor/assets/runtime/$abi")
    val requiredFiles = listOf("bootstrap.zip", "overlay.zip")

    fun missingRuntimeFiles(): List<String> =
        requiredFiles.filterNot { imageDir.file(it).asFile.isFile }

    if (missingRuntimeFiles().isNotEmpty()) {
        logger.warn(
            "PiKit: the bundled runtime image for $abi has not been built yet. " +
                "Run `python tools/build-runtime-image.py --all` before installing " +
                "the $flavor APK, or first launch will have no environment to unpack.",
        )
    }

    tasks.register("verifyRuntimeImage${flavor.replaceFirstChar(Char::uppercaseChar)}") {
        group = "verification"
        description = "Checks that the bundled runtime image for $abi exists."
        doLast {
            val missing = missingRuntimeFiles()
            if (missing.isNotEmpty()) {
                throw GradleException(
                    "The bundled runtime image for $abi is incomplete (missing " +
                        "${missing.joinToString()}).\n" +
                        "Build it with:\n" +
                        "    python tools/build-runtime-image.py --arch <aarch64|x86_64> " +
                        "--flavor $flavor\n" +
                        "or with --all to build every architecture.",
                )
            }
        }
    }
}

/**
 * Refuses a build whose ABI is not the connected device's, because the app cannot
 * paper over it.
 *
 * Android Studio's *Run* passes `android.injected.build.abi` — the ABI of the
 * device it is deploying to — and that property overrides every module's
 * `abiFilters`. Selecting the wrong variant therefore produces an APK that is a
 * genuine mixture: the flavor's `assets/runtime/<abi>/` (which is what the app
 * looks for) with the *device's* native libraries, so it installs and starts and
 * then reports that the runtime image is missing. Measured on an x86_64 emulator
 * with an `arm64Debug` variant: `lib/x86_64/libtermux.so` inside an APK whose only
 * runtime image was `runtime/arm64-v8a`.
 *
 * Failing here instead names the one thing to change, and only for the variant
 * actually being assembled: a plain `assembleX64Debug` on a machine with the
 * arm64 property injected still builds.
 */
androidComponents {
    onVariants { variant ->
        val flavor = variant.productFlavors.firstOrNull { it.first == "abi" }?.second ?: return@onVariants
        val abi = runtimeImagesByFlavor[flavor] ?: return@onVariants
        val injected = providers.gradleProperty("android.injected.build.abi")
        val check = tasks.register("verifyInjectedAbi${variant.name.replaceFirstChar(Char::uppercaseChar)}") {
            group = "verification"
            description = "Checks that $variant is not being built for another device's ABI."
            doLast {
                val deviceAbis = injected.orNull
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    .orEmpty()
                if (deviceAbis.isNotEmpty() && abi !in deviceAbis) {
                    throw GradleException(
                        "The '$flavor' variant carries the $abi runtime image, but Android " +
                            "Studio is building for ${deviceAbis.joinToString()} — the ABI of the " +
                            "device it is deploying to.\n" +
                            "The two cannot be mixed: the APK would install and then report that " +
                            "its runtime image is missing.\n" +
                            "Pick the variant that matches the device (in Android Studio, " +
                            "Build → Select Build Variant):\n" +
                            runtimeImagesByFlavor.entries.joinToString("\n") { (name, target) ->
                                "    ${name}Debug  for $target"
                            },
                    )
                }
            }
        }
        tasks.matching { it.name == "assemble${variant.name.replaceFirstChar(Char::uppercaseChar)}" }
            .configureEach { dependsOn(check) }
    }
}

// ---------------------------------------------------------------------------
// Packaged APK
// ---------------------------------------------------------------------------

/**
 * Refuses an APK whose file is larger than the archive it contains.
 *
 * Found by accident, and worth a guard because the failure is silent: one build
 * produced an `app-arm64-debug.apk` of 202.8 MB whose own entries accounted for
 * 126.3 MB. AGP's packaging does truncate — appending 50 MB to an APK and running
 * the packaging task again brought it back to its real size, which is how this was
 * checked — so the extra 76.5 MB came from the **build cache** (that build reported
 * "10 from cache"): a bloated artifact written by an earlier build was restored on
 * top of the real one. Whatever produced it, an APK like that is not merely fat —
 * a zip reader finds the central directory by scanning back from the *end* of the
 * file, so it may read the stale archive and refuse to install.
 *
 * The test compares the file's length with what its entries account for, with room
 * for a stored entry's 4 KB page alignment, the local headers and the central
 * directory. It is deliberately loose: it catches megabytes of slack, not bytes.
 */
private fun apkSlackBytes(apk: File): Long? {
    // `java.util.zip.ZipFile` spelled out does not resolve here: in a Gradle Kotlin
    // script `java` is the Java plugin's extension, not a package. Hence the imports.
    return try {
        ZipFile(apk).use { zip ->
            var accounted = 0L
            var entries = 0
            val iterator = zip.entries()
            while (iterator.hasMoreElements()) {
                val entry = iterator.nextElement()
                accounted += entry.compressedSize + 30L + entry.name.toByteArray().size +
                    (entry.extra?.size ?: 0)
                entries++
            }
            // zipalign pads each stored entry to a page, every entry has a local
            // header, and the central directory, its zip64 records and its comment are
            // not entries at all. A megabyte on top of that is slack no APK should
            // have.
            val accountedFor =
                accounted + entries * (PAGE_ALIGNMENT + LOCAL_HEADER_ALLOWANCE) + 1_048_576L
            apk.length() - accountedFor
        }
    } catch (notAZip: ZipException) {
        // The stronger failure: trailing bytes have hidden the central directory, so
        // nothing can read the file at all — not this check, and not the installer.
        null
    }
}

/**
 * `zipalign -p` aligns a stored entry's data to 4 KB.
 *
 * Plain `val`s rather than `const val`s: a `.gradle.kts` file is a script, and
 * `const` is not allowed outside a class or an object there.
 */
val PAGE_ALIGNMENT = 4096L

/** Room for extra fields and the data descriptor an entry may carry. */
val LOCAL_HEADER_ALLOWANCE = 512L

androidComponents {
    onVariants { variant ->
        val variantName = variant.name.replaceFirstChar(Char::uppercaseChar)
        val check = tasks.register("verifyApkPackaging$variantName") {
            group = "verification"
            description = "Checks that the $variant APK holds nothing beyond its own entries."
            // Packaging first, explicitly: `assemble` depending on this task says
            // nothing about the order of the two, and a check that reads the previous
            // build's APK — or a half-written one — is worse than no check.
            dependsOn("package$variantName")
            doLast {
                val directory = layout.buildDirectory
                    .dir("outputs/apk/${variant.flavorName}/${variant.buildType}")
                    .get()
                    .asFile
                val apks = directory.listFiles { file -> file.isFile && file.name.endsWith(".apk") }
                    .orEmpty()
                apks.forEach { apk ->
                    val slack = apkSlackBytes(apk)
                    if (slack != null && slack <= 0) return@forEach
                    val what = if (slack == null) {
                        "${apk.name} cannot be read as a zip at all (${apk.length()} bytes)."
                    } else {
                        "${apk.name} is ${apk.length()} bytes, but its own entries account " +
                            "for ${apk.length() - slack}: $slack bytes of it are not in the archive."
                    }
                    throw GradleException(
                        "$what\n" +
                            "That is a stale artifact — normally a build-cache entry written by " +
                            "an earlier build, since the packaging task itself truncates — and " +
                            "an APK like it does not install: a zip reader looks for the central " +
                            "directory at the end.\n" +
                            "Rebuild it without the cache:\n" +
                            "    ./gradlew :app:assemble$variantName --no-build-cache\n" +
                            "or delete the APK and this task's output first.",
                    )
                }
            }
        }
        tasks.matching { it.name == "assemble$variantName" }.configureEach { dependsOn(check) }
    }
}
