plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

group = "io.gutapk"
version = "0.1.26"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    // Deprecated accessor, kept on purpose. It pins material3 1.9.0, which is
    // the newest stable on Maven Central: the 1.11 and 1.12 lines exist only as
    // alphas on the dev repository. Declaring 1.12.0 by coordinates fails.
    implementation(compose.material3)
    implementation(libs.apksig)
    implementation(libs.dbus.java.core)
    implementation(libs.dbus.java.transport)
    implementation(libs.materialkolor.utilities)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "io.gutapk.MainKt"
        // Lands in the jpackage .cfg as a java-option, which gutapk-launch
        // passes through, so the running app knows its own version.
        // The add-opens lets Main set WM_CLASS, which the dock uses to find
        // the desktop entry. One token each, gutapk-launch splits on spaces.
        jvmArgs += listOf(
            "-Dgutapk.version=${project.version}",
            "--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED",
        )
        nativeDistributions {
            packageName = "GutapK"
            packageVersion = project.version.toString()
            // jdk.security.auth gives dbus-java the user id for the bus
            // handshake, jdk.net its socket options. Without them the
            // keyring fails at runtime with NoClassDefFoundError.
            modules("java.instrument", "jdk.unsupported", "jdk.security.auth", "jdk.net")
        }
    }
}
