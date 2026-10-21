plugins {
    id("videoeditor.android.library")
    id("videoeditor.hilt")
}

android {
    namespace = "com.videoeditor.core.media"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))

    api(libs.media3.exoplayer)
    api(libs.media3.ui)
    api(libs.media3.transformer)
    implementation(libs.media3.muxer)
    api(libs.media3.effect)
    api(libs.media3.common)

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(project(":editing"))
    androidTestImplementation(libs.kotlinx.serialization.json)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
