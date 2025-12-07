package com.example.myapplication

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Build
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import kotlin.math.min
import kotlin.math.max
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
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.myapplication.ui.theme.MyApplicationTheme
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val CAMERA_PERMISSION_REQUEST_CODE = 1001
    private val NOTIFICATION_PERMISSION_REQUEST_CODE = 1002
    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "ObjectDetection"
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        createNotificationChannel()
        
        // Check camera permission
        if (allPermissionsGranted()) {
            // Check notification permission for Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                    != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(
                        this, 
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS), 
                        NOTIFICATION_PERMISSION_REQUEST_CODE
                    )
                } else {
                    setContent {
                        MyApplicationTheme {
                            CameraScreen()
                        }
                    }
                }
            } else {
                setContent {
                    MyApplicationTheme {
                        CameraScreen()
                    }
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
        when (requestCode) {
            CAMERA_PERMISSION_REQUEST_CODE -> {
                if (allPermissionsGranted()) {
                    // Check notification permission for Android 13+
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                            != PackageManager.PERMISSION_GRANTED) {
                            ActivityCompat.requestPermissions(
                                this, 
                                arrayOf(Manifest.permission.POST_NOTIFICATIONS), 
                                NOTIFICATION_PERMISSION_REQUEST_CODE
                            )
                        } else {
                            setContent {
                                MyApplicationTheme {
                                    CameraScreen()
                                }
                            }
                        }
                    } else {
                        setContent {
                            MyApplicationTheme {
                                CameraScreen()
                            }
                        }
                    }
                } else {
                    Toast.makeText(this,
                        "Permissions not granted by the user.",
                        Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            NOTIFICATION_PERMISSION_REQUEST_CODE -> {
                // Notification permission result - regardless of result, we still show the UI
                setContent {
                    MyApplicationTheme {
                        CameraScreen()
                    }
                }
            }
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Object Detection"
            val descriptionText = "Notifications for detected objects"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    fun showDetectionNotification(className: String, confidence: Float) {
        // Use default app icon instead of potentially missing ic_launcher_foreground
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Object Detected")
            .setContentText("$className detected with ${String.format("%.2f", confidence * 100)}% confidence")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        try {
            with(NotificationManagerCompat.from(this)) {
                if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) 
                    == PackageManager.PERMISSION_GRANTED) {
                    notify(NOTIFICATION_ID, builder.build())
                }
            }
        } catch (e: SecurityException) {
            Log.e("MainActivity", "Failed to show notification", e)
        }
    }
    
    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
fun CameraScreen(modifier: Modifier = Modifier) {
    var isBackCamera by remember { mutableStateOf(true) }
    val cameraSelector = if (isBackCamera) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
    
    val context = LocalContext.current as MainActivity
    val objectDetector = remember { ObjectDetector(context) }
    var detectionResults by remember { mutableStateOf<List<DetectionResult>>(emptyList()) }
    var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    val imageSize = remember { android.graphics.Point(640, 640) } // Default size
    val viewSize = remember { android.graphics.Point(1080, 1920) } // Default size
    var isDetecting by remember { mutableStateOf(false) }
    var lastDetectionTime by remember { mutableStateOf(0L) }
    var processingImage by remember { mutableStateOf(false) }
    
    DisposableEffect(objectDetector) {
        onDispose {
            objectDetector.close()
        }
    }
    
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { isBackCamera = !isBackCamera }
                ) {
                    Text(if (isBackCamera) "切换到前置摄像头" else "切换到后置摄像头")
                }
                
                Button(
                    onClick = { 
                        isDetecting = !isDetecting
                        // Always clear detection results when toggling the button
                        detectionResults = emptyList()
                    }
                ) {
                    Text(if (isDetecting) "停止检测" else "开始检测")
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = modifier.fillMaxSize().padding(innerPadding)) {
            CameraPreview(
                modifier = Modifier.fillMaxSize(),
                cameraSelector = cameraSelector,
                onImageCaptured = { image: ImageProxy ->
                    // Skip if already processing an image to prevent overload
                    if (processingImage) {
                        return@CameraPreview
                    }
                    
                    processingImage = true
                    
                    try {
                        // Convert ImageProxy to Bitmap with optimized options
                        val bitmap = imageProxyToBitmap(image)
                        bitmap?.let {
                            // Scale down image if too large to prevent memory issues
                            val scaledBitmap = if (it.width > 1024 || it.height > 1024) {
                                val ratio = min(1024f / it.width, 1024f / it.height)
                                val width = (it.width * ratio).toInt()
                                val height = (it.height * ratio).toInt()
                                Bitmap.createScaledBitmap(it, width, height, true)
                            } else it
                            
                            imageBitmap = scaledBitmap.asImageBitmap()
                            
                            // Run object detection only if detecting flag is true
                            if (isDetecting) {
                                // Limit detection frequency to avoid overload
                                val currentTime = System.currentTimeMillis()
                                if (currentTime - lastDetectionTime > 1000) { // At least 1 second between detections
                                    lastDetectionTime = currentTime
                                    
                                    val results = objectDetector.detect(scaledBitmap)
                                    detectionResults = results
                                    
                                    // Show notification for first detected object
                                    if (results.isNotEmpty()) {
                                        val firstResult = results.first()
                                        context.showDetectionNotification(firstResult.className, firstResult.confidence)
                                    }
                                    
                                    // Update image size for overlay
                                    imageSize.set(scaledBitmap.width, scaledBitmap.height)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error processing image", e)
                    } finally {
                        processingImage = false
                    }
                    // Note: Image will be closed in CameraPreview.kt's analyzer callback
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
    return try {
        // Use a more efficient approach with lower quality to reduce processing load
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer
        
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        
        val nv21 = ByteArray(ySize + uSize + vSize)
        
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        // Reduce JPEG quality to 70% to speed up decoding
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 70, out)
        val imageBytes = out.toByteArray()
        
        // Use BitmapFactory options to optimize decoding
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565 // Use less memory
            inSampleSize = 1 // Can be increased if needed
        }
        
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
    } catch (e: Exception) {
        Log.e("MainActivity", "Error converting image proxy to bitmap", e)
        null
    }
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