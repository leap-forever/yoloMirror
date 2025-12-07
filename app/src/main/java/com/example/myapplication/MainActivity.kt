package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageProxy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.myapplication.ui.theme.MyApplicationTheme
import java.io.ByteArrayOutputStream

class MainActivity : ComponentActivity() {
    private val CAMERA_PERMISSION_REQUEST_CODE = 1001
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Check camera permission
        if (allPermissionsGranted()) {
            setContent {
                MyApplicationTheme {
                    CameraScreen()
                }
            }
        } else {
            requestPermissions()
        }
    }
    
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }
    
    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this, REQUIRED_PERMISSIONS, CAMERA_PERMISSION_REQUEST_CODE
        )
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (allPermissionsGranted()) {
                setContent {
                    MyApplicationTheme {
                        CameraScreen()
                    }
                }
            } else {
                Toast.makeText(this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
    
    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}

@Composable
fun CameraScreen(modifier: Modifier = Modifier) {
    var isBackCamera by remember { mutableStateOf(true) }
    val cameraSelector = if (isBackCamera) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
    
    val context = LocalContext.current
    val objectDetector = remember { ObjectDetector(context) }
    var detectionResults by remember { mutableStateOf<List<DetectionResult>>(emptyList()) }
    var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    val imageSize = remember { android.graphics.Point(640, 640) } // Default size
    val viewSize = remember { android.graphics.Point(1080, 1920) } // Default size
    
    DisposableEffect(objectDetector) {
        onDispose {
            objectDetector.close()
        }
    }
    
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.Bottom
            ) {
                Button(
                    onClick = { isBackCamera = !isBackCamera }
                ) {
                    Text(if (isBackCamera) "切换到前置摄像头" else "切换到后置摄像头")
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = modifier.fillMaxSize().padding(innerPadding)) {
            CameraPreview(
                modifier = modifier.fillMaxSize(),
                cameraSelector = cameraSelector,
                onImageCaptured = { image: ImageProxy ->
                    // Convert ImageProxy to Bitmap
                    val bitmap = imageProxyToBitmap(image)
                    bitmap?.let {
                        imageBitmap = it.asImageBitmap()
                        
                        // Run object detection
                        val results = objectDetector.detect(it)
                        detectionResults = results
                        
                        // Update image size for overlay
                        imageSize.set(it.width, it.height)
                    }
                    
                    image.close()
                }
            )
            
            // Display detection results overlay
            imageBitmap?.let {
                DetectionOverlay(
                    detectionResults = detectionResults,
                    imageSize = imageSize,
                    viewSize = viewSize,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
    val planeProxy = image.planes[0]
    val buffer = planeProxy.buffer
    val imageData = ByteArray(buffer.remaining()).also { buffer.get(it) }
    
    val yuvImage = YuvImage(
        imageData,
        ImageFormat.NV21,
        image.width,
        image.height,
        null
    )
    
    val outputStream = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 100, outputStream)
    val jpegData = outputStream.toByteArray()
    
    return BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    // Original greeting function kept for preview
    androidx.compose.material3.Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    MyApplicationTheme {
        Greeting("Android")
    }
}