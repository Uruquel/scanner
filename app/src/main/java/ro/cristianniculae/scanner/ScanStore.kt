package ro.cristianniculae.scanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ScanDoc(
    val id: String,
    val name: String,
    val fileName: String,
    val created: Long,
    val pages: Int,
    val drive: String, // "pending" | "done" | "error"
)

/** Lista documentelor scanate, păstrată într-un fișier JSON în memoria aplicației. */
class ScanStore(context: Context) {
    val dir: File = File(context.filesDir, "scans").apply { mkdirs() }
    private val index = File(context.filesDir, "index.json")

    fun load(): List<ScanDoc> {
        if (!index.exists()) return emptyList()
        return try {
            val arr = JSONArray(index.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ScanDoc(
                    o.getString("id"), o.getString("name"), o.getString("fileName"),
                    o.getLong("created"), o.getInt("pages"), o.optString("drive", "pending"),
                )
            }.filter { File(dir, it.fileName).exists() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(docs: List<ScanDoc>) {
        val arr = JSONArray()
        docs.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id).put("name", it.name).put("fileName", it.fileName)
                    .put("created", it.created).put("pages", it.pages).put("drive", it.drive)
            )
        }
        val tmp = File(index.path + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(index)
    }

    fun file(doc: ScanDoc) = File(dir, doc.fileName)
}
