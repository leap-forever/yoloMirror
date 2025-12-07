package com.example.myapplication

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import kotlinx.coroutines.delay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
    onImageCaptured: (ImageProxy) -> Unit = { _ -> }
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    val cameraProviderFuture = remember {
        ProcessCameraProvider.getInstance(context)
    }
    
    val executor = remember { 
        Executors.newSingleThreadExecutor()
    }
    
    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            previewView
        },
        modifier = modifier,
        update = { previewView ->
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                
                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    // Set target resolution to reduce processing load
                    .setTargetResolution(android.util.Size(640, 480))
                    .build()
                    .also {
                        it.setAnalyzer(executor) { image ->
                            try {
                                // Directly call onImageCaptured without Thread.sleep
                                onImageCaptured(image)
                            } catch (e: Exception) {
                                Log.e("CameraPreview", "Error analyzing image", e)
                            } finally {
                                image.close()
                            }
                        }
                    }
                
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalyzer
                    )
                    Log.d("CameraPreview", "Camera successfully bound")
                } catch (ex: Exception) {
                    Log.e("CameraPreview", "Use case binding failed", ex)
                }
            }, ContextCompat.getMainExecutor(context))
        }
    )
}

// Detection result data class
data class DetectionResult(
    val boundingBox: android.graphics.RectF,
    val confidence: Float,
    val className: String
)

// Overlay composable to draw detection results
@Composable
fun DetectionOverlay(
    detectionResults: List<DetectionResult>,
    imageSize: android.graphics.Point,
    viewSize: android.graphics.Point,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val scaleX = viewSize.x.toFloat() / imageSize.x
        val scaleY = viewSize.y.toFloat() / imageSize.y
        
        detectionResults.forEach { result ->
            val left = result.boundingBox.left * scaleX
            val top = result.boundingBox.top * scaleY
            val right = result.boundingBox.right * scaleX
            val bottom = result.boundingBox.bottom * scaleY
            
            // Draw bounding box
            drawRect(
                color = androidx.compose.ui.graphics.Color.Red,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                style = Stroke(width = 4f)
            )
            
            // Draw label
            drawContext.canvas.nativeCanvas.apply {
                val paint = Paint().apply {
                    color = Color.RED
                    textSize = 40f
                    isAntiAlias = true
                    style = Paint.Style.FILL
                }
                
                drawText(
                    "${result.className} ${String.format("%.2f", result.confidence)}",
                    left,
                    top - 10,
                    paint
                )
            }
        }
    }
}