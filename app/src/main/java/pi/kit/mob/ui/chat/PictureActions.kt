/*
 * The two things a reader can do with a picture they did not draw: keep it, or hand it on.
 *
 * Both work from the same [PictureSource] the viewer draws, because a picture's origin is
 * irrelevant to these two: a prompt's copy is a file in this app's own cache, which
 * `FileProvider` exposes through the `files-path` root it already declares (`res/xml/
 * file_paths.xml`), and a composer attachment is a document the picker handed over, whose
 * content URI the receiving app can read with the grant the intent carries.
 *
 * Saving goes through `MediaStore` rather than to a path: the picture has to appear in the
 * phone's gallery, and an entry is what makes it appear there. On Q and later the insert
 * needs no permission for a file the app wrote itself — the media database owns the bytes
 * — which is why that is the branch with `RELATIVE_PATH` and `IS_PENDING`; before Q the
 * public directory is a real path and `WRITE_EXTERNAL_STORAGE` is required (`maxSdkVersion
 * = 29` in the manifest), so a device that has not granted it answers false and the viewer
 * says the save failed rather than pretending it worked.
 *
 * Both answer a Boolean and neither throws: the caller turns a failure into a toast, which
 * is the only honest thing a picture's own window can do about a refused write.
 */
package pi.kit.mob.ui.chat

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Hands [source] to another app, and says whether a chooser was shown.
 *
 * The chooser is explicit for the same reason `OpenExternally.kt` gives: an app that claims
 * no picture still shows Android's own "no apps can do this" list rather than throwing
 * `ActivityNotFoundException` at the reader.
 */
internal fun sharePicture(context: Context, source: PictureSource): Boolean {
    val uri = contentUri(context, source) ?: return false
    val type = context.contentResolver.getType(uri) ?: "image/*"
    val send = Intent(Intent.ACTION_SEND).apply {
        this.type = type
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return runCatching {
        context.startActivity(Intent.createChooser(send, null))
    }.onFailure { Log.w(TAG, "no app took a shared picture", it) }.isSuccess
}

/** Copies [source] into the phone's gallery, and says whether it landed. */
internal fun savePicture(context: Context, source: PictureSource): Boolean = try {
    val name = "PiKit-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".jpg"
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + File.separator + FOLDER,
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        // Before Q a row is placed by its own path, and the directory has to exist first.
        val folder = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            FOLDER,
        ).apply { mkdirs() }
        values.put(MediaStore.Images.Media.DATA, File(folder, name).absolutePath)
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }
    val target = context.contentResolver.insert(collection, values) ?: return false
    val copied = runCatching {
        context.contentResolver.openOutputStream(target)?.use { output ->
            openPicture(context, source)?.use { input -> input.copyTo(output) }
                ?: return@runCatching false
        } ?: return@runCatching false
        true
    }.getOrDefault(false)
    if (!copied) {
        context.contentResolver.delete(target, null, null)
        return false
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // Published only once the bytes are all there: a half-written picture in the
        // gallery is worse than one that appears a moment later.
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        context.contentResolver.update(target, values, null, null)
    } else {
        val path = values.getAsString(MediaStore.Images.Media.DATA)
        MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf("image/jpeg"), null)
    }
    true
} catch (e: Exception) {
    Log.w(TAG, "could not save a picture", e)
    false
}

/**
 * The URI another app is given for [source].
 *
 * A cached copy goes through the app's `FileProvider`; a composer attachment is already a
 * content URI and needs no translation.
 */
private fun contentUri(context: Context, source: PictureSource): android.net.Uri? {
    source.uri?.let { return it }
    val key = source.key ?: return null
    val file = ChatPictureCache.file(context.filesDir, key)
    if (!file.isFile) return null
    return runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.onFailure { Log.w(TAG, "no FileProvider root covers ${file.absolutePath}", it) }.getOrNull()
}

/** [source]'s bytes, for the copy into the gallery. */
private fun openPicture(context: Context, source: PictureSource): java.io.InputStream? {
    source.uri?.let { return context.contentResolver.openInputStream(it) }
    val key = source.key ?: return null
    val file = ChatPictureCache.file(context.filesDir, key)
    if (!file.isFile) return null
    return file.inputStream()
}

/** The folder in the phone's gallery that this app's pictures land in. */
private const val FOLDER = "PiKit"

/** The one tag every log line in this app carries. */
private const val TAG = "PiKit"
