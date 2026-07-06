import java.io.ByteArrayOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
    id("com.google.dagger.hilt.android")
}

// ----- eBPF daemon cross-build pipeline -----
val daemonDir = rootProject.file("daemon")
val daemonBinary = daemonDir.resolve("btrace")
val generatedDaemonAssets = layout.buildDirectory.dir("generated/daemon-asset")

val buildDaemon = tasks.register<Exec>("buildDaemon") {
    group = "build"
    description = "Cross-compile the eBPF daemon (linux/arm64) via daemon/Makefile."
    workingDir = daemonDir
    commandLine("make", "all")

    inputs.files(fileTree(daemonDir) {
        include("*.go", "*.c", "Makefile", "go.mod", "go.sum")
    })
    inputs.dir(daemonDir.resolve("headers"))
    outputs.file(daemonBinary)

    doFirst {
        try {
            exec {
                commandLine("go", "version")
                standardOutput = ByteArrayOutputStream()
                errorOutput = ByteArrayOutputStream()
            }
        } catch (e: Exception) {
            throw GradleException(
                "Go toolchain not found on PATH. Install Go 1.22+ to build the daemon. " +
                "Underlying error: ${e.message}"
            )
        }
    }
}

val stageDaemonAsset = tasks.register<Copy>("stageDaemonAsset") {
    group = "build"
    description = "Stage the cross-compiled daemon binary into the app's generated assets."
    dependsOn(buildDaemon)
    from(daemonBinary)
    into(generatedDaemonAssets)
}

val cleanDaemon = tasks.register<Exec>("cleanDaemon") {
    group = "build"
    description = "Run `make clean` in daemon/ to remove bpf2go artifacts and binary."
    workingDir = daemonDir
    commandLine("make", "clean")
    isIgnoreExitValue = true
}
tasks.named("clean").configure { dependsOn(cleanDaemon) }

android {
    namespace = "com.btrace.viewer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.btrace.viewer"
        minSdk = 26
        targetSdk = 34
        // 版本号由 git tag 驱动(CI 注入 VERSION_NAME=v主.次.修订,见 release.yml);本地构建回落 dev
        val tagVer = System.getenv("VERSION_NAME")
            ?.let { Regex("""(\d+)\.(\d+)\.(\d+)""").find(it)?.groupValues }
        versionCode = tagVer?.let { it[1].toInt() * 10000 + it[2].toInt() * 100 + it[3].toInt() } ?: 1
        versionName = tagVer?.get(0) ?: "1.0.0-dev"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // spec 2026-05-03 § 4.4:签名缓存容量上限通过 BuildConfig 暴露,预留 RemoteConfig 接入位。
        // 默认值见 PersistentSignatureCache / SignatureCache 伴生对象;调节范围(SIG_MEM 64..1024、
        // SIG_DISK_PKGS 16..256、SIG_DISK_BYTES_PER_PKG 1KB..16KB、SIG_DISK_TOTAL 64KB..1MB)
        // 由 spec § 4.4 表格规定。
        buildConfigField("int", "SIG_CACHE_MAX_ENTRIES", "256")
        buildConfigField("int", "SIG_CACHE_MAX_PACKAGES", "64")
        buildConfigField("int", "SIG_CACHE_MAX_BYTES_PER_PACKAGE", "4096")
        buildConfigField("long", "SIG_CACHE_TOTAL_SOFT_LIMIT_BYTES", "262144L")
    }

    // 可选通过环境变量注入自定义 release 签名;未配置时回落到默认 debug 签名
    val ciKeystore = System.getenv("KEYSTORE_FILE")
    if (ciKeystore != null) {
        signingConfigs.create("release") {
            storeFile = file(ciKeystore)
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = "release"
            keyPassword = System.getenv("KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        // 启用 BuildConfig 字段生成,容量上限通过 buildConfigField 暴露给运行时(spec § 4.4)。
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.4"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    sourceSets.named("main") {
        assets.srcDir(generatedDaemonAssets)
    }

    // 单元测试默认让 android.* stub 方法返回默认值,避免 Log.v / Log.d 抛 "not mocked"。
    // 否则任何用 CLogUtils 的纯 JVM 测试都跑不起来。
    // isIncludeAndroidResources = true 是 Robolectric 跑通的前置 —— 它需要 manifest / 资源。
    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }
}

afterEvaluate {
    tasks.named("preBuild").configure { dependsOn(stageDaemonAsset) }
}

dependencies {

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    // ProcessLifecycleOwner: 用于 MonitoringService 检测 app 前后台,实现"前台时悬浮窗自动隐藏"
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2023.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.7.6")
    
    // Hilt
    implementation("com.google.dagger:hilt-android:2.50")
    kapt("com.google.dagger:hilt-android-compiler:2.50")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    // Icons
    implementation("androidx.compose.material:material-icons-extended")

    // Material 2 (for pull refresh)
    implementation("androidx.compose.material:material")

    // libsu
    implementation("com.github.topjohnwu.libsu:core:5.2.1")

    // HiddenApiBypass: 允许反射读 android.*$Stub 里的 TRANSACTION_* 字段(非 SDK 接口)
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    // org.json 真实实现:android.jar stub 在 unit test 里只返回默认值,纯 JVM 测试需要真 impl。
    // Robolectric 测试已隐式带,但 StaticMethodTableTest 不走 Robolectric,所以显式加在 testImplementation。
    testImplementation("org.json:json:20180813")
    // Robolectric:在 JVM 单测里跑真实 Parcel 路径(Parcel.obtain / unmarshall)。
    // 没它的话 ParcelArgumentDecoder 整条主路径根本测不了。
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.test.ext:junit:1.1.5")
    // Mockito:MethodResolver 需要 mock Context/AppClassLoaderRegistry(final 类)。
    // 4.x 系列兼容 JVM target 1.8;5.x 起要求 JDK 11。
    testImplementation("org.mockito:mockito-core:4.11.0")
    testImplementation("org.mockito:mockito-inline:4.11.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:4.1.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.08.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

kapt {
    correctErrorTypes = true
}
