package io.gutapk

import io.gutapk.registry.Feature
import io.gutapk.registry.Registry
import io.gutapk.registry.Source
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private class Stub(
    override val id: String,
    override val sources: Set<Source>,
) : Feature {
    override val label: String get() = id
}

class RegistryTest {

    @BeforeTest
    fun reset() = Registry.clear()

    @AfterTest
    fun clean() = Registry.clear()

    @Test
    fun filtersBySource() {
        Registry.register(Stub("a", setOf(Source.APK)))
        Registry.register(Stub("b", setOf(Source.APK, Source.DEVICE)))
        Registry.register(Stub("c", setOf(Source.REPO)))

        assertEquals(listOf("a", "b"), Registry.forSource(Source.APK).map { it.id })
        assertEquals(listOf("b"), Registry.forSource(Source.DEVICE).map { it.id })
        assertEquals(listOf("c"), Registry.forSource(Source.REPO).map { it.id })
    }

    @Test
    fun rejectsDuplicateId() {
        Registry.register(Stub("a", setOf(Source.APK)))
        assertFailsWith<IllegalArgumentException> {
            Registry.register(Stub("a", setOf(Source.REPO)))
        }
    }
}
