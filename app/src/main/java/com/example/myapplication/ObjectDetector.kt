package com.example.myapplication

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

class ObjectDetector(private val context: Context) {
    private var interpreter: Interpreter? = null
    private val labels = listOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
        "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
        "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
        "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
        "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
        "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
        "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair",
        "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
        "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator",
        "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
    )

    init {
        try {
            interpreter = Interpreter(loadModelFile())
        } catch (e: Exception) {
            Log.e("ObjectDetector", "Error initializing TensorFlow Lite interpreter", e)
        }
    }

    @Throws(IOException::class)
    private fun loadModelFile(): MappedByteBuffer {
        val modelFile = context.assets.openFd("yolov11n.tflite")
        val inputStream = FileInputStream(modelFile.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = modelFile.startOffset
        val declaredLength = modelFile.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun detect(bitmap: Bitmap): List<DetectionResult> {
        if (interpreter == null) {
            return emptyList()
        }

        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, false)
        
        // Preprocessing
        val byteBuffer = convertBitmapToByteBuffer(resizedBitmap)
        
        // Output arrays - YOLO models typically have a single output tensor
        val output = Array(1) { FloatArray(DETECTION_COUNT * (NUM_CLASSES + 5)) }  // [1, 8400, 85] where 85 = 4 bbox + 1 objectness + 80 classes
        
        // Run inference
        interpreter?.run(byteBuffer, output)
        
        // Post-processing
        return parseDetectionResult(output[0], bitmap.width, bitmap.height)
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3)
        byteBuffer.order(ByteOrder.nativeOrder())
        
        val intValues = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        
        var pixel = 0
        for (i in 0 until INPUT_SIZE) {
            for (j in 0 until INPUT_SIZE) {
                val pixelValue = intValues[pixel++]
                // Normalize the pixel values to [0, 1]
                byteBuffer.putFloat((pixelValue shr 16 and 0xFF) / 255.0f) // Red
                byteBuffer.putFloat((pixelValue shr 8 and 0xFF) / 255.0f)  // Green
                byteBuffer.putFloat((pixelValue and 0xFF) / 255.0f)         // Blue
            }
        }
        return byteBuffer
    }

    private fun parseDetectionResult(
        output: FloatArray,
        imageWidth: Int,
        imageHeight: Int
    ): List<DetectionResult> {
        val results = mutableListOf<DetectionResult>()
        
        // Reshape output from [1, 8400, 85] to [8400, 85]
        val detections = mutableListOf<FloatArray>()
        for (i in 0 until DETECTION_COUNT) {
            val detection = FloatArray(NUM_CLASSES + 5)
            for (j in 0 until (NUM_CLASSES + 5)) {
                detection[j] = output[i * (NUM_CLASSES + 5) + j]
            }
            detections.add(detection)
        }
        
        for (detection in detections) {
            val objectness = detection[4]
            
            // Check if objectness is above threshold
            if (objectness > PROBABILITY_THRESHOLD) {
                // Extract bounding box coordinates
                val xCenter = detection[0] * imageWidth
                val yCenter = detection[1] * imageHeight
                val w = detection[2] * imageWidth
                val h = detection[3] * imageHeight
                
                // Convert center coordinates to corner coordinates
                val left = max(0f, xCenter - w / 2)
                val top = max(0f, yCenter - h / 2)
                val right = min(imageWidth.toFloat(), xCenter + w / 2)
                val bottom = min(imageHeight.toFloat(), yCenter + h / 2)
                
                // Find the class with highest confidence
                var maxClassScore = 0f
                var classIndex = 0
                for (i in 0 until NUM_CLASSES) {
                    val classScore = detection[5 + i] * objectness
                    if (classScore > maxClassScore) {
                        maxClassScore = classScore
                        classIndex = i
                    }
                }
                
                // Check if class confidence is above threshold
                if (maxClassScore > PROBABILITY_THRESHOLD) {
                    val boundingBox = android.graphics.RectF(left, top, right, bottom)
                    
                    results.add(
                        DetectionResult(
                            boundingBox = boundingBox,
                            confidence = maxClassScore,
                            className = if (classIndex < labels.size) labels[classIndex] else "Unknown"
                        )
                    )
                }
            }
        }
        
        return results.filterNonMaxSuppression()
    }

    // Simple NMS implementation
    private fun List<DetectionResult>.filterNonMaxSuppression(): List<DetectionResult> {
        if (this.isEmpty()) return this
        
        val sorted = this.sortedByDescending { it.confidence }
        val selected = mutableListOf<DetectionResult>()
        val visited = BooleanArray(this.size) { false }
        
        for (i in sorted.indices) {
            if (visited[i]) continue
            
            val current = sorted[i]
            selected.add(current)
            
            for (j in i + 1 until sorted.size) {
                if (visited[j]) continue
                
                val other = sorted[j]
                val iou = calculateIoU(current.boundingBox, other.boundingBox)
                if (iou > IOU_THRESHOLD) {
                    visited[j] = true
                }
            }
        }
        
        return selected
    }
    
    private fun calculateIoU(box1: android.graphics.RectF, box2: android.graphics.RectF): Float {
        val intersectionLeft = max(box1.left, box2.left)
        val intersectionTop = max(box1.top, box2.top)
        val intersectionRight = min(box1.right, box2.right)
        val intersectionBottom = min(box1.bottom, box2.bottom)
        
        if (intersectionRight <= intersectionLeft || intersectionBottom <= intersectionTop) {
            return 0f
        }
        
        val intersectionArea = (intersectionRight - intersectionLeft) * (intersectionBottom - intersectionTop)
        val box1Area = (box1.right - box1.left) * (box1.bottom - box1.top)
        val box2Area = (box2.right - box2.left) * (box2.bottom - box2.top)
        val unionArea = box1Area + box2Area - intersectionArea
        
        return intersectionArea / unionArea
    }

    fun isModelLoaded(): Boolean {
        return interpreter != null
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }

    companion object {
        private const val INPUT_SIZE = 640
        private const val DETECTION_COUNT = 8400
        private const val NUM_CLASSES = 80  // COCO dataset has 80 classes
        private const val PROBABILITY_THRESHOLD = 0.5f
        private const val IOU_THRESHOLD = 0.2f
    }
}