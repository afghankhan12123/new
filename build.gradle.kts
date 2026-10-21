// Root build file.
//
// All Gradle plugins (AGP, Kotlin, Compose, KSP, Hilt, serialization) are placed
// on the build classpath by buildSrc so that convention plugins can apply them
// by id. Declaring them here as well would cause a classpath version conflict,
// so this plugins block is intentionally empty.
