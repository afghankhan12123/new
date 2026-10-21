plugins {
    id("videoeditor.android.library")
    id("videoeditor.hilt")
}

android {
    namespace = "com.videoeditor.editing"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
    api(project(":core:media"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
