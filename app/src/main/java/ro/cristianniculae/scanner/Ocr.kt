package ro.cristianniculae.scanner

import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

data class OcrLine(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Recunoaștere text pe telefon (fără internet). Coordonatele sunt în pixelii imaginii originale. */
object Ocr {
    suspend fun recognize(jpeg: ByteArray, width: Int, height: Int): List<OcrLine> {
        var sample = 1
        while (maxOf(width, height) / sample > 2600) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return emptyList()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val result = recognizer.process(InputImage.fromBitmap(bmp, 0)).await()
            val s = sample.toFloat()
            return result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                val b = line.boundingBox ?: return@mapNotNull null
                if (line.text.isBlank()) null
                else OcrLine(line.text, b.left * s, b.top * s, b.right * s, b.bottom * s)
            }
        } finally {
            recognizer.close()
            bmp.recycle()
        }
    }
}
