package ru.vdl.catalog

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import ru.vdl.catalog.databinding.ActivityScannerBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Сканер линейных и QR-кодов на CameraX + ML Kit (модель встроена в APK,
 * распознавание работает офлайн, Google Play services не требуются).
 */
class ScannerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_FORMAT = "format"
    }

    private lateinit var b: ActivityScannerBinding
    private lateinit var exec: ExecutorService
    private var scanner: BarcodeScanner? = null
    private var camera: androidx.camera.core.Camera? = null
    private var torchOn = false
    private var delivered = false

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else {
            Toast.makeText(this, "Без доступа к камере доступен только ручной ввод", Toast.LENGTH_LONG).show()
            manualInput()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityScannerBinding.inflate(layoutInflater)
        setContentView(b.root)
        exec = Executors.newSingleThreadExecutor()

        b.torchBtn.setOnClickListener {
            torchOn = !torchOn
            camera?.cameraControl?.enableTorch(torchOn)
        }
        b.manualBtn.setOnClickListener { manualInput() }

        val opts = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39, Barcode.FORMAT_CODE_93,
                Barcode.FORMAT_CODABAR, Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E, Barcode.FORMAT_ITF,
                Barcode.FORMAT_QR_CODE, Barcode.FORMAT_DATA_MATRIX, Barcode.FORMAT_PDF417,
                Barcode.FORMAT_AZTEC
            ).build()
        scanner = BarcodeScanning.getClient(opts)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            askCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(b.preview.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(exec) { proxy -> analyze(proxy) }
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                Toast.makeText(this, "Камера недоступна: ${e.message}", Toast.LENGTH_LONG).show()
                manualInput()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        val sc = scanner
        if (media == null || sc == null || delivered) { proxy.close(); return }
        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        sc.process(image)
            .addOnSuccessListener { list ->
                val hit = list.firstOrNull { !it.rawValue.isNullOrBlank() }
                if (hit != null) deliver(hit.rawValue!!.trim(), formatName(hit.format))
            }
            .addOnCompleteListener { proxy.close() }
    }

    private fun formatName(f: Int): String = when (f) {
        Barcode.FORMAT_QR_CODE -> "QR"
        Barcode.FORMAT_CODE_128 -> "Code 128"
        Barcode.FORMAT_CODE_39 -> "Code 39"
        Barcode.FORMAT_CODE_93 -> "Code 93"
        Barcode.FORMAT_EAN_13 -> "EAN-13"
        Barcode.FORMAT_EAN_8 -> "EAN-8"
        Barcode.FORMAT_UPC_A -> "UPC-A"
        Barcode.FORMAT_UPC_E -> "UPC-E"
        Barcode.FORMAT_ITF -> "ITF"
        Barcode.FORMAT_CODABAR -> "Codabar"
        Barcode.FORMAT_DATA_MATRIX -> "DataMatrix"
        Barcode.FORMAT_PDF417 -> "PDF417"
        Barcode.FORMAT_AZTEC -> "Aztec"
        else -> "код"
    }

    private fun deliver(code: String, format: String) {
        if (delivered) return
        delivered = true
        runOnUiThread {
            try {
                val v = getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator
                @Suppress("DEPRECATION") v?.vibrate(60)
            } catch (_: Exception) { }
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_CODE, code).putExtra(EXTRA_FORMAT, format))
            finish()
        }
    }

    private fun manualInput() {
        val edit = EditText(this).apply {
            hint = "Введите номер"
            setSingleLine()
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.manual_input)
            .setView(edit)
            .setPositiveButton("ОК") { _, _ ->
                val text = edit.text.toString().trim()
                if (text.isNotEmpty()) deliver(text, "вручную")
                else finish()
            }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        exec.shutdown()
        scanner?.close()
    }
}
