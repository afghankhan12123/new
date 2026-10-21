plugins {
    id("videoeditor.android.application")
    id("videoeditor.compose")
    id("videoeditor.hilt")
}

val stage6TestAssets = tasks.register<Sync>("prepareStage6TestAssets") {
    from("../core/media/src/androidTest/assets") {
        include("transition_identity_*.mp4", "audio_sample_identity/*.m4a", "stage8/*.m4a")
    }
    into(layout.buildDirectory.dir("generated/stage6TestAssets"))
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("AndroidTestAssets") }.configureEach {
    dependsOn(stage6TestAssets)
}

android {
    namespace = "com.videoeditor.app"

    // Opt-in instrumentation of the real R8/resource-shrunk variant on test devices.
    // Normal release artifacts remain unsigned for production signing.
    if (providers.gradleProperty("videoeditor.instrumentRelease").orNull == "true") {
        testBuildType = "release"
        sourceSets.getByName("release").java.srcDir("src/debug/java")
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("debug")
        buildTypes.getByName("release").proguardFile("proguard-release-instrumentation.pro")
    }

    defaultConfig {
        testInstrumentationRunner = "com.videoeditor.app.BenchmarkFixtureRunner"
        testProguardFiles("proguard-android-test.pro")
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets.getByName("androidTest").assets.srcDir("../feature/editor/src/androidTest/assets")
    sourceSets.getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("generated/stage6TestAssets"))

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    androidTestImplementation(libs.kotlinx.serialization.json)
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:media"))
    implementation(project(":editing"))
    implementation(project(":feature:home"))
    implementation(project(":feature:projects"))
    implementation(project(":feature:mediaimport"))
    implementation(project(":feature:editor"))
    implementation(project(":feature:export"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.foundation)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.room.runtime)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}
