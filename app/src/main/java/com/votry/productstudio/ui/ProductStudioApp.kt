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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.launch

private sealed interface Screen {
    data object Camera : Screen
    data class Processing(val text: String, val progress: Int? = null) : Screen
    data class Result(val bitmap: Bitmap) : Screen
}

@Composable
fun ProductStudioApp() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
            val context = LocalContext.current
            var screen: Screen by remember { mutableStateOf(Screen.Camera) }
            var hasPermission by remember {
                mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            }
            val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
            when (val current = screen) {
                Screen.Camera -> {
                    if (hasPermission) CameraPage(
                        onCaptured = { files ->
                            screen = Screen.Processing("Préparation de votre photo…")
                            val primary = choosePrimary(files)
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                                try {
                                    val downloader = ModelDownloader(context)
                                    val model = downloader.ensureModel { p -> screen = Screen.Processing("Préparation initiale du moteur…", p) }
                                    val source = ImageTools.decodeOriented(primary)
                                    val result = BackgroundRemover().process(source, model) { message ->
                                        screen = Screen.Processing(message)
                                    }
                                    if (result !== source) source.recycle()
                                    screen = Screen.Result(result)
                                } catch (t: Throwable) {
                                    Toast.makeText(context, t.message ?: "Erreur", Toast.LENGTH_LONG).show()
                                    screen = Screen.Camera
                                } finally {
                                    files.forEach { it.delete() }
                                }
                            }
                        }
                    ) else PermissionPage { permission.launch(Manifest.permission.CAMERA) }
                }
                is Screen.Processing -> ProcessingPage(current.text, current.progress)
                is Screen.Result -> ResultPage(
                    bitmap = current.bitmap,
                    onSave = {
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                            runCatching { ImageTools.saveToGallery(context, current.bitmap) }
                                .onSuccess { Toast.makeText(context, "Photo enregistrée ✓", Toast.LENGTH_SHORT).show() }
                                .onFailure { Toast.makeText(context, it.message ?: "Erreur", Toast.LENGTH_LONG).show() }
                        }
                    },
                    onRetake = { current.bitmap.recycle(); screen = Screen.Camera },
                )
            }
        }
    }
}

private fun choosePrimary(files: List<File>): File = files[files.size / 2]

@Composable
private fun PermissionPage(onRequest: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("PHOTO PRODUIT", fontSize = 28.sp)
        Spacer(Modifier.height(24.dp))
        Text("Autorisez la caméra une seule fois pour prendre les photos.", fontSize = 18.sp)
        Spacer(Modifier.height(30.dp))
        BigButton("AUTORISER LA CAMÉRA", onRequest)
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
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White.copy(alpha = 0.94f)).padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Placez le produit au centre", fontSize = 20.sp, color = Color.Black)
            Spacer(Modifier.height(14.dp))
            BigButton(if (busy) "PHOTO EN COURS…" else "PRENDRE LA PHOTO", enabled = !busy) {
                busy = true
                scope.launch {
                    runCatching { controller.captureSmartBracket() }
                        .onSuccess(onCaptured)
                        .onFailure { Toast.makeText(context, it.message ?: "Erreur caméra", Toast.LENGTH_LONG).show(); busy = false }
                }
            }
        }
    }
}

@Composable
private fun ProcessingPage(text: String, progress: Int?) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(26.dp))
        Text(text, fontSize = 21.sp)
        if (progress != null) {
            Spacer(Modifier.height(10.dp))
            Text("$progress %", fontSize = 18.sp)
        }
        Spacer(Modifier.height(12.dp))
        Text("Ne fermez pas l'application.", fontSize = 16.sp, color = Color.Gray)
    }
}

@Composable
private fun ResultPage(bitmap: Bitmap, onSave: () -> Unit, onRetake: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color.White).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("PHOTO PRÊTE ✓", fontSize = 26.sp, modifier = Modifier.padding(12.dp))
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Résultat",
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentScale = ContentScale.Fit,
        )
        Spacer(Modifier.height(14.dp))
        BigButton("ENREGISTRER LA PHOTO", onClick = onSave)
        Spacer(Modifier.height(10.dp))
        BigButton("PRENDRE UNE AUTRE", onClick = onRetake, secondary = true)
    }
}

@Composable
private fun BigButton(text: String, enabled: Boolean = true, secondary: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(64.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (secondary) Color(0xFFE8E8E8) else Color(0xFF111111),
            contentColor = if (secondary) Color.Black else Color.White,
        ),
    ) { Text(text, fontSize = 18.sp) }
}
