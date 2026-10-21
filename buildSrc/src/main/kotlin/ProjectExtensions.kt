import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

fun Project.androidLibrary() {
    pluginManager.apply("com.android.library")
    pluginManager.apply("org.jetbrains.kotlin.android")

    configure<LibraryExtension> {
        compileSdk = AndroidConfig.compileSdk
        defaultConfig {
            minSdk = AndroidConfig.minSdk
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            consumerProguardFiles("consumer-rules.pro")
        }
        // Libraries have standalone test APKs; they do not inherit the app target.
        testOptions.targetSdk = AndroidConfig.targetSdk
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }
    configureMedia3CompositorPatch()
    configureKotlinJvmTarget()
}

fun Project.androidApplication() {
    pluginManager.apply("com.android.application")
    pluginManager.apply("org.jetbrains.kotlin.android")

    configure<ApplicationExtension> {
        namespace = AndroidConfig.namespace
        compileSdk = AndroidConfig.compileSdk
        defaultConfig {
            applicationId = AndroidConfig.namespace
            minSdk = AndroidConfig.minSdk
            targetSdk = AndroidConfig.targetSdk
            versionCode = AndroidConfig.versionCode
            versionName = AndroidConfig.versionName
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
        buildTypes {
            release {
                isMinifyEnabled = true
                isShrinkResources = true
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro",
                )
            }
            debug {
                isMinifyEnabled = false
            }
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }
    configureMedia3CompositorPatch()
    configureKotlinJvmTarget()
}

fun Project.configureKotlinJvmTarget() {
    tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java).configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}
