import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // 备用接入(Room 仍是 Java annotationProcessor,后续需要 KSP 处理器时直接使用)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.github.tvbox.osc"
    compileSdk = libs.versions.compileSdk.get().toInt()
    // 见 libs.versions.toml 的 ndk 说明:仅用于启用 AGP 的 .so 符号剥离
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "com.github.avbox.osc"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 9
        versionName = "1.0.8"
        multiDexEnabled = true
        ndk {
            abiFilters += setOf("arm64-v8a")
        }
    }

    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/beans.xml")
        }
    }

    sourceSets {
        getByName("main") {
            // src/python/java:Python 采集源桥接层(包含 Python 支持)
            java.directories += "src/python/java"
        }
    }

    signingConfigs {
        // 签名信息从 gradle.properties 读取;密钥库不存在时不创建,release 保持未签名
        val storeFilePath = project.findProperty("RELEASE_STORE_FILE") as String? ?: ".key/app-release.jks"
        val storeFileResolved = rootProject.file(storeFilePath)
        if (storeFileResolved.exists()) {
            create("release") {
                storeFile = storeFileResolved
                storePassword = project.findProperty("RELEASE_STORE_PASSWORD") as String
                keyAlias = project.findProperty("RELEASE_KEY_ALIAS") as String
                keyPassword = project.findProperty("RELEASE_KEY_PASSWORD") as String
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfigs.findByName("release")?.let { signingConfig = it }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro", "proguard-python.pro")
        }
    }

    // 禁用 ABI 分割
    splits {
        abi {
            isEnable = false
        }
    }

    compileOptions {
        // 脱糖:minSdk 24 下 java.time / java.util.stream / java.nio.file 等 JDK 库 API 改写为 j$ 实现
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        // 2026-09-14:LOG.FILE_LOG 用 BuildConfig.DEBUG 控制落盘排查通道(release 关闭)
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    testOptions {
        // 单测跑在纯 JVM:未 mock 的 android API(如 android.util.Log)默认抛 "not mocked",
        // LOG 工具在 catch 分支打日志会踩到;置 true 后返回默认值,单测焦点保持在纯逻辑
        unitTests.isReturnDefaultValues = true
    }
}

// Kotlin jvmTarget 与 Java 21 编译等级对齐
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

// 指定 Room 的 Schema 导出位置（Room 3 走 KSP 通道）
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// APK 按 AVBox_<buildType>.apk 命名(替代 AGP 9 已移除的 applicationVariants 旧 API)
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("AVBox_${variant.buildType}.apk")
        }
    }
}

dependencies {
    api(fileTree("libs") { include("*.jar", "*.aar") })

    implementation(libs.nanohttpd)
    implementation(libs.cling.core)
    implementation(libs.cling.support)
    compileOnly(libs.cdi.api)
    compileOnly(libs.javax.inject)
    compileOnly(libs.javax.annotation.api)
    compileOnly(libs.javax.servlet.api)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.media)
    implementation(libs.okhttp)
    implementation(libs.okhttp.dnsoverhttps)
    // Room 3：仅支持 KSP 处理器，且必须搭配 SQLite driver
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.okio)
    implementation(libs.gson)
    implementation(libs.autosize)
    implementation(libs.xstream) {
        // 排除与 Android 平台冲突的 xmlpull/xpp3(平台自带 kxml2 实现,R8 亦要求排除)
        exclude(group = "xmlpull", module = "xmlpull")
        exclude(group = "xpp3", module = "xpp3_min")
    }
    implementation(libs.eventbus)
    // KV 存储:MMKV(mmap + protobuf,单进程不加密);Hawk/Conceal 已于 2026-09-13 随迁移代码一并移除
    implementation(libs.mmkv)
    implementation(libs.danmaku.flame.master)

    implementation(project(":player"))
    implementation(project(":quickjs"))
    implementation(project(":pyramid"))

    implementation(libs.okgo)
    implementation(libs.xx.permissions)
    implementation(libs.jsoup)
    implementation(libs.commons.io)
    implementation(libs.juniversalchardet)
    // zxing:动态加载的爬虫 jar 运行期需要 com.google.zxing.*(二维码),宿主必须提供。
    // 宿主源码无静态引用,禁止按"零引用"删除;keep 规则见 proguard-rules.pro
    implementation(libs.zxing.core)
    // sardine:订阅源 jar 里的 WebDAV 爬虫(com.github.catvod.spider.WebDAV)用它做
    implementation(libs.sardine) {
        // 传递依赖 simple-xml → xpp3:xpp3 与平台自带的 org.xmlpull.v1 同名,
        // R8 报 "Library class android.content.res.XmlResourceParser implements program class
        // org.xmlpull.v1.XmlPullParser" 直接失败(release 挂、debug 不跑 R8 所以看不出来);
        // XML 解析走平台自带实现即可,与上面 xstream 的 xmlpull/xpp3 排除同理
        exclude(group = "xpp3", module = "xpp3")
    }

    // Compose UI(avbox-mobile-ui-spec §2)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.materialkolor)
    // 液态玻璃导航栏:backdrop 本地 fork(见 libs/backdrop)+ capsule 连续曲率胶囊形状
    implementation(project(":libs:backdrop"))
    implementation(libs.capsule)

    // 脱糖运行时库(由本模块打进 APK;库模块各自声明同名依赖以启用自身代码的脱糖)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // KV 编解码单测(纯 JVM,不需要 Robolectric):集合/嵌套泛型语义对齐是本次迁移最大风险点
    testImplementation(libs.junit)
}
