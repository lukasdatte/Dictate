package net.devemperor.dictate.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import net.devemperor.dictate.R

/**
 * Renders the auto-enter indicator drawable for the record button:
 *  - active: a white knockout circle with a transparent arrow cutout (PorterDuff DST_OUT)
 *  - inactive: a plain white arrow icon on a transparent background
 *
 * **Lifecycle contract:** One instance per [KeyboardUiController] lifetime. The [context] is
 * view-scoped (the record button's context). When the controller is recreated — e.g. on
 * [android.content.res.Configuration] change via `onCreateInputView` — the renderer is recreated
 * alongside it, automatically picking up a fresh Context with the current density/theme.
 *
 * Do **not** share a single renderer instance across controller recreations: the captured
 * Context may become stale.
 *
 * Both drawables are cached lazily on first access. [invalidate] drops the caches so that the
 * next [get] rebuilds them from the current context — used by `stopPipeline()` to defend against
 * density/theme changes between pipeline runs.
 */
class AutoEnterIconRenderer(private val context: Context) {
    private var cachedActive: Drawable? = null
    private var cachedInactive: Drawable? = null

    fun get(active: Boolean): Drawable =
        if (active) {
            cachedActive ?: buildActive().also { cachedActive = it }
        } else {
            cachedInactive ?: buildInactive().also { cachedInactive = it }
        }

    fun invalidate() {
        cachedActive = null
        cachedInactive = null
    }

    /**
     * Builds the "active" knockout drawable: white circle with a transparent arrow cutout.
     */
    private fun buildActive(): Drawable {
        val density = context.resources.displayMetrics.density
        val sizePx = (24 * density).toInt()

        val enterIcon = context.getDrawable(R.drawable.ic_baseline_subdirectory_arrow_left_24)?.mutate()
        enterIcon?.setBounds(0, 0, sizePx, sizePx)
        enterIcon?.setTint(Color.WHITE)

        // Render icon to a temporary bitmap, offset slightly left+up for optical centering
        val iconBitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val iconCanvas = Canvas(iconBitmap)
        val offset = -1.5f * density
        iconCanvas.translate(offset, offset)
        enterIcon?.draw(iconCanvas)

        // Create the knockout composite
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Draw white circle
        paint.color = Color.WHITE
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, paint)

        // Punch out the icon using DST_OUT on the icon bitmap
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        canvas.drawBitmap(iconBitmap, 0f, 0f, paint)
        paint.xfermode = null

        iconBitmap.recycle()

        val drawable = BitmapDrawable(context.resources, bitmap)
        drawable.setBounds(0, 0, sizePx, sizePx)
        return drawable
    }

    /**
     * Builds the "inactive" drawable: white arrow icon on a transparent background.
     */
    private fun buildInactive(): Drawable {
        val density = context.resources.displayMetrics.density
        val sizePx = (24 * density).toInt()

        val enterIcon = context.getDrawable(R.drawable.ic_baseline_subdirectory_arrow_left_24)?.mutate()!!
        enterIcon.setTint(Color.WHITE)
        enterIcon.setBounds(0, 0, sizePx, sizePx)
        return enterIcon
    }
}
