package dev.plume

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.media.ExifInterface
import android.util.AtomicFile
import java.io.File
import java.io.IOException

/** Private, bounded PNGs: icons keep transparency; banners fill a 16:9 tile. */
internal object AppPictures {
    private val packageName = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")

    fun file(context: Context, pkg: String, banner: Boolean): File {
        require(packageName.matches(pkg))
        return File(context.filesDir, "app-pictures/$pkg-${if (banner) "banner" else "icon"}.png")
    }

    fun read(context: Context, pkg: String, banner: Boolean): BitmapDrawable? = try {
        AtomicFile(file(context, pkg, banner)).openRead().use { input ->
            BitmapFactory.decodeStream(input)?.let { BitmapDrawable(context.resources, it) }
        }
    } catch (_: IOException) { null }

    fun reset(context: Context, pkg: String) {
        for (banner in listOf(false, true)) AtomicFile(file(context, pkg, banner)).delete()
    }

    fun fit(src: File, banner: Boolean): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.path, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w !in 1..8192 || h !in 1..8192 || w.toLong() * h > 32_000_000) return null
        if (bounds.outMimeType !in setOf("image/png", "image/jpeg", "image/webp")) return null
        var sample = 1
        while (maxOf(w, h) / sample > 1024) sample *= 2
        val decoded = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        var upright = decoded
        try {
            val orientation = try {
                ExifInterface(src.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } catch (_: IOException) { ExifInterface.ORIENTATION_NORMAL }
            val matrix = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
                }
            }
            if (!matrix.isIdentity) upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            val width = if (banner) 960 else 512
            val height = if (banner) 540 else 512
            val scale = if (banner) maxOf(width.toFloat() / upright.width, height.toFloat() / upright.height)
                else minOf(width.toFloat() / upright.width, height.toFloat() / upright.height)
            val dw = upright.width * scale
            val dh = upright.height * scale
            val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(upright, null, RectF((width - dw) / 2, (height - dh) / 2, (width + dw) / 2, (height + dh) / 2),
                Paint(Paint.FILTER_BITMAP_FLAG))
            return out
        } finally {
            if (upright !== decoded) upright.recycle()
            decoded.recycle()
        }
    }

    /** Failed or cancelled uploads keep the previous picture. */
    fun save(file: File, bitmap: Bitmap, active: () -> Boolean): Boolean {
        val target = AtomicFile(file)
        val stream = try { target.startWrite() } catch (_: IOException) { return false }
        return try {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream) || !active()) {
                target.failWrite(stream)
                false
            } else {
                target.finishWrite(stream)
                true
            }
        } catch (_: IOException) {
            target.failWrite(stream)
            false
        }
    }
}
