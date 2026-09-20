plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

group = "io.gutapk"
version = "0.1.1"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    // Declared by coordinates and not through the compose extension: the
    // compose.material3 accessor is deprecated and pins 1.9.0, which drags a
    // second Compose version into the graph and breaks resolution.
    implementation(libs.compose.material3)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "io.gutapk.MainKt"
        nativeDistributions {
            packageName = "GutapK"
            packageVersion = project.version.toString()
            modules("java.instrument", "jdk.unsupported")
        }
    }
}
