import java.net.HttpURLConnection
import java.net.URI

plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.lsplugin.apksign)
    alias(libs.plugins.lsplugin.resopt)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.parcelize)
    alias(libs.plugins.compose.compiler)
}

val signingProperties = listOf(
    "KEYSTORE_FILE",
    "KEYSTORE_PASSWORD",
    "KEY_ALIAS",
    "KEY_PASSWORD",
)

/** About-page developer identity: only the profile data is fetched, this id is fixed. */
val DEVELOPER_GITHUB_ID = "Mercury000"
val DEVELOPER_NAME_FALLBACK = "Mercury"

if (signingProperties.all { providers.gradleProperty(it).isPresent }) {
    apksign {
        storeFileProperty = "KEYSTORE_FILE"
        storePasswordProperty = "KEYSTORE_PASSWORD"
        keyAliasProperty = "KEY_ALIAS"
        keyPasswordProperty = "KEY_PASSWORD"
    }
}

android {
    namespace = "com.mercury.sonypods"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mercury.sonypods"
        minSdk = 34
        targetSdk = 36
        versionCode = 19
        versionName = "1.6.1"
        buildConfigField("long", "BUILD_TIMESTAMP", System.currentTimeMillis().toString())
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    dependenciesInfo.includeInApk = false

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        jniLibs {
            // Replaces the removed manifest android:extractNativeLibs="false".
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/**.version"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "okhttp3/**"
            excludes += "kotlin/**"
            excludes += "org/**"
            excludes += "**.properties"
            excludes += "**.bin"
            excludes += "kotlin-tooling-metadata.json"
        }
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(JavaVersion.VERSION_22.majorVersion)
    }
}

kotlin {
    jvmToolchain(JavaVersion.VERSION_22.majorVersion.toInt())
}

configurations.configureEach {
    exclude(group = "androidx.lifecycle", module = "lifecycle-viewmodel-ktx")
}

// ---------------------------------------------------------------------------
// Developer profile for the About page: fetched from the GitHub API at build
// time so every package carries the current avatar / display name / login.
// Resolution order: fresh fetch (cache older than 24h) -> stale cache ->
// committed defaults. Builds never hard-fail on network problems.
// ---------------------------------------------------------------------------
val developerProfileResDir = layout.buildDirectory.dir("generated/developerProfile/res").get().asFile
val developerProfileCacheDir = layout.buildDirectory.dir("generated/developerProfile/cache").get().asFile

val generateDeveloperProfile = tasks.register("generateDeveloperProfile") {
    outputs.dir(developerProfileResDir)
    outputs.upToDateWhen { false }

    doLast {
        val resDir = developerProfileResDir.apply { deleteRecursively(); mkdirs() }
        val cacheDir = developerProfileCacheDir
        val fallbackAvatar = rootProject.file("app/developer/fallback_avatar.png")

        fun writeRes(name: String, id: String, avatar: Pair<String, ByteArray>) {
            resDir.resolve("values").mkdirs()
            resDir.resolve("drawable-nodpi").mkdirs()
            resDir.resolve("values/developer_profile.xml").writeText(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <resources>
                    <string name="about_developer_name">${name.xmlEscape()}</string>
                    <string name="about_developer_id">${id.xmlEscape()}</string>
                </resources>
                """.trimIndent()
            )
            resDir.resolve("drawable-nodpi/about_developer_avatar.${avatar.first}")
                .writeBytes(avatar.second)
        }

        fun cacheFiles() = cacheDir.listFiles()?.filter { it.isFile } ?: emptyList()
        fun readCachedAvatar(): Pair<String, ByteArray>? = cacheFiles()
            .firstOrNull { it.name.startsWith("about_developer_avatar.") }
            ?.let { it.extension to it.readBytes() }

        fun fetch(url: String, timeoutMs: Int = 10_000): ByteArray {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("User-Agent", "SonyPods-build")
            try {
                if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode} for $url")
                return connection.inputStream.use { it.readBytes() }
            } finally {
                connection.disconnect()
            }
        }

        fun imageExtension(bytes: ByteArray): String? = when {
            bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "png"
            bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
            bytes.size > 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WEBP" -> "webp"
            else -> null
        }

        val cachedMeta = cacheDir.resolve("profile.properties").takeIf { it.isFile() }
        val cacheIsFresh = cachedMeta != null &&
            System.currentTimeMillis() - cachedMeta.lastModified() < 24 * 60 * 60 * 1000L

        var avatar: Pair<String, ByteArray>? = null
        var name: String? = null
        var id: String? = null

        if (!cacheIsFresh) {
            runCatching {
                val profile = fetch("https://api.github.com/users/$DEVELOPER_GITHUB_ID").decodeToString()
                name = Regex(""""name"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(profile)?.groupValues?.get(1)
                id = Regex(""""login"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(profile)?.groupValues?.get(1)
                val avatarUrl = Regex(""""avatar_url"\s*:\s*"([^"]+)"""").find(profile)?.groupValues?.get(1)
                if (avatarUrl != null) {
                    val bytes = fetch(avatarUrl)
                    imageExtension(bytes)?.let { ext ->
                        avatar = ext to bytes
                        cacheDir.apply { mkdirs(); resolve("about_developer_avatar.$ext").writeBytes(bytes) }
                        cacheDir.listFiles().orEmpty()
                            .filter { it.name.startsWith("about_developer_avatar.") && it.extension != ext }
                            .forEach { it.delete() }
                        cacheDir.resolve("profile.properties").writeText(
                            "name=${name ?: ""}\nid=${id ?: DEVELOPER_GITHUB_ID}\n"
                        )
                        logger.lifecycle("generateDeveloperProfile: fetched ${name ?: id} from GitHub")
                    }
                }
            }.onFailure { logger.warn("generateDeveloperProfile: GitHub fetch failed (${it.message}); falling back to cache") }
        }

        if (avatar == null) {
            // Fresh-cache shortcut or stale-cache fallback after a failed fetch.
            val meta = cachedMeta
            if (meta != null) {
                val props = meta.readLines().mapNotNull { line ->
                    line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
                }.toMap()
                name = props["name"]?.takeIf { it.isNotBlank() }
                id = props["id"]?.takeIf { it.isNotBlank() }
            }
            avatar = readCachedAvatar()
            if (avatar == null) {
                avatar = "png" to fallbackAvatar.readBytes()
                logger.warn("generateDeveloperProfile: no cached avatar, using bundled fallback")
            }
        }

        writeRes(name ?: DEVELOPER_NAME_FALLBACK, id ?: DEVELOPER_GITHUB_ID, avatar!!)
    }
}

fun String.xmlEscape(): String =
    replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

android {
    sourceSets {
        getByName("main") {
            res.srcDir(developerProfileResDir)
        }
    }
}

tasks.named("preBuild") {
    dependsOn(generateDeveloperProfile)
}

dependencies {
    implementation(libs.coreKtx)
    compileOnly(libs.libxposedApi)
    implementation(libs.libxposedService)
    implementation(libs.kotlinx.serialization.json)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)

    // MIUIX
    implementation(libs.miuix)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.nav)

    // HyperOS Focus Island API
    implementation(libs.focus.api)

    testImplementation(libs.junit)
}
