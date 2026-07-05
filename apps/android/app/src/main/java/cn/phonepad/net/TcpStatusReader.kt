package cn.phonepad.net

import org.json.JSONObject
import java.io.InputStream
import java.net.SocketTimeoutException

object TcpStatusReader {
    fun readJson(input: InputStream): String? {
        val buffer = ByteArray(8192)
        val builder = StringBuilder()

        while (true) {
            val read = try {
                input.read(buffer)
            } catch (_: SocketTimeoutException) {
                break
            }
            if (read <= 0) break
            builder.append(String(buffer, 0, read, Charsets.UTF_8))
            if (isValidJson(builder.toString())) {
                return builder.toString()
            }
        }

        val text = builder.toString()
        return text.takeIf { isValidJson(it) }
    }

    private fun isValidJson(text: String): Boolean =
        runCatching { JSONObject(text) }.isSuccess
}
