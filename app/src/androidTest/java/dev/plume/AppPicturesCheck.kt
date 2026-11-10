package dev.plume

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import java.io.File
import java.net.Socket

/** Real bitmap and HTTP checks, using separate files and no installed app's artwork. */
class AppPicturesCheck(private val runner: Instrumentation) {
    fun run() {
        val context = runner.targetContext
        val pkg = "dev.plume.picturecheck"
        val src = File(context.cacheDir, "picture-check.png")
        val temp = File(context.cacheDir, "picture-upload-check.tmp")
        val icon = AppPictures.file(context, pkg, false)
        val banner = AppPictures.file(context, pkg, true)
        val source = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        var server: UploadServer? = null
        try {
            src.outputStream().use { check(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val png = src.readBytes()
            AppPictures.fit(src, false)!!.let { picture ->
                check(picture.width == 512 && picture.height == 512)
                check(picture.getPixel(256, 0) == Color.TRANSPARENT) { "Icon lost transparent padding" }
                check(picture.getPixel(256, 256) == Color.RED)
                check(AppPictures.save(icon, picture) { true })
                picture.recycle()
            }
            val before = icon.readBytes()
            AppPictures.fit(src, true)!!.let { picture ->
                check(picture.width == 960 && picture.height == 540)
                check(picture.getPixel(0, 0) == Color.RED) { "Banner did not fill the tile" }
                check(AppPictures.save(banner, picture) { true })
                check(!AppPictures.save(icon, picture) { false })
                picture.recycle()
            }
            check(icon.readBytes().contentEquals(before)) { "Cancelled upload replaced the icon" }
            AppPictures.read(context, pkg, false)!!.bitmap.let { check(it.width == 512); it.recycle() }
            src.writeText("invalid image")
            check(AppPictures.fit(src, false) == null)
            check(icon.readBytes().contentEquals(before))
            Bitmap.createBitmap(8193, 1, Bitmap.Config.ARGB_8888).let { oversized ->
                src.outputStream().use { oversized.compress(Bitmap.CompressFormat.PNG, 100, it) }
                oversized.recycle()
            }
            check(AppPictures.fit(src, false) == null) { "Oversized image was accepted" }
            src.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            ExifInterface(src.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            AppPictures.fit(src, false)!!.let { picture ->
                check(Color.alpha(picture.getPixel(0, 256)) == 0) { "Portrait rotation was lost" }
                check(Color.alpha(picture.getPixel(256, 0)) == 255)
                picture.recycle()
            }
            server = UploadServer("1234", temp, imagesOnly = true) { uploaded, _ ->
                val picture = AppPictures.fit(uploaded, false)
                if (picture == null) null else try {
                    if (AppPictures.save(icon, picture) { true }) "Test app" else null
                } finally { picture.recycle() }
            }
            fun request(path: String, type: String? = null, data: ByteArray = byteArrayOf(), length: Int = data.size): String =
                Socket("127.0.0.1", server.port).use { socket ->
                    socket.soTimeout = 5_000
                    val headers = "${if (type == null) "GET" else "POST"} /$path HTTP/1.1\r\nHost: localhost\r\n" +
                        if (type == null) "\r\n" else "Content-Type: $type\r\nContent-Length: $length\r\n\r\n"
                    socket.getOutputStream().apply { write(headers.toByteArray()); write(data); flush() }
                    socket.getInputStream().bufferedReader().readText()
                }
            check(request("wrong").startsWith("HTTP/1.1 404"))
            val page = request("1234")
            check(page.contains("Change app picture") && !page.contains("video/mp4"))
            check(request("1234", "video/mp4").startsWith("HTTP/1.1 415"))
            check(request("1234", "image/png", length = 5 * 1024 * 1024 + 1).startsWith("HTTP/1.1 413"))
            check(request("1234", "image/png", "invalid".toByteArray()).startsWith("HTTP/1.1 415"))
            check(icon.readBytes().contentEquals(before)) { "Rejected upload replaced the icon" }
            check(request("1234", "image/png", png).startsWith("HTTP/1.1 200"))
            check(BitmapFactory.decodeFile(icon.path)!!.let { it.width == 512 && it.height == 512 })
            AppPictures.reset(context, pkg)
            check(!icon.exists() && !banner.exists())
            check(AppPictures.read(context, pkg, false) == null)
            checkHome(png)
        } finally {
            server?.stop()
            source.recycle()
            AppPictures.reset(context, pkg)
            src.delete()
            temp.delete()
        }
    }

    private fun checkHome(png: ByteArray) = with(runner) {
        val prefs = targetContext.getSharedPreferences("home", Context.MODE_PRIVATE)
        val setup = prefs.all["setup_shown"] as? Boolean
        val lock = prefs.getString("lock_pin", null)
        val style = prefs.getString("icon_style", null)
        var home: MainActivity? = null
        val originals = mutableMapOf<File, ByteArray?>()
        try {
            prefs.edit().putBoolean("setup_shown", true).remove("lock_pin").putString("icon_style", "colour").commit()
            val app = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            home = app
            val row = app.findViewById<ViewGroup>(R.id.appRow)
            await { row.childCount > 0 }
            val pkg = row.getChildAt(0).tag as String
            val name = row.getChildAt(0).contentDescription.toString()
            for (banner in listOf(false, true)) {
                val file = AppPictures.file(targetContext, pkg, banner)
                originals[file] = file.takeIf { it.exists() }?.readBytes()
            }
            val count = row.childCount
            val list = app.findViewById<ViewGroup>(R.id.settingsList)
            val upload = MainActivity::class.java.getDeclaredField("upload").apply { isAccessible = true }
            val pictures = MainActivity::class.java.getDeclaredMethod("appPictures", String::class.java, String::class.java).apply { isAccessible = true }
            val render = MainActivity::class.java.getDeclaredMethod("renderApps").apply { isAccessible = true }
            runOnMainSync { row.getChildAt(0).requestFocus(); pictures.invoke(app, pkg, name) }
            await { app.findViewById<TextView>(R.id.settingsTitle).text.toString() == targetContext.getString(R.string.change_app_picture) }
            for (banner in listOf(false, true)) {
                runOnMainSync { list.getChildAt(if (banner) 1 else 0).performClick() }
                val server = upload.get(app) as UploadServer
                val response = Socket("127.0.0.1", server.port).use { socket ->
                    socket.soTimeout = 5_000
                    socket.getOutputStream().apply {
                        write("POST /${server.code} HTTP/1.1\r\nHost: localhost\r\nContent-Type: image/png\r\nContent-Length: ${png.size}\r\n\r\n".toByteArray())
                        write(png)
                        flush()
                    }
                    socket.getInputStream().bufferedReader().readText()
                }
                check(response.startsWith("HTTP/1.1 200")) { response }
                waitForIdleSync()
                check(AppPictures.file(targetContext, pkg, banner).exists())
                check(row.childCount == count && row.getChildAt(0).tag == pkg) { "Upload changed the app row" }
                check(row.getChildAt(0).contentDescription.toString() == name)
                if (!banner) {
                    for (style in listOf("mono", "colour")) {
                        prefs.edit().putString("icon_style", style).commit()
                        runOnMainSync { render.invoke(app) }
                        val image = (row.getChildAt(0) as ViewGroup).getChildAt(0) as ImageView
                        check((image.drawable.colorFilter != null) == (style == "mono")) { "Uploaded icon ignored $style style" }
                    }
                }
                runOnMainSync { list.getChildAt(list.childCount - 1).performClick() }
                check(upload.get(app) == null) { "Done left the server running" }
            }
            prefs.edit().putString("icon_style", "banner").commit()
            runOnMainSync { render.invoke(app) }
            check(row.getChildAt(0) is FrameLayout) { "Custom banner did not use the banner tile" }
            runOnMainSync { list.getChildAt(2).performClick() }
            check(listOf(false, true).none { AppPictures.file(targetContext, pkg, it).exists() }) { "Reset left custom pictures" }
            runOnMainSync { app.onBackPressed() }
            check(app.currentFocus?.tag == pkg) { "Closing the page lost the app selection" }
            prefs.edit().putString("lock_pin", hashPin("1234")).commit()
            runOnMainSync { pictures.invoke(app, pkg, name); list.getChildAt(0).performClick() }
            check(app.findViewById<TextView>(R.id.settingsTitle).text.toString() == targetContext.getString(R.string.enter_pin))
            check(upload.get(app) == null) { "Locked layout started an upload" }
            prefs.edit().remove("lock_pin").commit()
            runOnMainSync { pictures.invoke(app, pkg, name); list.getChildAt(0).performClick() }
            check(upload.get(app) != null)
            runOnMainSync { app.finish() }
            await { app.isDestroyed }
            check(upload.get(app) == null) { "Leaving the screen left the upload server running" }
        } finally {
            runOnMainSync { home?.finish() }
            for ((file, bytes) in originals) if (bytes == null) file.delete() else file.writeBytes(bytes)
            prefs.edit().apply {
                if (setup == null) remove("setup_shown") else putBoolean("setup_shown", setup)
                if (lock == null) remove("lock_pin") else putString("lock_pin", lock)
                if (style == null) remove("icon_style") else putString("icon_style", style)
            }.commit()
        }
    }

    private fun await(condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 5_000
        do {
            var done = false
            runner.runOnMainSync { done = condition() }
            if (done) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < until)
        error("Home did not reach the expected state")
    }
}
