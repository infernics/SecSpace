package infernics.notes

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Optional biometric unlock. Android Keystore protects a wrapped copy of the existing
 * passphrase-derived vault key. The notes themselves remain encrypted with the original
 * AES-256-GCM key. A biometric enrollment change invalidates this wrapper; password unlock
 * remains available and can be used to enroll biometrics again.
 */
object BiometricVault {
    private const val KEY_ALIAS = "SecNoteBiometricKey"
    private const val PREFS = "secnote_biometric"
    private const val WRAPPED_KEY = "wrapped_vault_key"
    private const val IV = "wrapped_vault_key_iv"
    private const val ENABLED = "enabled"
    private const val TAG_BITS = 128

    fun canAuthenticate(context: Context): Int =
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(ENABLED, false) &&
            prefs(context).contains(WRAPPED_KEY) && prefs(context).contains(IV)

    fun disable(context: Context) {
        prefs(context).edit().clear().apply()
        runCatching {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        }
    }

    fun enable(
        activity: MainActivity,
        crypto: Crypto,
        onEnabled: () -> Unit,
        onError: (String) -> Unit
    ) {
        val availability = canAuthenticate(activity)
        if (availability != BiometricManager.BIOMETRIC_SUCCESS) {
            onError(availabilityMessage(availability))
            return
        }

        val cipher = try {
            createEncryptionCipher()
        } catch (error: Exception) {
            onError("Не удалось подготовить биометрическую защиту: ${error.message ?: "ошибка Keystore"}")
            return
        }

        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    activity.allowTemporarySystemUi = false
                    activity.stoppedDuringTemporarySystemUi = false
                    try {
                        val authorizedCipher = result.cryptoObject?.cipher
                            ?: error("Система не вернула криптографический объект")
                        val rawVaultKey = crypto.exportKeyForBiometricWrapping()
                        val wrapped = try {
                            authorizedCipher.doFinal(rawVaultKey)
                        } finally {
                            rawVaultKey.fill(0)
                        }
                        prefs(activity).edit()
                            .putString(WRAPPED_KEY, Base64.encodeToString(wrapped, Base64.NO_WRAP))
                            .putString(IV, Base64.encodeToString(authorizedCipher.iv, Base64.NO_WRAP))
                            .putBoolean(ENABLED, true)
                            .apply()
                        onEnabled()
                    } catch (error: Exception) {
                        disable(activity)
                        onError("Не удалось включить биометрию: ${error.message ?: "ошибка шифрования"}")
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    activity.allowTemporarySystemUi = false
                    onError(errString.toString())
                }
            }
        )
        activity.allowTemporarySystemUi = true
        try {
            prompt.authenticate(promptInfo("Включить вход по биометрии", "Подтвердите личность для защиты ключа"), BiometricPrompt.CryptoObject(cipher))
        } catch (error: Exception) {
            activity.allowTemporarySystemUi = false
            onError("Не удалось открыть системную биометрию: ${error.message ?: "ошибка"}")
        }
    }

    fun unlock(
        activity: MainActivity,
        onUnlocked: (Crypto) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!isEnabled(activity)) {
            onError("Вход по биометрии не включён")
            return
        }
        val stored = prefs(activity)
        val wrapped = runCatching {
            Base64.decode(stored.getString(WRAPPED_KEY, null), Base64.NO_WRAP)
        }.getOrNull()
        val iv = runCatching {
            Base64.decode(stored.getString(IV, null), Base64.NO_WRAP)
        }.getOrNull()
        if (wrapped == null || iv == null || iv.size != 12) {
            disable(activity)
            onError("Биометрический ключ повреждён. Войдите по паролю и включите биометрию заново.")
            return
        }

        val cipher = try {
            createDecryptionCipher(iv)
        } catch (_: KeyPermanentlyInvalidatedException) {
            disable(activity)
            onError("Отпечатки изменились. Войдите по основному паролю и включите биометрию заново.")
            return
        } catch (error: Exception) {
            disable(activity)
            onError("Биометрический ключ недоступен. Войдите по паролю и настройте биометрию заново.")
            return
        }

        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    activity.allowTemporarySystemUi = false
                    activity.stoppedDuringTemporarySystemUi = false
                    try {
                        val authorizedCipher = result.cryptoObject?.cipher
                            ?: error("Система не вернула криптографический объект")
                        val rawKey = authorizedCipher.doFinal(wrapped)
                        try {
                            onUnlocked(Crypto.fromRawKeyBytes(rawKey))
                        } finally {
                            rawKey.fill(0)
                        }
                    } catch (error: Exception) {
                        disable(activity)
                        onError("Не удалось открыть биометрический ключ. Войдите по паролю.")
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    activity.allowTemporarySystemUi = false
                    onError(errString.toString())
                }
            }
        )
        activity.allowTemporarySystemUi = true
        try {
            prompt.authenticate(promptInfo("Вход в SecNote", "Подтвердите личность для разблокировки"), BiometricPrompt.CryptoObject(cipher))
        } catch (error: Exception) {
            activity.allowTemporarySystemUi = false
            onError("Не удалось открыть системную биометрию: ${error.message ?: "ошибка"}")
        }
    }

    private fun createEncryptionCipher(): Cipher {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val specBuilder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            specBuilder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            specBuilder.setUserAuthenticationValidityDurationSeconds(-1)
        }

        generator.init(specBuilder.build())
        val secretKey = generator.generateKey()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, secretKey)
        }
    }

    private fun createDecryptionCipher(iv: ByteArray): Cipher {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            ?: error("Ключ Android Keystore не найден")
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        }
    }

    private fun promptInfo(title: String, subtitle: String): BiometricPrompt.PromptInfo =
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Отмена")
            .setConfirmationRequired(false)
            .build()

    private fun availabilityMessage(status: Int): String = when (status) {
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "На устройстве нет биометрического датчика."
        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "Биометрический датчик сейчас недоступен."
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "Сначала добавьте отпечаток или другую биометрию в настройках Android."
        else -> "Устройство не поддерживает сильную биометрическую проверку."
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
