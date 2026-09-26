package io.gutapk

import io.gutapk.core.apk.SetProblem
import io.gutapk.core.apk.SplitPart
import io.gutapk.core.apk.SplitSet
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The rules that decide whether a set is complete, on parts described by
// hand. Reading a real manifest is ApkTest's job.
class SplitSetTest {

    private fun part(
        split: String? = null,
        pkg: String = "com.example.game",
        code: Long = 7,
        requires: Boolean = false,
        required: List<String> = emptyList(),
        types: List<String> = emptyList(),
        unityData: Boolean = false,
        unityLib: Boolean = false,
    ) = SplitPart(Path.of("x.apk"), "", pkg, split, code, requires, required, types, unityData, unityLib)

    @Test
    fun bundletoolArchiveKeepsOnlyTheSplits() {
        val names = listOf("toc.pb", "splits/base-master.apk", "splits/base-arm64_v8a.apk", "standalones/standalone-arm64_v8a.apk")
        assertEquals(listOf("splits/base-master.apk", "splits/base-arm64_v8a.apk"), SplitSet.pick(names))
    }

    @Test
    fun flatArchiveKeepsEveryApk() {
        val names = listOf("manifest.json", "com.example.game.apk", "config.arm64_v8a.apk", "Android/obb/com.example.game/main.7.com.example.game.obb")
        assertEquals(listOf("com.example.game.apk", "config.arm64_v8a.apk"), SplitSet.pick(names))
    }

    @Test
    fun completeSetPasses() {
        val base = part(requires = true, required = listOf("base__abi"), unityData = true)
        val abi = part(split = "config.arm64_v8a", types = listOf("base__abi"), unityLib = true)
        assertNull(SplitSet.check(listOf(base, abi)))
    }

    @Test
    fun namesTheMissingSplitType() {
        val base = part(requires = true, required = listOf("base__abi", "base__density"))
        val abi = part(split = "config.arm64_v8a", types = listOf("base__abi"))
        assertEquals(SetProblem.SplitsMissing(listOf("base__density")), SplitSet.check(listOf(base, abi)))
    }

    @Test
    fun baseThatNeedsSplitsAlone() {
        assertEquals(SetProblem.SplitsMissing(emptyList()), SplitSet.check(listOf(part(requires = true))))
    }

    @Test
    fun unityDataWithoutItsLibrary() {
        val base = part(requires = true, unityData = true)
        val density = part(split = "config.xxhdpi")
        assertEquals(SetProblem.UnityLibMissing, SplitSet.check(listOf(base, density)))
    }

    @Test
    fun refusesSetsThatDoNotBelongTogether() {
        assertEquals(SetProblem.NoApk, SplitSet.check(emptyList()))
        assertEquals(SetProblem.NoBase, SplitSet.check(listOf(part(split = "config.arm64_v8a"))))
        assertEquals(SetProblem.SeveralBases, SplitSet.check(listOf(part(), part())))
        assertEquals(
            SetProblem.MixedPackages(listOf("com.example.game", "com.other")),
            SplitSet.check(listOf(part(), part(split = "config.en", pkg = "com.other"))),
        )
        assertEquals(SetProblem.MixedVersions, SplitSet.check(listOf(part(), part(split = "config.en", code = 8))))
        assertEquals(
            SetProblem.DuplicateSplit("config.en"),
            SplitSet.check(listOf(part(), part(split = "config.en"), part(split = "config.en"))),
        )
    }

    @Test
    fun onlyABaseThatNeedsNothingIsStandalone() {
        assertTrue(SplitSet.standalone(part()))
        assertFalse(SplitSet.standalone(part(requires = true)))
        assertFalse(SplitSet.standalone(part(required = listOf("base__abi"))))
        assertFalse(SplitSet.standalone(part(split = "config.en")))
    }
}
