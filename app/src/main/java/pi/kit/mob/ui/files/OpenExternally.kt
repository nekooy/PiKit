/*
 * Handing one of the agent's files to another app.
 *
 * Split out of the preview sheet because it now has two call sites — the sheet's
 * **Open with** button and the row menu in the listing — and because the two things
 * that make it work are both non-obvious and both belong in one place:
 *
 *  - `FLAG_GRANT_READ_URI_PERMISSION` is required: the URI points into this app's
 *    private directory, which the receiving app cannot read on its own.
 *  - The chooser is explicit rather than a bare `ACTION_VIEW`, so a file with no
 *    viewer still shows a list (and Android's own "no apps can do this" message)
 *    instead of throwing `ActivityNotFoundException` at the user.
 *
 * ## Why the failure is returned rather than swallowed
 *
 * This used to be `FileProvider.getUriForFile(...) ?: return`, and that `return` was
 * the whole bug report: with shared storage switched on, tapping **Open with** on a
 * file under the `storage` links did nothing whatsoever — no chooser, no error. The
 * provider resolves a file's *canonical* path, and `~/storage/shared` is a symlink to
 * `/storage/emulated/0`, so the file was outside every configured root and the call
 * threw. The roots are fixed in `res/xml/file_paths.xml`; this returns a failure
 * anyway, because a silent no-op is the worst possible answer from a button.
 */
package pi.kit.mob.ui.files

import android.content.Context
import android.content.Intent
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/**
 * Opens [file] in whatever app claims it, and says whether a chooser was shown.
 *
 * @return true when a chooser was actually shown.
 */
internal fun openExternally(context: Context, file: File): Boolean {
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.onFailure {
        Log.w(TAG, "no FileProvider root covers ${file.absolutePath}", it)
    }.getOrNull() ?: return false

    val mime = mimeTypeOf(file)
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    // The chooser always exists, so this does not throw for "no viewer" — the
    // fallback is for an OEM build that has taken it away, and it keeps the failure
    // path honest rather than pretending the first attempt worked.
    return runCatching {
        context.startActivity(
            Intent.createChooser(view, file.name)
                .putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(send)),
        )
    }.recoverCatching {
        context.startActivity(Intent.createChooser(send, file.name))
    }.isSuccess
}

private fun mimeTypeOf(file: File): String {
    val extension = file.extension.lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        ?: "application/octet-stream"
}

/** The one tag every log line in this app carries. */
private const val TAG = "PiKit"
