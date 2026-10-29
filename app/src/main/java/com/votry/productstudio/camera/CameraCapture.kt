package com.votry.productstudio.camera

import android.content.Context
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine

class CameraCapture(private val context: Context) {
    var imageCapture: ImageCapture? = null
        private set
    var camera: Camera? = null
        private set
    private var provider: ProcessCameraProvider? = null

    suspend fun bind(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val cameraProvider = ProcessCameraProvider.getInstance(context).await()
        provider = cameraProvider
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setJpegQuality(100)
            .build()
        imageCapture = capture
        cameraProvider.unbindAll()
        camera = cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
    }

    fun unbind() { provider?.unbindAll() }

    suspend fun captureSmartBracket(): List<File> {
        val capture = imageCapture ?: error("Caméra non prête")
        val cam = camera ?: error("Caméra non prête")
        val exposureState = cam.cameraInfo.exposureState
        val range = exposureState.exposureCompensationRange
        val step = exposureState.exposureCompensationStep.toFloat().takeIf { it > 0f } ?: 1f
        val desired = listOf(-2f, 0f, 2f)
        val indexes = desired.map { ev ->
            (ev / step).toInt().coerceIn(range.lower, range.upper)
        }.distinct()
        val output = mutableListOf<File>()
        try {
            for ((n, index) in indexes.withIndex()) {
                runCatching { cam.cameraControl.setExposureCompensationIndex(index).await() }
                delay(120)
                val file = File(context.cacheDir, "capture_${System.currentTimeMillis()}_${n}.jpg")
                captureToFile(capture, file)
                output += file
            }
        } finally {
            runCatching { cam.cameraControl.setExposureCompensationIndex(0).await() }
        }
        if (output.isEmpty()) error("Aucune photo capturée")
        return output
    }

    private suspend fun captureToFile(capture: ImageCapture, file: File) = suspendCancellableCoroutine<Unit> { cont ->
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(options, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                if (cont.isActive) cont.resume(Unit)
            }
            override fun onError(exception: ImageCaptureException) {
                if (cont.isActive) cont.resumeWithException(exception)
            }
        })
    }
}

private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
    addListener({
        try {
            if (cont.isActive) cont.resume(get())
        } catch (t: Throwable) {
            if (cont.isActive) cont.resumeWithException(t)
        }
    }, Runnable::run)
    cont.invokeOnCancellation { cancel(true) }
}
