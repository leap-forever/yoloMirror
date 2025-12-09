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

        val originalWidth = bitmap.width
        val originalHeight = bitmap.height
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, false)
        
        // Preprocessing
        val byteBuffer = convertBitmapToByteBuffer(resizedBitmap)
        ¬
        try {
            // 根据错误信息，模型输出是 [1, 8400, 4]
            // 创建一个符合该形状的输出缓冲区
            val output = Array(1) { Array(DETECTION_COUNT) { FloatArray(4) } }
            
            Log.d("ObjectDetector", "Input bitmap: ${originalWidth}x${originalHeight}, Resized: ${INPUT_SIZE}x${INPUT_SIZE}")
            
            // 运行推理
            interpreter?.run(byteBuffer, output)
            
            // 解析输出，使用原始图像尺寸，而不是处理后的尺寸
            return parseDetectionResult(output[0], originalWidth, originalHeight)
        } catch (e: Exception) {
            Log.e("ObjectDetector", "Error during detection", e)
            return emptyList()
        }
    }
    
    // 更灵活的检测结果提取方法
    private fun extractDetectionsFromOutput(
        output: FloatArray,
        imageWidth: Int,
        imageHeight: Int
    ): List<DetectionResult> {
        val results = mutableListOf<DetectionResult>()
        
        // 假设输出是扁平化的 [1, 8400, N] 其中 N 是每个检测的特征数
        val numDetections = min(output.size / 4, DETECTION_COUNT)  // 假设每个检测至少有4个值
        
        for (i in 0 until numDetections) {
            val startIndex = i * 4
            
            // 确保不会越界
            if (startIndex + 3 >= output.size) break
            
            // 提取边界框坐标 (假设是YOLO格式: x_center, y_center, width, height)
            val xCenter = output[startIndex] * imageWidth
            val yCenter = output[startIndex + 1] * imageHeight
            val width = output[startIndex + 2] * imageWidth
            val height = output[startIndex + 3] * imageHeight
            
            // 转换为左上角和右下角坐标
            val left = max(0f, xCenter - width / 2)
            val top = max(0f, yCenter - height / 2)
            val right = min(imageWidth.toFloat(), xCenter + width / 2)
            val bottom = min(imageHeight.toFloat(), yCenter + height / 2)
            
            // 只添加有效的检测框
            if (right > left && bottom > top && width > 0 && height > 0) {
                val boundingBox = android.graphics.RectF(left, top, right, bottom)
                
                results.add(
                    DetectionResult(
                        boundingBox = boundingBox,
                        confidence = 0.8f,  // 默认置信度
                        className = "Object"  // 默认类别
                    )
                )
            }
        }
        
        // 应用NMS去除重叠的检测框
        return results.filterNonMaxSuppression()
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
        boxes: Array<FloatArray>,
        imageWidth: Int,
        imageHeight: Int
    ): List<DetectionResult> {
        val results = mutableListOf<DetectionResult>()
        
        Log.d("ObjectDetector", "Processing ${boxes.size} detection boxes for image size ${imageWidth}x${imageHeight}")
        
        // 尝试只处理前几个检测框，并降低阈值
        val maxDetections = 5
        
        for (i in 0 until maxDetections) {
            if (i >= boxes.size) break
            
            val box = boxes[i]
            
            Log.d("ObjectDetector", "Raw box $i: [${box[0]},${box[1]},${box[2]},${box[3]}]")
            
            // Extract bounding box coordinates - YOLO outputs are normalized [0, 1]
            val xCenter = box[0]
            val yCenter = box[1]
            val w = box[2]
            val h = box[3]
            
            // Skip invalid boxes
            if (w <= 0 || h <= 0) continue
            
            // Convert center coordinates to corner coordinates (still normalized)
            val leftNorm = max(0f, xCenter - w / 2)
            val topNorm = max(0f, yCenter - h / 2)
            val rightNorm = min(1f, xCenter + w / 2)
            val bottomNorm = min(1f, yCenter + h / 2)
            
            // Convert normalized coordinates to pixel coordinates
            val left = leftNorm * imageWidth
            val top = topNorm * imageHeight
            val right = rightNorm * imageWidth
            val bottom = bottomNorm * imageHeight
            
            Log.d("ObjectDetector", "Box $i: normalized=[$leftNorm,$topNorm,$rightNorm,$bottomNorm] pixel=[$left,$top,$right,$bottom]")
            
            // Only add detections with valid boxes (non-zero area)
            if ((right - left) > 10 && (bottom - top) > 10) {  // 降低最小尺寸要求
                val boundingBox = android.graphics.RectF(left, top, right, bottom)
                
                results.add(
                    DetectionResult(
                        boundingBox = boundingBox,
                        confidence = 0.9f,  // 提高置信度以便更容易看到
                        className = "Object"  // 暂时使用固定类别，因为模型没有输出类别信息
                    )
                )
            }
        }
        
        Log.d("ObjectDetector", "Returning ${results.size} valid detection boxes")
        
        // 暂时禁用NMS以简化调试
        return results
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