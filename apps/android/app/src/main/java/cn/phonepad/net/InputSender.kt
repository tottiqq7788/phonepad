package cn.phonepad.net

import cn.phonepad.protocol.Protocol
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class InputSender {
    private val socket = DatagramSocket()
    private val sequence = AtomicInteger(0)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "phonepad-udp-sender").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
        }
    }
    private val addressCache = ConcurrentHashMap<String, InetAddress>()
    @Volatile
    private var secret: String = ""

    init {
        socket.broadcast = true
    }

    fun setSecret(value: String) {
        secret = value
        sequence.set(0)
    }

    fun send(host: String, port: Int, packet: Protocol.InputPacket) {
        val nextSequence = sequence.incrementAndGet()
        val authToken = Protocol.authToken(secret, nextSequence)
        val payload = packet.copy(sequence = nextSequence, authToken = authToken).encode()
        executor.execute {
            if (secret.isBlank()) return@execute
            runCatching {
                val address = addressCache.getOrPut(host) { InetAddress.getByName(host) }
                val datagram = DatagramPacket(payload, payload.size, address, port)
                socket.send(datagram)
            }
        }
    }

    fun close() {
        socket.close()
        executor.shutdownNow()
    }
}
