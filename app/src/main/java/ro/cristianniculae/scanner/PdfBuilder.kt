package ro.cristianniculae.scanner

import android.graphics.Paint
import android.graphics.Typeface
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

data class JpegInfo(val width: Int, val height: Int, val components: Int)

class PageInput(val jpeg: ByteArray, val info: JpegInfo, val lines: List<OcrLine>)

/**
 * Scrie un PDF cu imaginile JPEG incluse direct (fără recompresie, deci fișier mic)
 * și, opțional, un strat de text invizibil (OCR) pentru căutare și copiere.
 */
object PdfBuilder {

    fun jpegInfo(data: ByteArray): JpegInfo? {
        var i = 2
        while (i + 9 < data.size) {
            if (data[i].toInt() and 0xFF != 0xFF) { i++; continue }
            val marker = data[i + 1].toInt() and 0xFF
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7 || marker == 0xFF) { i++; continue }
            val len = ((data[i + 2].toInt() and 0xFF) shl 8) or (data[i + 3].toInt() and 0xFF)
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                val h = ((data[i + 5].toInt() and 0xFF) shl 8) or (data[i + 6].toInt() and 0xFF)
                val w = ((data[i + 7].toInt() and 0xFF) shl 8) or (data[i + 8].toInt() and 0xFF)
                val c = data[i + 9].toInt() and 0xFF
                return JpegInfo(w, h, c)
            }
            i += 2 + len
        }
        return null
    }

    // Diacritice românești mapate pe coduri 128..137 (vezi /Differences în font)
    private val special = mapOf(
        'ă' to 128, 'Ă' to 129, 'ș' to 130, 'Ș' to 131, 'ț' to 132, 'Ț' to 133,
        'ş' to 134, 'Ş' to 135, 'ţ' to 136, 'Ţ' to 137,
    )

    private fun encode(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (ch in text) {
            val code = special[ch] ?: when {
                ch.code in 32..126 -> ch.code
                ch.code in 160..255 -> ch.code
                else -> '?'.code
            }
            if (code == '('.code || code == ')'.code || code == '\\'.code) out.write('\\'.code)
            out.write(code)
        }
        return out.toByteArray()
    }

    private fun f(v: Float) = String.format(Locale.US, "%.2f", v)

    fun write(pages: List<PageInput>, target: File) {
        val out = ByteArrayOutputStream()
        val offsets = mutableListOf<Int>()
        fun ascii(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        fun beginObj(n: Int) { while (offsets.size < n) offsets.add(0); offsets[n - 1] = out.size(); ascii("$n 0 obj\n") }

        val paint = Paint().apply { typeface = Typeface.SANS_SERIF }

        // 1 catalog, 2 pages, 3 font, apoi câte 3 obiecte pe pagină: page, content, image
        val pageIds = pages.indices.map { 4 + it * 3 }
        ascii("%PDF-1.4\n%âãÏÓ\n")

        beginObj(1); ascii("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        beginObj(2); ascii("<< /Type /Pages /Count ${pages.size} /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] >>\nendobj\n")
        beginObj(3); ascii(
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding << /Type /Encoding /BaseEncoding /WinAnsiEncoding " +
                "/Differences [128 /abreve /Abreve /scommaaccent /Scommaaccent /tcommaaccent /Tcommaaccent /scedilla /Scedilla /tcedilla /Tcedilla] >> >>\nendobj\n"
        )

        pages.forEachIndexed { idx, p ->
            val pageId = pageIds[idx]
            val w = 595f
            val h = w * p.info.height / p.info.width
            val sx = w / p.info.width

            val content = ByteArrayOutputStream()
            content.write("q ${f(w)} 0 0 ${f(h)} 0 0 cm /Im0 Do Q\n".toByteArray())
            if (p.lines.isNotEmpty()) {
                content.write("BT 3 Tr\n".toByteArray())
                for (l in p.lines) {
                    val boxH = (l.bottom - l.top) * sx
                    val boxW = (l.right - l.left) * sx
                    if (boxH <= 0f || boxW <= 0f) continue
                    val fs = boxH * 0.85f
                    paint.textSize = fs
                    val measured = paint.measureText(l.text)
                    val hs = if (measured > 0f) (100f * boxW / measured).coerceIn(10f, 1000f) else 100f
                    val x = l.left * sx
                    val y = h - l.bottom * sx + boxH * 0.18f
                    content.write("/F1 ${f(fs)} Tf ${f(hs)} Tz 1 0 0 1 ${f(x)} ${f(y)} Tm (".toByteArray())
                    content.write(encode(l.text))
                    content.write(") Tj\n".toByteArray())
                }
                content.write("ET\n".toByteArray())
            }
            val contentBytes = content.toByteArray()

            beginObj(pageId); ascii(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${f(w)} ${f(h)}] " +
                    "/Resources << /Font << /F1 3 0 R >> /XObject << /Im0 ${pageId + 2} 0 R >> >> /Contents ${pageId + 1} 0 R >>\nendobj\n"
            )
            beginObj(pageId + 1); ascii("<< /Length ${contentBytes.size} >>\nstream\n")
            out.write(contentBytes); ascii("\nendstream\nendobj\n")

            val cs = when (p.info.components) { 1 -> "/DeviceGray"; 4 -> "/DeviceCMYK"; else -> "/DeviceRGB" }
            val decode = if (p.info.components == 4) " /Decode [1 0 1 0 1 0 1 0]" else ""
            beginObj(pageId + 2); ascii(
                "<< /Type /XObject /Subtype /Image /Width ${p.info.width} /Height ${p.info.height} " +
                    "/ColorSpace $cs /BitsPerComponent 8$decode /Filter /DCTDecode /Length ${p.jpeg.size} >>\nstream\n"
            )
            out.write(p.jpeg); ascii("\nendstream\nendobj\n")
        }

        val xref = out.size()
        ascii("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { ascii(String.format(Locale.US, "%010d 00000 n \n", it)) }
        ascii("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")

        val tmp = File(target.path + ".tmp")
        tmp.writeBytes(out.toByteArray())
        tmp.renameTo(target)
    }
}
