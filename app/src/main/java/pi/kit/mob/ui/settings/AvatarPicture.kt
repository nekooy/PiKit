package pi.kit.mob.ui.settings

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.io.File
import kotlinx.coroutines.launch
import pi.kit.mob.data.AvatarStore
import pi.kit.mob.locales.Strings

/**
 * One avatar picture's picker: the launcher, the crop window, and the store.
 *
 * Extracted from the personalization page's sheet when chat presets needed the same
 * flow — a preset carries its own picture, and a second copy of "pick, decode, crop,
 * write, replace the old file" is the shape that drifts: the two would disagree about
 * releasing the crop bitmap or about which picture the old one replaced.
 *
 * The caller owns the *choice* of what a picture means (a speaker's avatar, a preset's);
 * this owns the platform's part of it. [onStored] receives the absolute path of the
 * file that was written, and is not called when the pick or the crop failed.
 *
 * Nothing is returned before the crop is answered — the caller reads [failed] to say so
 * — and the crop window is keyed on the slot, so a second pick starts from the new
 * picture rather than from the previous drag.
 */
internal class AvatarPicturePicker(
    val pick: () -> Unit,
    val failed: Boolean,
)

@Composable
internal fun rememberAvatarPicturePicker(
    filesDir: File,
    /** The slot the file is stored under: `ai`, `user`, or `preset-<id>`. */
    slotId: String,
    text: Strings,
    onStored: (String) -> Unit,
): AvatarPicturePicker {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var failed by remember(slotId) { mutableStateOf(false) }
    // Two values rather than one: the crop window animates out through `show`, so the
    // picture it is drawing has to outlive the answer that closes it — a null slot would
    // remove the window from the hierarchy with its content already gone.
    var cropping by remember(slotId) { mutableStateOf<Bitmap?>(null) }
    var cropShown by remember(slotId) { mutableStateOf<Bitmap?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val decoded = AvatarStore.decodeForCrop(context, uri)
            failed = decoded == null
            if (decoded != null) {
                cropShown = decoded
                cropping = decoded
            }
        }
    }

    cropShown?.let { picture ->
        AvatarCropDialog(
            picture = picture,
            show = cropping != null,
            text = text,
            onDismiss = { cropping = null },
            onDismissFinished = {
                picture.recycle()
                cropShown = null
            },
            onConfirm = { square ->
                val stored = AvatarStore.storeSquare(filesDir, slotId, square)
                failed = stored == null
                cropping = null
                // The crop is the caller's to release: `createBitmap` hands back the
                // source itself when the crop is the whole picture, and that one is the
                // window's — it is recycled once the window has finished leaving.
                if (square !== picture) square.recycle()
                // The old picture is replaced only once the new one is on disk: a crop
                // that could not be written leaves the avatar as it was rather than
                // blanking it.
                if (stored != null) onStored(stored)
            },
        )
    }

    return AvatarPicturePicker(
        pick = { launcher.launch(IMAGE_MIME) },
        failed = failed,
    )
}

/** Anything the system picker can hand back as an image. */
internal const val IMAGE_MIME = "image/*"
