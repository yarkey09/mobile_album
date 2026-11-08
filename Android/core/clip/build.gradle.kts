import java.util.Properties
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

plugins {
    alias(libs.plugins.android.library)
}

// Keep the large ORT build outside the Android module build directory so Gradle clean does not
// discard native incremental state. The stable AAR is the only artifact consumed by this module.
val ortSourceDir = rootProject.projectDir.parentFile.resolve("onnxruntime")
val ortDebugBuildDir = ortSourceDir.resolve("build/android_aar_debug")
val ortDebugAbi = providers.gradleProperty("ortDebugAbi").orElse("arm64-v8a")
val ortNativeBuildDir = ortDebugBuildDir.resolve("intermediates/${ortDebugAbi.get()}")
val ortBuiltDebugAar = ortNativeBuildDir.resolve(
    "Debug/java/build/android/outputs/aar/onnxruntime-debug.aar"
)
val ortDebugAar = ortDebugBuildDir.resolve("artifacts/onnxruntime-debug.aar")
val ortNdkVersion = providers.gradleProperty("ortNdkVersion").orElse("28.2.13676358")
val ortCmakeVersion = providers.gradleProperty("ortCmakeVersion").orElse("3.31.6")
val ortNinjaVersion = providers.gradleProperty("ortNinjaVersion").orElse("1.11.1.1")
val ortBuildToolsDir = ortSourceDir.resolve("build/android_build_tools")
val ortCmakeBinDir = ortBuildToolsDir.resolve("cmake/data/bin")
val ortNinjaBinDir = ortBuildToolsDir.resolve("ninja/data/bin")
val ortCmakePath = ortCmakeBinDir.resolve("cmake")
val ortCtestPath = ortCmakeBinDir.resolve("ctest")
val ortNinjaPath = ortNinjaBinDir.resolve("ninja")
val javaToolchains = extensions.getByType<JavaToolchainService>()
val ortJavaLauncher = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(17)
}
val ortJavaHome = providers.gradleProperty("ortJavaHome")
    .map(::File)
    .orElse(ortJavaLauncher.map { it.metadata.installationPath.asFile })

// ORT 1.31.1 requires CMake 3.28+, while commonly installed Android SDK CMake versions are older.
// Install pinned tools into ORT's ignored build directory instead of modifying the host system.
val installOrtBuildTools by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Installs pinned CMake and Ninja binaries for the local ORT build."

    inputs.property("ortCmakeVersion", ortCmakeVersion)
    inputs.property("ortNinjaVersion", ortNinjaVersion)
    inputs.property("ortJavaHome", ortJavaHome.map { it.absolutePath })
    outputs.files(ortCmakePath, ortCtestPath, ortNinjaPath)

    doFirst {
        ortBuildToolsDir.mkdirs()
        commandLine(
            providers.environmentVariable("PYTHON").orElse("python3").get(),
            "-m",
            "pip",
            "install",
            "--disable-pip-version-check",
            "--upgrade",
            "--target",
            ortBuildToolsDir.absolutePath,
            "cmake==${ortCmakeVersion.get()}",
            "ninja==${ortNinjaVersion.get()}",
        )
    }
}

val buildOrtDebugAar by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the local ONNX Runtime Debug AAR for Android."
    workingDir(ortSourceDir)
    dependsOn(installOrtBuildTools)

    // ORT runs auxiliary Android lint packaging after producing the Debug AAR. That packaging may
    // fail for the 1+ GB unstripped library, so doLast validates the real deliverable explicitly.
    isIgnoreExitValue = true

    inputs.property("ortDebugAbi", ortDebugAbi)
    inputs.property("ortNdkVersion", ortNdkVersion)
    inputs.property("ortCmakeVersion", ortCmakeVersion)
    inputs.property("ortNinjaVersion", ortNinjaVersion)
    inputs.file(ortSourceDir.resolve("VERSION_NUMBER"))
    inputs.files(
        fileTree(ortSourceDir.resolve("cmake")),
        fileTree(ortSourceDir.resolve("include")),
        fileTree(ortSourceDir.resolve("java/src/main")),
        fileTree(ortSourceDir.resolve("onnxruntime")),
        fileTree(ortSourceDir.resolve("tools/ci_build")) {
            include("**/*.py")
        },
    ).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(ortDebugAar)

    doFirst {
        val localProperties = Properties().apply {
            val localPropertiesFile = rootProject.file("local.properties")
            if (localPropertiesFile.isFile) {
                localPropertiesFile.inputStream().use(::load)
            }
        }
        val sdkPath = providers.gradleProperty("ortAndroidSdkPath").orNull
            ?: providers.environmentVariable("ANDROID_HOME").orNull
            ?: providers.environmentVariable("ANDROID_SDK_ROOT").orNull
            ?: localProperties.getProperty("sdk.dir")
            ?: throw GradleException(
                "Android SDK not found. Set sdk.dir, ANDROID_HOME, or -PortAndroidSdkPath."
            )
        val ndkPath = providers.gradleProperty("ortAndroidNdkPath").orNull
            ?: providers.environmentVariable("ANDROID_NDK_HOME").orNull
            ?: File(sdkPath, "ndk/${ortNdkVersion.get()}").absolutePath
        val javaHome = ortJavaHome.get()

        check(File(sdkPath).isDirectory) {
            "Android SDK directory does not exist: $sdkPath"
        }
        check(File(ndkPath).isDirectory) {
            "Android NDK directory does not exist: $ndkPath. " +
                "Install NDK ${ortNdkVersion.get()} or set -PortAndroidNdkPath."
        }
        check(ortCmakePath.isFile && ortCtestPath.isFile && ortNinjaPath.isFile) {
            "ORT build tools are incomplete under $ortCmakeBinDir."
        }
        check(javaHome.resolve("bin/java").isFile) {
            "JDK 17+ not found under $javaHome. Set -PortJavaHome."
        }

        // Unit-test binaries add several gigabytes and are not needed to debug inference from the
        // app. LTO stays disabled in Debug so LLDB can map instructions back to ORT source lines.
        commandLine(
            providers.environmentVariable("PYTHON").orElse("python3").get(),
            ortSourceDir.resolve("tools/ci_build/build.py").absolutePath,
            "--android",
            "--parallel",
            "--cmake_generator=Ninja",
            "--cmake_path=${ortCmakePath.absolutePath}",
            "--ctest_path=${ortCtestPath.absolutePath}",
            "--cmake_extra_defines",
            "onnxruntime_BUILD_UNIT_TESTS=OFF",
            "--build_java",
            "--build_shared_lib",
            "--skip_tests",
            "--no_telemetry",
            "--compile_no_warning_as_error",
            "--android_api=24",
            "--android_abi=${ortDebugAbi.get()}",
            "--android_sdk_path=$sdkPath",
            "--android_ndk_path=$ndkPath",
            "--config=Debug",
            "--use_vcpkg",
            "--use_vcpkg_ms_internal_asset_cache",
            "--build_dir=${ortNativeBuildDir.absolutePath}",
        )
        environment("JAVA_HOME", javaHome.absolutePath)
        environment(
            "PATH",
            listOf(
                javaHome.resolve("bin").absolutePath,
                ortCmakeBinDir.absolutePath,
                ortNinjaBinDir.absolutePath,
                providers.environmentVariable("PATH").orElse("").get(),
            ).joinToString(File.pathSeparator),
        )
    }

    doLast {
        // Copy the AAR out of CMake's intermediate tree to a stable Gradle output. This lets Gradle
        // skip the expensive native build until an ORT source or build-property input changes.
        check(ortBuiltDebugAar.isFile) {
            "ORT native build did not produce the expected Debug AAR: $ortBuiltDebugAar"
        }
        if (executionResult.get().exitValue != 0) {
            logger.warn(
                "ORT produced the Debug AAR, but a later auxiliary packaging task failed. " +
                    "Using the completed Debug AAR."
            )
        }
        ortDebugAar.parentFile.mkdirs()
        ortBuiltDebugAar.copyTo(ortDebugAar, overwrite = true)
    }
}

android {
    namespace = "com.yarkey.clip"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    // Local symbols and sources are used only for debugging; release builds keep the published AAR.
    add("debugImplementation", files(ortDebugAar).builtBy(buildOrtDebugAar))
    add("releaseImplementation", libs.onnxruntime.android)

    testImplementation(libs.junit)
}
