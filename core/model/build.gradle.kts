plugins {
    id("videoeditor.android.library")
    id("videoeditor.serialization")
}

android {
    namespace = "com.videoeditor.core.model"
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
