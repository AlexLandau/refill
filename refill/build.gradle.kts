plugins {
    id("org.jetbrains.kotlin.jvm").version("1.3.70")
}

repositories {
    mavenCentral()
}

// TODO: Take advantage of Kotlin 1.4 Gradle plugin to avoid a standard library declaration here
dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
    testImplementation("org.awaitility:awaitility:3.1.6")
}
