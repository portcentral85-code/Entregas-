package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.ViewGroup
import android.webkit.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import androidx.compose.animation.core.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.graphics.RectF
import android.util.Log
import androidx.compose.ui.platform.LocalDensity
import androidx.core.content.FileProvider
import com.example.ui.theme.MyApplicationTheme
import com.example.data.SiceRepository
import com.example.data.WebData
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import java.io.File
import java.io.FileWriter

fun findActivity(context: Context): Activity? {
    var ctx = context
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

class MainActivity : ComponentActivity() {

    companion object {
        const val CAMERA_PERMISSION_CODE = 101
        var pendingPermissionRequest: PermissionRequest? = null
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            pendingPermissionRequest?.let { req ->
                val resources = req.resources ?: emptyArray()
                req.grant(resources.map { it.toString() }.toTypedArray())
            }
        } else {
            pendingPermissionRequest?.deny()
            Toast.makeText(this, "Permissão de câmera é necessária para escanear QR Codes.", Toast.LENGTH_LONG).show()
        }
        pendingPermissionRequest = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Request Camera Permission on startup to avoid any user interruption during QR scanning
        requestCameraPermission()

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                ) {
                    SiceWebViewContainer()
                }
            }
        }
    }

    private fun requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Suppress("DEPRECATION")
@Composable
fun SiceWebViewContainer() {
    var fileChooserCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    
    // Scanner States
    var isScanning by remember { mutableStateOf(false) }
    var scanTarget by remember { mutableStateOf("entrada") }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    
    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            var results: Array<Uri>? = null
            if (data?.data != null) {
                results = arrayOf(data.data!!)
            } else if (data?.clipData != null) {
                val clipData = data.clipData!!
                results = Array(clipData.itemCount) { i ->
                    clipData.getItemAt(i).uri
                }
            }
            fileChooserCallback?.onReceiveValue(results)
        } else {
            fileChooserCallback?.onReceiveValue(null)
        }
        fileChooserCallback = null
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                val activityContext = findActivity(context) ?: context
                
                // Pre-create WebView cache directories to prevent Chromium opendir Errors in logcat
                try {
                    val cacheDir = activityContext.cacheDir
                    if (cacheDir != null) {
                        val jsDir = java.io.File(cacheDir, "WebView/Default/HTTP Cache/Code Cache/js")
                        if (!jsDir.exists()) {
                            jsDir.mkdirs()
                        }
                        val wasmDir = java.io.File(cacheDir, "WebView/Default/HTTP Cache/Code Cache/wasm")
                        if (!wasmDir.exists()) {
                            wasmDir.mkdirs()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                
                WebView(activityContext).apply {
                    webViewInstance = this
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    
                    // Configure persistent DOM databases & hardware compatibility settings
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        allowFileAccess = true
                        allowContentAccess = true
                        allowFileAccessFromFileURLs = true
                        allowUniversalAccessFromFileURLs = true
                        mediaPlaybackRequiresUserGesture = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        
                        // Support viewport meta tags & auto scalability
                        useWideViewPort = true
                        loadWithOverviewMode = true
                    }
                    
                    // Set custom WebChromeClient to handle HTML5 getUserMedia camera permissions on standard WebView
                    webChromeClient = object : WebChromeClient() {
                        override fun onShowFileChooser(
                            webView: WebView?,
                            filePathCallback: ValueCallback<Array<Uri>>?,
                            fileChooserParams: FileChooserParams?
                        ): Boolean {
                            fileChooserCallback?.onReceiveValue(null)
                            fileChooserCallback = filePathCallback
                            
                            val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
                            try {
                                filePickerLauncher.launch(intent)
                            } catch (e: Exception) {
                                fileChooserCallback = null
                                return false
                            }
                            return true
                        }
                        
                        override fun onJsAlert(
                            view: WebView?,
                            url: String?,
                            message: String?,
                            result: android.webkit.JsResult?
                        ): Boolean {
                            val activity = findActivity(context)
                            if (activity != null) {
                                android.app.AlertDialog.Builder(activity)
                                    .setTitle("Aviso")
                                    .setMessage(message)
                                    .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                                    .setCancelable(false)
                                    .create()
                                    .show()
                                return true
                            }
                            return super.onJsAlert(view, url, message, result)
                        }

                        override fun onJsConfirm(
                            view: WebView?,
                            url: String?,
                            message: String?,
                            result: android.webkit.JsResult?
                        ): Boolean {
                            val activity = findActivity(context)
                            if (activity != null) {
                                android.app.AlertDialog.Builder(activity)
                                    .setTitle("Confirmação")
                                    .setMessage(message)
                                    .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                                    .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
                                    .setCancelable(false)
                                    .create()
                                    .show()
                                return true
                            }
                            return super.onJsConfirm(view, url, message, result)
                        }

                        override fun onJsPrompt(
                            view: WebView?,
                            url: String?,
                            message: String?,
                            defaultValue: String?,
                            result: android.webkit.JsPromptResult?
                        ): Boolean {
                            val activity = findActivity(context)
                            if (activity != null) {
                                val input = android.widget.EditText(activity)
                                input.setText(defaultValue)
                                android.app.AlertDialog.Builder(activity)
                                    .setTitle("Entrada")
                                    .setMessage(message)
                                    .setView(input)
                                    .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm(input.text.toString()) }
                                    .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
                                    .setCancelable(false)
                                    .create()
                                    .show()
                                return true
                            }
                            return super.onJsPrompt(view, url, message, defaultValue, result)
                        }

                        override fun onPermissionRequest(request: PermissionRequest) {
                            val resources = request.resources ?: emptyArray()
                            var containsCamera = false
                            for (resource in resources) {
                                if (PermissionRequest.RESOURCE_VIDEO_CAPTURE == resource) {
                                    containsCamera = true
                                    break
                                }
                            }
                            if (containsCamera) {
                                val activity = findActivity(context)
                                if (activity != null) {
                                    if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                        activity.runOnUiThread {
                                            request.grant(resources)
                                        }
                                    } else {
                                        MainActivity.pendingPermissionRequest = request
                                        ActivityCompat.requestPermissions(
                                            activity,
                                            arrayOf(Manifest.permission.CAMERA),
                                            MainActivity.CAMERA_PERMISSION_CODE
                                        )
                                    }
                                } else {
                                    request.deny()
                                }
                            } else {
                                request.grant(resources)
                            }
                        }
                    }
                    
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val url = request?.url?.toString() ?: return false
                            if (url.startsWith("http://") || url.startsWith("https://")) {
                                return false // allow online CDN resources load standardly
                            }
                            return super.shouldOverrideUrlLoading(view, request)
                        }
                    }
                    
                    // Add bridges to support modern local actions natively
                    val activity = findActivity(context) ?: (context as Activity)
                    addJavascriptInterface(AndroidShareInterface(context), "AndroidShare")
                    addJavascriptInterface(AndroidPrintInterface(context, this), "AndroidPrint")
                    addJavascriptInterface(CameraPermissionInterface(activity), "CameraPermissionLauncher")
                    addJavascriptInterface(AndroidDBInterface(context), "AndroidDB")
                    addJavascriptInterface(AndroidScannerInterface(activity) { target ->
                        scanTarget = target
                        isScanning = true
                    }, "AndroidScanner")
                    addJavascriptInterface(AndroidGeminiInterface(activity, this), "AndroidGemini")
                    
                    // Load local compiled assets page
                    clearCache(true)
                    loadUrl("file:///android_asset/index.html")
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (isScanning) {
            NativeScannerView(
                onBarcodeScanned = { barcode ->
                    isScanning = false
                    webViewInstance?.post {
                        val cleanBarcode = barcode.replace("\'", "\\'")
                        if (scanTarget == "entrada") {
                            webViewInstance?.loadUrl("javascript:(function() { document.getElementById('barcode-input').value = '$cleanBarcode'; processBarcode('$cleanBarcode'); })()")
                        } else {
                            webViewInstance?.loadUrl("javascript:(function() { document.getElementById('barcode-baixa-input').value = '$cleanBarcode'; processarValidacaoQR('$cleanBarcode'); })()")
                        }
                    }
                },
                onClose = {
                    isScanning = false
                    webViewInstance?.post {
                        if (scanTarget == "entrada") {
                            webViewInstance?.loadUrl("javascript:desativarCamera()")
                        } else {
                            webViewInstance?.loadUrl("javascript:desativarCameraBaixa()")
                        }
                    }
                }
            )
        }
    }
}

class AndroidShareInterface(private val context: Context) {
    @JavascriptInterface
    fun shareText(text: String, filename: String) {
        val activity = findActivity(context)
        activity?.runOnUiThread {
            try {
                val cacheFile = File(context.cacheDir, filename)
                FileWriter(cacheFile).use { writer ->
                    writer.write(text)
                }
                
                val uri: Uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    cacheFile
                )
                
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Relatório Geral de Entregas")
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                
                val chooser = Intent.createChooser(shareIntent, "Compartilhar Relatório")
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            } catch (e: Exception) {
                Toast.makeText(context, "Erro ao exportar: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    @JavascriptInterface
    fun shareImageBase64(base64String: String, filename: String) {
        val activity = findActivity(context)
        activity?.runOnUiThread {
            try {
                // Determine format
                val mimeType = "image/png"
                val cleanedBase64 = base64String.substringAfter("base64,")
                val decodedBytes = android.util.Base64.decode(cleanedBase64, android.util.Base64.DEFAULT)
                
                val cacheFile = File(context.cacheDir, filename)
                java.io.FileOutputStream(cacheFile).use { fos ->
                    fos.write(decodedBytes)
                }
                
                val uri: Uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    cacheFile
                )
                
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                
                val chooser = Intent.createChooser(shareIntent, "Compartilhar Imagem via WhatsApp")
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            } catch (e: Exception) {
                Toast.makeText(context, "Erro ao compartilhar imagem: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    @JavascriptInterface
    fun shareAppApk() {
        val activity = findActivity(context)
        activity?.runOnUiThread {
            try {
                val apkPath = context.applicationInfo.sourceDir
                val apkFile = File(apkPath)
                val destinationFile = File(context.cacheDir, "Controle_Entregas.apk")
                apkFile.copyTo(destinationFile, overwrite = true)
                
                val uri: Uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    destinationFile
                )
                
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/vnd.android.package-archive"
                    putExtra(Intent.EXTRA_SUBJECT, "Instalador Controle de Entregas")
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                
                val chooser = Intent.createChooser(shareIntent, "Compartilhar Aplicativo (APK)")
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            } catch (e: Exception) {
                Toast.makeText(context, "Erro ao compartilhar aplicativo: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

class AndroidPrintInterface(private val context: Context, private val webView: WebView) {
    @JavascriptInterface
    fun print() {
        val activity = findActivity(context)
        activity?.runOnUiThread {
            try {
                val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
                val jobName = "CondEntregas Tag Física"
                val printAdapter = webView.createPrintDocumentAdapter(jobName)
                printManager.print(jobName, printAdapter, PrintAttributes.Builder().build())
            } catch (e: Exception) {
                Toast.makeText(context, "Erro de impressão: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

class CameraPermissionInterface(private val activity: Activity) {
    @JavascriptInterface
    fun requestPermission() {
        activity.runOnUiThread {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.CAMERA), 101)
            }
        }
    }

    @JavascriptInterface
    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
}

class AndroidDBInterface(
    private val context: Context
) {
    private val app = context.applicationContext as SiceApplication
    private val repository: SiceRepository
        get() = app.repository
    private val backupFile = File(context.filesDir, "entregas_backup_v16.json")

    @JavascriptInterface
    fun save(json: String) {
        try {
            runBlocking(Dispatchers.IO) {
                repository.insertWebData(com.example.data.WebData(keyName = "sice_db_v16", jsonValue = json))
                
                try {
                    val jObj = org.json.JSONObject(json)
                    
                    // Sync Moradores
                    if (jObj.has("moradores")) {
                        repository.deleteAllMoradores()
                        val arr = jObj.getJSONArray("moradores")
                        for (i in 0 until arr.length()) {
                            val item = arr.getJSONObject(i)
                            repository.insertMorador(com.example.data.Morador(
                                nome = item.optString("nome", ""),
                                bloco = item.optString("bloco", ""),
                                apto = item.optString("apto", "")
                            ))
                        }
                    }
                    
                    // Sync Porteiros
                    if (jObj.has("porteiros")) {
                        repository.deleteAllPorteiros()
                        val arr = jObj.getJSONArray("porteiros")
                        for (i in 0 until arr.length()) {
                            val item = arr.getJSONObject(i)
                            repository.insertPorteiro(com.example.data.Porteiro(
                                nome = item.optString("nome", ""),
                                turno = item.optString("turno", ""),
                                bloco = item.optString("bloco", "")
                            ))
                        }
                    }
                    
                    // Sync Entregadores
                    if (jObj.has("entregadores")) {
                        repository.deleteAllEntregadores()
                        val arr = jObj.getJSONArray("entregadores")
                        for (i in 0 until arr.length()) {
                            val item = arr.getJSONObject(i)
                            repository.insertEntregador(com.example.data.Entregador(
                                nome = item.optString("nome", ""),
                                empresa = item.optString("empresa", "")
                            ))
                        }
                    }
                    
                    // Sync Encomendas
                    if (jObj.has("encomendas")) {
                        repository.deleteAllEncomendas()
                        val arr = jObj.getJSONArray("encomendas")
                        for (i in 0 until arr.length()) {
                            val item = arr.getJSONObject(i)
                            repository.insertEncomenda(com.example.data.Encomenda(
                                id = item.optInt("id", 0),
                                rastreio = item.optString("rastreio", ""),
                                plataforma = item.optString("plataforma", ""),
                                bloco = item.optString("bloco", ""),
                                apto = item.optString("apto", ""),
                                morador = item.optString("morador", ""),
                                entregador = item.optString("entregador", ""),
                                status = item.optString("status", ""),
                                dataEntrada = item.optString("dataEntrada", ""),
                                horaEntrada = item.optString("horaEntrada", ""),
                                dataEntrega = item.optString("dataEntrega", ""),
                                horaEntrega = item.optString("horaEntrega", ""),
                                porteiroEntrega = item.optString("porteiroEntrega", ""),
                                assinaturaRaw = item.optString("assinaturaRaw", "")
                            ))
                        }
                    }
                    
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            java.io.FileWriter(backupFile).use { writer ->
                writer.write(json)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @JavascriptInterface
    fun load(): String {
        var data: String? = null
        try {
            runBlocking(Dispatchers.IO) {
                data = repository.getWebData("sice_db_v16")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (data.isNullOrBlank()) {
            if (backupFile.exists()) {
                try {
                    data = backupFile.readText()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        return data ?: ""
    }

    @JavascriptInterface
    fun exportOriginalDatabase() {
        val activity = findActivity(context)
        activity?.runOnUiThread {
            try {
                runBlocking(Dispatchers.IO) {
                    try {
                        val siceDb = app.database
                        siceDb.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(FULL)")
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                val dbFile = context.getDatabasePath("entregas.db")
                if (!dbFile.exists()) {
                    Toast.makeText(context, "Erro: Banco de dados não existe no armazenamento local.", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }

                val cacheBackupFile = File(context.cacheDir, "backup_banco.db")
                dbFile.copyTo(cacheBackupFile, overwrite = true)

                val uri: Uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    cacheBackupFile
                )

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_SUBJECT, "Backup Original (.db)")
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                val chooser = Intent.createChooser(shareIntent, "Salvar Backup (.db)")
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            } catch (e: Exception) {
                Toast.makeText(context, "Erro ao exportar banco de dados original: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    @JavascriptInterface
    fun importOriginalDatabase(base64DataUrl: String): Boolean {
        try {
            val pureBase64 = if (base64DataUrl.contains("base64,")) {
                base64DataUrl.substringAfter("base64,")
            } else {
                base64DataUrl
            }
            val dbBytes = android.util.Base64.decode(pureBase64, android.util.Base64.DEFAULT)
            
            val sqliteSignature = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
            if (dbBytes.size < 16) {
                return false
            }
            val match = dbBytes.take(16).toByteArray().contentEquals(sqliteSignature)
            if (!match) {
                return false
            }

            app.resetDatabaseAndRepository()

            val dbPath = context.getDatabasePath("entregas.db")
            val dbWalPath = context.getDatabasePath("entregas.db-wal")
            val dbShmPath = context.getDatabasePath("entregas.db-shm")

            dbPath.parentFile?.mkdirs()
            java.io.FileOutputStream(dbPath).use { fos ->
                fos.write(dbBytes)
            }

            if (dbWalPath.exists()) dbWalPath.delete()
            if (dbShmPath.exists()) dbShmPath.delete()

            app.resetDatabaseAndRepository()

            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }
}

class AndroidScannerInterface(
    private val activity: Activity,
    private val onStartScan: (String) -> Unit
) {
    @JavascriptInterface
    fun startScanner(target: String) {
        activity.runOnUiThread {
            onStartScan(target)
        }
    }
}

class AndroidGeminiInterface(
    private val activity: Activity,
    private val webView: WebView
) {
    private val client = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.IO)

    @JavascriptInterface
    fun identifyPlatform(barcode: String, callbackName: String) {
        scope.launch {
            try {
                val apiKey = BuildConfig.GEMINI_API_KEY
                val json = JSONObject().apply {
                    put("contents", org.json.JSONArray().put(JSONObject().apply {
                        put("parts", org.json.JSONArray().put(JSONObject().apply {
                            put("text", "Dada a transportadora/logística baseada apenas neste código de rastreamento de uma encomenda e na sua experiência, identifique de qual plataforma de entrega esse código com maior probabilidade pertence (como Mercado Livre, Amazon, Shopee, Correios, Jadlog, Loggi, Shein, AliExpress, Magazine Luiza, etc). Retorne SOMENTE o nome da plataforma, sem ponto final, sem frases. Se não souber, retorne 'Outra'. Código de rastreio: $barcode")
                        }))
                    }))
                    put("generationConfig", JSONObject().apply {
                        put("temperature", 0.1)
                    })
                }

                val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey")
                    .post(body!!)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                
                var platformName = "Outra"
                if (response.isSuccessful && responseBody.isNotEmpty()) {
                    val root = JSONObject(responseBody)
                    val candidates = root.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val firstCandidate = candidates.getJSONObject(0)
                        val content = firstCandidate.optJSONObject("content")
                        val parts = content?.optJSONArray("parts")
                        if (parts != null && parts.length() > 0) {
                            val text = parts.getJSONObject(0).optString("text")
                            if (!text.isNullOrBlank()) {
                                platformName = text.trim().replace("\n", "").replace("'", "")
                            }
                        }
                    }
                }
                
                activity.runOnUiThread {
                    webView.evaluateJavascript("javascript:${callbackName}('$platformName')", null)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                activity.runOnUiThread {
                    webView.evaluateJavascript("javascript:${callbackName}('Outra')", null)
                }
            }
        }
    }

    @JavascriptInterface
    fun verifyFace(targetBase64: String, porteirosJson: String, callbackName: String) {
        scope.launch {
            try {
                val apiKey = BuildConfig.GEMINI_API_KEY
                
                val cleanTarget = targetBase64.substringAfter("base64,")
                val porteiros = org.json.JSONArray(porteirosJson)
                val partsArray = org.json.JSONArray()
                
                partsArray.put(JSONObject().apply {
                    put("text", "I will provide a target face photo, and then several reference photos with their IDs. Reply ONLY with the ID of the reference photo that matches the person in the target photo. If none match, reply with 'NENHUM'.")
                })
                
                partsArray.put(JSONObject().apply {
                    put("text", "TARGET PHOTO:")
                })
                partsArray.put(JSONObject().apply {
                    put("inlineData", JSONObject().apply {
                        put("mimeType", "image/jpeg")
                        put("data", cleanTarget)
                    })
                })
                
                for (i in 0 until porteiros.length()) {
                    val p = porteiros.getJSONObject(i)
                    val id = p.getString("nome")
                    val foto = p.optString("foto", "")
                    if (foto.isNotEmpty()) {
                        val cleanFoto = foto.substringAfter("base64,")
                        partsArray.put(JSONObject().apply {
                            put("text", "REFERENCE PHOTO ID: $id")
                        })
                        partsArray.put(JSONObject().apply {
                            put("inlineData", JSONObject().apply {
                                put("mimeType", "image/jpeg")
                                put("data", cleanFoto)
                            })
                        })
                    }
                }
                
                val json = JSONObject().apply {
                    put("contents", org.json.JSONArray().put(JSONObject().apply {
                        put("parts", partsArray)
                    }))
                    put("generationConfig", JSONObject().apply {
                        put("temperature", 0.0)
                    })
                }

                val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey")
                    .post(body!!)
                    .build()

                val geminiClient = OkHttpClient.Builder()
                    .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                val response = geminiClient.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                
                var matchedId = "NENHUM"
                if (response.isSuccessful && responseBody.isNotEmpty()) {
                    val root = JSONObject(responseBody)
                    val candidates = root.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val firstCandidate = candidates.getJSONObject(0)
                        val content = firstCandidate.optJSONObject("content")
                        val parts = content?.optJSONArray("parts")
                        if (parts != null && parts.length() > 0) {
                            val text = parts.getJSONObject(0).optString("text")
                            if (!text.isNullOrBlank()) {
                                matchedId = text.trim().replace("\n", "").replace("'", "")
                            }
                        }
                    }
                }
                
                activity.runOnUiThread {
                    webView.evaluateJavascript("javascript:${callbackName}('$matchedId')", null)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                activity.runOnUiThread {
                    webView.evaluateJavascript("javascript:${callbackName}('ERRO')", null)
                }
            }
        }
    }
}

@Composable
fun NativeScannerView(
    onBarcodeScanned: (String) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    // Set up Barcode Scanner with ALL_FORMATS for maximum sensitivity
    val options = remember {
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .build()
    }
    val scanner = remember { BarcodeScanning.getClient(options) }

    var isTorchOn by remember { mutableStateOf(false) }
    var cameraControl by remember { mutableStateOf<androidx.camera.core.CameraControl?>(null) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(lifecycleOwner) {
        onDispose {
            try {
                if (cameraProviderFuture.isDone) {
                    val cameraProvider = cameraProviderFuture.get()
                    cameraProvider.unbindAll()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            try {
                cameraExecutor.shutdown()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    if (!hasCameraPermission) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.9f)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(24.dp)
            ) {
                Text(
                    text = "Permissão de Câmera Necessária",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "A permissão de câmera é necessária para o funcionamento do leitor de códigos nativo.",
                    color = Color.LightGray,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                androidx.compose.material3.Button(
                    onClick = {
                        val activity = findActivity(context)
                        if (activity != null) {
                            ActivityCompat.requestPermissions(
                                activity,
                                arrayOf(Manifest.permission.CAMERA),
                                101
                            )
                        }
                    }
                ) {
                    Text("Conceder Permissão")
                }
                Spacer(modifier = Modifier.height(12.dp))
                androidx.compose.material3.TextButton(onClick = onClose) {
                    Text("Cancelar", color = Color.White)
                }
            }
        }
    } else {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val screenWidthPx = constraints.maxWidth.toFloat()
            val screenHeightPx = constraints.maxHeight.toFloat()

            // Detect orientation
            val isLandscape = screenWidthPx > screenHeightPx

            // Define scan box dimensions in dp based on orientation
            val boxWidthDp = if (isLandscape) 420.dp else 280.dp
            val boxHeightDp = if (isLandscape) 220.dp else 200.dp

            val density = LocalDensity.current
            val boxWidthPx = with(density) { boxWidthDp.toPx() }
            val boxHeightPx = with(density) { boxHeightDp.toPx() }

            val targetLeft = (screenWidthPx - boxWidthPx) / 2
            val targetTop = (screenHeightPx - boxHeightPx) / 2
            val targetRight = targetLeft + boxWidthPx
            val targetBottom = targetTop + boxHeightPx
            val targetRect = remember(targetLeft, targetTop, targetRight, targetBottom) {
                RectF(targetLeft, targetTop, targetRight, targetBottom)
            }

            fun mapPoint(normX: Float, normY: Float, rotationDegrees: Int): Pair<Float, Float> {
                return when (rotationDegrees) {
                    0 -> Pair(normX, normY)
                    90 -> Pair(1.0f - normY, normX)
                    180 -> Pair(1.0f - normX, 1.0f - normY)
                    270 -> Pair(normY, 1.0f - normX)
                    else -> Pair(normX, normY)
                }
            }

            val currentTargetRect by rememberUpdatedState(targetRect)
            val currentOnBarcodeScanned by rememberUpdatedState(onBarcodeScanned)
            val currentScreenWidthPx by rememberUpdatedState(screenWidthPx)
            val currentScreenHeightPx by rememberUpdatedState(screenHeightPx)

            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()

                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }

                        val imageAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setTargetResolution(android.util.Size(1280, 720))
                            .build()

                        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                            @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
                            val mediaImage = imageProxy.image
                            if (mediaImage != null) {
                                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                                val image = InputImage.fromMediaImage(mediaImage, rotationDegrees)
                                scanner.process(image)
                                    .addOnSuccessListener { barcodes ->
                                        for (barcode in barcodes) {
                                            val rawValue = barcode.rawValue
                                            if (rawValue != null && rawValue.isNotBlank()) {
                                                val rawRect = barcode.boundingBox
                                                val tRect = currentTargetRect
                                                val sWidth = currentScreenWidthPx
                                                val sHeight = currentScreenHeightPx
                                                if (rawRect != null) {
                                                    val rawWidth = imageProxy.width.toFloat()
                                                    val rawHeight = imageProxy.height.toFloat()

                                                    // 1. Normalize coordinates
                                                    val normLeft = rawRect.left / rawWidth
                                                    val normTop = rawRect.top / rawHeight
                                                    val normRight = rawRect.right / rawWidth
                                                    val normBottom = rawRect.bottom / rawHeight

                                                    // 2. Rotate to match screen orientation
                                                    val p1 = mapPoint(normLeft, normTop, rotationDegrees)
                                                    val p2 = mapPoint(normRight, normBottom, rotationDegrees)

                                                    // 3. Swap dimensions if rotated by 90 or 270
                                                    val rotatedWidth = if (rotationDegrees == 90 || rotationDegrees == 270) rawHeight else rawWidth
                                                    val rotatedHeight = if (rotationDegrees == 90 || rotationDegrees == 270) rawWidth else rawHeight

                                                    // 4. Compute scale and offset under ScaleType.FILL_CENTER
                                                    val scale = maxOf(sWidth / rotatedWidth, sHeight / rotatedHeight)
                                                    val scaledWidth = rotatedWidth * scale
                                                    val scaledHeight = rotatedHeight * scale
                                                    val offsetX = (scaledWidth - sWidth) / 2
                                                    val offsetY = (scaledHeight - sHeight) / 2

                                                    // 5. Transform to screen coordinates
                                                    val screenX1 = p1.first * scaledWidth - offsetX
                                                    val screenY1 = p1.second * scaledHeight - offsetY
                                                    val screenX2 = p2.first * scaledWidth - offsetX
                                                    val screenY2 = p2.second * scaledHeight - offsetY

                                                    val barcodeScreenRect = RectF(
                                                        minOf(screenX1, screenX2),
                                                        minOf(screenY1, screenY2),
                                                        maxOf(screenX1, screenX2),
                                                        maxOf(screenY1, screenY2)
                                                    )

                                                    // 6. Restrict scan exclusively to barcodes that fall within targetRect (center point)
                                                    val centerX = barcodeScreenRect.centerX()
                                                    val centerY = barcodeScreenRect.centerY()
                                                    val centerInside = tRect.contains(centerX, centerY)

                                                    if (!centerInside) {
                                                        Log.d("Scanner", "Ignorando código fora do retângulo alvo: $barcodeScreenRect")
                                                        continue
                                                    }
                                                }
                                                currentOnBarcodeScanned(rawValue)
                                                break
                                            }
                                        }
                                    }
                                    .addOnFailureListener {
                                        // Ignore and continue
                                    }
                                    .addOnCompleteListener {
                                        imageProxy.close()
                                    }
                            } else {
                                imageProxy.close()
                            }
                        }

                        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                        try {
                            cameraProvider.unbindAll()
                            val camera = cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                preview,
                                imageAnalysis
                            )
                            cameraControl = camera.cameraControl
                            cameraControl?.enableTorch(isTorchOn)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )

            // Dynamic torch support watcher
            LaunchedEffect(isTorchOn, cameraControl) {
                try {
                    cameraControl?.enableTorch(isTorchOn)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // Outer dark frame with centered scan box (Hollow window using EvenOdd path)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val path = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
                    addRoundRect(
                        androidx.compose.ui.geometry.RoundRect(
                            left = targetLeft,
                            top = targetTop,
                            right = targetRight,
                            bottom = targetBottom,
                            cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx())
                        )
                    )
                }
                drawPath(path, color = Color.Black.copy(alpha = 0.5f))
            }

            // Beautiful borders on top of the hollow window
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(width = boxWidthDp, height = boxHeightDp)
                    .border(width = 3.dp, color = Color(0xFF0D9488), shape = RoundedCornerShape(12.dp))
            ) {
                // Moving red laser line simulation
                val infiniteTransition = rememberInfiniteTransition(label = "laser")
                val translationY by infiniteTransition.animateFloat(
                    initialValue = 10f,
                    targetValue = boxHeightPx - 10f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1500, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "laserY"
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .graphicsLayer(translationY = translationY)
                        .background(Color.Red.copy(alpha = 0.8f))
                )
            }

            // Instruction text below the scan target
            Text(
                text = "Centralize o código de barras ou QR Code no quadrado acima",
                color = Color.White,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(top = if (isLandscape) 250.dp else 270.dp)
                    .background(Color.Black.copy(alpha = 0.7f), shape = RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )

            // Top action bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp)
                    .padding(16.dp)
                    .align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(48.dp).background(Color.Black.copy(alpha = 0.5f), shape = CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Fechar Leitor",
                        tint = Color.White
                    )
                }

                Text(
                    text = "CondEntregas - Leitor Nativo",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.5f), shape = RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )

                // Standard Flashlight button containing flashlight emoji (ultra-compatible)
                IconButton(
                    onClick = { isTorchOn = !isTorchOn },
                    modifier = Modifier.size(48.dp).background(Color.Black.copy(alpha = 0.5f), shape = CircleShape)
                ) {
                    Text(
                        text = if (isTorchOn) "💡" else "🔦",
                        fontSize = 20.sp
                    )
                }
            }
        }
    }
}


