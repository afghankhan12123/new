plugins {
    id("videoeditor.android.library")
    id("videoeditor.hilt")
    id("videoeditor.room")
}

android {
    namespace = "com.videoeditor.core.database"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
