package ro.bara.whatsappcontactphotosync

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/** Lets the user drag a crop box over a displayed bitmap to pick the photo region. */
class CropOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var imageWidth = 0
    var imageHeight = 0

    // Crop box, normalized [0,1] relative to the image (not the view).
    var cropLeft = 0.04f
    var cropTop = 0.04f
    var cropRight = 0.96f
    var cropBottom = 0.96f

    private val dimPaint = Paint().apply { color = Color.parseColor("#AA000000") }
    private val boxPaint = Paint().apply {
        color = Color.parseColor("#25D366")
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val handlePaint = Paint().apply {
        color = Color.parseColor("#25D366")
        style = Paint.Style.FILL
    }

    private enum class DragMode { NONE, MOVE, LEFT, TOP, RIGHT, BOTTOM, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    private var dragMode = DragMode.NONE
    private var lastX = 0f
    private var lastY = 0f
    private val touchSlop = 60f
    private val minSize = 0.05f

    private fun imageRect(): RectF {
        if (imageWidth == 0 || imageHeight == 0 || width == 0 || height == 0) {
            return RectF(0f, 0f, width.toFloat(), height.toFloat())
        }
        val viewRatio = width.toFloat() / height
        val imgRatio = imageWidth.toFloat() / imageHeight
        return if (imgRatio > viewRatio) {
            val h = width / imgRatio
            val top = (height - h) / 2f
            RectF(0f, top, width.toFloat(), top + h)
        } else {
            val w = height * imgRatio
            val left = (width - w) / 2f
            RectF(left, 0f, left + w, height.toFloat())
        }
    }

    private fun cropRectPx(): RectF {
        val img = imageRect()
        return RectF(
            img.left + cropLeft * img.width(),
            img.top + cropTop * img.height(),
            img.left + cropRight * img.width(),
            img.top + cropBottom * img.height()
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val box = cropRectPx()

        canvas.drawRect(0f, 0f, width.toFloat(), box.top, dimPaint)
        canvas.drawRect(0f, box.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, box.top, box.left, box.bottom, dimPaint)
        canvas.drawRect(box.right, box.top, width.toFloat(), box.bottom, dimPaint)

        canvas.drawRect(box, boxPaint)
        val r = 16f
        for ((cx, cy) in listOf(
            box.left to box.top, box.right to box.top,
            box.left to box.bottom, box.right to box.bottom
        )) {
            canvas.drawCircle(cx, cy, r, handlePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val img = imageRect()
        if (img.width() <= 0 || img.height() <= 0) return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragMode = detectMode(event.x, event.y, cropRectPx())
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                lastX = event.x
                lastY = event.y
                applyDrag(dragMode, dx, dy, img)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragMode = DragMode.NONE
        }
        return true
    }

    private fun detectMode(x: Float, y: Float, box: RectF): DragMode {
        val nearLeft = abs(x - box.left) < touchSlop
        val nearRight = abs(x - box.right) < touchSlop
        val nearTop = abs(y - box.top) < touchSlop
        val nearBottom = abs(y - box.bottom) < touchSlop

        return when {
            nearLeft && nearTop -> DragMode.TOP_LEFT
            nearRight && nearTop -> DragMode.TOP_RIGHT
            nearLeft && nearBottom -> DragMode.BOTTOM_LEFT
            nearRight && nearBottom -> DragMode.BOTTOM_RIGHT
            nearLeft -> DragMode.LEFT
            nearRight -> DragMode.RIGHT
            nearTop -> DragMode.TOP
            nearBottom -> DragMode.BOTTOM
            x in box.left..box.right && y in box.top..box.bottom -> DragMode.MOVE
            else -> DragMode.NONE
        }
    }

    private fun applyDrag(mode: DragMode, dx: Float, dy: Float, img: RectF) {
        val dxFrac = dx / img.width()
        val dyFrac = dy / img.height()

        fun clamp01(v: Float) = v.coerceIn(0f, 1f)

        when (mode) {
            DragMode.MOVE -> {
                var newLeft = cropLeft + dxFrac
                var newRight = cropRight + dxFrac
                var newTop = cropTop + dyFrac
                var newBottom = cropBottom + dyFrac
                if (newLeft < 0f) { newRight -= newLeft; newLeft = 0f }
                if (newRight > 1f) { newLeft -= (newRight - 1f); newRight = 1f }
                if (newTop < 0f) { newBottom -= newTop; newTop = 0f }
                if (newBottom > 1f) { newTop -= (newBottom - 1f); newBottom = 1f }
                cropLeft = newLeft; cropRight = newRight; cropTop = newTop; cropBottom = newBottom
            }
            DragMode.LEFT -> cropLeft = clamp01(cropLeft + dxFrac).coerceAtMost(cropRight - minSize)
            DragMode.RIGHT -> cropRight = clamp01(cropRight + dxFrac).coerceAtLeast(cropLeft + minSize)
            DragMode.TOP -> cropTop = clamp01(cropTop + dyFrac).coerceAtMost(cropBottom - minSize)
            DragMode.BOTTOM -> cropBottom = clamp01(cropBottom + dyFrac).coerceAtLeast(cropTop + minSize)
            DragMode.TOP_LEFT -> {
                cropLeft = clamp01(cropLeft + dxFrac).coerceAtMost(cropRight - minSize)
                cropTop = clamp01(cropTop + dyFrac).coerceAtMost(cropBottom - minSize)
            }
            DragMode.TOP_RIGHT -> {
                cropRight = clamp01(cropRight + dxFrac).coerceAtLeast(cropLeft + minSize)
                cropTop = clamp01(cropTop + dyFrac).coerceAtMost(cropBottom - minSize)
            }
            DragMode.BOTTOM_LEFT -> {
                cropLeft = clamp01(cropLeft + dxFrac).coerceAtMost(cropRight - minSize)
                cropBottom = clamp01(cropBottom + dyFrac).coerceAtLeast(cropTop + minSize)
            }
            DragMode.BOTTOM_RIGHT -> {
                cropRight = clamp01(cropRight + dxFrac).coerceAtLeast(cropLeft + minSize)
                cropBottom = clamp01(cropBottom + dyFrac).coerceAtLeast(cropTop + minSize)
            }
            DragMode.NONE -> {}
        }
    }
}
