package io.gutapk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.android.apksig.SigningCertificateLineage
import io.gutapk.core.sign.Der
import io.gutapk.core.sign.KeyChoice
import io.gutapk.core.sign.OwnKey
import io.gutapk.core.sign.Subject
import io.gutapk.core.sign.TestKey
import io.gutapk.core.sign.fingerprint
import io.gutapk.job.Job
import io.gutapk.job.JobQueue
import io.gutapk.job.JobState
import io.gutapk.tools.RunLog
import java.nio.file.Files
import java.nio.file.Path
import java.security.cert.X509Certificate
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val KEYGEN_JOB = "keygen"
const val KEY_BACKUP_JOB = "keybackup"
const val KEY_RESTORE_JOB = "keyrestore"
const val KEY_IDENTITY_JOB = "keyidentity"

// Long enough to resist guessing if the file leaks, short enough to type.
private const val MIN_PASSWORD = 10

// The model of Android Studio's validity spinner (25, 1, 1000, 1) with our
// numbers: 30 years as before, and no key is made for more than a century.
private const val DEFAULT_VALIDITY = 30L
private const val MIN_VALIDITY = 1L
private const val MAX_VALIDITY = 100L

// The fields of Android Studio's new key form, in its order, the required
// name first. Create stays disabled until findNonEmptyCertificateField holds.
// https://raw.githubusercontent.com/JetBrains/android/4cc14cb12fe7d22d8cc07821014d5e3dacd2f87a/project-system-gradle/src/org/jetbrains/android/exportSignedPackage/NewKeyForm.java#getValidity
// https://raw.githubusercontent.com/JetBrains/android/4cc14cb12fe7d22d8cc07821014d5e3dacd2f87a/project-system-gradle/src/org/jetbrains/android/exportSignedPackage/NewKeyForm.java#findNonEmptyCertificateField
// https://raw.githubusercontent.com/JetBrains/android/4cc14cb12fe7d22d8cc07821014d5e3dacd2f87a/project-system-gradle/src/org/jetbrains/android/exportSignedPackage/NewKeyForm.java#getDName
// not ported: buildDName (the trim and the empty fields are Der.getDName's)
private class NewKeyForm {
    var myFirstAndLastNameField by mutableStateOf("")
        private set
    var myOrganizationUnitField by mutableStateOf("")
        private set
    var myOrganizationField by mutableStateOf("")
        private set
    var myCityField by mutableStateOf("")
        private set
    var myStateOrProvinceField by mutableStateOf("")
        private set
    var myCountryCodeField by mutableStateOf("")
        private set
    var myValiditySpinner by mutableStateOf(DEFAULT_VALIDITY.toString())
        private set

    // Each field takes its text as getDName reads it, the input past an RFC
    // 5280 bound is refused and the previous text stays.
    // https://raw.githubusercontent.com/JetBrains/android/4cc14cb12fe7d22d8cc07821014d5e3dacd2f87a/project-system-gradle/src/org/jetbrains/android/exportSignedPackage/NewKeyForm.java#getDName
    // The JVM names differ from the property setters' so they do not clash.
    @JvmName("putMyFirstAndLastNameField")
    fun setMyFirstAndLastNameField(text: String) {
        if (Der.length(text) <= Der.MAX_CN) myFirstAndLastNameField = text
    }

    @JvmName("putMyOrganizationUnitField")
    fun setMyOrganizationUnitField(text: String) {
        if (Der.length(text) <= Der.MAX_OU) myOrganizationUnitField = text
    }

    @JvmName("putMyOrganizationField")
    fun setMyOrganizationField(text: String) {
        if (Der.length(text) <= Der.MAX_O) myOrganizationField = text
    }

    @JvmName("putMyCityField")
    fun setMyCityField(text: String) {
        if (Der.length(text) <= Der.MAX_L) myCityField = text
    }

    @JvmName("putMyStateOrProvinceField")
    fun setMyStateOrProvinceField(text: String) {
        if (Der.length(text) <= Der.MAX_ST) myStateOrProvinceField = text
    }

    // Letters only, in capitals: the country code is two letters A-Z.
    @JvmName("putMyCountryCodeField")
    fun setMyCountryCodeField(text: String) {
        myCountryCodeField = text.filter { it in 'a'..'z' || it in 'A'..'Z' }.uppercase().take(Der.COUNTRY)
    }

    // Digits only, as many as the largest validity has.
    @JvmName("putMyValiditySpinner")
    fun setMyValiditySpinner(text: String) {
        myValiditySpinner = text.filter { it in '0'..'9' }.take(MAX_VALIDITY.toString().length)
    }

    // Null outside the model's range, which also covers an empty field.
    fun getValidity(): Long? = myValiditySpinner.toLongOrNull()?.takeIf { it in MIN_VALIDITY..MAX_VALIDITY }

    // One letter is no country: the code is two letters or nothing.
    fun isCountryCodeIncomplete(): Boolean = myCountryCodeField.length == Der.COUNTRY - 1

    fun findNonEmptyCertificateField(): Boolean =
        myFirstAndLastNameField.trim().isNotEmpty() && !isCountryCodeIncomplete() && getValidity() != null

    fun getDName() = Subject(
        myFirstAndLastNameField,
        myOrganizationUnitField,
        myOrganizationField,
        myCityField,
        myStateOrProvinceField,
        myCountryCodeField,
    )
}

@Composable
fun keyLabel(choice: KeyChoice?): String = when (choice) {
    KeyChoice.TEST -> t("key_test")
    KeyChoice.OWN -> t("key_own")
    null -> t("sign_key_none")
}

// The certificate fingerprint of a choice, when it can be known without
// asking the keyring. Null for an own key not created yet.
fun keyFingerprint(choice: KeyChoice?): String? = when (choice) {
    KeyChoice.TEST -> TestKey.CERT_SHA256
    KeyChoice.OWN -> OwnKey.readCertificate()?.let { fingerprint(it) }
    null -> null
}

// One entry point for choosing a key, from Settings and from the sign
// dialog alike. Choosing the own key the first time creates it, after a
// dialog that says what is created, where, and what losing it means.
@Composable
fun KeyChooser(current: KeyChoice?, onChosen: (KeyChoice) -> Unit, onDismiss: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val view = currentJobView()
    LaunchedEffect(view) {
        val job = started
        if (job != null && view != null && JobQueue.current.value === job) {
            when (view.state) {
                JobState.DONE -> {
                    started = null
                    creating = false
                    onChosen(KeyChoice.OWN)
                }
                JobState.FAILED -> {
                    started = null
                    error = view.message
                }
                JobState.CANCELLED -> {
                    started = null
                }
                else -> {}
            }
        }
    }

    val failure = error
    when {
        failure != null -> AlertDialog(
            onDismissRequest = {
                error = null
                creating = false
            },
            title = { Text(t("create_failed")) },
            text = { Text(failure, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    error = null
                    creating = false
                }) { Text(t("close")) }
            },
        )
        started != null -> AlertDialog(
            // Closing it would not stop the job, so it is not closable.
            onDismissRequest = {},
            title = { Text(t("creating")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(t("creating_note"), style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {},
        )
        creating -> CreateKeyDialog(
            onCreate = { subject, years ->
                val job = JobQueue.start(KEYGEN_JOB) { job ->
                    val cert = OwnKey.create(subject, years, job)
                    job.result = fingerprint(cert)
                }
                if (job != null) started = job
            },
            onDismiss = { creating = false },
        )
        else -> KeyDialog(
            current = current,
            onPick = { picked ->
                if (picked == KeyChoice.OWN && !OwnKey.exists()) {
                    creating = true
                } else {
                    onChosen(picked)
                }
            },
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun KeyDialog(current: KeyChoice?, onPick: (KeyChoice) -> Unit, onDismiss: () -> Unit) {
    val own = OwnKey.readCertificate()?.let { t("key_own_ready", fingerprint(it).take(23)) } ?: t("key_own_new")
    ChoiceDialog<KeyChoice?>(
        title = t("key_title"),
        options = listOf(
            KeyChoice.TEST to t("key_test") + "\n" + t("key_test_d"),
            KeyChoice.OWN to t("key_own") + "\n" + own,
        ),
        current = current,
        onPick = { picked -> if (picked != null) onPick(picked) },
        onDismiss = onDismiss,
    )
}

@Composable
private fun CreateKeyDialog(onCreate: (Subject, Long) -> Unit, onDismiss: () -> Unit) {
    // Fields in the order of the new key form, the validity read as getValidity does.
    // https://raw.githubusercontent.com/JetBrains/android/4cc14cb12fe7d22d8cc07821014d5e3dacd2f87a/project-system-gradle/src/org/jetbrains/android/exportSignedPackage/NewKeyForm.java#getValidity
    // https://raw.githubusercontent.com/JetBrains/android/4cc14cb12fe7d22d8cc07821014d5e3dacd2f87a/project-system-gradle/src/org/jetbrains/android/exportSignedPackage/NewKeyForm.java#getDName
    val form = remember { NewKeyForm() }
    val validity = form.getValidity()
    val subject = form.getDName()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("create_title")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Fact(t("dl_what"), t("create_what_v"))
                // https://raw.githubusercontent.com/JetBrains/android/4cc14cb12fe7d22d8cc07821014d5e3dacd2f87a/project-system-gradle/src/org/jetbrains/android/exportSignedPackage/NewKeyForm.java#getDName
                NewKeyFields(form)
                SelectionContainer { Fact(t("set_key_file"), OwnKey.keystore.toString()) }
                Fact(t("create_password"), t("create_password_v"))
                Text(t("create_backup"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = { if (validity != null) onCreate(subject, validity) }, enabled = form.findNonEmptyCertificateField()) {
                Text(t("create_go"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("cancel")) } },
    )
}

// The field block of the new key form, shared by the creation and the new
// identity so both show the same fields in the same order.
// https://raw.githubusercontent.com/JetBrains/android/4cc14cb12fe7d22d8cc07821014d5e3dacd2f87a/project-system-gradle/src/org/jetbrains/android/exportSignedPackage/NewKeyForm.java#getDName
// not ported: getDName, getValidity (already in NewKeyForm above, this only shows its fields)
@Composable
private fun NewKeyFields(form: NewKeyForm) {
    CertificateField(form.myFirstAndLastNameField, t("create_name"), false, null) { form.setMyFirstAndLastNameField(it) }
    CertificateField(form.myOrganizationUnitField, t("create_unit"), false, null) { form.setMyOrganizationUnitField(it) }
    CertificateField(form.myOrganizationField, t("create_org"), false, null) { form.setMyOrganizationField(it) }
    CertificateField(form.myCityField, t("create_city"), false, null) { form.setMyCityField(it) }
    CertificateField(form.myStateOrProvinceField, t("create_state"), false, null) { form.setMyStateOrProvinceField(it) }
    CertificateField(form.myCountryCodeField, t("create_country"), form.isCountryCodeIncomplete(), t("create_country_hint")) { form.setMyCountryCodeField(it) }
    CertificateField(form.myValiditySpinner, t("create_validity"), form.getValidity() == null, null) { form.setMyValiditySpinner(it) }
}

// The new key form's fields filled through its setters with the certificate
// in use, the names getDName reads back and the validity getValidity reads.
// https://raw.githubusercontent.com/JetBrains/android/4cc14cb12fe7d22d8cc07821014d5e3dacd2f87a/project-system-gradle/src/org/jetbrains/android/exportSignedPackage/NewKeyForm.java#getDName
// not ported: getDName, getValidity, buildDName (already in NewKeyForm above, this only fills its fields)
private fun NewKeyForm.setFrom(myCertificate: X509Certificate) {
    val dname = Der.getSubject(myCertificate)
    val validity = getValidity(
        MIN_VALIDITY..MAX_VALIDITY,
        myCertificate.notBefore.toInstant().atZone(ZoneOffset.UTC),
        myCertificate.notAfter.toInstant().atZone(ZoneOffset.UTC),
    )
    setMyFirstAndLastNameField(dname.cn)
    setMyOrganizationUnitField(dname.ou)
    setMyOrganizationField(dname.o)
    setMyCityField(dname.l)
    setMyStateOrProvinceField(dname.st)
    setMyCountryCodeField(dname.c)
    setMyValiditySpinner(validity.toString())
}

// The whole years between notBefore and notAfter in UTC, inside the model of
// the validity spinner.
// https://raw.githubusercontent.com/JetBrains/android/4cc14cb12fe7d22d8cc07821014d5e3dacd2f87a/project-system-gradle/src/org/jetbrains/android/exportSignedPackage/NewKeyForm.java#getValidity
private fun getValidity(model: ClosedRange<Long>, notBefore: ZonedDateTime, notAfter: ZonedDateTime): Long =
    ChronoUnit.YEARS.between(notBefore, notAfter).coerceIn(model)

// Apply waits for a certificate field that differs from the current one,
// each trimmed as findNonEmptyCertificateField reads a field.
// https://raw.githubusercontent.com/JetBrains/android/4cc14cb12fe7d22d8cc07821014d5e3dacd2f87a/project-system-gradle/src/org/jetbrains/android/exportSignedPackage/NewKeyForm.java#findNonEmptyCertificateField
private fun findChangedCertificateField(dname: Subject, current: Subject): Boolean =
    dname.cn.trim() != current.cn.trim() || dname.ou.trim() != current.ou.trim() || dname.o.trim() != current.o.trim() ||
        dname.l.trim() != current.l.trim() || dname.st.trim() != current.st.trim() || dname.c.trim() != current.c.trim()

// The certificate of the chosen key, test or own, null when there is none or
// it cannot be read. Reads files: call it from IO only.
fun identityCertificate(choice: KeyChoice?): X509Certificate? = when (choice) {
    // load refuses a bundled file that is not AOSP's, the row stays empty then.
    KeyChoice.TEST -> runCatching { TestKey.load().certificate }.getOrNull()
    KeyChoice.OWN -> OwnKey.readCertificate()
    null -> null
}

// Whether a certificate is the user's own GutapK key: the current one, or a
// past one of the key's lineage, as Android counts a past certificate of a
// lineage as the same signer. A lineage that cannot be read is not own, as
// OwnKey.usableLineage ignores it. Reads files: call it from IO only.
// https://android.googlesource.com/platform/tools/apksig/+/b741c26163bd17eb3cbe9f90d9cc4c2879dd3b70/src/main/java/com/android/apksig/SigningCertificateLineage.java#readFromFile
// https://android.googlesource.com/platform/tools/apksig/+/b741c26163bd17eb3cbe9f90d9cc4c2879dd3b70/src/main/java/com/android/apksig/SigningCertificateLineage.java#isCertificateInLineage
// https://android.googlesource.com/platform/tools/apksig/+/b741c26163bd17eb3cbe9f90d9cc4c2879dd3b70/src/apksigner/java/com/android/apksigner/ApkSignerTool.java#getLineageFromInputFile
// not ported: readFromFile, isCertificateInLineage (called as they are)
// not ported: getLineageFromInputFile's APK branch and its magic check (the file is GutapK's own lineage file, written by apksig)
fun isOwnCertificate(cert: X509Certificate): Boolean {
    if (!OwnKey.exists()) return false
    if (OwnKey.readCertificate()?.let { fingerprint(it) == fingerprint(cert) } == true) return true
    if (!Files.isRegularFile(OwnKey.lineage)) return false
    return runCatching { SigningCertificateLineage.readFromFile(OwnKey.lineage.toFile()).isCertificateInLineage(cert) }.getOrDefault(false)
}

// The certificate in use is read off the UI thread, the form exists once it
// is known: an empty list is a certificate that cannot be read.
// https://raw.githubusercontent.com/JetBrains/android/4cc14cb12fe7d22d8cc07821014d5e3dacd2f87a/project-system-gradle/src/org/jetbrains/android/exportSignedPackage/NewKeyForm.java#getDName
// https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/runtime/runtime/src/commonMain/kotlin/androidx/compose/runtime/ProduceState.kt#produceState
// not ported: getValidity, buildDName, findNonEmptyCertificateField (already in NewKeyForm above)
// not ported: produceState (called as it is)
@Composable
fun IdentityDialog(choice: KeyChoice, onDismiss: () -> Unit) {
    val myCertificate by produceState<List<X509Certificate>?>(null, choice) {
        value = withContext(Dispatchers.IO) { listOfNotNull(identityCertificate(choice)) }
    }
    myCertificate?.let {
        when (choice) {
            KeyChoice.TEST -> TestIdentity(it.firstOrNull(), onDismiss)
            KeyChoice.OWN -> IdentityForm(it.firstOrNull(), onDismiss)
        }
    }
}

// The test key's certificate, read only, with a line saying it carries no
// identity of the user.
@Composable
private fun TestIdentity(myCertificate: X509Certificate?, onDismiss: () -> Unit) {
    CertificateDialog(t("key_test"), myCertificate, emptyList(), t("identity_test_note"), onDismiss)
}

// A certificate, read only: its six names as the new key form shows them (the
// subject as one field when Der does not know a name, an arbitrary APK can
// carry any), the dates and the fingerprint, then one field per fact and an
// optional line. Close is the only button, nothing can be applied.
// https://raw.githubusercontent.com/JetBrains/android/4cc14cb12fe7d22d8cc07821014d5e3dacd2f87a/project-system-gradle/src/org/jetbrains/android/exportSignedPackage/NewKeyForm.java#getDName
// https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/AlertDialog.kt#AlertDialog
// not ported: getValidity, buildDName, findNonEmptyCertificateField (already in NewKeyForm above)
// not ported: AlertDialog (called as it is)
// not ported: dname (the names come from Der.getSubject)
@Composable
fun CertificateDialog(
    title: String,
    certificate: X509Certificate?,
    facts: List<Pair<String, String>>,
    note: String?,
    onDismiss: () -> Unit,
) {
    val names = certificate?.let { runCatching { Der.getSubject(it) }.getOrNull() }
    val subjectLabel = t("ov_signer")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (certificate != null) {
                    if (names != null) {
                        CertificateField(names.cn, t("create_name"), false, null, readOnly = true) {}
                        CertificateField(names.ou, t("create_unit"), false, null, readOnly = true) {}
                        CertificateField(names.o, t("create_org"), false, null, readOnly = true) {}
                        CertificateField(names.l, t("create_city"), false, null, readOnly = true) {}
                        CertificateField(names.st, t("create_state"), false, null, readOnly = true) {}
                        CertificateField(names.c, t("create_country"), false, null, readOnly = true) {}
                    } else {
                        CertificateField(certificate.subjectX500Principal.name, subjectLabel, false, null, readOnly = true) {}
                    }
                    inflateCertificateView(certificate)
                }
                facts.forEach { (label, value) -> CertificateField(value, label, false, null, readOnly = true) {} }
                if (note != null) Text(note, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t("close")) } },
    )
}

// What Android's certificate view shows besides the names: issued on,
// expires on and the SHA-256 fingerprint, read only so they can be selected
// and copied.
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/net/http/SslCertificate.java#inflateCertificateView
// not ported: getDigest, fingerprint (io.gutapk.core.sign.fingerprint already gives the SHA-256 in colon hex)
// not ported: getSerialNumber, getIssuedBy, SHA-1 (a self-signed key has its issuer equal to its subject, the serial and SHA-1 tell the user nothing more)
// not ported: getIssuedTo, DName, getCName, getOName, getUName (Der.getSubject already reads the names, NewKeyFields shows them)
// not ported: saveState, restoreState (nothing is kept between two openings of the dialog)
// not ported: getValidNotBefore, getValidNotAfter, toString, parseDate, formatDate, cloneDate (date strings and copies, java.time needs none)
// not ported: getX509Certificate (the certificate is the parameter)
@Composable
private fun inflateCertificateView(certificate: X509Certificate) {
    val lang = LocalLang.current
    CertificateField(formatCertificateDate(lang, getValidNotBeforeDate(certificate)), t("identity_issued_on"), false, null, readOnly = true) {}
    CertificateField(formatCertificateDate(lang, getValidNotAfterDate(certificate)), t("identity_expires_on"), false, null, readOnly = true) {}
    CertificateField(fingerprint(certificate), t("signed_signer"), false, null, readOnly = true) {}
}

// The medium date of the language in use, in UTC as the validity is, the
// empty string without a date.
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/net/http/SslCertificate.java#formatCertificateDate
private fun formatCertificateDate(lang: Lang, certificateDate: Date?): String {
    if (certificateDate == null) {
        return ""
    }
    return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.forLanguageTag(lang.code))
        .format(certificateDate.toInstant().atZone(ZoneOffset.UTC))
}

// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/net/http/SslCertificate.java#getValidNotBeforeDate
private fun getValidNotBeforeDate(certificate: X509Certificate): Date? = certificate.notBefore

// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/net/http/SslCertificate.java#getValidNotAfterDate
private fun getValidNotAfterDate(certificate: X509Certificate): Date? = certificate.notAfter

// The new key form's fields filled with the current ones, same key kept.
// Apply stays disabled until a field changed, the job reissues the certificate
// and closes the dialog, a failure is shown as the backup shows it.
// https://raw.githubusercontent.com/JetBrains/android/4cc14cb12fe7d22d8cc07821014d5e3dacd2f87a/project-system-gradle/src/org/jetbrains/android/exportSignedPackage/NewKeyForm.java#getDName
// https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/AlertDialog.kt#AlertDialog
// not ported: getValidity, buildDName, findNonEmptyCertificateField (already in NewKeyForm above)
// not ported: AlertDialog (called as it is, the dialogs below are its content)
// not ported: dname (the name is read from the form by getDName above)
@Composable
private fun IdentityForm(myCertificate: X509Certificate?, onDismiss: () -> Unit) {
    val form = remember(myCertificate) { NewKeyForm().apply { if (myCertificate != null) setFrom(myCertificate) } }
    // What the form held once filled, what a change is measured against.
    val current = remember(form) { form.getDName() to form.getValidity() }
    var started by remember { mutableStateOf<Job?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    WatchJob(started) { state, message ->
        started = null
        if (state == JobState.DONE) onDismiss() else if (state == JobState.FAILED) failure = message
    }
    val validity = form.getValidity()
    val dname = form.getDName()
    val failed = failure
    when {
        failed != null -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(t("key_failed")) },
            text = { Text(failed) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(t("close")) } },
        )
        started != null -> WorkingDialog(t("identity_title"))
        else -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(t("identity_title")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                    NewKeyFields(form)
                    if (myCertificate != null) inflateCertificateView(myCertificate)
                    Text(t("identity_note"), style = MaterialTheme.typography.bodyMedium)
                    Text(t("identity_backup"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = form.findNonEmptyCertificateField() && (findChangedCertificateField(dname, current.first) || validity != current.second),
                    onClick = {
                        if (validity != null) {
                            started = JobQueue.start(KEY_IDENTITY_JOB) { job ->
                                job.result = fingerprint(OwnKey.reissue(dname, validity, job))
                            }
                        }
                    },
                ) { Text(t("identity_go")) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(t("cancel")) } },
        )
    }
}

// The file manager of the desktop, through xdg-open, on the folder holding
// the file. A desktop without it only loses the shortcut.
fun showInFolder(file: Path) {
    val dir = (if (Files.isDirectory(file)) file else file.parent) ?: return
    runCatching {
        ProcessBuilder("xdg-open", dir.toString())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
    }.onFailure { RunLog.line("xdg-open failed: ${it.message}") }
}

// Calls onEnd once the given job has finished, whatever the outcome.
@Composable
private fun WatchJob(started: Job?, onEnd: (JobState, String) -> Unit) {
    val view = currentJobView()
    val end by rememberUpdatedState(onEnd)
    LaunchedEffect(view) {
        if (started != null && view != null && JobQueue.current.value === started) {
            when (view.state) {
                JobState.DONE, JobState.FAILED, JobState.CANCELLED -> end(view.state, view.message)
                else -> {}
            }
        }
    }
}

@Composable
private fun WorkingDialog(title: String) {
    AlertDialog(
        // Closing it would not stop the job, so it is not closable.
        onDismissRequest = {},
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(t("key_working"), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {},
    )
}

// PasswordField below without its transformation: a certificate field is
// shown as typed. The parameters are those of material3's OutlinedTextField.
// https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/OutlinedTextField.kt#OutlinedTextField
@Composable
private fun CertificateField(
    value: String,
    label: String,
    isError: Boolean,
    supportingText: String?,
    readOnly: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        readOnly = readOnly,
        label = { Text(label) },
        // A read only value never holds a newline, so a long one wraps.
        singleLine = !readOnly,
        isError = isError,
        supportingText = supportingText?.let { hint -> { Text(hint) } },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PasswordField(value: String, label: String, error: String?, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { e -> { Text(e) } },
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
}

// The own key copied into a file the user keeps, under a password of their
// own. What, where and the risk are said before anything is written.
@Composable
fun KeyBackupDialog(onDismiss: () -> Unit) {
    var folder by remember { mutableStateOf<Path?>(null) }
    var password by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var started by remember { mutableStateOf<Job?>(null) }
    var outcome by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    // Set by the job once the backup is written, so the body reads no file.
    var lineage by remember { mutableStateOf<Path?>(null) }
    WatchJob(started) { state, message ->
        started = null
        if (state != JobState.CANCELLED) outcome = (state == JobState.DONE) to message
    }
    val chooseTitle = t("key_backup_choose")
    val name = "gutapk-signing-key-" + LocalDate.now() + ".p12"
    val done = outcome
    when {
        done != null -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(t(if (done.first) "key_backup_title" else "key_failed")) },
            text = {
                val saved = if (done.first) t("key_backup_done", done.second) else done.second
                val history = lineage
                Text(if (done.first && history != null) saved + "\n\n" + t("key_backup_lineage", history.toString()) else saved, style = MaterialTheme.typography.bodyMedium)
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text(t("close")) } },
            dismissButton = {
                if (done.first) TextButton(onClick = { showInFolder(Path.of(done.second)) }) { Text(t("key_show")) }
            },
        )
        started != null -> WorkingDialog(t("key_backup_title"))
        else -> {
            val short = password.isNotEmpty() && password.length < MIN_PASSWORD
            val differ = again.isNotEmpty() && again != password
            val target = folder?.resolve(name)
            val ready = target != null && password.length >= MIN_PASSWORD && password == again
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(t("key_backup_title")) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Fact(t("dl_what"), t("key_backup_what"))
                        SelectionContainer { Fact(t("key_backup_file"), target?.toString() ?: t("key_backup_none")) }
                        TextButton(onClick = {
                            Chooser.folder(chooseTitle, System.getProperty("user.home")) { p -> if (p != null) folder = p }
                        }) { Text(chooseTitle) }
                        PasswordField(password, t("create_password"), if (short) t("key_password_short") else null) { password = it }
                        PasswordField(again, t("key_password_again"), if (differ) t("key_password_differ") else null) { again = it }
                        Text(t("key_backup_warn"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = ready,
                        onClick = {
                            val pw = password.toCharArray()
                            if (target != null) {
                                started = JobQueue.start(KEY_BACKUP_JOB) { job ->
                                    try {
                                        OwnKey.backup(target, pw, job)
                                        lineage = OwnKey.backupLineage(target).takeIf { Files.exists(it) }
                                        job.result = target.toString()
                                    } finally {
                                        pw.fill('\u0000')
                                    }
                                }
                            }
                        },
                    ) { Text(t("key_backup_go")) }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text(t("cancel")) } },
            )
        }
    }
}

// A backup made GutapK's own key, only while it has none. onRestored gets
// the fingerprint so the caller can select the key.
@Composable
fun KeyRestoreDialog(onRestored: () -> Unit, onDismiss: () -> Unit) {
    var file by remember { mutableStateOf<Path?>(null) }
    var password by remember { mutableStateOf("") }
    var started by remember { mutableStateOf<Job?>(null) }
    var outcome by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    WatchJob(started) { state, message ->
        started = null
        if (state != JobState.CANCELLED) outcome = (state == JobState.DONE) to message
        if (state == JobState.DONE) onRestored()
    }
    val chooseTitle = t("key_restore_choose")
    val filter = t("key_restore_filter")
    val done = outcome
    when {
        done != null -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(t(if (done.first) "key_restore_title" else "key_failed")) },
            text = { Text(if (done.first) t("key_restore_done", done.second) else done.second, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(t("close")) } },
        )
        started != null -> WorkingDialog(t("key_restore_title"))
        else -> {
            val source = file
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(t("key_restore_title")) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Fact(t("dl_what"), t("key_restore_what"))
                        SelectionContainer { Fact(t("key_restore_file"), source?.toString() ?: t("key_backup_none")) }
                        TextButton(onClick = {
                            Chooser.fileOf(chooseTitle, filter, listOf("p12", "pfx", "jks", "keystore")) { p -> if (p != null) file = p }
                        }) { Text(chooseTitle) }
                        PasswordField(password, t("create_password"), null) { password = it }
                        SelectionContainer { Fact(t("set_key_file"), OwnKey.keystore.toString()) }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = source != null && password.isNotEmpty(),
                        onClick = {
                            val pw = password.toCharArray()
                            if (source != null) {
                                started = JobQueue.start(KEY_RESTORE_JOB) { job ->
                                    try {
                                        job.result = fingerprint(OwnKey.restore(source, pw, job))
                                    } finally {
                                        pw.fill('\u0000')
                                    }
                                }
                            }
                        },
                    ) { Text(t("key_restore_go")) }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text(t("cancel")) } },
            )
        }
    }
}
