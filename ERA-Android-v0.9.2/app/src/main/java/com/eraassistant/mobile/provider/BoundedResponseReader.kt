package com.eraassistant.mobile.provider

import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader

/** Reads at most maxChars + 1 decoded characters so oversized responses are rejected early. */
internal object BoundedResponseReader {
    @Throws(IOException::class)
    fun readUtf8(input: InputStream, maxChars: Int): String {
        require(maxChars > 0) { "maxChars must be positive." }
        InputStreamReader(input, Charsets.UTF_8).use { reader ->
            val output = StringBuilder(minOf(maxChars, 8_192))
            val buffer = CharArray(4_096)
            var total = 0
            while (true) {
                val remainingWithSentinel = maxChars - total + 1
                val count = reader.read(buffer, 0, minOf(buffer.size, remainingWithSentinel))
                if (count < 0) break
                total += count
                if (total > maxChars) {
                    throw IOException("Provider response exceeded the $maxChars character limit.")
                }
                output.append(buffer, 0, count)
            }
            return output.toString()
        }
    }
}
