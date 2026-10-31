package dev.plume

import android.content.ContentProvider
import android.content.Context
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * Hands a downloaded update to the TV's own installer, on TVs without Android's confirm box (Xiaomi HyperOS).
 * Serves only that file, read only.
 */
class ApkProvider : ContentProvider() {
    private val apk get() = File(context!!.cacheDir, NAME)

    override fun onCreate() = true

    override fun getType(uri: Uri) = "application/vnd.android.package-archive"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY)

    // Some installers ask for the name and size first.
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): Cursor =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf(NAME, apk.length())) }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?) = 0

    companion object {
        const val NAME = "Plume.apk"

        /** The authority is `${applicationId}.apk` in the manifest. */
        fun uri(c: Context): Uri = Uri.parse("content://${c.packageName}.apk/$NAME")
    }
}
