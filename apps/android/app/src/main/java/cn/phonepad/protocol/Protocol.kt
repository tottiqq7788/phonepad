package cn.phonepad.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

object Protocol {
    const val VERSION: Byte = 1
    const val PACKET_LEN = 24
    const val UDP_INPUT_PORT = 45454
    const val TCP_CONTROL_PORT = 45455
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
            buffer.put(0) // reserved
            return buffer.array()
        }

        companion object {
            fun move(sequence: Int, timestampMicros: Long, dx: Int, dy: Int, fingers: Int = 1): InputPacket {
                return InputPacket(
                    kind = PacketKind.Move,
                    sequence = sequence,
                    timestampMicros = timestampMicros,
                    x = dx.coerceIn(-32768, 32767).toShort(),
                    y = dy.coerceIn(-32768, 32767).toShort(),
                    fingers = fingers.toByte(),
                )
            }

            fun scroll(sequence: Int, timestampMicros: Long, dx: Int, dy: Int): InputPacket {
                return InputPacket(
                    kind = PacketKind.Scroll,
                    sequence = sequence,
                    timestampMicros = timestampMicros,
                    x = dx.coerceIn(-32768, 32767).toShort(),
                    y = dy.coerceIn(-32768, 32767).toShort(),
                    fingers = 2,
                )
            }

            fun click(sequence: Int, timestampMicros: Long, button: MouseButton): InputPacket {
                return InputPacket(
                    kind = PacketKind.Click,
                    sequence = sequence,
                    timestampMicros = timestampMicros,
                    button = button,
                    action = ButtonAction.Click,
                )
            }
        }
    }
}
