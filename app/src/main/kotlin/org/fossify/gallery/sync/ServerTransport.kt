package org.fossify.gallery.sync

import android.os.Process
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.ClientHandler
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

// Prenosová vrstva LAN servera nad NanoHTTPD 2.3.1 (server-rychlost):
// - TCP_NODELAY na prijatých spojeniach — bez neho Nagle + oneskorené ACK (Windows ~200 ms) brzdili
//   každú malú odpoveď (hlavička a telo idú v dvoch zápisoch),
// - výstup cez 64 kB buffer (NanoHTTPD inak zapisuje telo po 16 kB priamo do socketu),
// - limit súbežných spojení: ďalšie dostane hneď 503 + Retry-After a zavrie sa (vlákno sa nevytvorí),
// - spojenie požiadavky je známe vláknu (ClientConn) — dekódovanie náhľadu, ktorý klient zrušil, sa
//   nespustí.
object ServerTransport {
    // čítanie požiadavky (aj pauza v uploade z PC); NanoHTTPD má predvolene 5 s
    const val READ_TIMEOUT_MS = 25_000
    const val MAX_CONNECTIONS = 20
    const val OUT_BUFFER = 64 * 1024

    // vlákno požiadavky beží s normálnou prioritou (NanoHTTPD ho vytvára z vlákna, ktoré mohlo mať nízku)
    fun normalPriority() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
        } catch (ignored: RuntimeException) {
        }
    }
}

class TunedSocketFactory : NanoHTTPD.ServerSocketFactory {
    override fun create(): ServerSocket = TunedServerSocket()
}

private class TunedServerSocket : ServerSocket() {
    override fun accept(): Socket {
        val s = BufferedSocket()
        implAccept(s)
        try {
            s.tcpNoDelay = true
        } catch (ignored: SocketException) {
        }
        return s
    }
}

// výstup cez buffer: NanoHTTPD po hlavičke aj po tele volá flush(), takže nič neostane visieť
private class BufferedSocket : Socket() {
    private var out: OutputStream? = null

    @Synchronized
    override fun getOutputStream(): OutputStream =
        out ?: BufferedOutputStream(super.getOutputStream(), ServerTransport.OUT_BUFFER).also { out = it }
}

// Vlákno na spojenie (ako NanoHTTPD), ale najviac `max` naraz. Nad limit odpovie 503 priamo na
// prijímacom vlákne a spojenie zavrie.
class LimitedRunner(private val max: Int, private val onReject: () -> Unit) : NanoHTTPD.AsyncRunner {
    private val running: MutableList<ClientHandler> = Collections.synchronizedList(ArrayList())
    private val sockets = ConcurrentHashMap<ClientHandler, Socket>()
    private val seq = AtomicLong(0)

    val open: Int get() = running.size

    // MediaServer.createClientHandler: spojenie patriace k handleru (exec ho potrebuje)
    fun register(handler: ClientHandler, socket: Socket) {
        sockets[handler] = socket
    }

    override fun closeAll() {
        ArrayList(running).forEach { it.close() }
    }

    override fun closed(handler: ClientHandler) {
        running.remove(handler)
        sockets.remove(handler)
    }

    override fun exec(handler: ClientHandler) {
        val socket = sockets[handler]
        if (running.size >= max) {
            sockets.remove(handler)
            onReject()
            reject(socket)
            handler.close()
            return
        }
        running.add(handler)
        val t = Thread({
            ClientConn.bind(socket)
            try {
                handler.run()
            } finally {
                ClientConn.unbind()
            }
        }, "galeria-http-${seq.incrementAndGet()}")
        t.isDaemon = true
        t.start()
    }

    private fun reject(socket: Socket?) {
        if (socket == null) return
        try {
            val body = "{\"error\":\"busy\"}".toByteArray(Charsets.UTF_8)
            val head = "HTTP/1.1 503 Service Unavailable\r\nContent-Type: application/json; charset=utf-8\r\n" +
                "Retry-After: 1\r\nConnection: close\r\nContent-Length: ${body.size}\r\n\r\n"
            val out = socket.getOutputStream()
            out.write(head.toByteArray(Charsets.US_ASCII))
            out.write(body)
            out.flush()
        } catch (ignored: IOException) {
        }
    }
}

// Spojenie aktuálnej požiadavky (vlákno = spojenie). gone() zistí, či klient spojenie už zavrel
// (prehliadač zrušil načítanie náhľadu): krátke „nazretie“ do vstupu — FIN = koniec prúdu.
object ClientConn {
    private const val PEEK_TIMEOUT_MS = 1
    private val current = ThreadLocal<Socket?>()

    fun bind(socket: Socket?) = current.set(socket)

    fun unbind() = current.remove()

    fun gone(session: IHTTPSession): Boolean {
        val s = current.get() ?: return false
        if (s.isClosed || s.isInputShutdown) return true
        val input: InputStream = session.inputStream ?: return false
        if (!input.markSupported()) return false
        return try {
            // čakajúce bajty = ďalšia požiadavka na keep-alive, spojenie žije
            if (input.available() > 0) return false
            val old = s.soTimeout
            try {
                s.soTimeout = PEEK_TIMEOUT_MS
                input.mark(1)
                val r = input.read()
                if (r >= 0) input.reset()
                r < 0
            } finally {
                s.soTimeout = old
            }
        } catch (ignored: SocketTimeoutException) {
            false
        } catch (ignored: IOException) {
            true
        }
    }
}
