package ro.cristianniculae.scanner

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private val Blue = Color(0xFF1565C0)
private val Orange = Color(0xFFFF8F00)

class MainActivity : ComponentActivity() {

    private lateinit var store: ScanStore
    private lateinit var drive: DriveUploader
    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    private val docs = mutableStateListOf<ScanDoc>()
    private var busy by mutableStateOf<String?>(null)
    private var pendingPages by mutableStateOf<List<ByteArray>?>(null)
    private var ocrEnabled by mutableStateOf(true)
    private var imageMode by mutableStateOf(ImageMode.COLOR)
    private var driveConnected by mutableStateOf(false)
    private var uploading = false

    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val result = GmsDocumentScanningResult.fromActivityResultIntent(res.data) ?: return@registerForActivityResult
        val uris = result.pages?.map { it.imageUri } ?: return@registerForActivityResult
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri -> contentResolver.openInputStream(uri)?.use { it.readBytes() } }
            }
            if (bytes.isNotEmpty()) pendingPages = bytes
        }
    }

    private val authLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode != Activity.RESULT_OK) {
            toast("Conectarea la Google Drive a fost anulată")
            return@registerForActivityResult
        }
        try {
            val r = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(res.data)
            if (r.accessToken != null) {
                markDriveConnected(true)
                uploadPending(interactive = false)
            }
        } catch (e: Exception) {
            toast("Eroare Google Drive: ${e.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ScanStore(this)
        drive = DriveUploader(this)
        docs.addAll(store.load().sortedByDescending { it.created })
        ocrEnabled = prefs.getBoolean("ocr", true)
        imageMode = ImageMode.from(prefs.getString("mode", null))
        driveConnected = prefs.getBoolean("driveConnected", false)

        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Blue, secondary = Orange)) {
                Screen()
            }
        }
        if (driveConnected) uploadPending(interactive = false)
    }

    // ---------- acțiuni ----------

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun markDriveConnected(v: Boolean) {
        driveConnected = v
        prefs.edit().putBoolean("driveConnected", v).apply()
    }

    private fun startScan() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(100)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(this)
            .addOnSuccessListener { scanLauncher.launch(IntentSenderRequest.Builder(it).build()) }
            .addOnFailureListener { toast("Scannerul nu a putut porni: ${it.message}") }
    }

    private fun saveScan(name: String, pages: List<ByteArray>) {
        lifecycleScope.launch {
            busy = "Prelucrez paginile…"
            val mode = imageMode
            try {
                val doc = withContext(Dispatchers.Default) {
                    val inputs = pages.mapNotNull { raw ->
                        val jpeg = try { Enhancer.process(raw, mode) } catch (_: Throwable) { raw }
                        val info = PdfBuilder.jpegInfo(jpeg) ?: return@mapNotNull null
                        val lines = if (ocrEnabled) {
                            try { Ocr.recognize(jpeg, info.width, info.height) } catch (_: Exception) { emptyList() }
                        } else emptyList()
                        PageInput(jpeg, info, lines)
                    }
                    val id = UUID.randomUUID().toString()
                    val d = ScanDoc(id, name, "$id.pdf", System.currentTimeMillis(), inputs.size, "pending")
                    PdfBuilder.write(inputs, store.file(d))
                    d
                }
                docs.add(0, doc)
                store.save(docs)
            } catch (e: Exception) {
                toast("Eroare la salvare: ${e.message}")
            } finally {
                busy = null
            }
            if (driveConnected) uploadPending(interactive = false)
        }
    }

    private fun updateDoc(doc: ScanDoc) {
        val i = docs.indexOfFirst { it.id == doc.id }
        if (i >= 0) docs[i] = doc
        store.save(docs)
    }

    private fun uploadPending(interactive: Boolean) {
        if (uploading) return
        uploading = true
        lifecycleScope.launch {
            try {
                val token = getToken(interactive) ?: return@launch
                if (!driveConnected) markDriveConnected(true)
                for (doc in docs.filter { it.drive != "done" }) {
                    try {
                        drive.upload(token, store.file(doc), doc.name)
                        updateDoc(doc.copy(drive = "done"))
                    } catch (e: DriveAuthException) {
                        toast("Reconectează Google Drive din setări")
                        break
                    } catch (e: Exception) {
                        updateDoc(doc.copy(drive = "error"))
                    }
                }
            } finally {
                uploading = false
            }
        }
    }

    /** Returnează token-ul Drive sau null; dacă e nevoie de confirmarea utilizatorului, o cere doar în modul interactiv. */
    private suspend fun getToken(interactive: Boolean): String? {
        return try {
            val req = AuthorizationRequest.builder()
                .setRequestedScopes(listOf(Scope(DriveUploader.SCOPE)))
                .build()
            val r = Identity.getAuthorizationClient(this).authorize(req).await()
            if (r.hasResolution()) {
                if (interactive) {
                    r.pendingIntent?.let { authLauncher.launch(IntentSenderRequest.Builder(it.intentSender).build()) }
                }
                null
            } else r.accessToken
        } catch (e: Exception) {
            if (interactive) toast("Eroare Google Drive: ${e.message}")
            null
        }
    }

    private fun uriFor(doc: ScanDoc) =
        FileProvider.getUriForFile(this, "$packageName.files", store.file(doc))

    private fun openDoc(doc: ScanDoc) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(doc), "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(i) } catch (_: Exception) { toast("Nu există aplicație pentru PDF") }
    }

    private fun shareDoc(doc: ScanDoc) {
        val i = Intent(Intent.ACTION_SEND).setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uriFor(doc))
            .putExtra(Intent.EXTRA_SUBJECT, doc.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, "Trimite ${doc.name}"))
    }

    private fun deleteDoc(doc: ScanDoc) {
        store.file(doc).delete()
        docs.removeAll { it.id == doc.id }
        store.save(docs)
    }

    // ---------- interfață ----------

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Screen() {
        var showSettings by remember { mutableStateOf(false) }
        var toDelete by remember { mutableStateOf<ScanDoc?>(null) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Scanner") },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Blue, titleContentColor = Color.White, actionIconContentColor = Color.White),
                    actions = {
                        IconButton(onClick = { showSettings = true }) { Icon(Icons.Filled.Settings, "Setări") }
                    },
                )
            },
            floatingActionButton = {
                if (busy == null) ExtendedFloatingActionButton(
                    onClick = { startScan() },
                    containerColor = Orange, contentColor = Color.White,
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text("Scanează") },
                )
            },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                if (docs.isEmpty()) {
                    Text("Niciun document încă.\nApasă „Scanează”.", Modifier.align(Alignment.Center), color = Color.Gray)
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                        items(docs, key = { it.id }) { doc ->
                            DocRow(doc, onDelete = { toDelete = doc })
                            HorizontalDivider()
                        }
                    }
                }
                busy?.let {
                    Card(Modifier.align(Alignment.Center).padding(24.dp)) {
                        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(28.dp))
                            Spacer(Modifier.width(16.dp))
                            Text(it)
                        }
                    }
                }
            }
        }

        pendingPages?.let { pages -> NameDialog(pages.size, onCancel = { pendingPages = null }) { name ->
            pendingPages = null
            saveScan(name, pages)
        } }

        toDelete?.let { doc ->
            AlertDialog(
                onDismissRequest = { toDelete = null },
                title = { Text("Ștergi „${doc.name}”?") },
                text = { Text(if (doc.drive == "done") "Se șterge doar de pe telefon. Copia din Google Drive rămâne." else "Documentul nu a fost încă încărcat în Google Drive și va fi pierdut.") },
                confirmButton = { TextButton(onClick = { deleteDoc(doc); toDelete = null }) { Text("Șterge") } },
                dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Anulează") } },
            )
        }

        if (showSettings) SettingsDialog { showSettings = false }
    }

    @Composable
    private fun DocRow(doc: ScanDoc, onDelete: () -> Unit) {
        var menu by remember { mutableStateOf(false) }
        val date = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ro")).format(Date(doc.created))
        Row(
            Modifier.fillMaxWidth().clickable { openDoc(doc) }.padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(doc.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val (label, color) = when {
                    !driveConnected -> "doar pe telefon" to Color.Gray
                    doc.drive == "done" -> "✓ în Drive" to Color(0xFF2E7D32)
                    doc.drive == "error" -> "eroare Drive" to Color(0xFFC62828)
                    else -> "se încarcă…" to Orange
                }
                Text("$date · ${doc.pages} pag. · $label", style = MaterialTheme.typography.bodySmall, color = color)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Opțiuni") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Trimite") }, onClick = { menu = false; shareDoc(doc) })
                    if (driveConnected && doc.drive != "done") {
                        DropdownMenuItem(text = { Text("Încarcă în Drive") }, onClick = { menu = false; uploadPending(interactive = true) })
                    }
                    DropdownMenuItem(text = { Text("Șterge") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }

    @Composable
    private fun NameDialog(pages: Int, onCancel: () -> Unit, onSave: (String) -> Unit) {
        val default = remember { "Scan " + SimpleDateFormat("yyyy-MM-dd HH.mm", Locale("ro")).format(Date()) }
        var name by remember { mutableStateOf(default) }
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Salvează ($pages pag.)") },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Nume document") }, singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val clean = name.replace(Regex("[\\\\/:*?\"<>|]"), "-").trim().ifEmpty { default }
                    onSave(clean)
                }) { Text("Salvează") }
            },
            dismissButton = { TextButton(onClick = onCancel) { Text("Renunță") } },
        )
    }

    @Composable
    private fun SettingsDialog(onClose: () -> Unit) {
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Setări") },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Text căutabil (OCR)")
                            Text("PDF în care poți căuta și copia text", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                        Switch(checked = ocrEnabled, onCheckedChange = {
                            ocrEnabled = it
                            prefs.edit().putBoolean("ocr", it).apply()
                        })
                    }
                    Spacer(Modifier.height(20.dp))
                    Text("Aspect imagine")
                    ImageMode.entries.forEach { m ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                imageMode = m
                                prefs.edit().putString("mode", m.key).apply()
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = imageMode == m, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(m.label)
                                Text(m.hint, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Text("Google Drive")
                    Text(
                        if (driveConnected) "Conectat · folder „${DriveUploader.FOLDER_NAME}”" else "Neconectat",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (driveConnected) {
                        OutlinedButton(onClick = { markDriveConnected(false) }) { Text("Deconectează") }
                    } else {
                        Button(onClick = { onClose(); uploadPending(interactive = true) }) { Text("Conectează Google Drive") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onClose) { Text("Închide") } },
        )
    }
}
