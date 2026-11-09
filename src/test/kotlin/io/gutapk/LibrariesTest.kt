package io.gutapk

import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import io.gutapk.core.offline.DexStringRewrite
import io.gutapk.tools.ChildFirstClassLoader
import io.gutapk.tools.Libraries
import io.gutapk.tools.Releases
import io.gutapk.tools.Tools
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// The libraries GutapK downloads as tools: the newest version a
// maven-metadata.xml lists, as Gradle reads one, and a class of GutapK loaded
// child-first beside the jars, as Flink loads a job's code.
// https://github.com/gradle/gradle/blob/v9.8.2/platforms/software/dependency-management/src/main/java/org/gradle/api/internal/artifacts/repositories/maven/MavenMetadataLoader.java#parseMavenMetadataInto
// https://github.com/apache/flink/blob/release-2.2.1/flink-core/src/main/java/org/apache/flink/util/ChildFirstClassLoader.java#loadClassWithoutExceptionHandling
// not ported: load and parseMavenMetadataInfo (the resource cache, the test reads text), the snapshot versioning (a release is never a snapshot)
class LibrariesTest {
    private fun metadata(vararg version: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <metadata>
          <groupId>com.google.guava</groupId>
          <artifactId>guava</artifactId>
          <versioning>
            <latest>${version.last()}</latest>
            <release>${version.last()}</release>
            <versions>
              ${version.joinToString("") { "<version>$it</version>" }}
            </versions>
            <lastUpdated>20260101000000</lastUpdated>
          </versioning>
        </metadata>
    """.trimIndent()

    @Test
    fun theNewestVersionMatchingThePackage() {
        val versions = metadata("33.9.0-jre", "33.10.0-android", "33.10.0-jre", "9999.0-empty-to-avoid-conflict-with-guava", "33.2.1-jre")
        // Newest by number, not by text or by the order listed.
        assertEquals("33.10.0-jre", Releases.parseMavenMetadataInto(versions, "[0-9]+(\\.[0-9]+)*-jre"))
        assertEquals("33.10.0-android", Releases.parseMavenMetadataInto(versions, "[0-9]+(\\.[0-9]+)*-android"))
        assertNull(Releases.parseMavenMetadataInto(versions, "[0-9]+(\\.[0-9]+)*"))
        assertEquals("1.0.3", Releases.parseMavenMetadataInto(metadata("1.0.1", "1.0.3", "1.0.2"), "[0-9]+(\\.[0-9]+)*"))
    }

    @Test
    fun aDoctypeIsRefused() {
        val xml = "<?xml version=\"1.0\"?><!DOCTYPE metadata [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><metadata><versioning><versions><version>&x;</version></versions></versioning></metadata>"
        assertTrue(runCatching { Releases.parseMavenMetadataInto(xml, ".*") }.isFailure)
    }

    @Test
    fun everyLibraryIsATool() {
        (Libraries.DEXLIB2 + Libraries.AIRCOMPRESSOR).forEach { assertNotNull(Tools.byId(it), it) }
    }

    @Test
    fun anOwnedClassLoadsBesideTheJars() {
        // The jars the test runs with, as the installed tools would be.
        fun jar(c: Class<*>) = Path.of(c.protectionDomain.codeSource.location.toURI())
        // Guava comes with dexlib2 at run time only.
        val jars = listOf(jar(DexBackedDexFile::class.java), jar(Class.forName("com.google.common.collect.ImmutableList")))
        val rewrite = Libraries.load(jars, "io.gutapk.core.offline.DexStrings", DexStringRewrite::class.java)
        val loader = rewrite.javaClass.classLoader
        assertTrue(loader is ChildFirstClassLoader)
        // dexlib2 is the jar's, the interface GutapK's own.
        assertSame(loader, loader.loadClass(DexBackedDexFile::class.java.name).classLoader)
        assertSame(DexStringRewrite::class.java, loader.loadClass(DexStringRewrite::class.java.name))
        assertSame(String::class.java, loader.loadClass("java.lang.String"))
    }
}
