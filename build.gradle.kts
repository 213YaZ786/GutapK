plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

group = "io.gutapk"
version = "0.1.137"

kotlin {
    jvmToolchain(21)
}

// The offline cache server GutapK injects into an APK is Java 8 source under
// src/dex, compiled against stubs of the Android API (src/dexstub, never
// shipped), turned into a dex by D8 and put on the resource path as
// io/gutapk/offline/server.dex. The dex is built, never committed.
// https://r8.googlesource.com/r8/+/refs/heads/9.5/src/main/java/com/android/tools/r8/D8.java#main
val d8Tool = configurations.create("d8Tool")

dependencies {
    d8Tool(libs.r8)
    testImplementation(libs.org.json)
    implementation(compose.desktop.currentOs)
    // Deprecated accessor, kept on purpose. It pins material3 1.9.0, which is
    // the newest stable on Maven Central: the 1.11 and 1.12 lines exist only as
    // alphas on the dev repository. Declaring 1.12.0 by coordinates fails.
    implementation(compose.material3)
    implementation(libs.apksig)
    // Built against, not carried: GutapK downloads their newest version as
    // tools (tools.tsv, source maven) and loads them with tools/Libraries.kt.
    compileOnly(libs.dexlib2)
    compileOnly(libs.aircompressor)
    testImplementation(libs.dexlib2)
    testImplementation(libs.aircompressor)
    implementation(libs.dbus.java.core)
    implementation(libs.dbus.java.transport)
    implementation(libs.materialkolor.utilities)
    implementation(libs.vico.compose.m3)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

// https://r8.googlesource.com/r8/+/refs/heads/9.5/src/main/java/com/android/tools/r8/D8.java#main
// https://github.com/Zennmn/AM-plus-plus/blob/main/examples/basic-plugin/build.gradle.kts
// https://github.com/Kifranei/AM-plus-plus-media-plugin/blob/main/media-plugin/build.gradle.kts
// D8.main refuses an empty command line and runs the compiler on the rest:
// the dex task does the same through a JavaExec. The stubs play the part of
// android.jar.
// Not ported: main, run, runForTesting, readApp, rewriteNonDexInputs (the compiler's own steps, inside the r8 jar the JavaExec runs)
// Not ported: classes, jars, references (the plugin's AAR handling, the server has no libraries)
// Not ported: pluginZip, printPluginVersion (GutapK ships the dex as a resource, not a zip)
val dexDirectory = layout.buildDirectory.dir("dex")

val androidClasses = tasks.register<JavaCompile>("androidClasses") {
    source(fileTree("src/dexstub/java"))
    classpath = files()
    destinationDirectory = dexDirectory.map { it.dir("android-classes") }
    options.release = 8
    options.compilerArgs.add("-Xlint:-options")
}

val androidJar = tasks.register<Jar>("androidJar") {
    from(androidClasses.flatMap { it.destinationDirectory })
    archiveFileName = "android.jar"
    destinationDirectory = dexDirectory.map { it.dir("jars") }
}

val javaClasses = tasks.register<JavaCompile>("serverClasses") {
    source(fileTree("src/dex/java"))
    classpath = files(androidClasses.flatMap { it.destinationDirectory })
    destinationDirectory = dexDirectory.map { it.dir("classes") }
    options.release = 8
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-options"))
}

val serverJar = tasks.register<Jar>("serverJar") {
    from(javaClasses.flatMap { it.destinationDirectory })
    archiveFileName = "server.jar"
    destinationDirectory = dexDirectory.map { it.dir("jars") }
}

val dex = tasks.register<JavaExec>("dex") {
    val destination = dexDirectory.map { it.dir("out") }
    val program = serverJar.flatMap { it.archiveFile }
    val androidSdk = androidJar.flatMap { it.archiveFile }
    // D8 wants java.lang in its library to desugar, like the JDK the classes were compiled against.
    val javaHome = javaToolchains.launcherFor(java.toolchain).map { it.metadata.installationPath.asFile.path }
    classpath = d8Tool
    mainClass = "com.android.tools.r8.D8"
    inputs.file(program)
    inputs.file(androidSdk)
    outputs.dir(destination)
    doFirst {
        val out = destination.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        if (!program.get().asFile.isFile) throw GradleException("Invalid invocation. The server jar is missing.")
        setArgs(
            listOf(
                "--release", "--min-api", "21",
                "--lib", androidSdk.get().asFile.path,
                "--lib", javaHome.get(),
                "--output", out.path,
                program.get().asFile.path,
            ),
        )
    }
}

tasks.processResources {
    from(dex) {
        include("classes.dex")
        rename { "server.dex" }
        into("io/gutapk/offline")
    }
}

dependencies {
    testImplementation(files(javaClasses.flatMap { it.destinationDirectory }))
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
            // keyring fails at runtime with NoClassDefFoundError. java.net.http
            // carries the HttpClient the server admin talks to.
            modules("java.instrument", "jdk.unsupported", "jdk.security.auth", "jdk.net", "java.net.http")
        }
    }
}
