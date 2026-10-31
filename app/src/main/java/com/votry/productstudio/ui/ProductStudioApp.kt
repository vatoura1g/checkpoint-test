package com.votry.productstudio.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.votry.productstudio.camera.CameraCapture
import com.votry.productstudio.enhance.RealEsrganEnhancer
import com.votry.productstudio.ml.BackgroundRemover
import com.votry.productstudio.ml.ModelDownloader
import com.votry.productstudio.ml.sam.TargetSelector
import com.votry.productstudio.processing.CatalogFramer
import com.votry.productstudio.processing.ImageTools
import java.io.File
import kotlinx.coroutines.launch

private sealed interface Screen {
    data object Camera : Screen
    data class Select(val file: File, val preview: Bitmap) : Screen
    data class Processing(val text: String, val progress: Int? = null) : Screen
    data class Result(
        val file: File,
        val preview: Bitmap,
        val width: Int,
        val height: Int,
        val autoSaved: Boolean,
        val enhanced: Boolean,
    ) : Screen
    data class Error(val message: String) : Screen
}

@Composable
fun ProductStudioApp() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
            val context = LocalContext.current
            val appScope = rememberCoroutineScope()
            var screen: Screen by remember { mutableStateOf(Screen.Camera) }
            var hasPermission by remember {
                mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            }
            val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }

            fun disposeResult(current: Screen.Result) {
                if (!current.preview.isRecycled) current.preview.recycle()
                current.file.delete()
            }

            when (val current = screen) {
                Screen.Camera -> {
                    if (hasPermission) {
                        CameraPage(
                            onCaptured = { files ->
                                try {
                                    val primary = choosePrimary(files)
                                    files.filter { it.absolutePath != primary.absolutePath }.forEach { it.delete() }
                                    val preview = ImageTools.decodeForSelection(primary)
                                    screen = Screen.Select(primary, preview)
                                } catch (t: Throwable) {
                                    files.forEach { it.delete() }
                                    screen = Screen.Error(t.message ?: "Impossible de préparer la photo.")
                                }
                            },
                        )
                    } else {
                        PermissionPage { permission.launch(Manifest.permission.CAMERA) }
                    }
                }

                is Screen.Select -> SelectionPage(
                    preview = current.preview,
                    onContinue = { nx, ny ->
                        if (!current.preview.isRecycled) current.preview.recycle()
                        screen = Screen.Processing("Identification du produit…", 4)
                        appScope.launch {
                            var source: Bitmap? = null
                            var processed: Bitmap? = null
                            var enhancedBitmap: Bitmap? = null
                            var framed: Bitmap? = null
                            var cacheFile: File? = null
                            var savedToGallery = false
                            var enhanceSucceeded = false
                            try {
                                source = ImageTools.decodeForProcessing(current.file)
                                val target = TargetSelector(context).select(source!!, nx, ny) { progress, message ->
                                    screen = Screen.Processing(message, progress)
                                }
                                val model = ModelDownloader(context).ensureModel { percent ->
                                    val mapped = 30 + (percent.coerceIn(0, 100) * 15 / 100)
                                    screen = Screen.Processing("Préparation du moteur photo…", mapped)
                                }
                                processed = BackgroundRemover().process(source!!, model, target) { progress, message ->
                                    screen = Screen.Processing(message, progress)
                                }

                                // Final local restoration pass. Real-ESRGAN improves perceived detail while
                                // a conservative blend keeps strong text/label pixels from being rewritten.
                                enhancedBitmap = try {
                                    RealEsrganEnhancer(context).enhance(processed!!) { p, message ->
                                        val mapped = 95 + (p.coerceIn(0, 100) * 3 / 100)
                                        screen = Screen.Processing(message, mapped)
                                    }.also { enhanceSucceeded = true }
                                } catch (_: Throwable) {
                                    screen = Screen.Processing("Enhance HD indisponible — finition standard…", 98)
                                    null
                                }

                                screen = Screen.Processing("Cadrage catalogue…", 98)
                                val frameInput = enhancedBitmap ?: processed!!
                                framed = CatalogFramer.frame(frameInput, target)
                                val resultWidth = framed!!.width
                                val resultHeight = framed!!.height
                                cacheFile = ImageTools.writeResultCache(context, framed!!)

                                framed!!.recycle(); framed = null
                                enhancedBitmap?.takeIf { !it.isRecycled }?.recycle(); enhancedBitmap = null
                                processed!!.recycle(); processed = null
                                source!!.recycle(); source = null

                                screen = Screen.Processing("Enregistrement automatique…", 99)
                                ImageTools.saveCachedResultToGallery(context, cacheFile!!)
                                savedToGallery = true

                                val preview = try {
                                    ImageTools.decodePreview(cacheFile!!)
                                } catch (previewError: Throwable) {
                                    screen = Screen.Error(
                                        "La photo a bien été enregistrée dans Pictures/Ghazi, mais l'aperçu n'a pas pu s'ouvrir : " +
                                            (previewError.message ?: previewError.javaClass.simpleName),
                                    )
                                    return@launch
                                }

                                screen = Screen.Result(
                                    file = cacheFile!!,
                                    preview = preview,
                                    width = resultWidth,
                                    height = resultHeight,
                                    autoSaved = savedToGallery,
                                    enhanced = enhanceSucceeded,
                                )
                            } catch (oom: OutOfMemoryError) {
                                framed?.takeIf { !it.isRecycled }?.recycle()
                                enhancedBitmap?.takeIf { !it.isRecycled }?.recycle()
                                processed?.takeIf { !it.isRecycled }?.recycle()
                                source?.takeIf { !it.isRecycled }?.recycle()
                                val savedNote = if (savedToGallery) " La photo finale est déjà enregistrée dans Pictures/Ghazi." else ""
                                screen = Screen.Error("La mémoire du téléphone est insuffisante pour afficher le résultat.$savedNote")
                            } catch (t: Throwable) {
                                framed?.takeIf { !it.isRecycled }?.recycle()
                                enhancedBitmap?.takeIf { !it.isRecycled }?.recycle()
                                processed?.takeIf { !it.isRecycled }?.recycle()
                                source?.takeIf { !it.isRecycled }?.recycle()
                                val savedNote = if (savedToGallery) " La photo finale est déjà enregistrée dans Pictures/Ghazi." else ""
                                screen = Screen.Error((t.message ?: "Le traitement n'a pas pu être terminé.") + savedNote)
                            } finally {
                                current.file.delete()
                            }
                        }
                    },
                    onRetake = {
                        if (!current.preview.isRecycled) current.preview.recycle()
                        current.file.delete()
                        screen = Screen.Camera
                    },
                )

                is Screen.Processing -> ProcessingPage(current.text, current.progress)

                is Screen.Result -> ResultPage(
                    current = current,
                    onSaveCopy = {
                        appScope.launch {
                            runCatching { ImageTools.saveCachedResultToGallery(context, current.file) }
                                .onSuccess { Toast.makeText(context, "Une copie a été enregistrée ✓", Toast.LENGTH_SHORT).show() }
                                .onFailure { Toast.makeText(context, it.message ?: "Erreur d'enregistrement", Toast.LENGTH_LONG).show() }
                        }
                    },
                    onRetake = {
                        disposeResult(current)
                        screen = Screen.Camera
                    },
                )

                is Screen.Error -> ErrorPage(
                    message = current.message,
                    onRetry = { screen = Screen.Camera },
                )
            }
        }
    }
}

private fun choosePrimary(files: List<File>): File = files[files.size / 2]

@Composable
private fun BrandHeader(subtitle: String? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("GHAZI", fontSize = 34.sp, fontWeight = FontWeight.Black, color = Color.Black)
        if (subtitle != null) Text(subtitle, fontSize = 16.sp, color = Color(0xFF555555))
    }
}

@Composable
private fun Signature() {
    Text(
        "Ghazi Fourati — Special Edition",
        fontSize = 13.sp,
        fontStyle = FontStyle.Italic,
        color = Color(0xFF666666),
        modifier = Modifier.padding(vertical = 10.dp),
    )
}

@Composable
private fun PermissionPage(onRequest: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color.White).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BrandHeader("Studio photo produit")
        Spacer(Modifier.height(26.dp))
        Text("Autorisez la caméra une seule fois.", fontSize = 19.sp, color = Color.Black)
        Spacer(Modifier.height(30.dp))
        BigButton("AUTORISER LA CAMÉRA", onClick = onRequest)
        Spacer(Modifier.height(20.dp))
        Signature()
    }
}

@Composable
private fun CameraPage(onCaptured: (List<File>) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val controller = remember { CameraCapture(context) }
    var busy by remember { mutableStateOf(false) }
    val preview = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    LaunchedEffect(preview) { controller.bind(lifecycleOwner, preview) }
    DisposableEffect(Unit) { onDispose { controller.unbind() } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White)
                .padding(horizontal = 22.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("GHAZI", fontSize = 22.sp, fontWeight = FontWeight.Black, color = Color.Black)
            Text("Placez le produit au centre", fontSize = 18.sp, color = Color(0xFF444444))
            Spacer(Modifier.height(12.dp))
            BigButton(if (busy) "PHOTO EN COURS…" else "PRENDRE LA PHOTO", enabled = !busy) {
                busy = true
                scope.launch {
                    runCatching { controller.captureSmartBracket() }
                        .onSuccess(onCaptured)
                        .onFailure {
                            Toast.makeText(context, it.message ?: "Erreur caméra", Toast.LENGTH_LONG).show()
                            busy = false
                        }
                }
            }
            Signature()
        }
    }
}

@Composable
private fun SelectionPage(
    preview: Bitmap,
    onContinue: (Float, Float) -> Unit,
    onRetake: () -> Unit,
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var selectedPoint by remember { mutableStateOf<Offset?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().background(Color.White).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandHeader("Touchez le produit à garder")
        Text("Un seul toucher suffit.", fontSize = 16.sp, color = Color(0xFF555555))
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().background(Color(0xFFF4F4F4))
                .onSizeChanged { containerSize = it }
                .pointerInput(preview.width, preview.height, containerSize) {
                    detectTapGestures { tap ->
                        mapTapToNormalized(tap, containerSize, preview.width, preview.height)?.let {
                            selectedPoint = it
                        }
                    }
                },
        ) {
            Image(
                bitmap = preview.asImageBitmap(),
                contentDescription = "Photo à sélectionner",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            Canvas(Modifier.fillMaxSize()) {
                val point = selectedPoint ?: return@Canvas
                val display = fitRect(size.width, size.height, preview.width, preview.height)
                val center = Offset(
                    display.left + point.x * display.width,
                    display.top + point.y * display.height,
                )
                drawCircle(Color.White, radius = 16.dp.toPx(), center = center)
                drawCircle(Color.Black, radius = 11.dp.toPx(), center = center, style = Stroke(width = 4.dp.toPx()))
                drawCircle(Color.Black, radius = 3.5.dp.toPx(), center = center)
            }
        }
        Spacer(Modifier.height(12.dp))
        BigButton("CONTINUER", enabled = selectedPoint != null) {
            selectedPoint?.let { onContinue(it.x, it.y) }
        }
        Spacer(Modifier.height(8.dp))
        BigButton("REPRENDRE", secondary = true, onClick = onRetake)
        Signature()
    }
}

private data class DisplayRect(val left: Float, val top: Float, val width: Float, val height: Float)

private fun fitRect(viewWidth: Float, viewHeight: Float, imageWidth: Int, imageHeight: Int): DisplayRect {
    if (viewWidth <= 0f || viewHeight <= 0f || imageWidth <= 0 || imageHeight <= 0) {
        return DisplayRect(0f, 0f, 0f, 0f)
    }
    val imageAspect = imageWidth.toFloat() / imageHeight.toFloat()
    val viewAspect = viewWidth / viewHeight
    return if (imageAspect > viewAspect) {
        val width = viewWidth
        val height = width / imageAspect
        DisplayRect(0f, (viewHeight - height) * 0.5f, width, height)
    } else {
        val height = viewHeight
        val width = height * imageAspect
        DisplayRect((viewWidth - width) * 0.5f, 0f, width, height)
    }
}

private fun mapTapToNormalized(
    tap: Offset,
    viewSize: IntSize,
    imageWidth: Int,
    imageHeight: Int,
): Offset? {
    val rect = fitRect(viewSize.width.toFloat(), viewSize.height.toFloat(), imageWidth, imageHeight)
    if (rect.width <= 0f || rect.height <= 0f) return null
    if (tap.x < rect.left || tap.x > rect.left + rect.width || tap.y < rect.top || tap.y > rect.top + rect.height) return null
    return Offset(
        ((tap.x - rect.left) / rect.width).coerceIn(0f, 1f),
        ((tap.y - rect.top) / rect.height).coerceIn(0f, 1f),
    )
}

@Composable
private fun ProcessingPage(text: String, progress: Int?) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color.White).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BrandHeader("Traitement haute qualité")
        Spacer(Modifier.height(34.dp))
        CircularProgressIndicator(modifier = Modifier.size(68.dp), color = Color.Black)
        Spacer(Modifier.height(26.dp))
        Text(text, fontSize = 21.sp, color = Color.Black)
        if (progress != null) {
            Spacer(Modifier.height(10.dp))
            Text("${progress.coerceIn(0, 100)} %", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.Black)
        }
        Spacer(Modifier.height(12.dp))
        Text("Ghazi isole, reconstruit puis améliore le produit.", fontSize = 15.sp, color = Color(0xFF666666))
        Spacer(Modifier.height(24.dp))
        Signature()
    }
}

@Composable
private fun ResultPage(current: Screen.Result, onSaveCopy: () -> Unit, onRetake: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color.White).padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandHeader("Photo prête ✓")
        Text("${current.width} × ${current.height} px · Catalogue 4:5", fontSize = 14.sp, color = Color(0xFF666666))
        Spacer(Modifier.height(4.dp))
        Text(
            if (current.enhanced) "Enhance HD ✓" else "Finition standard",
            fontSize = 14.sp,
            color = if (current.enhanced) Color(0xFF2E6B3A) else Color(0xFF777777),
        )
        if (current.autoSaved) {
            Spacer(Modifier.height(4.dp))
            Text("Enregistrée automatiquement dans Pictures/Ghazi ✓", fontSize = 14.sp, color = Color(0xFF2E6B3A))
        }
        Spacer(Modifier.height(10.dp))
        Image(
            bitmap = current.preview.asImageBitmap(),
            contentDescription = "Résultat Ghazi",
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentScale = ContentScale.Fit,
        )
        Spacer(Modifier.height(12.dp))
        BigButton("ENREGISTRER UNE COPIE", onClick = onSaveCopy)
        Spacer(Modifier.height(8.dp))
        BigButton("PRENDRE UNE AUTRE", onClick = onRetake, secondary = true)
        Signature()
    }
}

@Composable
private fun ErrorPage(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color.White).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BrandHeader("À refaire simplement")
        Spacer(Modifier.height(24.dp))
        Text(message, fontSize = 18.sp, color = Color.Black)
        Spacer(Modifier.height(28.dp))
        BigButton("REPRENDRE UNE PHOTO", onClick = onRetry)
        Spacer(Modifier.height(20.dp))
        Signature()
    }
}

@Composable
private fun BigButton(
    text: String,
    enabled: Boolean = true,
    secondary: Boolean = false,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(64.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (secondary) Color(0xFFEDEDED) else Color(0xFF111111),
            contentColor = if (secondary) Color.Black else Color.White,
        ),
    ) {
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}
