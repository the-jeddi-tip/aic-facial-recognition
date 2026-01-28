package com.example.aicfacialrecognition

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.common.util.concurrent.ListenableFuture
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: PreviewView
    private lateinit var faceBoxOverlay: FaceBoxOverlay
    private lateinit var switchCameraButton: FloatingActionButton
    private var lensFacing = CameraSelector.LENS_FACING_FRONT
    private var compiledModel: CompiledModel? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                startCamera()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.previewView)
        faceBoxOverlay = findViewById(R.id.face_box_overlay)
        switchCameraButton = findViewById(R.id.switch_camera_button)
        cameraExecutor = Executors.newSingleThreadExecutor()

        switchCameraButton.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                CameraSelector.LENS_FACING_BACK
            } else {
                CameraSelector.LENS_FACING_FRONT
            }
            startCamera()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        try {
            val modelPath = "assets/mobile_face_net.tflite"
            val modelFileDescriptor = assets.openFd(modelPath)
            val modelByteBuffer = modelFileDescriptor.createInputStream().channel.map(
                java.nio.channels.FileChannel.MapMode.READ_ONLY,
                modelFileDescriptor.startOffset,
                modelFileDescriptor.declaredLength
            )

            compiledModel = CompiledModel.create(
                modelPath,
                CompiledModel.Options(Accelerator.CPU) // Change to GPU if available
            )
        } catch (e: IOException) {
            Log.e(TAG, "Error loading TFLite model", e)
        }
    }

    private fun startCamera() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            bindPreview(cameraProvider)
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun bindPreview(cameraProvider: ProcessCameraProvider) {
        val preview = Preview.Builder().build()
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()
        preview.surfaceProvider = previewView.surfaceProvider

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val image = imageProxy.image
            if (image != null) {
                val processImage = InputImage.fromMediaImage(image, rotationDegrees)
                val highAccuracyOpts = FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                    .build()

                val detector = FaceDetection.getClient(highAccuracyOpts)
                detector.process(processImage)
                    .addOnSuccessListener { faces ->
                        faceBoxOverlay.drawFaces(faces, imageProxy.width, imageProxy.height, lensFacing)
                        if (faces.isNotEmpty()) {
                            // Convert ImageProxy to Bitmap for cropping
                            val bitmap = imageProxyToBitmap(imageProxy)
                            if (bitmap != null) {
                                // Crop the face from the original image
                                val face = faces[0] // process first face
                                val faceBitmap = cropFace(bitmap, face, imageProxy.imageInfo.rotationDegrees)
                                // TODO: Run inference on the cropped face bitmap.
                            }
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Face detection failed", e)
                    }
                    .addOnCompleteListener {
                        imageProxy.close()
                    }
            }
        }
        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        val yBuffer = image.planes[0].buffer // Y
        val uBuffer = image.planes[1].buffer // U
        val vBuffer = image.planes[2].buffer // V

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 100, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    private fun cropFace(bitmap: Bitmap, face: Face, rotationDegrees: Int): Bitmap {
        val imageRect = Rect(0, 0, bitmap.width, bitmap.height)
        val boundingBox = face.boundingBox

        val clippedBoundingBox = Rect(boundingBox)
        if (!clippedBoundingBox.intersect(imageRect)) {
            return bitmap
        }

        val croppedBitmap = Bitmap.createBitmap(
            bitmap,
            clippedBoundingBox.left,
            clippedBoundingBox.top,
            clippedBoundingBox.width(),
            clippedBoundingBox.height()
        )

        Log.d(TAG, "Original bitmap: ${bitmap.width}x${bitmap.height}, Cropped bitmap: ${croppedBitmap.width}x${croppedBitmap.height}")

        if (rotationDegrees != 0) {
            val matrix = Matrix()
            matrix.postRotate(rotationDegrees.toFloat())
            return Bitmap.createBitmap(
                croppedBitmap, 0, 0,
                croppedBitmap.width, croppedBitmap.height, matrix, true
            )
        }

        return croppedBitmap
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
