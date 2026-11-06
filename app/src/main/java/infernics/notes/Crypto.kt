package infernics.notes

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM encryption for both note records and vault files.
 * The passphrase-derived key remains the primary key. A copy of that key is
 * wrapped by Android Keystore only when the user explicitly enables biometrics.
 */
class Crypto private constructor(private val key: SecretKey) {

    fun encrypt(plain: ByteArray): ByteArray {
        val iv = ByteArray(IV_LEN).also(SECURE_RANDOM::nextBytes)
        val cipher = newCipher(Cipher.ENCRYPT_MODE, iv)
        return iv + cipher.doFinal(plain)
    }

    fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size >= IV_LEN + TAG_LEN_BYTES) { "Encrypted data is incomplete" }
        val iv = blob.copyOfRange(0, IV_LEN)
        val cipher = newCipher(Cipher.DECRYPT_MODE, iv)
        return cipher.doFinal(blob, IV_LEN, blob.size - IV_LEN)
    }

    fun encrypt(text: String): ByteArray = encrypt(text.toByteArray(Charsets.UTF_8))

    fun decryptToText(blob: ByteArray): String = String(decrypt(blob), Charsets.UTF_8)

    /**
     * Chunked AES-GCM file format. Each 64 KiB chunk has its own random IV and
     * authenticated index/length; an encrypted footer authenticates the total size
     * and chunk count. This keeps memory bounded and detects truncation/reordering.
     */
    fun encryptStream(input: InputStream, output: OutputStream): Long {
        val data = DataOutputStream(output)
        data.write(FILE_MAGIC)
        val buffer = ByteArray(FILE_CHUNK_SIZE)
        var chunkIndex = 0
        var totalBytes = 0L

        while (true) {
            var count = input.read(buffer)
            if (count < 0) break
            if (count == 0) {
                // InputStream may legally return zero for a non-empty buffer.
                // Force progress rather than spinning indefinitely.
                val singleByte = input.read()
                if (singleByte < 0) break
                buffer[0] = singleByte.toByte()
                count = 1
            }

            val iv = ByteArray(IV_LEN).also(SECURE_RANDOM::nextBytes)
            val cipher = fileCipher(Cipher.ENCRYPT_MODE, iv).apply {
                updateAAD(chunkAad(chunkIndex, count))
            }
            val encrypted = cipher.doFinal(buffer, 0, count)
            data.writeInt(count)
            data.write(iv)
            data.write(encrypted)
            chunkIndex++
            totalBytes += count
        }

        val footer = ByteBuffer.allocate(12).putInt(chunkIndex).putLong(totalBytes).array()
        val footerIv = ByteArray(IV_LEN).also(SECURE_RANDOM::nextBytes)
        val footerCipher = fileCipher(Cipher.ENCRYPT_MODE, footerIv).apply {
            updateAAD(footerAad(chunkIndex))
        }
        val encryptedFooter = footerCipher.doFinal(footer)
        data.writeInt(0) // terminal record; followed by an authenticated footer
        data.write(footerIv)
        data.write(encryptedFooter)
        data.flush()
        return totalBytes
    }

    /** Each chunk is authenticated before its plaintext is written to the private temp file. */
    fun decryptStream(input: InputStream, output: OutputStream): Long {
        val data = DataInputStream(input)
        val magic = ByteArray(FILE_MAGIC.size)
        data.readFully(magic)
        require(magic.contentEquals(FILE_MAGIC)) { "Unknown encrypted file format" }

        var chunkIndex = 0
        var totalBytes = 0L
        while (true) {
            val plainLength = data.readInt()
            require(plainLength in 0..FILE_CHUNK_SIZE) { "Invalid encrypted file block" }
            val iv = ByteArray(IV_LEN)
            data.readFully(iv)
            val encryptedLength = if (plainLength == 0) 12 + TAG_LEN_BYTES else plainLength + TAG_LEN_BYTES
            val encrypted = ByteArray(encryptedLength)
            data.readFully(encrypted)

            if (plainLength == 0) {
                val footerCipher = fileCipher(Cipher.DECRYPT_MODE, iv).apply {
                    updateAAD(footerAad(chunkIndex))
                }
                val footer = ByteBuffer.wrap(footerCipher.doFinal(encrypted))
                val expectedChunks = footer.int
                val expectedBytes = footer.long
                require(expectedChunks == chunkIndex && expectedBytes == totalBytes) {
                    "Encrypted file footer does not match its contents"
                }
                require(data.read() == -1) { "Unexpected data after encrypted file footer" }
                output.flush()
                return totalBytes
            }

            val cipher = fileCipher(Cipher.DECRYPT_MODE, iv).apply {
                updateAAD(chunkAad(chunkIndex, plainLength))
            }
            val plain = cipher.doFinal(encrypted)
            require(plain.size == plainLength) { "Invalid decrypted block length" }
            output.write(plain)
            totalBytes += plain.size
            chunkIndex++
        }
    }

    private fun fileCipher(mode: Int, iv: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(TAG_BITS, iv))
        }

    private fun chunkAad(index: Int, length: Int): ByteArray =
        FILE_MAGIC + ByteBuffer.allocate(8).putInt(index).putInt(length).array()

    private fun footerAad(chunkCount: Int): ByteArray =
        FILE_MAGIC + byteArrayOf(0x45, 0x4E, 0x44) + ByteBuffer.allocate(4).putInt(chunkCount).array()

    /** Used only to wrap the existing vault key inside Android Keystore after explicit opt-in. */
    internal fun exportKeyForBiometricWrapping(): ByteArray {
        val encoded = key.encoded ?: error("Vault key cannot be exported")
        return try {
            encoded.copyOf()
        } finally {
            encoded.fill(0)
        }
    }

    private fun newCipher(mode: Int, iv: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(TAG_BITS, iv))
        }

    companion object {
        private val SECURE_RANDOM = SecureRandom()
        private const val ITERATIONS = 250_000
        private const val KEY_BITS = 256
        private const val SALT_LEN = 16
        private const val IV_LEN = 12
        private const val TAG_BITS = 128
        private const val TAG_LEN_BYTES = TAG_BITS / 8
        private const val FILE_CHUNK_SIZE = 64 * 1024
        private val FILE_MAGIC = byteArrayOf(0x53, 0x4E, 0x46, 0x31) // SNF1

        fun newSalt(): ByteArray = ByteArray(SALT_LEN).also(SECURE_RANDOM::nextBytes)

        fun fromPassphrase(passphrase: String, salt: ByteArray): Crypto {
            val spec = PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, KEY_BITS)
            return try {
                val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                val keyBytes = factory.generateSecret(spec).encoded
                try {
                    fromRawKeyBytes(keyBytes)
                } finally {
                    keyBytes.fill(0)
                }
            } finally {
                spec.clearPassword()
            }
        }

        internal fun fromRawKeyBytes(rawKey: ByteArray): Crypto {
            require(rawKey.size == KEY_BITS / 8) { "Invalid vault key length" }
            return Crypto(SecretKeySpec(rawKey.copyOf(), "AES"))
        }
    }
}
