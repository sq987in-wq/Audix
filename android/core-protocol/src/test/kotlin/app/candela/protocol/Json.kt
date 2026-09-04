package app.candela.protocol

/**
 * Minimal JSON reader for the golden-vector tests.
 *
 * Hand-rolled deliberately: the sandbox cannot reach Maven Central, and adding a
 * JSON dependency to a test that exists purely to prove wire-format parity would
 * be a self-inflicted supply-chain risk. Only what the vectors use is supported.
 */
sealed interface JsonValue {
    data class JStr(val value: String) : JsonValue
    data class JNum(val value: Double) : JsonValue
    data class JBool(val value: Boolean) : JsonValue
    object JNull : JsonValue
    data class JArr(val items: List<JsonValue>) : JsonValue
    data class JObj(val fields: Map<String, JsonValue>) : JsonValue
}

class JsonParser(private val src: String) {
    private var i = 0

    companion object {
        fun parse(text: String): JsonValue = JsonParser(text).run {
            skipWs()
            val v = parseValue()
            skipWs()
            v
        }
    }

    private fun skipWs() {
        while (i < src.length && src[i].isWhitespace()) i++
    }

    private fun parseValue(): JsonValue {
        skipWs()
        return when (val c = src[i]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonValue.JStr(parseString())
            't' -> { expect("true"); JsonValue.JBool(true) }
            'f' -> { expect("false"); JsonValue.JBool(false) }
            'n' -> { expect("null"); JsonValue.JNull }
            else -> if (c == '-' || c.isDigit()) parseNumber()
            else throw IllegalArgumentException("Unexpected char '$c' at $i")
        }
    }

    private fun expect(word: String) {
        require(src.startsWith(word, i)) { "Expected $word at $i" }
        i += word.length
    }

    private fun parseObject(): JsonValue.JObj {
        i++ // {
        val map = LinkedHashMap<String, JsonValue>()
        skipWs()
        if (src[i] == '}') { i++; return JsonValue.JObj(map) }
        while (true) {
            skipWs()
            val key = parseString()
            skipWs()
            require(src[i] == ':') { "Expected ':' at $i" }
            i++
            map[key] = parseValue()
            skipWs()
            when (src[i]) {
                ',' -> i++
                '}' -> { i++; return JsonValue.JObj(map) }
                else -> throw IllegalArgumentException("Expected ',' or '}' at $i")
            }
        }
    }

    private fun parseArray(): JsonValue.JArr {
        i++ // [
        val list = ArrayList<JsonValue>()
        skipWs()
        if (src[i] == ']') { i++; return JsonValue.JArr(list) }
        while (true) {
            list.add(parseValue())
            skipWs()
            when (src[i]) {
                ',' -> i++
                ']' -> { i++; return JsonValue.JArr(list) }
                else -> throw IllegalArgumentException("Expected ',' or ']' at $i")
            }
        }
    }

    private fun parseString(): String {
        require(src[i] == '"') { "Expected string at $i" }
        i++
        val sb = StringBuilder()
        while (src[i] != '"') {
            if (src[i] == '\\') {
                i++
                when (val e = src[i]) {
                    '"' -> sb.append('"'); '\\' -> sb.append('\\')
                    '/' -> sb.append('/'); 'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                    'r' -> sb.append('\r'); 't' -> sb.append('\t')
                    'u' -> { sb.append(src.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                    else -> throw IllegalArgumentException("Bad escape \\$e")
                }
                i++
            } else {
                sb.append(src[i]); i++
            }
        }
        i++
        return sb.toString()
    }

    private fun parseNumber(): JsonValue.JNum {
        val start = i
        if (src[i] == '-') i++
        while (i < src.length && (src[i].isDigit() || src[i] in ".eE+-")) i++
        return JsonValue.JNum(src.substring(start, i).toDouble())
    }
}

// Convenience accessors — tests read a lot of nested fields.
fun JsonValue.obj(): Map<String, JsonValue> = (this as JsonValue.JObj).fields
fun JsonValue.arr(): List<JsonValue> = (this as JsonValue.JArr).items
fun JsonValue.str(): String = (this as JsonValue.JStr).value
fun JsonValue.num(): Double = (this as JsonValue.JNum).value
fun JsonValue.int(): Int = num().toInt()
operator fun JsonValue.get(key: String): JsonValue =
    obj()[key] ?: throw IllegalArgumentException("Missing field '$key'")
