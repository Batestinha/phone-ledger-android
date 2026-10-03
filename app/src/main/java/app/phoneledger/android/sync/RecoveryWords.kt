package app.phoneledger.android.sync

import java.security.MessageDigest
import java.util.Locale

/** The 256-bit entropy form of BIP-39, used as a human-readable recovery key. */
object RecoveryWords {
    private val dictionary: List<String> by lazy {
        checkNotNull(RecoveryWords::class.java.getResourceAsStream("/bip39_english.txt")) {
            "BIP-39 English dictionary is missing"
        }.bufferedReader(Charsets.UTF_8).use { reader -> reader.readLines() }.also {
            check(it.size == 2_048 && it.distinct().size == 2_048) { "BIP-39 English dictionary is invalid" }
        }
    }
    private val dictionaryIndex: Map<String, Int> by lazy { dictionary.withIndex().associate { it.value to it.index } }

    fun encode(root: ByteArray): String {
        require(root.size == ENTROPY_BYTES) { "Recovery key must be 256 bits" }
        val checksum = MessageDigest.getInstance("SHA-256").digest(root)[0].toInt() and 0xff
        return (0 until WORD_COUNT).joinToString(" ") { wordIndex ->
            var dictionaryIndex = 0
            repeat(BITS_PER_WORD) { bitInWord ->
                val bitPosition = wordIndex * BITS_PER_WORD + bitInWord
                val bit = if (bitPosition < ENTROPY_BITS) {
                    bit(root[bitPosition / 8].toInt() and 0xff, 7 - bitPosition % 8)
                } else {
                    bit(checksum, 7 - (bitPosition - ENTROPY_BITS))
                }
                dictionaryIndex = (dictionaryIndex shl 1) or bit
            }
            dictionary[dictionaryIndex]
        }
    }

    fun decode(words: String): ByteArray {
        val normalized = words.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter(String::isNotBlank)
        require(normalized.size == WORD_COUNT) { "Enter all 24 recovery words" }
        val indices = normalized.map { word ->
            dictionaryIndex[word] ?: throw IllegalArgumentException("Unknown recovery word: $word")
        }
        val entropy = ByteArray(ENTROPY_BYTES)
        repeat(ENTROPY_BITS) { bitPosition ->
            val index = indices[bitPosition / BITS_PER_WORD]
            val bit = bit(index, 10 - bitPosition % BITS_PER_WORD)
            if (bit == 1) {
                entropy[bitPosition / 8] = entropy[bitPosition / 8].toInt()
                    .or(1 shl (7 - bitPosition % 8)).toByte()
            }
        }
        val expectedChecksum = MessageDigest.getInstance("SHA-256").digest(entropy)[0].toInt() and 0xff
        repeat(CHECKSUM_BITS) { checksumBit ->
            val position = ENTROPY_BITS + checksumBit
            val actual = bit(indices[position / BITS_PER_WORD], 10 - position % BITS_PER_WORD)
            require(actual == bit(expectedChecksum, 7 - checksumBit)) { "Recovery words or checksum are invalid" }
        }
        return entropy
    }

    private fun bit(value: Int, offset: Int): Int = value ushr offset and 1

    private const val ENTROPY_BYTES = 32
    private const val ENTROPY_BITS = ENTROPY_BYTES * 8
    private const val CHECKSUM_BITS = 8
    private const val BITS_PER_WORD = 11
    private const val WORD_COUNT = (ENTROPY_BITS + CHECKSUM_BITS) / BITS_PER_WORD
}
