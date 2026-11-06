package infernics.notes

import android.content.Context
import java.io.File

/** Password verifier and salt. The password and derived key are never persisted in plaintext. */
object Vault {
    private fun saltFile(context: Context) = File(context.filesDir, "key.salt")
    private fun checkFile(context: Context) = File(context.filesDir, "key.check")

    fun isSetup(context: Context): Boolean = saltFile(context).exists() && checkFile(context).exists()

    fun create(context: Context, passphrase: String): Crypto {
        require(passphrase.length >= 8) { "Пароль должен содержать не менее 8 символов" }
        val salt = Crypto.newSalt()
        val crypto = Crypto.fromPassphrase(passphrase, salt)
        writeAtomically(saltFile(context), salt)
        writeAtomically(checkFile(context), crypto.encrypt("verify"))
        return crypto
    }

    fun unlock(context: Context, passphrase: String): Crypto? {
        if (!isSetup(context)) return null
        return try {
            val salt = saltFile(context).readBytes()
            if (salt.size != 16) return null
            val crypto = Crypto.fromPassphrase(passphrase, salt)
            crypto.decryptToText(checkFile(context).readBytes())
                .takeIf { it == "verify" }
                ?.let { crypto }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeAtomically(destination: File, bytes: ByteArray) {
        val temp = File(destination.parentFile, destination.name + ".tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(destination)) {
            destination.writeBytes(bytes)
            temp.delete()
        }
    }
}
