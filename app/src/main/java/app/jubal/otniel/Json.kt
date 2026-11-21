package app.jubal.otniel

/**
 * A small JSON reader. The site's data is read with it the same way on the phone
 * and in the tests that run on a computer (where Android's org.json is missing).
 */
class J private constructor(val raw: Any?) {

    operator fun get(key: String): J = J((raw as? Map<*, *>)?.get(key))

    operator fun get(index: Int): J = J((raw as? List<*>)?.getOrNull(index))

    val isNull: Boolean get() = raw == null

    fun str(default: String = ""): String = when (raw) {
        null -> default
        is String -> raw
        is Double -> if (raw % 1.0 == 0.0 && kotlin.math.abs(raw) < 1e15) raw.toLong().toString() else raw.toString()
        is Boolean -> raw.toString()
        else -> default
    }

    fun long(default: Long = 0): Long = when (raw) {
        is Double -> raw.toLong()
        is String -> raw.trim().toLongOrNull() ?: raw.trim().toDoubleOrNull()?.toLong() ?: default
        is Boolean -> if (raw) 1 else 0
        else -> default
    }

    fun int(default: Int = 0): Int = long(default.toLong()).toInt()

    fun bool(default: Boolean = false): Boolean = when (raw) {
        is Boolean -> raw
        is Double -> raw != 0.0
        is String -> raw == "true" || raw == "1"
        else -> default
    }

    fun list(): List<J> = (raw as? List<*>)?.map { J(it) } ?: emptyList()

    fun keys(): Set<String> = (raw as? Map<*, *>)?.keys?.mapNotNull { it as? String }?.toSet() ?: emptySet()

    /** WordPress puts texts under "rendered". */
    fun rendered(): String = if (raw is Map<*, *>) get("rendered").str() else str()

    companion object {
        fun parse(text: String): J = J(Parser(text).document())

        fun of(value: Any?): J = J(value)
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun document(): Any? {
            ws()
            if (i < s.length && s[i] == '﻿') i++
            ws()
            if (i >= s.length) return null
            val v = value()
            ws()
            return v
        }

        private fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun fail(what: String): Nothing = throw IllegalArgumentException("JSON: $what at $i")

        private fun value(): Any? {
            ws()
            if (i >= s.length) fail("unexpected end")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> string()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> if (c == '-' || c.isDigit()) number() else fail("unexpected '$c'")
            }
        }

        private fun word(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) fail("bad literal")
            i += w.length
            return v
        }

        private fun number(): Double {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            return s.substring(start, i).toDoubleOrNull() ?: fail("bad number")
        }

        private fun string(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) fail("bad escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("bad unicode escape")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun arr(): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') {
                i++
                return out
            }
            while (true) {
                out += value()
                ws()
                if (i >= s.length) fail("unterminated array")
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> fail("expected , or ]")
                }
            }
        }

        private fun obj(): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') {
                i++
                return out
            }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') fail("expected key")
                val key = string()
                ws()
                if (i >= s.length || s[i++] != ':') fail("expected :")
                out[key] = value()
                ws()
                if (i >= s.length) fail("unterminated object")
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> fail("expected , or }")
                }
            }
        }
    }
}
