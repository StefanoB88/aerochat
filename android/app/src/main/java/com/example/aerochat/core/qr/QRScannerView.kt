package com.example.aerochat.core.qr

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.aerochat.ui.components.Haptics
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Errori dello scanner (Swift `QRScannerError`). */
enum class QRScannerError { cameraUnavailable, permissionDenied }

/**
 * Scanner QR basato su CameraX (anteprima) + ML Kit (decodifica), equivalente di
 * `AVCaptureMetadataOutput`. Chiede da solo il permesso fotocamera. Chiama [onCode] sul main thread
 * per ogni QR inquadrato, con debounce: lo stesso codice non viene riconsegnato per 2 secondi.
 *
 * Macro automatica: se per qualche secondo non si decodifica nulla, il codice e' probabilmente troppo
 * vicino per la grandangolare (sfocato). Si rimette a fuoco al centro e, dove la fotocamera logica ha
 * un'ultra-grandangolare (zoom < 1x, distanza minima di fuoco piu' corta), si passa a quella; se
 * nemmeno cosi' arriva nulla si torna a 1x, alternando finche' il codice non viene letto.
 */
@Composable
fun QRScannerView(
    onCode: (String) -> Unit,
    modifier: Modifier = Modifier,
    onError: ((QRScannerError) -> Unit)? = null,
    // Bitmask ML Kit dei formati accettati (es. `FORMAT_QR_CODE or FORMAT_PDF417 or FORMAT_AZTEC` per le carte d'imbarco).
    formats: Int = Barcode.FORMAT_QR_CODE,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnCode by rememberUpdatedState(onCode)
    val currentOnError by rememberUpdatedState(onError)
    var granted by remember { mutableStateOf(hasCameraPermission(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (!ok) currentOnError?.invoke(QRScannerError.permissionDenied)
    }
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // TextureView: si ritaglia con gli angoli arrotondati e funziona dentro i fogli modali.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    Box(modifier.background(Color.Black)) {
        if (granted) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        }
    }

    DisposableEffect(granted, lifecycleOwner, formats) {
        if (!granted) return@DisposableEffect onDispose {}
        val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(formats).build(),
        )
        val debouncer = CodeDebouncer()
        var provider: ProcessCameraProvider? = null
        var disposed = false
        val mainHandler = Handler(Looper.getMainLooper())
        var macro: MacroFocusAssist? = null
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (disposed) return@addListener
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { proxy ->
                    analyze(proxy, scanner, formats) { value ->
                        ContextCompat.getMainExecutor(context).execute {
                            if (disposed) return@execute
                            macro?.codeSeen()
                            if (debouncer.accept(value)) {
                                Haptics.impact(Haptics.ImpactStyle.medium)
                                currentOnCode(value)
                            }
                        }
                    }
                }
                if (!cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    currentOnError?.invoke(QRScannerError.cameraUnavailable)
                    return@addListener
                }
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                macro = MacroFocusAssist(camera, previewView, mainHandler).also { it.start() }
            } catch (e: Exception) {
                Log.w("QRScannerView", "fotocamera non disponibile: $e")
                currentOnError?.invoke(QRScannerError.cameraUnavailable)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            disposed = true
            macro?.stop()
            provider?.unbindAll()
            scanner.close()
            analysisExecutor.shutdown()
        }
    }
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

@SuppressLint("UnsafeOptInUsageError")
private fun analyze(proxy: ImageProxy, scanner: BarcodeScanner, formats: Int, onValue: (String) -> Unit) {
    val mediaImage = proxy.image
    if (mediaImage == null) {
        proxy.close()
        return
    }
    val input = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
    scanner.process(input)
        .addOnSuccessListener { barcodes ->
            barcodes.firstOrNull { (it.format and formats) != 0 }?.rawValue?.let(onValue)
        }
        .addOnCompleteListener { proxy.close() }
}

/** Debounce: lo stesso codice non viene riconsegnato per 2 secondi. */
private class CodeDebouncer {
    private var lastCode: String? = null
    private var lastCodeAt = 0L

    fun accept(value: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (value == lastCode && now - lastCodeAt < 2_000) return false
        lastCode = value
        lastCodeAt = now
        return true
    }
}

/**
 * Messa a fuoco ravvicinata assistita. Ogni [checkInterval] senza decodifiche: rimette a fuoco al centro
 * dell'inquadratura e, se la fotocamera puo' scendere sotto 1x (ultra-grandangolare, che su molti
 * telefoni e' anche l'obiettivo macro), alterna tra 1x e lo zoom minimo. Appena un codice viene letto
 * lo zoom torna a 1x e il timer riparte.
 */
private class MacroFocusAssist(
    private val camera: Camera,
    private val previewView: PreviewView,
    private val handler: Handler,
) {
    private val checkInterval = 2_200L
    private val minZoom: Float = camera.cameraInfo.zoomState.value?.minZoomRatio ?: 1f
    private val canSwitchLens = minZoom < 0.95f
    private var lastCodeAt = SystemClock.elapsedRealtime()
    private var macroEngaged = false
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val idle = SystemClock.elapsedRealtime() - lastCodeAt
            if (idle >= checkInterval) {
                if (canSwitchLens) {
                    macroEngaged = !macroEngaged
                    runCatching { camera.cameraControl.setZoomRatio(if (macroEngaged) minZoom else 1f) }
                }
                focusCenter()
                // Nuova finestra di osservazione per l'obiettivo appena scelto.
                lastCodeAt = SystemClock.elapsedRealtime()
            }
            handler.postDelayed(this, checkInterval / 2)
        }
    }

    fun start() {
        running = true
        focusCenter()
        handler.postDelayed(tick, checkInterval)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    /** Un QR e' stato decodificato: l'obiettivo corrente funziona, si resta li' (e si torna a 1x se eravamo in macro). */
    fun codeSeen() {
        lastCodeAt = SystemClock.elapsedRealtime()
        if (macroEngaged) {
            macroEngaged = false
            runCatching { camera.cameraControl.setZoomRatio(1f) }
        }
    }

    private fun focusCenter() {
        val width = previewView.width.toFloat()
        val height = previewView.height.toFloat()
        if (width <= 0f || height <= 0f) return
        val point = previewView.meteringPointFactory.createPoint(width / 2f, height / 2f)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        runCatching { camera.cameraControl.startFocusAndMetering(action) }
    }
}
