package io.gutapk.core.sign

import com.android.apksig.ApkSigner
import com.android.apksig.KeyConfig
import io.gutapk.core.apk.SignatureInfo
import io.gutapk.core.apk.Signatures
import io.gutapk.job.JobEvent
import io.gutapk.job.JobSink
import io.gutapk.tools.CheckFailed
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

// JAR signing is the only scheme Android before 7.0 reads, and it is weaker.
// It is written only when the APK can still install on such a device.
private const val V2_FROM_SDK = 24

object ApkSigning {
    private const val LIBRARY_ALIGNMENT = 16384

    fun schemesFor(minSdk: Int?): List<String> =
        if (minSdk == null || minSdk < V2_FROM_SDK) listOf("v1", "v2", "v3") else listOf("v2", "v3")

    // The signed file for one key, next to the original in the package
    // folder. Signing twice with the same key replaces it.
    fun output(packageDir: Path, packageName: String, version: String?, choice: KeyChoice): Path {
        val v = version?.takeIf { it.isNotBlank() }?.let { "-" + safe(it) }.orEmpty()
        return packageDir.resolve("out").resolve(safe(packageName) + v + "-signed-" + choice.name.lowercase() + ".apk")
    }

    // A split set is signed into a folder, one APK per part.
    fun setOutput(packageDir: Path, packageName: String, version: String?, choice: KeyChoice): Path =
        output(packageDir, packageName, version, choice).let { it.resolveSibling(it.fileName.toString().removeSuffix(".apk")) }

    // apksig aligns every stored entry while it signs, 4 bytes, and native
    // libraries on 16 KB, the page size of Android 15 devices that use it,
    // which is also a multiple of 4 KB. No separate zipalign pass. The result
    // is verified before it replaces anything: a file that does not verify
    // never reaches the out folder.
    fun sign(input: Path, output: Path, key: SigningKey, minSdk: Int?, version: String, sink: JobSink): SignatureInfo {
        Files.createDirectories(output.parent)
        val part = output.resolveSibling(output.fileName.toString() + ".part")
        Files.deleteIfExists(part)
        try {
            sink.emit(JobEvent.Step("sign", 1, 2))
            val schemes = schemesFor(minSdk)
            val lineage = key.lineage
            val lineageNote = if (lineage == null) "" else ", lineage of ${lineage.size()} certificates"
            sink.emit(JobEvent.Line("signing ${input.fileName} with ${key.sha256}, schemes ${schemes.joinToString(" ")}$lineageNote"))
            // apksigner sign --lineage: the signers are the lineage's certificates,
            // oldest first. v1 and v2 are signed by signerConfigs[0] only, which
            // DefaultApkSignerEngine requires to be the lineage's oldest, the
            // newest goes to v3.1 from API 33. Both use the one private key.
            // https://android.googlesource.com/platform/tools/apksig/+/b741c26163bd17eb3cbe9f90d9cc4c2879dd3b70/src/apksigner/java/com/android/apksigner/ApkSignerTool.java#L344-L400
            // https://android.googlesource.com/platform/tools/apksig/+/b741c26163bd17eb3cbe9f90d9cc4c2879dd3b70/src/main/java/com/android/apksig/DefaultApkSignerEngine.java#L228-L330
            // not ported: the stamp, v4, verity and provider options, setMinSdkVersionForRotation (the default is kept, v3.0 carries the oldest signer)
            val signerConfigs = ArrayList<ApkSigner.SignerConfig>()
            if (lineage == null) {
                signerConfigs.add(ApkSigner.SignerConfig.Builder(key.name, KeyConfig.Jca(key.privateKey), listOf(key.certificate)).build())
            } else {
                val oldest = lineage.certificatesInLineage.first()
                signerConfigs.add(ApkSigner.SignerConfig.Builder(key.name, KeyConfig.Jca(key.privateKey), listOf(oldest)).build())
                signerConfigs.add(ApkSigner.SignerConfig.Builder(key.name + "-new", KeyConfig.Jca(key.privateKey), listOf(key.certificate)).build())
            }
            val builder = ApkSigner.Builder(signerConfigs)
                .setInputApk(input.toFile())
                .setOutputApk(part.toFile())
                .setV1SigningEnabled("v1" in schemes)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setCreatedBy("GutapK $version")
                .setLibraryPageAlignmentBytes(LIBRARY_ALIGNMENT)
            if (minSdk != null) builder.setMinSdkVersion(minSdk)
            if (lineage != null) builder.setSigningCertificateLineage(lineage)
            builder.build().sign()

            sink.emit(JobEvent.Step("verify", 2, 2))
            val check = Signatures.verify(part, minSdk)
            if (!check.verified) {
                throw CheckFailed("the signed APK does not verify: " + check.problems.take(3).joinToString(", "))
            }
            Files.move(part, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            sink.emit(JobEvent.Line("signed ${output.toAbsolutePath()}"))
            return check
        } finally {
            Files.deleteIfExists(part)
        }
    }

    private fun safe(s: String): String = s.map { c -> if (c.isLetterOrDigit() || c == '.' || c == '_') c else '-' }.joinToString("")
}
