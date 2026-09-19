package pi.kit.mob.pi

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Splits a byte stream into JSONL records for pi's RPC mode.
 *
 * pi's framing rules are unusually strict, and getting them wrong corrupts
 * records silently rather than failing loudly:
 *
 *  - **LF (`0x0A`) is the only delimiter.** `U+2028` and `U+2029` are valid
 *    inside JSON strings and pi emits them unescaped, so any reader built on
 *    `BufferedReader.readLine()`, `String.lines()`, `Scanner` or
 *    `lineSequence()` is wrong — those also split on Unicode line separators.
 *  - **A record may straddle reads, and one read may carry many records.**
 *  - **Multi-byte UTF-8 characters may straddle reads**, so decoding must be
 *    incremental; never decode a raw chunk in isolation.
 *
 * This class is deliberately free of Android and coroutine dependencies so it
 * can be exercised directly by unit tests.
 */
class JsonlFramer {

    private val decoder: CharsetDecoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    /** Bytes of an incomplete multi-byte sequence carried over between feeds. */
    private var pendingBytes: ByteBuffer = ByteBuffer.allocate(0)
    private val charBuffer: CharBuffer = CharBuffer.allocate(CHAR_BUFFER_SIZE)

    /** Characters of an incomplete record carried over between feeds. */
    private val line = StringBuilder()

    private var finished = false

    /**
     * Feeds [length] bytes from [input] and invokes [sink] once per complete
     * record, in order.
     */
    fun feed(input: ByteArray, offset: Int = 0, length: Int = input.size, sink: (String) -> Unit) {
        check(!finished) { "JsonlFramer already finished" }
        if (length <= 0) return

        val merged = ByteBuffer.allocate(pendingBytes.remaining() + length)
        merged.put(pendingBytes)
        merged.put(input, offset, length)
        merged.flip()

        while (true) {
            charBuffer.clear()
            val result = decoder.decode(merged, charBuffer, false)
            charBuffer.flip()
            drainChars(sink)
            if (result.isOverflow) continue
            // UNDERFLOW (need more input) or a REPLACE'd error: stop decoding
            // this chunk. On error the offending bytes were consumed.
            if (result.isUnderflow) break
            if (!merged.hasRemaining()) break
        }

        pendingBytes = ByteBuffer.allocate(merged.remaining()).apply {
            put(merged)
            flip()
        }
    }

    /**
     * Signals end of stream: flushes the decoder and emits a trailing record
     * that was not terminated by a newline.
     */
    fun end(sink: (String) -> Unit) {
        if (finished) return
        finished = true

        while (true) {
            charBuffer.clear()
            val result = decoder.decode(pendingBytes, charBuffer, true)
            charBuffer.flip()
            drainChars(sink)
            if (!result.isOverflow) break
        }
        pendingBytes = ByteBuffer.allocate(0)

        while (true) {
            charBuffer.clear()
            val result = decoder.flush(charBuffer)
            charBuffer.flip()
            drainChars(sink)
            if (!result.isOverflow) break
        }

        emit(sink)
    }

    private fun drainChars(sink: (String) -> Unit) {
        while (charBuffer.hasRemaining()) {
            val c = charBuffer.get()
            if (c == LF) emit(sink) else line.append(c)
        }
    }

    private fun emit(sink: (String) -> Unit) {
        if (line.isEmpty()) return
        var record = line.toString()
        line.setLength(0)
        // Tolerate CRLF producers; pi itself only writes LF.
        if (record.endsWith(CR)) record = record.substring(0, record.length - 1)
        if (record.isNotEmpty()) sink(record)
    }

    private companion object {
        const val LF = '\n'
        const val CR = '\r'
        const val CHAR_BUFFER_SIZE = 8192
    }
}
