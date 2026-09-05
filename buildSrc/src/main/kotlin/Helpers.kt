import com.android.build.api.dsl.ApplicationExtension
import com.android.build.gradle.AbstractAppExtension
import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByName
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import java.util.Base64
import java.util.Properties
import kotlin.system.exitProcess

private val Project.android get() = extensions.getByName<ApplicationExtension>("android")

// Read on every call. buildSrc classes outlive a single build inside the Gradle daemon, so holding
// these in file-scope state made an edited nb4a.properties / local.properties invisible until the
// daemon was restarted — a bumped version silently produced APKs labelled with the previous one.
fun Project.requireMetadata(): Properties = Properties().apply {
    rootProject.file("nb4a.properties").inputStream().use { load(it) }
}

fun Project.requireLocalProperties(): Properties = Properties().apply {
    val base64 = System.getenv("LOCAL_PROPERTIES")
    if (!base64.isNullOrBlank()) {
        Base64.getDecoder().decode(base64).inputStream().use { load(it) }
    } else {
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }
}

fun Project.setupCommon() {
    android.apply {
        buildToolsVersion = "36.0.0"
        compileSdk = 36
        defaultConfig {
            minSdk = 21
            targetSdk = 36
        }
        buildTypes {
            getByName("release") {
                isMinifyEnabled = true
            }
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }
        this@setupCommon.extensions.getByName<KotlinAndroidProjectExtension>("kotlin").compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
        lint {
            showAll = true
            checkAllWarnings = true
            checkReleaseBuilds = true
            warningsAsErrors = true
            textOutput = project.file("build/lint.txt")
            htmlOutput = project.file("build/lint.html")
        }
        packaging {
            resources.excludes.addAll(
                listOf(
                    "**/*.kotlin_*",
                    "/META-INF/*.version",
                    "/META-INF/native/**",
                    "/META-INF/native-image/**",
                    "/META-INF/INDEX.LIST",
                    "DebugProbesKt.bin",
                    "**/*.java",
                    "**/*.proto",
                    "okhttp3/**"
                )
            )
        }
        (this as? AbstractAppExtension)?.apply {
            buildTypes {
                getByName("release") {
                    isShrinkResources = true
                    if (System.getenv("nkmr_minify") == "0") {
                        isShrinkResources = false
                        isMinifyEnabled = false
                    }
                }
                getByName("debug") {
                    applicationIdSuffix = "debug"
                    debuggable(true)
                    jniDebuggable(true)
                }
            }
        }
    }
}

fun Project.setupAppCommon() {
    setupCommon()

    val lp = requireLocalProperties()
    val keystorePwd = lp.getProperty("KEYSTORE_PASS") ?: System.getenv("KEYSTORE_PASS")
    val alias = lp.getProperty("ALIAS_NAME") ?: System.getenv("ALIAS_NAME")
    val pwd = lp.getProperty("ALIAS_PASS") ?: System.getenv("ALIAS_PASS")

    android.apply {
        if (keystorePwd != null) {
            signingConfigs {
                create("release") {
                    storeFile = rootProject.file("release.keystore")
                    storePassword = keystorePwd
                    keyAlias = alias
                    keyPassword = pwd
                }
            }
        }
        buildTypes {
            val key = signingConfigs.findByName("release")
            if (key != null) {
                getByName("release").signingConfig = key
                getByName("debug").signingConfig = key
            }
        }
    }
}

fun Project.setupApp() {
    val pkgName = requireNotNull(requireMetadata().getProperty("PACKAGE_NAME")) {
        "PACKAGE_NAME is missing in nb4a.properties"
    }
    val verName = requireNotNull(requireMetadata().getProperty("VERSION_NAME")) {
        "VERSION_NAME is missing in nb4a.properties"
    }
    val verCode = requireNotNull(requireMetadata().getProperty("VERSION_CODE")) {
        "VERSION_CODE is missing in nb4a.properties"
    }.toInt() * 5
    android.apply {
        defaultConfig {
            applicationId = pkgName
            versionCode = verCode
            versionName = verName
            buildConfigField("String", "PRE_VERSION_NAME", "\"\"")
        }
    }
    setupAppCommon()

    android.apply {
        this as AbstractAppExtension

        buildTypes {
            getByName("release") {
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    file("proguard-rules.pro")
                )
            }
        }

        splits.abi {
            reset()
            isEnable = true
            isUniversalApk = true
            include("arm64-v8a")
            include("x86_64")
        }

        flavorDimensions += "vendor"
        productFlavors {
            create("oss")
            create("fdroid")
            create("preview") {
                buildConfigField(
                    "String",
                    "PRE_VERSION_NAME",
                    "\"${requireNotNull(requireMetadata().getProperty("PRE_VERSION_NAME")) {
                        "PRE_VERSION_NAME is missing in nb4a.properties"
                    }}\""
                )
            }
        }

        applicationVariants.all {
            outputs.all {
                this as BaseVariantOutputImpl
                val isPreview = outputFileName.contains("-preview")
                outputFileName = if (isPreview) {
                    outputFileName.replace(
                        project.name,
                        "NekoBox-" + requireNotNull(requireMetadata().getProperty("PRE_VERSION_NAME")) {
                            "PRE_VERSION_NAME is missing in nb4a.properties"
                        }
                    ).replace("-preview", "")
                        .replace("-release", "")
                } else {
                    outputFileName.replace(project.name, "NekoBox-$versionName")
                        .replace("-release", "")
                        .replace("-oss", "")
                }
            }
        }

        for (abi in listOf("Arm64", "X64")) {
            tasks.register("assemble" + abi + "FdroidRelease") {
                // Historical task name kept for existing callers: it builds the full
                // fdroid release (all ABI splits), not just the ABI in the name.
                description = "Builds the full fdroid release (all ABIs); the ABI in the task name is historical."
                dependsOn("assembleFdroidRelease")
            }
        }

        sourceSets.getByName("main").apply {
            jniLibs.srcDir("executableSo")
        }
    }
}