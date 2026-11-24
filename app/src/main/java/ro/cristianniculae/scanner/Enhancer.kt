package ro.cristianniculae.scanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.math.pow

enum class ImageMode(val key: String, val label: String, val hint: String) {
    COLOR("color", "Document color", "Fundal alb, text clar, culori păstrate"),
    BW("bw", "Alb-negru", "Cel mai curat pentru text, fișier mic"),
    ORIGINAL("original", "Original", "Poza neprelucrată (ex. fotografii)");

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key } ?: COLOR
    }
}

/**
 * Curăță imaginea unui document: estimează fundalul foii (umbre, lumină inegală),
 * îl face alb, întunecă textul și accentuează ușor marginile literelor.
 */
object Enhancer {

    fun process(jpeg: ByteArray, mode: ImageMode): ByteArray {
        if (mode == ImageMode.ORIGINAL) return jpeg
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 3600) sample *= 2
        val src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return jpeg

        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        // 1. Fundalul: imagine micșorată, filtru de maxim (șterge textul), apoi estompare
        val sw = maxOf(8, w / 8)
        val sh = maxOf(8, h / 8)
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        src.recycle()
        val sp = IntArray(sw * sh)
        small.getPixels(sp, 0, sw, 0, 0, sw, sh)
        small.recycle()
        val bgSmall = IntArray(sw * sh)
        for (shift in intArrayOf(16, 8, 0)) {
            var ch = IntArray(sw * sh) { (sp[it] shr shift) and 0xFF }
            ch = maxFilter(ch, sw, sh, 3)
            ch = boxBlur(ch, sw, sh, 4)
            for (i in ch.indices) bgSmall[i] = bgSmall[i] or (ch[i].coerceIn(1, 255) shl shift)
        }
        val bgBmp = Bitmap.createBitmap(bgSmall, sw, sh, Bitmap.Config.ARGB_8888)
        val bgFull = Bitmap.createScaledBitmap(bgBmp, w, h, true)
        bgBmp.recycle()
        val bg = IntArray(w * h)
        bgFull.getPixels(bg, 0, w, 0, 0, w, h)
        bgFull.recycle()

        // 2. Normalizare + curbă de contrast (tabel precalculat pe raportul pixel/fundal)
        val lut = IntArray(1024) { i ->
            val n = i / 1023f * 1.25f
            val v = if (mode == ImageMode.BW) ((n - 0.50f) / (0.80f - 0.50f)).coerceIn(0f, 1f)
            else ((n - 0.12f) / (0.90f - 0.12f)).coerceIn(0f, 1f).pow(1.6f)
            (v * 255f + 0.5f).toInt()
        }
        fun idx(c: Int, b: Int) = ((c.toFloat() / b) / 1.25f * 1023f).toInt().coerceIn(0, 1023)

        val out = IntArray(w * h)
        for (i in px.indices) {
            val p = px[i]
            val b = bg[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val bl = p and 0xFF
            val br = maxOf(1, (b shr 16) and 0xFF)
            val bgc = maxOf(1, (b shr 8) and 0xFF)
            val bb = maxOf(1, b and 0xFF)
            out[i] = if (mode == ImageMode.BW) {
                val y = (r * 299 + g * 587 + bl * 114) / 1000
                val by = maxOf(1, (br * 299 + bgc * 587 + bb * 114) / 1000)
                val v = lut[idx(y, by)]
                (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            } else {
                (0xFF shl 24) or (lut[idx(r, br)] shl 16) or (lut[idx(g, bgc)] shl 8) or lut[idx(bl, bb)]
            }
        }

        // 3. Accentuare ușoară (doar la color; la alb-negru curba e deja abruptă)
        val result = if (mode == ImageMode.COLOR) sharpen(out, w, h, 0.4f) else out

        val bmp = Bitmap.createBitmap(result, w, h, Bitmap.Config.ARGB_8888)
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, if (mode == ImageMode.BW) 75 else 82, bos)
        bmp.recycle()
        return bos.toByteArray()
    }

    private fun maxFilter(a: IntArray, w: Int, h: Int, r: Int): IntArray {
        val tmp = IntArray(a.size)
        for (y in 0 until h) for (x in 0 until w) {
            var m = 0
            for (k in maxOf(0, x - r)..minOf(w - 1, x + r)) m = maxOf(m, a[y * w + k])
            tmp[y * w + x] = m
        }
        val res = IntArray(a.size)
        for (y in 0 until h) for (x in 0 until w) {
            var m = 0
            for (k in maxOf(0, y - r)..minOf(h - 1, y + r)) m = maxOf(m, tmp[k * w + x])
            res[y * w + x] = m
        }
        return res
    }

    private fun boxBlur(a: IntArray, w: Int, h: Int, r: Int): IntArray {
        val tmp = IntArray(a.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0; var n = 0
            for (k in maxOf(0, x - r)..minOf(w - 1, x + r)) { s += a[y * w + k]; n++ }
            tmp[y * w + x] = s / n
        }
        val res = IntArray(a.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0; var n = 0
            for (k in maxOf(0, y - r)..minOf(h - 1, y + r)) { s += tmp[k * w + x]; n++ }
            res[y * w + x] = s / n
        }
        return res
    }

    /** Mască de claritate 3x3: pixel + amount * (pixel - media vecinilor). */
    private fun sharpen(a: IntArray, w: Int, h: Int, amount: Float): IntArray {
        val res = a.copyOf()
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                var c = 0xFF shl 24
                for (shift in intArrayOf(16, 8, 0)) {
                    val v = (a[i] shr shift) and 0xFF
                    val avg = (((a[i - 1] shr shift) and 0xFF) + ((a[i + 1] shr shift) and 0xFF) +
                        ((a[i - w] shr shift) and 0xFF) + ((a[i + w] shr shift) and 0xFF)) / 4
                    val nv = (v + amount * (v - avg)).toInt().coerceIn(0, 255)
                    c = c or (nv shl shift)
                }
                res[i] = c
            }
        }
        return res
    }
}
