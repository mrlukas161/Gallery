package org.fossify.gallery.family

import java.io.ByteArrayOutputStream
import java.io.UnsupportedEncodingException
import java.net.URLDecoder
import java.net.URLEncoder

// Drôtový formát rodinného prepojenia (čistá logika, bez Androidu).
//
// Obálka = hlavička + rámec:
//   hlavička: 'G' 'F' verzia(1) | dĺžka deviceId(1) | deviceId (UTF-8) | poradové číslo (8 B, big-endian)
//   rámec:    [nonce12][ciphertext][tag16] (FamilyCrypto.seal)
// AAD rámca = celá hlavička (teda deviceId odosielateľa + poradové číslo) + označenie smeru a endpointu
// ("req:/f/ops", "res:/f/ops") + voliteľná väzba (napr. nonce klienta pri hello). Rámec skopírovaný na iný
// endpoint, s iným poradovým číslom alebo v opačnom smere sa preto neoverí.
object FamilyWire {
    const val VERSION: Byte = 1
    const val MAX_DEVICE_ID = 64
    private const val SEQ_BYTES = 8
    private const val BYTE_BITS = 8
    private const val BYTE_MASK = 0xffL
    private const val PREFIX_BYTES = 4
    private val MAGIC = byteArrayOf('G'.code.toByte(), 'F'.code.toByte(), VERSION)

    // prečítaná hlavička obálky; frameOffset = kde začína rámec
    class Header(val deviceId: String, val seq: Long, val bytes: ByteArray) {
        val frameOffset: Int get() = bytes.size
    }

    fun requestLabel(path: String): String = "req:$path"

    fun responseLabel(path: String): String = "res:$path"

    fun header(deviceId: String, seq: Long): ByteArray {
        val id = deviceId.utf8()
        require(id.isNotEmpty() && id.size <= MAX_DEVICE_ID) { "deviceId" }
        require(seq >= 0) { "seq" }
        val out = ByteArrayOutputStream(MAGIC.size + 1 + id.size + SEQ_BYTES)
        out.write(MAGIC)
        out.write(id.size)
        out.write(id)
        for (i in SEQ_BYTES - 1 downTo 0) out.write(((seq ushr (i * BYTE_BITS)) and BYTE_MASK).toInt())
        return out.toByteArray()
    }

    fun readHeader(envelope: ByteArray): Header? {
        val fixed = MAGIC.size + 1
        val valid = envelope.size >= fixed && envelope.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)
        val idLen = if (valid) envelope[MAGIC.size].toInt() and BYTE_MASK.toInt() else 0
        val end = fixed + idLen + SEQ_BYTES
        if (idLen == 0 || idLen > MAX_DEVICE_ID || envelope.size < end) return null
        val deviceId = String(envelope, fixed, idLen, Charsets.UTF_8)
        var seq = 0L
        for (i in fixed + idLen until end) seq = (seq shl BYTE_BITS) or (envelope[i].toLong() and BYTE_MASK)
        return if (seq < 0) null else Header(deviceId, seq, envelope.copyOfRange(0, end))
    }

    fun seal(
        key: ByteArray,
        deviceId: String,
        seq: Long,
        label: String,
        plaintext: ByteArray,
        bind: ByteArray = ByteArray(0),
    ): ByteArray {
        val head = header(deviceId, seq)
        return head + FamilyCrypto.seal(key, head + label.utf8() + bind, plaintext)
    }

    // null = rámec sa neoveril (iný kľúč, zmenený bajt, iné AAD)
    fun open(
        key: ByteArray,
        header: Header,
        envelope: ByteArray,
        label: String,
        bind: ByteArray = ByteArray(0),
    ): ByteArray? = FamilyCrypto.open(key, header.bytes + label.utf8() + bind, envelope, header.frameOffset)

    // Balík s binárnymi dátami: [dĺžka hlavičky 4 B][hlavička ako KvCodec][dáta]
    fun packBlob(meta: Map<String, String>, data: ByteArray): ByteArray {
        val head = KvCodec.encode(meta).utf8()
        val out = ByteArrayOutputStream(PREFIX_BYTES + head.size + data.size)
        for (i in PREFIX_BYTES - 1 downTo 0) out.write((head.size ushr (i * BYTE_BITS)) and BYTE_MASK.toInt())
        out.write(head)
        out.write(data)
        return out.toByteArray()
    }

    fun unpackBlob(blob: ByteArray): Pair<Map<String, String>, ByteArray>? {
        if (blob.size < PREFIX_BYTES) return null
        var len = 0
        for (i in 0 until PREFIX_BYTES) len = (len shl BYTE_BITS) or (blob[i].toInt() and BYTE_MASK.toInt())
        if (len < 0 || len > blob.size - PREFIX_BYTES) return null
        val meta = KvCodec.decode(String(blob, PREFIX_BYTES, len, Charsets.UTF_8)) ?: return null
        return meta to blob.copyOfRange(PREFIX_BYTES + len, blob.size)
    }
}

// Jednoduché kľúč=hodnota&… (URL kódovanie, UTF-8) pre párovacie správy a pair URI. Bez org.json,
// aby sa to dalo testovať mimo Androidu.
object KvCodec {
    private const val UTF8 = "UTF-8"

    fun encode(values: Map<String, String>): String =
        values.entries.joinToString("&") { (k, v) -> URLEncoder.encode(k, UTF8) + "=" + URLEncoder.encode(v, UTF8) }

    // null = poškodené kódovanie
    fun decode(text: String): Map<String, String>? {
        if (text.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (part in text.split('&')) {
            if (part.isEmpty()) continue
            val eq = part.indexOf('=')
            val rawKey = if (eq < 0) part else part.substring(0, eq)
            val rawValue = if (eq < 0) "" else part.substring(eq + 1)
            val key = decodePart(rawKey) ?: return null
            val value = decodePart(rawValue) ?: return null
            out[key] = value
        }
        return out
    }

    private fun decodePart(text: String): String? = try {
        URLDecoder.decode(text, UTF8)
    } catch (ignored: IllegalArgumentException) {
        null
    } catch (ignored: UnsupportedEncodingException) {
        null
    }
}
