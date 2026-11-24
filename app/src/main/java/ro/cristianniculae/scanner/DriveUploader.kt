package ro.cristianniculae.scanner

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class DriveAuthException : Exception("Autorizare Google Drive expirată")

/** Încărcare în Google Drive prin API-ul REST (doar fișierele create de aplicație: scope drive.file). */
class DriveUploader(context: Context) {
    private val prefs = context.getSharedPreferences("drive", Context.MODE_PRIVATE)

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.file"
        const val FOLDER_NAME = "Scanări"
    }

    private fun request(url: String, method: String, token: String, contentType: String? = null, body: ByteArray? = null): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 20000
        conn.readTimeout = 120000
        conn.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", contentType)
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
        }
        val code = conn.responseCode
        if (code == 401) throw DriveAuthException()
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw Exception("Drive HTTP $code: $text")
        return text
    }

    private fun folderId(token: String): String {
        prefs.getString("folderId", null)?.let { id ->
            try {
                val o = JSONObject(request("https://www.googleapis.com/drive/v3/files/$id?fields=id,trashed", "GET", token))
                if (!o.optBoolean("trashed")) return id
            } catch (e: DriveAuthException) {
                throw e
            } catch (_: Exception) { }
        }
        val q = URLEncoder.encode(
            "name='$FOLDER_NAME' and mimeType='application/vnd.google-apps.folder' and trashed=false", "UTF-8"
        )
        val found = JSONObject(request("https://www.googleapis.com/drive/v3/files?q=$q&fields=files(id)", "GET", token))
            .getJSONArray("files")
        val id = if (found.length() > 0) found.getJSONObject(0).getString("id") else {
            val meta = JSONObject().put("name", FOLDER_NAME).put("mimeType", "application/vnd.google-apps.folder")
            JSONObject(
                request("https://www.googleapis.com/drive/v3/files?fields=id", "POST", token, "application/json; charset=UTF-8", meta.toString().toByteArray())
            ).getString("id")
        }
        prefs.edit().putString("folderId", id).apply()
        return id
    }

    suspend fun upload(token: String, file: File, name: String) = withContext(Dispatchers.IO) {
        val parent = folderId(token)
        val boundary = "scanner" + System.nanoTime()
        val meta = JSONObject().put("name", "$name.pdf").put("parents", org.json.JSONArray().put(parent))
        val body = java.io.ByteArrayOutputStream().apply {
            write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n".toByteArray())
            write(meta.toString().toByteArray())
            write("\r\n--$boundary\r\nContent-Type: application/pdf\r\n\r\n".toByteArray())
            write(file.readBytes())
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        request(
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id", "POST", token,
            "multipart/related; boundary=$boundary", body,
        )
    }
}
