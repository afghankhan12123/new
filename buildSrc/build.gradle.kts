plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    // Android Gradle Plugin and Kotlin are on the build classpath so that
    // convention plugins can apply them by id without version conflicts.
    implementation("com.android.tools.build:gradle:8.7.3")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
    implementation("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.0.21")
    implementation("org.jetbrains.kotlin:kotlin-serialization:2.0.21")
    implementation("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.0.21-1.0.28")
    implementation("com.google.dagger:hilt-android-gradle-plugin:2.53.1")
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "videoeditor.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "videoeditor.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("compose") {
            id = "videoeditor.compose"
            implementationClass = "ComposeConventionPlugin"
        }
        register("hilt") {
            id = "videoeditor.hilt"
            implementationClass = "HiltConventionPlugin"
        }
        register("serialization") {
            id = "videoeditor.serialization"
            implementationClass = "SerializationConventionPlugin"
        }
        register("room") {
            id = "videoeditor.room"
            implementationClass = "RoomConventionPlugin"
        }
    }
}
