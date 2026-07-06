package cn.phonepad.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

object Protocol {
    const val VERSION: Byte = 2
    const val PACKET_LEN = 32
    const val UDP_INPUT_PORT = 45454
    const val TCP_CONTROL_PORT = 45455
    const val TCP_FILE_PORT = 45457
    const val UDP_DISCOVERY_PORT = 45456
    val DISCOVERY_REQUEST = "PHONEPAD_DISCOVER_V1".toByteArray(Charsets.UTF_8)

    enum class PacketKind(val code: Byte) {
        Move(1),
        Scroll(2),
        Click(3),
        Button(4),
        Ping(5),
        Pong(6),
    }

    enum class MouseButton(val code: Byte) {
        Left(1),
        Right(2),
        Middle(3),
    }

    enum class ButtonAction(val code: Byte) {
        Down(1),
        Up(2),
        Click(3),
    }

    data class InputPacket(
        val kind: PacketKind,
        val sequence: Int,
        val timestampMicros: Long,
        val x: Short = 0,
        val y: Short = 0,
        val button: MouseButton? = null,
        val action: ButtonAction? = null,
        val fingers: Byte = 0,
        val authToken: Long = 0L,
    ) {
        fun encode(): ByteArray {
            val buffer = ByteBuffer.allocate(PACKET_LEN).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put('T'.code.toByte())
            buffer.put('P'.code.toByte())
            buffer.put(VERSION)
            buffer.put(kind.code)
            buffer.putInt(sequence)
            buffer.putLong(timestampMicros)
            buffer.putShort(x)
            buffer.putShort(y)
            buffer.put(button?.code ?: 0)
            buffer.put(action?.code ?: 0)
            buffer.put(fingers)
            buffer.put(0)
            buffer.putLong(authToken)
            return buffer.array()
        }

        companion object {
            fun move(
                sequence: Int,
                timestampMicros: Long,
                dx: Int,
                dy: Int,
                authToken: Long,
                fingers: Int = 1,
            ): InputPacket {
                return InputPacket(
                    kind = PacketKind.Move,
                    sequence = sequence,
                    timestampMicros = timestampMicros,
                    x = dx.coerceIn(-32768, 32767).toShort(),
                    y = dy.coerceIn(-32768, 32767).toShort(),
                    fingers = fingers.toByte(),
                    authToken = authToken,
                )
            }

            fun scroll(
                sequence: Int,
                timestampMicros: Long,
                dx: Int,
                dy: Int,
                authToken: Long,
            ): InputPacket {
                return InputPacket(
                    kind = PacketKind.Scroll,
                    sequence = sequence,
                    timestampMicros = timestampMicros,
                    x = dx.coerceIn(-32768, 32767).toShort(),
                    y = dy.coerceIn(-32768, 32767).toShort(),
                    fingers = 2,
                    authToken = authToken,
                )
            }

            fun click(
                sequence: Int,
                timestampMicros: Long,
                button: MouseButton,
                authToken: Long,
            ): InputPacket {
                return InputPacket(
                    kind = PacketKind.Click,
                    sequence = sequence,
                    timestampMicros = timestampMicros,
                    button = button,
                    action = ButtonAction.Click,
                    authToken = authToken,
                )
            }
        }
    }

    fun authToken(secret: String, sequence: Int): Long {
        val bytes = secret.toByteArray(Charsets.UTF_8) + intToLeBytes(sequence)
        return fnv1a64(bytes)
    }

    private fun intToLeBytes(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte(),
        )
    }

    private fun fnv1a64(data: ByteArray): Long {
        var hash = -3750763034362895579L
        for (byte in data) {
            hash = hash xor (byte.toLong() and 0xFF)
            hash *= 1099511628211L
        }
        return hash
    }
}
