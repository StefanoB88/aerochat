package com.example.aerochat.core.qr

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
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

/** Errori dello scanner (Swift `QRScannerError`). */
enum class QRScannerError { cameraUnavailable, permissionDenied }

/**
 * Scanner QR basato su CameraX (anteprima) + ML Kit (decodifica), equivalente di
 * `AVCaptureMetadataOutput`. Chiede da solo il permesso fotocamera. Chiama [onCode] sul main thread
 * per ogni QR inquadrato, con debounce: lo stesso codice non viene riconsegnato per 2 secondi.
 */
@Composable
fun QRScannerView(
    onCode: (String) -> Unit,
    modifier: Modifier = Modifier,
    onError: ((QRScannerError) -> Unit)? = null,
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

    DisposableEffect(granted, lifecycleOwner) {
        if (!granted) return@DisposableEffect onDispose {}
        val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
        )
        val debouncer = CodeDebouncer()
        var provider: ProcessCameraProvider? = null
        var disposed = false
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
                    analyze(proxy, scanner) { value ->
                        ContextCompat.getMainExecutor(context).execute {
                            if (!disposed && debouncer.accept(value)) {
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
                cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                Log.w("QRScannerView", "fotocamera non disponibile: $e")
                currentOnError?.invoke(QRScannerError.cameraUnavailable)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            disposed = true
            provider?.unbindAll()
            scanner.close()
            analysisExecutor.shutdown()
        }
    }
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

@SuppressLint("UnsafeOptInUsageError")
private fun analyze(proxy: ImageProxy, scanner: BarcodeScanner, onValue: (String) -> Unit) {
    val mediaImage = proxy.image
    if (mediaImage == null) {
        proxy.close()
        return
    }
    val input = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
    scanner.process(input)
        .addOnSuccessListener { barcodes ->
            barcodes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }?.rawValue?.let(onValue)
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
