package com.tia.galerie

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.io.File
import android.graphics.Canvas as AndroidCanvas
import android.graphics.ColorMatrix as AColorMatrix
import android.graphics.Matrix as AMatrix

data class Media(val uri: Uri, val video: Boolean, val album: String) { val key: String get() = uri.toString() }

fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()

fun loadMedia(ctx: Context, trashed: Boolean = false): List<Media> {
    val out = mutableListOf<Media>()
    val b = Bundle().apply {
        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "media_type=? OR media_type=?")
        putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("1", "3"))
        putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf("date_added"))
        putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
        putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, if (trashed) MediaStore.MATCH_ONLY else MediaStore.MATCH_EXCLUDE)
    }
    try {
        ctx.contentResolver.query(MediaStore.Files.getContentUri("external"), arrayOf("_id", "media_type", "bucket_display_name"), b, null)?.use {
            while (it.moveToNext()) {
                val vid = it.getInt(1) == 3
                val base = if (vid) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                out += Media(ContentUris.withAppendedId(base, it.getLong(0)), vid, it.getString(2) ?: "Autres")
            }
        }
    } catch (e: Exception) { }
    return out
}

// ---- Partage
fun share(ctx: Context, m: Media) {
    val i = Intent(Intent.ACTION_SEND).apply {
        type = if (m.video) "video/*" else "image/*"
        putExtra(Intent.EXTRA_STREAM, m.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(i, "Partager").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

// ---- Coffre privé (stockage interne de l'application)
val VideoExt = setOf("mp4", "3gpp", "webm", "quicktime", "x-matroska", "mkv", "mov")
fun vaultDir(ctx: Context) = File(ctx.filesDir, "coffre").apply { mkdirs() }
fun loadVault(ctx: Context): List<Media> =
    vaultDir(ctx).listFiles()?.sortedByDescending { it.lastModified() }?.map { Media(Uri.fromFile(it), it.extension in VideoExt, "Coffre") } ?: emptyList()

fun vaultAdd(ctx: Context, m: Media) {
    val ext = ctx.contentResolver.getType(m.uri)?.substringAfter('/') ?: if (m.video) "mp4" else "jpeg"
    val f = File(vaultDir(ctx), "${System.currentTimeMillis()}.$ext")
    ctx.contentResolver.openInputStream(m.uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
}

fun vaultRestore(ctx: Context, m: Media) {
    val f = File(m.uri.path!!)
    val v = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
        put(MediaStore.MediaColumns.MIME_TYPE, (if (m.video) "video/" else "image/") + f.extension)
        put(MediaStore.MediaColumns.RELATIVE_PATH, (if (m.video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/TIA GALERIE")
    }
    val base = if (m.video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    ctx.contentResolver.insert(base, v)?.let { u ->
        ctx.contentResolver.openOutputStream(u)?.use { o -> f.inputStream().use { it.copyTo(o) } }
        f.delete()
    }
}

// ---- Empreinte / code
fun authenticate(act: FragmentActivity, title: String, strict: Boolean, ok: () -> Unit) {
    val flags = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    if (BiometricManager.from(act).canAuthenticate(flags) != BiometricManager.BIOMETRIC_SUCCESS) {
        if (strict) toast(act, "Active un verrouillage d'écran (empreinte ou code) dans les paramètres du téléphone") else ok()
        return
    }
    val info = BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(flags).build()
    BiometricPrompt(act, ContextCompat.getMainExecutor(act), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { ok() }
    }).authenticate(info)
}

// ---- Édition photo
val FilterNames = listOf("Original", "N&B", "Sépia", "Vif", "Froid", "Chaud")
val Ratios = listOf("Original" to 0f, "1:1" to 1f, "4:3" to 4f / 3f, "16:9" to 16f / 9f)

fun filterArr(i: Int): FloatArray? = when (i) {
    1 -> AColorMatrix().apply { setSaturation(0f) }.array
    2 -> floatArrayOf(.393f, .769f, .189f, 0f, 0f, .349f, .686f, .168f, 0f, 0f, .272f, .534f, .131f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
    3 -> AColorMatrix().apply { setSaturation(1.6f) }.array
    4 -> floatArrayOf(.9f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1.15f, 0f, 10f, 0f, 0f, 0f, 1f, 0f)
    5 -> floatArrayOf(1.15f, 0f, 0f, 0f, 10f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, .85f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
    else -> null
}

fun loadBitmap(ctx: Context, uri: Uri): Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { dec, info, _ ->
        dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val s = maxOf(info.size.width, info.size.height)
        if (s > 2048) dec.setTargetSampleSize(s / 2048)
    }

fun rotateBmp(b: Bitmap, deg: Int): Bitmap =
    if (deg % 360 == 0) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, AMatrix().apply { postRotate(deg.toFloat()) }, true)

fun cropBmp(b: Bitmap, r: Float): Bitmap {
    if (r == 0f) return b
    val cur = b.width.toFloat() / b.height
    return if (cur > r) { val nw = (b.height * r).toInt(); Bitmap.createBitmap(b, (b.width - nw) / 2, 0, nw, b.height) }
    else { val nh = (b.width / r).toInt(); Bitmap.createBitmap(b, 0, (b.height - nh) / 2, b.width, nh) }
}

fun saveEdited(ctx: Context, b: Bitmap, filter: Int) {
    val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
    val p = Paint()
    filterArr(filter)?.let { p.colorFilter = ColorMatrixColorFilter(AColorMatrix(it)) }
    AndroidCanvas(out).drawBitmap(b, 0f, 0f, p)
    val v = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "TIA_${System.currentTimeMillis()}.jpg")
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/TIA GALERIE")
    }
    val u = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return
    ctx.contentResolver.openOutputStream(u)?.use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
}
