package net.devemperor.dictate.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import net.devemperor.dictate.R
import kotlin.math.min

/**
 * A reusable FrameLayout wrapper that draws expanding, fading ripple circles
 * behind its child view. Wrap any button or view in this layout to add a
 * pulsating ripple effect.
 *
 * Configuration via XML attributes or programmatically:
 * - [pulseCount]: Number of concurrent ripple circles (default: 3)
 * - [pulseDuration]: Full cycle duration in ms (default: 2000)
 * - [pulseColor]: Circle fill color (default: current accent color)
 * - [pulseMaxRadiusFactor]: Max radius as factor of button size (default: 1.6)
 * - [pulseStartAlpha]: Starting alpha for each circle (default: 0.3)
 * - [pulseStyle]: Fill or stroke rendering (default: fill)
 * - [pulseStrokeWidth]: Stroke width in dp when using stroke style (default: 2)
 *
 * Usage in XML:
 * ```xml
 * <net.devemperor.dictate.widget.PulseLayout
 *     app:pulseColor="@color/accent"
 *     app:pulseCount="3">
 *     <MaterialButton android:id="@+id/my_button" ... />
 * </net.devemperor.dictate.widget.PulseLayout>
 * ```
 *
 * Parent views must set `android:clipChildren="false"` for circles to expand
 * beyond this layout's bounds.
 */
class PulseLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var pulseCount: Int = 3
    var pulseDuration: Long = 2000L
    var pulseColor: Int = 0x44FF0000
    var pulseMaxRadiusFactor: Float = 1.6f
    var pulseStartAlpha: Float = 0.3f
    var pulseStyle: PulseStyle = PulseStyle.FILL
    var pulseStrokeWidth: Float = 2f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var animator: ValueAnimator? = null
    private val density = context.resources.displayMetrics.density

    enum class PulseStyle { FILL, STROKE }

    init {
        // FrameLayout skips onDraw by default -- we need it for drawing circles
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false

        attrs?.let {
            val ta = context.obtainStyledAttributes(it, R.styleable.PulseLayout)
            pulseCount = ta.getInt(R.styleable.PulseLayout_pulseCount, 3)
            pulseDuration = ta.getInt(R.styleable.PulseLayout_pulseDuration, 2000).toLong()
            pulseColor = ta.getColor(R.styleable.PulseLayout_pulseColor, 0x44FF0000)
            pulseMaxRadiusFactor = ta.getFloat(R.styleable.PulseLayout_pulseMaxRadiusFactor, 1.6f)
            pulseStartAlpha = ta.getFloat(R.styleable.PulseLayout_pulseStartAlpha, 0.3f)
            pulseStrokeWidth = ta.getFloat(R.styleable.PulseLayout_pulseStrokeWidth, 2f)
            val styleOrdinal = ta.getInt(R.styleable.PulseLayout_pulseStyle, 0)
            pulseStyle = PulseStyle.entries.getOrElse(styleOrdinal) { PulseStyle.FILL }
            ta.recycle()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val anim = animator ?: return
        if (!anim.isRunning && !anim.isPaused) return

        val cx = width / 2f
        val cy = height / 2f
        val minRadius = min(width, height) / 2f
        val maxRadius = minRadius * pulseMaxRadiusFactor

        paint.color = pulseColor
        paint.style = when (pulseStyle) {
            PulseStyle.FILL -> Paint.Style.FILL
            PulseStyle.STROKE -> Paint.Style.STROKE
        }
        if (pulseStyle == PulseStyle.STROKE) {
            paint.strokeWidth = pulseStrokeWidth * density
        }

        for (i in 0 until pulseCount) {
            val progress = (anim.animatedFraction + i.toFloat() / pulseCount) % 1f
            paint.alpha = ((1f - progress) * pulseStartAlpha * 255).toInt()
            val radius = minRadius + (maxRadius - minRadius) * progress
            canvas.drawCircle(cx, cy, radius, paint)
        }
    }

    fun startPulse() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = pulseDuration
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { invalidate() }
            start()
        }
    }

    fun pausePulse() {
        animator?.takeIf { it.isRunning }?.pause()
    }

    fun resumePulse() {
        animator?.takeIf { it.isPaused }?.resume()
    }

    fun stopPulse() {
        animator?.cancel()
        animator = null
        invalidate()
    }

    val isPulsing: Boolean
        get() = animator?.isRunning == true

    val isPulsePaused: Boolean
        get() = animator?.isPaused == true

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }
}
