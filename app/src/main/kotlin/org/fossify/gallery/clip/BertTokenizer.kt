package org.fossify.gallery.clip

import android.util.JsonReader
import java.io.File
import java.io.InputStreamReader

// BERT WordPiece tokenizer (multilingválny, CASED). Vocab sa načíta z HuggingFace tokenizer.json
// (kľúč model.vocab). Postup ako v pôvodnom BERT-e: BasicTokenizer (vyčistenie, medzery okolo CJK,
// delenie na medzerách a interpunkcii, BEZ lowercase pre cased — zachová diakritiku) -> WordPiece
// (greedy najdlhšia zhoda s prefixom "##"). Pridá [CLS] ... [SEP].
class BertTokenizer private constructor(
    private val vocab: Map<String, Int>,
    private val unkId: Int,
    private val clsId: Int,
    private val sepId: Int,
) {
    fun encode(text: String, maxLen: Int = 128): IntArray {
        val ids = ArrayList<Int>(maxLen)
        ids.add(clsId)
        outer@ for (token in basicTokenize(text)) {
            for (pieceId in wordPiece(token)) {
                ids.add(pieceId)
                if (ids.size >= maxLen - 1) break@outer
            }
        }
        ids.add(sepId)
        return ids.toIntArray()
    }

    private fun wordPiece(token: String): List<Int> {
        if (token.length > 100) return listOf(unkId)
        val out = ArrayList<Int>()
        var start = 0
        while (start < token.length) {
            var end = token.length
            var curId: Int? = null
            while (start < end) {
                var sub = token.substring(start, end)
                if (start > 0) sub = "##$sub"
                val id = vocab[sub]
                if (id != null) {
                    curId = id
                    break
                }
                end--
            }
            if (curId == null) return listOf(unkId) // celé slovo -> [UNK]
            out.add(curId)
            start = end
        }
        return out
    }

    private fun basicTokenize(text: String): List<String> {
        // vyčistenie + medzery okolo CJK znakov
        val cleaned = StringBuilder(text.length + 16)
        for (ch in text) {
            val cp = ch.code
            if (cp == 0 || cp == 0xFFFD || isControl(ch)) continue
            when {
                isWhitespace(ch) -> cleaned.append(' ')
                isChinese(cp) -> cleaned.append(' ').append(ch).append(' ')
                else -> cleaned.append(ch)
            }
        }
        val tokens = ArrayList<String>()
        for (word in cleaned.toString().trim().split(WS)) {
            if (word.isEmpty()) continue
            // delenie na interpunkcii (každý interpunkčný znak = samostatný token)
            val sb = StringBuilder()
            for (ch in word) {
                if (isPunct(ch)) {
                    if (sb.isNotEmpty()) {
                        tokens.add(sb.toString()); sb.setLength(0)
                    }
                    tokens.add(ch.toString())
                } else {
                    sb.append(ch)
                }
            }
            if (sb.isNotEmpty()) tokens.add(sb.toString())
        }
        return tokens
    }

    private fun isWhitespace(ch: Char): Boolean {
        if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') return true
        return Character.getType(ch) == Character.SPACE_SEPARATOR.toInt()
    }

    private fun isControl(ch: Char): Boolean {
        if (ch == '\t' || ch == '\n' || ch == '\r') return false
        return when (Character.getType(ch)) {
            Character.CONTROL.toInt(), Character.FORMAT.toInt(),
            Character.SURROGATE.toInt(), Character.PRIVATE_USE.toInt(), Character.UNASSIGNED.toInt() -> true
            else -> false
        }
    }

    private fun isPunct(ch: Char): Boolean {
        val cp = ch.code
        if (cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126) return true
        return when (Character.getType(ch)) {
            Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt() -> true
            else -> false
        }
    }

    private fun isChinese(cp: Int): Boolean =
        (cp in 0x4E00..0x9FFF) || (cp in 0x3400..0x4DBF) || (cp in 0x20000..0x2A6DF) ||
            (cp in 0x2A700..0x2B73F) || (cp in 0x2B740..0x2B81F) || (cp in 0x2B820..0x2CEAF) ||
            (cp in 0xF900..0xFAFF) || (cp in 0x2F800..0x2FA1F)

    companion object {
        private val WS = Regex("\\s+")

        // načíta iba model.vocab z tokenizer.json (stream, aby sa 9 MB súbor nedržal celý v pamäti)
        fun load(tokenizerJson: File): BertTokenizer {
            val vocab = HashMap<String, Int>(120_000)
            tokenizerJson.inputStream().use { ins ->
                val r = JsonReader(InputStreamReader(ins, "UTF-8"))
                r.beginObject()
                while (r.hasNext()) {
                    if (r.nextName() == "model") {
                        r.beginObject()
                        while (r.hasNext()) {
                            if (r.nextName() == "vocab") {
                                r.beginObject()
                                while (r.hasNext()) {
                                    val tok = r.nextName()
                                    vocab[tok] = r.nextInt()
                                }
                                r.endObject()
                            } else {
                                r.skipValue()
                            }
                        }
                        r.endObject()
                    } else {
                        r.skipValue()
                    }
                }
                r.endObject()
                r.close()
            }
            if (vocab.isEmpty()) throw IllegalStateException("prázdny vocab v tokenizer.json")
            val unk = vocab["[UNK]"] ?: 100
            val cls = vocab["[CLS]"] ?: 101
            val sep = vocab["[SEP]"] ?: 102
            return BertTokenizer(vocab, unk, cls, sep)
        }
    }
}
