package com.votry.productstudio.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.votry.productstudio.camera.CameraCapture
import com.votry.productstudio.ml.BackgroundRemover
import com.votry.productstudio.ml.ModelDownloader
import com.votry.productstudio.processing.ImageTools
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface Screen {
    data object Camera : Screen
    data class Processing(val text: String, val progress: Int? = null) : Screen
    data class Result(val file: File, val preview: Bitmap, val width: Int, val height: Int) : Screen
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
                                screen = Screen.Processing("Préparation de votre photo…", 2)
                                val primary = choosePrimary(files)
                                appScope.launch {
                                    var source: Bitmap? = null
                                    var result: Bitmap? = null
                                    try {
                                        val model = ModelDownloader(context).ensureModel { percent ->
                                            screen = Screen.Processing("Préparation initiale du moteur…", percent.coerceIn(0, 100))
                                        }
                                        screen = Screen.Processing("Chargement de la photo…", 5)
                                        source = ImageTools.decodeForProcessing(primary)
                                        result = BackgroundRemover().process(source!!, model) { progress, message ->
                                            screen = Screen.Processing(message, progress)
                                        }
                                        screen = Screen.Processing("Création du résultat HD…", 96)
                                        val resultWidth = result!!.width
                                        val resultHeight = result!!.height
                                        val cacheFile = ImageTools.writeResultCache(context, result!!)
                                        result!!.recycle()
                                        result = null
                                        source!!.recycle()
                                        source = null

                                        screen = Screen.Processing("Préparation de l'aperçu…", 98)
                                        val preview = ImageTools.decodePreview(cacheFile)
                                        screen = Screen.Processing("Terminé", 100)
                                        delay(180)
                                        screen = Screen.Result(cacheFile, preview, resultWidth, resultHeight)
                                    } catch (oom: OutOfMemoryError) {
                                        result?.takeIf { !it.isRecycled }?.recycle()
                                        source?.takeIf { !it.isRecycled }?.recycle()
                                        screen = Screen.Error("La photo était trop lourde pour la mémoire du téléphone. Ghazi a arrêté le traitement sans perdre vos photos. Reprenez simplement la photo.")
                                    } catch (t: Throwable) {
                                        result?.takeIf { !it.isRecycled }?.recycle()
                                        source?.takeIf { !it.isRecycled }?.recycle()
                                        screen = Screen.Error(t.message ?: "Le traitement n'a pas pu être terminé.")
                                    } finally {
                                        files.forEach { it.delete() }
                                    }
                                }
                            },
                        )
                    } else {
                        PermissionPage { permission.launch(Manifest.permission.CAMERA) }
                    }
                }

                is Screen.Processing -> ProcessingPage(current.text, current.progress)

                is Screen.Result -> ResultPage(
                    current = current,
                    onSave = {
                        appScope.launch {
                            runCatching { ImageTools.saveCachedResultToGallery(context, current.file) }
                                .onSuccess { Toast.makeText(context, "Photo enregistrée dans Ghazi ✓", Toast.LENGTH_SHORT).show() }
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
        if (subtitle != null) {
            Text(subtitle, fontSize = 16.sp, color = Color(0xFF555555))
        }
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
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.White)
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
        Text("Ghazi préserve la forme, le verre et les inscriptions.", fontSize = 15.sp, color = Color(0xFF666666))
        Spacer(Modifier.height(24.dp))
        Signature()
    }
}

@Composable
private fun ResultPage(current: Screen.Result, onSave: () -> Unit, onRetake: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color.White).padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandHeader("Photo prête ✓")
        Text("${current.width} × ${current.height} px", fontSize = 14.sp, color = Color(0xFF666666))
        Spacer(Modifier.height(10.dp))
        Image(
            bitmap = current.preview.asImageBitmap(),
            contentDescription = "Résultat Ghazi",
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentScale = ContentScale.Fit,
        )
        Spacer(Modifier.height(12.dp))
        BigButton("ENREGISTRER", onClick = onSave)
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
        BrandHeader("Le traitement s'est arrêté")
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
