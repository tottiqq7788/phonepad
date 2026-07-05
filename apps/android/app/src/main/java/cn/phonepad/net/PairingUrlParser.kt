package cn.phonepad.net

object PairingUrlParser {
    fun parseHost(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null

        val withoutScheme = when {
            trimmed.startsWith("phonepad://", ignoreCase = true) ->
                trimmed.removePrefix("phonepad://").removePrefix("PHONEPAD://")
            trimmed.startsWith("totti-pad://", ignoreCase = true) ->
                trimmed.removePrefix("totti-pad://").removePrefix("TOTTI-PAD://")
            else -> trimmed
        }

        val hostPart = withoutScheme.substringBefore('/').substringBefore('?')
        val host = hostPart.substringBefore(':').trim()
        return host.takeIf { it.isNotBlank() && it != "desktop-ip" }
    }
}
