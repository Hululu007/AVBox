import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 本地 fork 自 io.github.kyant0:backdrop 2.0.1(Apache-2.0,见 NOTICE),
// 目的:高光/阴影/内阴影层"参数未变跳过 record"等内部性能优化 + HighlightStyle.Ambient 加 angle 参数
// + AmbientHighlightShaderString 背光侧改全透明;上游升级需手工合并。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.kyant.backdrop"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation("androidx.compose.ui:ui-graphics")
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.annotation)
    compileOnly("org.jetbrains:annotations:26.1.0")
    // lens 的圆角 SDF 形状(上游 backdrop 的运行时依赖)
    implementation(libs.kyant.shapes)
}
