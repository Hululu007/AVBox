plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.chaquopy)
}

android {
    namespace = "com.undcover.freedom.pyramid"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        ndk {
            abiFilters += setOf("arm64-v8a")
        }
    }

    buildTypes {
        debug {
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        // 脱糖:minSdk 24 下 java.time / java.util.stream / java.nio.file 等 JDK 库 API 改写为 j$ 实现
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    // 脱糖运行时库(实际打包在 :app,此处声明以启用本模块代码的脱糖)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}

chaquopy {
    defaultConfig {
        version = "3.10"
        // 构建机的 Python 位置因机而异,不把某一台的绝对路径写进仓库:
        // 优先 -PbuildPython / CHAQUOPY_BUILD_PYTHON,其次 Windows 标准安装位置,都没有则交给 Chaquopy 自行探测
        val explicitBuildPython = (project.findProperty("buildPython") as String?)
            ?: System.getenv("CHAQUOPY_BUILD_PYTHON")
        val defaultBuildPython = File(
            System.getProperty("user.home"),
            "AppData/Local/Programs/Python/Python310/python.exe",
        ).absolutePath
        listOfNotNull(explicitBuildPython, defaultBuildPython)
            .firstOrNull { file(it).exists() }
            ?.let { buildPython(it) }
        pip {
            options("-i", "https://mirrors.aliyun.com/pypi/simple/")
            options("--find-links", file("wheels/chaquopy-prebuilt").absolutePath)
            install("lxml")
            install("ujson")
            install("pyquery==2.0.2")
            install("requests")
            // jsonpath 0.54 只有 py2 源码包、无 wheel,本地移植后以 wheel 安装(见 wheels/README.md)
            install(file("wheels/jsonpath-0.54-py3-none-any.whl").absolutePath)
            install("cachetools")
            install("pycryptodome")
            install("beautifulsoup4")
        }
    }
    sourceSets {
        getByName("main") {
            setSrcDirs(listOf("src/python"))
        }
    }
}
