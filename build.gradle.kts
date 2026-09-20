plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

group = "io.gutapk"
version = "0.1.3"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    // Deprecated accessor, kept on purpose. It pins material3 1.9.0, which is
    // the newest stable on Maven Central: the 1.11 and 1.12 lines exist only as
    // alphas on the dev repository. Declaring 1.12.0 by coordinates fails.
    implementation(compose.material3)
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
