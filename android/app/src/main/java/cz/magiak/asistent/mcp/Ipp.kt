package cz.magiak.asistent.mcp

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

/** Just enough IPP 1.1 for printer status and one print job. */
object Ipp {
    const val PRINT_JOB = 0x0002
    const val GET_PRINTER_ATTRIBUTES = 0x000B

    const val NAME = 0x42
    const val KEYWORD = 0x44
    const val URI = 0x45
    const val CHARSET = 0x47
    const val LANGUAGE = 0x48
    const val MIME = 0x49

    class Attr(val tag: Int, val name: String, val values: List<ByteArray>)

    fun text(tag: Int, name: String, vararg values: String) =
        Attr(tag, name, values.map { it.toByteArray(Charsets.UTF_8) })

    fun integer(name: String, value: Int) = Attr(0x21, name, listOf(ByteBuffer.allocate(4).putInt(value).array()))

    fun encode(operation: Int, operationAttrs: List<Attr>, jobAttrs: List<Attr> = emptyList()): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeByte(1)
            writeByte(1)
            writeShort(operation)
            writeInt(1)
            writeByte(0x01)
            operationAttrs.forEach { write(this, it) }
            if (jobAttrs.isNotEmpty()) {
                writeByte(0x02)
                jobAttrs.forEach { write(this, it) }
            }
            writeByte(0x03)
        }
        return out.toByteArray()
    }

    private fun write(data: DataOutputStream, attr: Attr) {
        attr.values.forEachIndexed { i, value ->
            val name = if (i == 0) attr.name.toByteArray(Charsets.UTF_8) else ByteArray(0)
            data.writeByte(attr.tag)
            data.writeShort(name.size)
            data.write(name)
            data.writeShort(value.size)
            data.write(value)
        }
    }

    class Reply(val status: Int, private val attributes: Map<String, List<Any>>) {
        val ok get() = status < 0x0100
        fun ints(name: String) = attributes[name].orEmpty().filterIsInstance<Int>()
        fun strings(name: String) = attributes[name].orEmpty().filterIsInstance<String>()
    }

    fun decode(bytes: ByteArray): Reply {
        val buffer = ByteBuffer.wrap(bytes)
        buffer.short
        val status = buffer.short.toInt() and 0xFFFF
        buffer.int
        val attributes = linkedMapOf<String, MutableList<Any>>()
        var last: String? = null
        while (buffer.hasRemaining()) {
            val tag = buffer.get().toInt() and 0xFF
            if (tag == 0x03) break
            if (tag < 0x10) continue
            val nameLength = buffer.short.toInt() and 0xFFFF
            // An empty name continues the previous attribute with one more value.
            val name = if (nameLength > 0) String(ByteArray(nameLength).also { buffer.get(it) }, Charsets.UTF_8) else last
            val raw = ByteArray(buffer.short.toInt() and 0xFFFF).also { buffer.get(it) }
            val value: Any = when (tag) {
                0x21, 0x23 -> if (raw.size == 4) ByteBuffer.wrap(raw).int else raw
                0x22 -> raw.firstOrNull()?.toInt() == 1
                in 0x41..0x49 -> String(raw, Charsets.UTF_8)
                else -> raw
            }
            if (name != null) {
                attributes.getOrPut(name) { mutableListOf() }.add(value)
                last = name
            }
        }
        return Reply(status, attributes)
    }
}
