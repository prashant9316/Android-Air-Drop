package com.caremate.assistant

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A contact the assistant may call or alert in an emergency. */
data class CareContact(val name: String, val number: String)

/**
 * All user settings. The Claude API key is encrypted with an Android Keystore key
 * that never leaves the device's secure hardware.
 */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("caremate", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("api_key_enc", null)?.let { decrypt(it) } ?: ""
        set(v) = sp.edit().putString("api_key_enc", if (v.isBlank()) null else encrypt(v.trim())).apply()

    var model: String by str("model", DEFAULT_MODEL)
    var effort: String by str("effort", "low")
    var userName: String by str("user_name", "")
    var assistantName: String by str("assistant_name", "CareMate")
    var language: String by str("language", Locale.getDefault().toLanguageTag())
    var speechRate: Float
        get() = sp.getFloat("speech_rate", 0.9f)
        set(v) = sp.edit().putFloat("speech_rate", v).apply()
    var largeText: Boolean by bool("large_text", true)
    var confirmActions: Boolean by bool("confirm_actions", true)
    var keepListening: Boolean by bool("keep_listening", true)
    var aboutUser: String by str("about_user", "")

    /** One contact per line: "Name, +91 98xxxxxx". First line is called first in an SOS. */
    var emergencyContactsRaw: String by str("emergency_contacts", "")
    val emergencyContacts: List<CareContact> get() = parseContacts(emergencyContactsRaw)

    var careSmsEnabled: Boolean by bool("care_sms_enabled", false)
    var caregiverNumbersRaw: String by str("caregiver_numbers", "")
    var carePin: String by str("care_pin", "")
    val caregiverNumbers: List<String>
        get() = caregiverNumbersRaw.split(',', '\n', ';').map { it.trim() }.filter { it.isNotEmpty() }

    var onboarded: Boolean by bool("onboarded", false)

    private fun str(key: String, def: String) = object : kotlin.properties.ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = sp.getString(key, def) ?: def
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: String) =
            sp.edit().putString(key, value).apply()
    }

    private fun bool(key: String, def: Boolean) = object : kotlin.properties.ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = sp.getBoolean(key, def)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Boolean) =
            sp.edit().putBoolean(key, value).apply()
    }

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
        val MODELS = listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5")
        val EFFORTS = listOf("low", "medium", "high")
        private const val KEY_ALIAS = "caremate_api_key"

        fun parseContacts(raw: String): List<CareContact> = raw.lines().mapNotNull { line ->
            val parts = line.split(',', ':', ';').map { it.trim() }.filter { it.isNotEmpty() }
            when {
                parts.size >= 2 -> CareContact(parts[0], parts[1])
                parts.size == 1 && parts[0].any { it.isDigit() } -> CareContact(parts[0], parts[0])
                else -> null
            }
        }

        private fun secretKey(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            return gen.generateKey()
        }

        private fun encrypt(plain: String): String {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, secretKey())
            val out = c.iv + c.doFinal(plain.toByteArray())
            return Base64.encodeToString(out, Base64.NO_WRAP)
        }

        private fun decrypt(enc: String): String? = try {
            val bytes = Base64.decode(enc, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes, 0, 12))
            String(c.doFinal(bytes, 12, bytes.size - 12))
        } catch (e: Exception) {
            null
        }
    }
}
