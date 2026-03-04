import org.gradle.api.internal.project.ProjectInternal
import org.gradle.process.internal.ExecActionFactory
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        load(FileInputStream(keystorePropsFile))
    }
}
android {
    namespace = "zm.co.codelabs.utorr"
    compileSdk = 36
    signingConfigs {
        create("release") {
            storeFile = file(keystoreProps["storeFile"] as String)
            storePassword = keystoreProps["storePassword"] as String
            keyAlias = keystoreProps["keyAlias"] as String
            keyPassword = keystoreProps["keyPassword"] as String
        }
    }

    defaultConfig {
        applicationId = "zm.co.codelabs.utorr"
        minSdk = 26
        //noinspection EditedTargetSdkVersion
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin { jvmToolchain(11) }

    buildFeatures { viewBinding = true }
}
dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)


    implementation(files("libs/utorr.aar"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)


    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}


val engineDir: Directory = layout.projectDirectory.dir("../engine/utorr")
val libsOutDir: Directory = layout.projectDirectory.dir("libs")
val aarOutFile: RegularFile = libsOutDir.file("utorr.aar")


tasks.register("cleanUtorrEngine") {
    group = "utorr"
    description = "Delete generated Go engine artifacts (app/libs/utorr.aar and best-effort gomobile caches)."

    doLast {
        // Remove the produced AAR
        val aar = aarOutFile.asFile
        if (aar.exists()) {
            logger.lifecycle("Deleting: ${aar.absolutePath}")
            aar.delete()
        }
        val localCache = engineDir.file(".gomobile").asFile
        if (localCache.exists()) {
            logger.lifecycle("Deleting: ${localCache.absolutePath}")
            localCache.deleteRecursively()
        }

        val engineBuild = engineDir.file("build").asFile
        if (engineBuild.exists()) {
            logger.lifecycle("Deleting: ${engineBuild.absolutePath}")
            engineBuild.deleteRecursively()
        }
    }
}

/**
 * Build Go torrent engine AAR for device + emulator.
 * Includes:
 *  - arm (armeabi-v7a)
 *  - arm64 (arm64-v8a)
 *  - x86 (android/386)      -> emulator (older)
 *  - x86_64 (android/amd64) -> emulator (common)
 */
tasks.register("buildUtorrAar") {
    group = "utorr"
    description = "Build Go torrent engine AAR via gomobile bind into app/libs/utorr.aar (device + emulator ABIs)."

    inputs.dir(engineDir)
    outputs.file(aarOutFile)

    dependsOn("cleanUtorrEngine")

    doFirst {
        libsOutDir.asFile.mkdirs()
    }

    doLast {
        fun execChecked(workingDir: File, vararg cmd: String) {
            val out = ByteArrayOutputStream()
            val err = ByteArrayOutputStream()
            val result =
                (project as ProjectInternal).services.get(ExecActionFactory::class.java)
                    .newExecAction().apply {
                        workingDir(workingDir)
                        commandLine(*cmd)
                        standardOutput = out
                        errorOutput = err
                        isIgnoreExitValue = true
                    }.execute()
            if (result.exitValue != 0) {
                throw GradleException(
                    "Command failed (${cmd.joinToString(" ")}), exit=${result.exitValue}\n" +
                            "stdout:\n${out.toString(Charsets.UTF_8)}\n" +
                            "stderr:\n${err.toString(Charsets.UTF_8)}"
                )
            }
        }

        // Keep module tidy
        execChecked(engineDir.asFile, "go", "mod", "tidy")

        // Ensure gomobile toolchain is initialized
        execChecked(engineDir.asFile, "gomobile", "init")

        // Build AAR from ./utorr package
        execChecked(
            engineDir.asFile,
            "gomobile", "bind", "-v", "-ldflags", "-checklinkname=0",
            "-target", "android",
            "-androidapi", android.defaultConfig.minSdk.toString(),
            "-o", aarOutFile.asFile.absolutePath,
            "."
        )
    }
}

// Auto-build the AAR whenever Android builds (optional but convenient)
//tasks.matching { it.name == "preBuild" }.configureEach {
//    dependsOn("buildUtorrAar")
//}