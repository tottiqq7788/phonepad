package cn.phonepad.net

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import cn.phonepad.logging.PhonePadLogger
import cn.phonepad.protocol.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.math.BigInteger
import java.util.UUID

data class SelectedAttachment(
    val uri: Uri,
    val displayName: String,
    val size: Long,
    val mimeType: String,
)

data class FileTransferProgress(
    val batchId: String,
    val currentIndex: Int,
    val totalFiles: Int,
    val currentFileName: String,
    val sentBytes: Long,
    val totalBytes: Long,
    val fileSentBytes: Long,
    val fileTotalBytes: Long,
) {
    val percent: Int
        get() = if (totalBytes <= 0) 0 else ((sentBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
}

data class FileBeginResult(
    val ok: Boolean,
    val uploadPort: Int,
    val token: String,
    val error: String? = null,
)

data class FileCommitResult(
    val ok: Boolean,
    val savedPath: String? = null,
    val error: String? = null,
)

object AttachmentResolver {
    fun fromUris(context: Context, uris: List<Uri>): List<SelectedAttachment> {
        val resolver = context.contentResolver
        return uris.mapNotNull { uri ->
            resolve(resolver, uri)
        }
    }

    private fun resolve(resolver: ContentResolver, uri: Uri): SelectedAttachment? {
        var name = "file"
        var size = 0L
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex >= 0) {
                    name = cursor.getString(nameIndex) ?: name
                }
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }
        if (size <= 0L) {
            resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                if (descriptor.statSize > 0) {
                    size = descriptor.statSize
                }
            }
        }
        if (size <= 0L) {
            return null
        }
        val mimeType = resolver.getType(uri) ?: "application/octet-stream"
        return SelectedAttachment(
            uri = uri,
            displayName = name,
            size = size,
            mimeType = mimeType,
        )
    }
}

class FileTransferClient(
    private val controlClient: ControlClient = ControlClient(),
) {
    suspend fun uploadAttachments(
        context: Context,
        host: String,
        deviceId: String,
        secret: String,
        tcpPort: Int,
        attachments: List<SelectedAttachment>,
        onProgress: (FileTransferProgress) -> Unit,
        isCancelled: () -> Boolean,
    ): ControlResponse = withContext(Dispatchers.IO) {
        if (attachments.isEmpty()) {
            return@withContext ControlResponse(ok = true)
        }

        val batchId = UUID.randomUUID().toString()
        val totalBytes = attachments.sumOf { it.size }
        var sentBytes = 0L
        PhonePadLogger.i(
            "file_transfer",
            "file_batch_start",
            "batch_id=${PhonePadLogger.shortId(batchId)} files=${attachments.size} total_bytes=$totalBytes",
        )

        attachments.forEachIndexed { index, attachment ->
            if (isCancelled()) {
                return@withContext ControlResponse(ok = false, error = "传输已取消")
            }

            val transferId = UUID.randomUUID().toString()
            val begin = fileBegin(
                host = host,
                deviceId = deviceId,
                secret = secret,
                tcpPort = tcpPort,
                transferId = transferId,
                fileName = attachment.displayName,
                fileSize = attachment.size,
                mimeType = attachment.mimeType,
                batchId = batchId,
                fileIndex = index + 1,
                totalFiles = attachments.size,
            )
            if (!begin.ok) {
                PhonePadLogger.w(
                    "file_transfer",
                    "file_begin_failed",
                    "transfer_id=${PhonePadLogger.shortId(transferId)} file=${attachment.displayName} size=${attachment.size} reason=${begin.error ?: "unknown"}",
                )
                return@withContext ControlResponse(ok = false, error = begin.error ?: "无法开始文件传输")
            }
            PhonePadLogger.i(
                "file_transfer",
                "file_begin",
                "transfer_id=${PhonePadLogger.shortId(transferId)} file=${attachment.displayName} size=${attachment.size} mime=${attachment.mimeType} index=${index + 1}/${attachments.size}",
            )

            val uploadResult = uploadBinary(
                context = context,
                host = host,
                port = begin.uploadPort,
                token = begin.token,
                transferId = transferId,
                attachment = attachment,
                onChunkSent = { fileSent ->
                    onProgress(
                        FileTransferProgress(
                            batchId = batchId,
                            currentIndex = index + 1,
                            totalFiles = attachments.size,
                            currentFileName = attachment.displayName,
                            sentBytes = sentBytes + fileSent,
                            totalBytes = totalBytes,
                            fileSentBytes = fileSent,
                            fileTotalBytes = attachment.size,
                        ),
                    )
                },
                isCancelled = isCancelled,
            )
            if (!uploadResult.ok) {
                PhonePadLogger.w(
                    "file_transfer",
                    "file_upload_failed",
                    "transfer_id=${PhonePadLogger.shortId(transferId)} file=${attachment.displayName} reason=${uploadResult.error ?: "unknown"}",
                )
                fileCancel(host, deviceId, secret, tcpPort, transferId)
                return@withContext uploadResult
            }

            if (isCancelled()) {
                fileCancel(host, deviceId, secret, tcpPort, transferId)
                return@withContext ControlResponse(ok = false, error = "传输已取消")
            }

            val commit = fileCommit(host, deviceId, secret, tcpPort, transferId)
            if (!commit.ok) {
                PhonePadLogger.w(
                    "file_transfer",
                    "file_commit_failed",
                    "transfer_id=${PhonePadLogger.shortId(transferId)} file=${attachment.displayName} reason=${commit.error ?: "unknown"}",
                )
                fileCancel(host, deviceId, secret, tcpPort, transferId)
                return@withContext ControlResponse(ok = false, error = commit.error ?: "文件保存失败")
            }
            PhonePadLogger.i(
                "file_transfer",
                "file_commit",
                "transfer_id=${PhonePadLogger.shortId(transferId)} file=${attachment.displayName} saved_path=${commit.savedPath ?: "-"}",
            )

            sentBytes += attachment.size
            onProgress(
                FileTransferProgress(
                    batchId = batchId,
                    currentIndex = index + 1,
                    totalFiles = attachments.size,
                    currentFileName = attachment.displayName,
                    sentBytes = sentBytes,
                    totalBytes = totalBytes,
                    fileSentBytes = attachment.size,
                    fileTotalBytes = attachment.size,
                ),
            )
        }

        PhonePadLogger.i(
            "file_transfer",
            "file_batch_complete",
            "batch_id=${PhonePadLogger.shortId(batchId)} files=${attachments.size}",
        )
        ControlResponse(ok = true)
    }

    private fun fileBegin(
        host: String,
        deviceId: String,
        secret: String,
        tcpPort: Int,
        transferId: String,
        fileName: String,
        fileSize: Long,
        mimeType: String,
        batchId: String,
        fileIndex: Int,
        totalFiles: Int,
    ): FileBeginResult {
        val request = JSONObject()
            .put("type", "fileBegin")
            .put("deviceId", deviceId)
            .put("secret", secret)
            .put("transferId", transferId)
            .put("fileName", fileName)
            .put("fileSize", fileSize)
            .put("mimeType", mimeType)
            .put("batchId", batchId)
            .put("fileIndex", fileIndex)
            .put("totalFiles", totalFiles)
            .toString() + "\n"

        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, tcpPort), 5000)
                socket.soTimeout = 5000
                socket.getOutputStream().write(request.toByteArray())
                val jsonText = TcpStatusReader.readJson(socket.getInputStream()) ?: return FileBeginResult(
                    ok = false,
                    uploadPort = Protocol.TCP_FILE_PORT,
                    token = "",
                    error = "未收到桌面端响应",
                )
                val json = JSONObject(jsonText)
                if (!json.optBoolean("ok")) {
                    return FileBeginResult(
                        ok = false,
                        uploadPort = Protocol.TCP_FILE_PORT,
                        token = "",
                        error = json.optString("error").takeIf { it.isNotBlank() } ?: "无法开始文件传输",
                    )
                }
                val token = parseFileTransferToken(json)
                if (token == null || !validateFileTransferToken(token)) {
                    PhonePadLogger.w(
                        "file_transfer",
                        "token_invalid",
                        "transfer_id=${PhonePadLogger.shortId(transferId)} file=$fileName",
                    )
                    return FileBeginResult(
                        ok = false,
                        uploadPort = Protocol.TCP_FILE_PORT,
                        token = "",
                        error = "桌面端返回无效 token，请更新 PhonePad 到最新版本后重试",
                    )
                }
                FileBeginResult(
                    ok = true,
                    uploadPort = json.optInt("uploadPort", Protocol.TCP_FILE_PORT),
                    token = token,
                    error = null,
                )
            }
        }.getOrElse { err ->
            PhonePadLogger.w(
                "file_transfer",
                "file_begin_failed",
                "transfer_id=${PhonePadLogger.shortId(transferId)} file=$fileName reason=${err.message ?: "unknown"}",
                err,
            )
            FileBeginResult(
                ok = false,
                uploadPort = Protocol.TCP_FILE_PORT,
                token = "",
                error = err.message ?: "无法开始文件传输",
            )
        }
    }

    private fun uploadBinary(
        context: Context,
        host: String,
        port: Int,
        token: String,
        transferId: String,
        attachment: SelectedAttachment,
        onChunkSent: (Long) -> Unit,
        isCancelled: () -> Boolean,
    ): ControlResponse {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 10000)
                socket.soTimeout = 120_000
                val output = BufferedOutputStream(socket.getOutputStream())
                output.write(buildUploadHeader(token, transferId))

                context.contentResolver.openInputStream(attachment.uri)?.use { input ->
                    val buffered = BufferedInputStream(input)
                    val buffer = ByteArray(64 * 1024)
                    val expectedSize = attachment.size
                    var fileSent = 0L
                    while (fileSent < expectedSize) {
                        if (isCancelled()) {
                            return ControlResponse(ok = false, error = "传输已取消")
                        }
                        val toRead = minOf(buffer.size.toLong(), expectedSize - fileSent).toInt()
                        val read = buffered.read(buffer, 0, toRead)
                        if (read <= 0) {
                            break
                        }
                        output.write(buffer, 0, read)
                        fileSent += read
                        onChunkSent(fileSent)
                    }
                    if (fileSent != expectedSize) {
                        PhonePadLogger.w(
                            "file_transfer",
                            "file_upload_failed",
                            "transfer_id=${PhonePadLogger.shortId(transferId)} file=${attachment.displayName} reason=size_mismatch sent=$fileSent expected=$expectedSize",
                        )
                        return ControlResponse(ok = false, error = "文件大小与预期不符")
                    }
                } ?: return ControlResponse(ok = false, error = "无法读取附件")

                output.flush()
                val jsonText = TcpStatusReader.readJson(socket.getInputStream())
                    ?: return ControlResponse(ok = false, error = "文件上传无响应")
                val json = JSONObject(jsonText)
                ControlResponse(
                    ok = json.optBoolean("ok"),
                    error = json.optString("error").takeIf { it.isNotBlank() },
                )
            }
        }.getOrElse { err ->
            PhonePadLogger.w(
                "file_transfer",
                "file_upload_failed",
                "transfer_id=${PhonePadLogger.shortId(transferId)} file=${attachment.displayName} reason=${err.message ?: "unknown"}",
                err,
            )
            ControlResponse(ok = false, error = err.message ?: "文件上传失败")
        }
    }

    private fun fileCommit(
        host: String,
        deviceId: String,
        secret: String,
        tcpPort: Int,
        transferId: String,
    ): FileCommitResult {
        val request = JSONObject()
            .put("type", "fileCommit")
            .put("deviceId", deviceId)
            .put("secret", secret)
            .put("transferId", transferId)
            .toString() + "\n"

        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, tcpPort), 5000)
                socket.soTimeout = 5000
                socket.getOutputStream().write(request.toByteArray())
                val jsonText = TcpStatusReader.readJson(socket.getInputStream()) ?: return FileCommitResult(
                    ok = false,
                    error = "未收到桌面端响应",
                )
                val json = JSONObject(jsonText)
                FileCommitResult(
                    ok = json.optBoolean("ok"),
                    savedPath = json.optString("savedPath").takeIf { it.isNotBlank() },
                    error = json.optString("error").takeIf { it.isNotBlank() },
                )
            }
        }.getOrElse { err ->
            FileCommitResult(ok = false, error = err.message ?: "文件保存失败")
        }
    }

    private fun fileCancel(
        host: String,
        deviceId: String,
        secret: String,
        tcpPort: Int,
        transferId: String,
    ) {
        val request = JSONObject()
            .put("type", "fileCancel")
            .put("deviceId", deviceId)
            .put("secret", secret)
            .put("transferId", transferId)
            .toString() + "\n"
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, tcpPort), 3000)
                socket.soTimeout = 3000
                socket.getOutputStream().write(request.toByteArray())
            }
            PhonePadLogger.i(
                "file_transfer",
                "file_cancel",
                "transfer_id=${PhonePadLogger.shortId(transferId)}",
            )
        }
    }

    private fun buildUploadHeader(token: String, transferId: String): ByteArray {
        val header = ByteArray(47)
        header[0] = 'P'.code.toByte()
        header[1] = 'F'.code.toByte()
        header[2] = 1
        val tokenBytes = tokenToLittleEndianBytes(token)
        System.arraycopy(tokenBytes, 0, header, 3, 8)
        val idBytes = transferId.toByteArray(Charsets.UTF_8)
        val copyLen = idBytes.size.coerceAtMost(36)
        System.arraycopy(idBytes, 0, header, 11, copyLen)
        return header
    }
}

internal fun parseFileTransferToken(json: JSONObject): String? {
    if (!json.has("token") || json.isNull("token")) {
        return null
    }
    return when (val raw = json.get("token")) {
        is String -> raw.trim().takeIf { it.isNotEmpty() }
        is Number -> runCatching {
            java.math.BigDecimal(raw.toString()).toBigInteger().toString()
        }.getOrNull()
        else -> null
    }
}

internal fun validateFileTransferToken(token: String): Boolean =
    runCatching {
        val value = BigInteger(token.trim())
        value.signum() >= 0 && value.bitLength() <= 64
    }.getOrDefault(false)

internal fun tokenToLittleEndianBytes(token: String): ByteArray {
    val value = BigInteger(token)
    require(value.signum() >= 0 && value.bitLength() <= 64) { "invalid token" }
    val out = ByteArray(8)
    var current = value
    for (index in 0 until 8) {
        out[index] = (current and BigInteger.valueOf(0xFF)).toByte()
        current = current shr 8
    }
    return out
}
