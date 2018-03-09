package com.isaacudy.kfiltersample

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.support.v7.app.AppCompatActivity
import android.view.Surface
import android.view.TextureView
import android.view.View
import com.isaacudy.kfilter.BaseKfilter
import com.isaacudy.kfilter.filters.*
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private val ACTIVITY_CHOOSE_FILE = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (false) {
            setContentView(getTestView(this))
            return
        }

        setContentView(R.layout.activity_main)


        val filters = listOf(BaseKfilter(),
            GrayscaleFilter(),
            SepiaFilter(),
            PosterizeFilter(),
            WarmFilter(),
            WobbleFilter())

        var item = 0
        kfilterView.setFilters(filters)
        selectFilter.setOnClickListener {
            item++
            if (item >= 3) {
                item = 0
            }
            kfilterView.animateToSelection(item)
        }

        selectFileButton.setOnClickListener {
            val chooseFile = Intent(Intent.ACTION_GET_CONTENT)
            chooseFile.addCategory(Intent.CATEGORY_OPENABLE)
            chooseFile.type = "*/*"
            val intent = Intent.createChooser(chooseFile, "Choose a file")
            startActivityForResult(intent, ACTIVITY_CHOOSE_FILE)
        }

        saveOutputButton.setOnClickListener {
            File("storage/emulated/0/KfilterSample").apply {
                if (!exists()) mkdirs()
            }
            kfilterView.getProcessor()?.save("storage/emulated/0/KfilterSample/sample_${System.currentTimeMillis()}")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent) {
        if (resultCode != Activity.RESULT_OK) return
        if (requestCode == ACTIVITY_CHOOSE_FILE) {
            getUriPath(this, data.data)?.let {
                kfilterView.setContentPath(it)
            }
        }
    }
}

fun getTestView(context: Context): View {
    val view = TextureView(context)
    view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {

        var surface: Surface? = null
        var width = 0
        var height = 0

        override fun onSurfaceTextureUpdated(p0: SurfaceTexture?) {
        }

        override fun onSurfaceTextureAvailable(s: SurfaceTexture, width: Int, height: Int) {
            this.width = width
            this.height = height

            if (surface == null) {
                surface = Surface(s)
            }

            thread {
                val bitmap = BitmapFactory.decodeFile("storage/emulated/0/Download/overlay.png")
                while (true) {
                    surface?.let {
                        val canvas = it.lockCanvas(Rect(0, 0, width, height))
                        canvas.drawARGB(255, 255, 125, 0)
                        canvas.drawBitmap(bitmap, 0f, 0f, null)
                        it.unlockCanvasAndPost(canvas)
                    }
                    try {
                        Thread.sleep(20)
                    }
                    catch (ex: Exception) {
                    }
                }
            }
        }

        override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, width: Int, height: Int) {
            this.width = width
            this.height = height
        }

        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
            return false
        }
    }
    return view
}

/**
 * Get a file path from a Uri. This will get the the path for Storage Access
 * Framework Documents, as well as the _data field for the MediaStore and
 * other file-based ContentProviders.
 *
 * @param context The context.
 * @param uri The Uri to query.
 * @author paulburke
 */
fun getUriPath(context: Context, uri: Uri): String? {

    val isKitKat = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT

    // DocumentProvider
    if (isKitKat && DocumentsContract.isDocumentUri(context, uri)) {
        // ExternalStorageProvider
        if (isExternalStorageDocument(uri)) {
            val docId = DocumentsContract.getDocumentId(uri)
            val split = docId.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            val type = split[0]

            if ("primary".equals(type, ignoreCase = true)) {
                return "${Environment.getExternalStorageDirectory()}/${split[1]}"
            }

            // TODO handle non-primary volumes
        }
        else if (isDownloadsDocument(uri)) {

            val id = DocumentsContract.getDocumentId(uri)
            val contentUri = ContentUris.withAppendedId(
                Uri.parse("content://downloads/public_downloads"), java.lang.Long.valueOf(id)!!)

            return getDataColumn(context, contentUri, null, null)
        }
        else if (isMediaDocument(uri)) {
            val docId = DocumentsContract.getDocumentId(uri)
            val split = docId.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            val type = split[0]

            var contentUri: Uri? = null
            if ("image" == type) {
                contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            else if ("video" == type) {
                contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
            else if ("audio" == type) {
                contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }

            val selection = "_id=?"
            val selectionArgs = arrayOf(split[1])

            return getDataColumn(context, contentUri, selection, selectionArgs)
        }// MediaProvider
        // DownloadsProvider
    }
    else if ("content".equals(uri.scheme, ignoreCase = true)) {
        return getDataColumn(context, uri, null, null)
    }
    else if ("file".equals(uri.scheme, ignoreCase = true)) {
        return uri.path
    }// File
    // MediaStore (and general)

    return null
}

/**
 * Get the value of the data column for this Uri. This is useful for
 * MediaStore Uris, and other file-based ContentProviders.
 *
 * @param context The context.
 * @param uri The Uri to query.
 * @param selection (Optional) Filter used in the query.
 * @param selectionArgs (Optional) Selection arguments used in the query.
 * @return The value of the _data column, which is typically a file path.
 */
fun getDataColumn(context: Context, uri: Uri?, selection: String?,
                  selectionArgs: Array<String>?): String? {

    var cursor: Cursor? = null
    val column = "_data"
    val projection = arrayOf(column)

    try {
        cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
        if (cursor != null && cursor.moveToFirst()) {
            val columnIndex = cursor.getColumnIndexOrThrow(column)
            return cursor.getString(columnIndex)
        }
    }
    finally {
        if (cursor != null)
            cursor.close()
    }
    return null
}


/**
 * @param uri The Uri to check.
 * @return Whether the Uri authority is ExternalStorageProvider.
 */
fun isExternalStorageDocument(uri: Uri): Boolean {
    return "com.android.externalstorage.documents" == uri.authority
}

/**
 * @param uri The Uri to check.
 * @return Whether the Uri authority is DownloadsProvider.
 */
fun isDownloadsDocument(uri: Uri): Boolean {
    return "com.android.providers.downloads.documents" == uri.authority
}

/**
 * @param uri The Uri to check.
 * @return Whether the Uri authority is MediaProvider.
 */
fun isMediaDocument(uri: Uri): Boolean {
    return "com.android.providers.media.documents" == uri.authority
}
