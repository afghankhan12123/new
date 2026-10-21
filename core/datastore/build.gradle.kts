plugins {
    id("videoeditor.android.library")
    id("videoeditor.hilt")
}

android {
    namespace = "com.videoeditor.core.datastore"
}

dependencies {
    implementation(project(":core:model"))

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
