package com.example.aicfacialrecognition

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.camera.core.CameraSelector
import com.google.mlkit.vision.face.Face
import kotlin.math.max

class FaceBoxOverlay(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val paint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 8.0f
    }

    private var faces: List<Face> = emptyList()
    private var imageWidth: Int = 0
    private var imageHeight: Int = 0
    private var cameraSelector: Int = CameraSelector.LENS_FACING_FRONT

    fun drawFaces(faces: List<Face>, imageWidth: Int, imageHeight: Int, cameraSelector: Int) {
        this.faces = faces
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        this.cameraSelector = cameraSelector
        invalidate()
    }

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (faces.isEmpty() || imageWidth == 0 || imageHeight == 0) {
            return
        }

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        val scaleX = viewWidth / imageHeight.toFloat()
        val scaleY = viewHeight / imageWidth.toFloat()
        
        val scale = max(scaleX, scaleY)

        val offsetX = (viewWidth - imageHeight * scale) / 2f
        val offsetY = (viewHeight - imageWidth * scale) / 2f

        for (face in faces) {
            val boundingBox = face.boundingBox
            val translatedBoundingBox = RectF(
                boundingBox.left.toFloat(),
                boundingBox.top.toFloat(),
                boundingBox.right.toFloat(),
                boundingBox.bottom.toFloat()
            )
            
            translatedBoundingBox.left = translatedBoundingBox.left * scale + offsetX
            translatedBoundingBox.top = translatedBoundingBox.top * scale + offsetY
            translatedBoundingBox.right = translatedBoundingBox.right * scale + offsetX
            translatedBoundingBox.bottom = translatedBoundingBox.bottom * scale + offsetY

            if (cameraSelector == CameraSelector.LENS_FACING_FRONT) {
                val left = translatedBoundingBox.left
                translatedBoundingBox.left = viewWidth - translatedBoundingBox.right
                translatedBoundingBox.right = viewWidth - left
            }
            
            canvas.drawRect(translatedBoundingBox, paint)
        }
    }
}
