package dev.plume

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View

/** A full-resolution photo layer on TVs that render the app window at 1080p. */
internal class PhotoWallpaperSurface(private val view: SurfaceView) : SurfaceHolder.Callback {
    private var photo: Bitmap? = null
    private var onDraw: ((Boolean) -> Unit)? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        view.holder.setFixedSize(UHD_WIDTH, UHD_HEIGHT)
        view.holder.addCallback(this)
    }

    fun show(bitmap: Bitmap, done: (Boolean) -> Unit) {
        photo = bitmap
        onDraw = done
        view.visibility = View.VISIBLE
        if (view.holder.surface.isValid) draw(view.holder)
    }

    fun clear() {
        photo = null
        onDraw = null
        view.visibility = View.GONE
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit
    override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = draw(holder)

    private fun draw(holder: SurfaceHolder) {
        val bitmap = photo ?: return
        val ok = try {
            val canvas = holder.lockHardwareCanvas()
            try {
                canvas.drawColor(Color.BLACK)
                canvas.drawBitmap(bitmap, null, Rect(0, 0, canvas.width, canvas.height), paint)
                canvas.width == UHD_WIDTH && canvas.height == UHD_HEIGHT
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        } catch (_: RuntimeException) {
            false
        } catch (_: OutOfMemoryError) {
            false
        }
        val done = onDraw
        if (!ok) clear()
        done?.invoke(ok)
    }
}
