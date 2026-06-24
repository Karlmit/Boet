package se.jabba.boet.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.max

// Decode, downscale and JPEG-compress an image Uri to a base64 string suitable
// for upload. Keeps the longest edge <= maxEdge to bound payload size.
fun compressImageToBase64(context: Context, uri: Uri, maxEdge: Int = 1600): Pair<String, String>? {
    return try {
        val resolver = context.contentResolver

        // First pass: read bounds only.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longest = max(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)

        var sample = 1
        while (longest / sample > maxEdge * 2) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return null

        // Second pass: exact scale to maxEdge.
        val scale = maxEdge.toFloat() / max(decoded.width, decoded.height)
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
        } else decoded

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
        val base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        base64 to "image/jpeg"
    } catch (_: Exception) {
        null
    }
}
