package com.sagiii.twsevaluate.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCaptureException as CxImageCaptureException
import com.sagiii.twsevaluate.data.SessionRepository
import java.io.File
import java.util.concurrent.Executor

private const val TAG = "PhotoCaptureScreen"

@Composable
fun PhotoCaptureScreen(
    repository: SessionRepository,
    onCaptured: (sessionId: String, photoPath: String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCameraPermission = granted }

    DisposableEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
        onDispose { }
    }

    if (!hasCameraPermission) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("カメラの権限が必要です")
        }
        return
    }

    val imageCapture = remember { ImageCapture.Builder().build() }
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }

    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreview(imageCapture = imageCapture, modifier = Modifier.fillMaxSize())

        Button(
            onClick = { onCancel() },
            modifier = Modifier.padding(16.dp).align(Alignment.TopStart),
        ) { Text("キャンセル") }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 32.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            ShutterButton(
                onClick = {
                    capturePhoto(
                        imageCapture = imageCapture,
                        executor = mainExecutor,
                        repository = repository,
                        onSaved = { sessionId, path -> onCaptured(sessionId, path) },
                    )
                },
            )
        }
    }
}

@Composable
private fun ShutterButton(onClick: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(72.dp)
            .padding(4.dp),
    ) {
        Button(
            onClick = onClick,
            shape = androidx.compose.foundation.shape.CircleShape,
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color.White),
            modifier = Modifier.fillMaxSize(),
        ) {
            Icon(Icons.Default.Camera, contentDescription = "撮影", tint = Color.Black)
        }
    }
}

@Composable
private fun CameraPreview(imageCapture: ImageCapture, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageCapture)
                } catch (exc: Exception) {
                    Log.e(TAG, "カメラのバインドに失敗しました", exc)
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}

private fun capturePhoto(
    imageCapture: ImageCapture,
    executor: Executor,
    repository: SessionRepository,
    onSaved: (sessionId: String, path: String) -> Unit,
) {
    val sessionId = repository.newSessionId()
    val photoFile = File(repository.sessionDir(sessionId), "photo.jpg")
    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

    imageCapture.takePicture(
        outputOptions,
        executor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                onSaved(sessionId, photoFile.absolutePath)
            }

            override fun onError(exception: CxImageCaptureException) {
                Log.e(TAG, "写真の保存に失敗しました", exception)
            }
        },
    )
}
