package org.fossify.gallery.family

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

// Minimálny HTTP/1.1 klient nad Socket (čistá logika). HttpURLConnection na Androide 9+ zakazuje
// nešifrovaný HTTP (cleartext) a povoliť ho pre celú appku nechceme. Tu je obsah aj tak zašifrovaný
// rámcom FamilyWire, takže HTTP slúži len ako obal.
object FamilyHttp {
    const val STATUS_OK = 200
    const val MAX_RESPONSE_BYTES = 96L * 1024 * 1024
    private const val MAX_HEADER_BYTES = 16 * 1024
    private const val CR = '\r'.code
    private const val LF = '\n'.code
    private const val BUFFER = 8192
    private const val INVALID_LENGTH = -2L

    class Reply(val status: Int, val body: ByteArray)

    // IOException = nedostupné alebo prerušené spojenie
    @Throws(IOException::class)
    fun post(
        endpoint: FamilyEndpoint,
        path: String,
        body: ByteArray,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): Reply {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(endpoint.host, endpoint.port), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
            val head = "POST $path HTTP/1.1\r\n" +
                "Host: ${hostHeader(endpoint)}\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n"
            val out = socket.getOutputStream()
            out.write(head.toByteArray(Charsets.US_ASCII))
            out.write(body)
            out.flush()
            return readReply(BufferedInputStream(socket.getInputStream()))
        }
    }

    // ako post, ale nedostupné spojenie = null
    fun tryPost(endpoint: FamilyEndpoint, path: String, body: ByteArray, connectTimeoutMs: Int, readTimeoutMs: Int) =
        try {
            post(endpoint, path, body, connectTimeoutMs, readTimeoutMs)
        } catch (ignored: IOException) {
            null
        }

    @Throws(IOException::class)
    internal fun readReply(input: InputStream): Reply {
        val status = readLine(input)?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: throw IOException("bad status")
        val length = readContentLength(input)
        if (length < -1 || length > MAX_RESPONSE_BYTES) throw IOException("bad length")
        return Reply(status, if (length >= 0) readExactly(input, length.toInt()) else readToEnd(input))
    }

    // prečíta hlavičky až po prázdny riadok; -1 = bez Content-Length, -2 = neplatná hodnota
    private fun readContentLength(input: InputStream): Long {
        var length = -1L
        var total = 0
        while (true) {
            val line = readLine(input)
            total += line?.length ?: 0
            if (line == null || total > MAX_HEADER_BYTES) throw IOException("headers")
            if (line.isEmpty()) return length
            val colon = line.indexOf(':')
            if (colon > 0 && line.substring(0, colon).trim().equals("content-length", ignoreCase = true)) {
                length = line.substring(colon + 1).trim().toLongOrNull() ?: INVALID_LENGTH
            }
        }
    }

    private fun readLine(input: InputStream): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (line.size() == 0) null else line.toString(Charsets.ISO_8859_1.name())
            if (b == LF) break
            if (b != CR) line.write(b)
            if (line.size() > MAX_HEADER_BYTES) throw IOException("line too long")
        }
        return line.toString(Charsets.ISO_8859_1.name())
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buffer, read, length - read)
            if (n < 0) throw IOException("truncated")
            read += n
        }
        return buffer
    }

    private fun readToEnd(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_RESPONSE_BYTES) throw IOException("too large")
        }
        return out.toByteArray()
    }

    private fun hostHeader(endpoint: FamilyEndpoint): String {
        val host = endpoint.host.substringBefore('%')
        return if (host.contains(':')) "[$host]:${endpoint.port}" else "$host:${endpoint.port}"
    }
}
