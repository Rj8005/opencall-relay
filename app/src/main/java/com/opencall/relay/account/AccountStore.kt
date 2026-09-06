package com.opencall.relay.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.opencall.relay.offline.OfflineIdentity
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.KeyStore
import java.security.KeyStoreException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * PART 5.1/5.2: one account, three attributes attached to it. The root is
 * [OfflineIdentity]'s Ed25519 nodeId — this object never generates or stores
 * a competing identity, it only reads that one via its existing public API,
 * exactly the same delegation [com.opencall.relay.dialer.identity.
 * DialerIdentity] already uses.
 *
 * KEY-AT-REST PROTECTION: the one genuine secret here — a SIP account
 * password — is AES-256-GCM encrypted with a key generated in, and which
 * never leaves, AndroidKeyStore. This mirrors [OfflineIdentity]'s existing
 * wrapping code exactly (same KeyGenParameterSpec shape, same "no
 * setUserAuthenticationRequired" reasoning — an app that must be able to
 * place/receive calls in the background cannot demand a biometric prompt to
 * unwrap its own SIP password), but under its OWN Keystore alias: this is a
 * different secret for a different purpose than OfflineIdentity's signing
 * key, so it gets its own alias rather than reusing that one. Everything
 * else about the account (display name, SIM number, SIP username) is not
 * secret and is persisted in the same plain `SharedPreferences("opencall")`
 * store the rest of the app already uses — the same public/secret split
 * OfflineIdentity itself uses (display name in prefs, private key
 * Keystore-wrapped).
 */
object AccountStore {

    private const val PREFS_NAME = "opencall"
    private const val PREF_SIM_NUMBER = "account_sim_number"
    private const val PREF_SIM_VERIFIED = "account_sim_verified"
    private const val PREF_SIP_USERNAME = "account_sip_username"

    private const val CREDENTIAL_FILE = "account_credential.dat"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEYSTORE_ALIAS = "opencall_account_wrap_key"
    private const val GCM_TAG_BITS = 128
    private const val CREDENTIAL_VERSION = 1

    data class Account(
        val nodeIdHex: String,
        val displayName: String,
        val simNumber: String?,
        val simVerified: Boolean,
        val sipUsername: String?
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(context: Context): Account {
        val ctx = context.applicationContext
        val p = prefs(ctx)
        return Account(
            nodeIdHex = OfflineIdentity.hex(OfflineIdentity.nodeId(ctx)),
            displayName = OfflineIdentity.displayName(ctx),
            simNumber = p.getString(PREF_SIM_NUMBER, null),
            simVerified = p.getBoolean(PREF_SIM_VERIFIED, false),
            sipUsername = p.getString(PREF_SIP_USERNAME, null)
        )
    }

    fun setDisplayName(context: Context, name: String) {
        OfflineIdentity.setDisplayName(context.applicationContext, name)
    }

    fun setSimNumber(context: Context, msisdn: String, verified: Boolean) {
        prefs(context).edit()
            .putString(PREF_SIM_NUMBER, msisdn)
            .putBoolean(PREF_SIM_VERIFIED, verified)
            .apply()
    }

    fun clearSimNumber(context: Context) {
        prefs(context).edit().remove(PREF_SIM_NUMBER).remove(PREF_SIM_VERIFIED).apply()
    }

    /** 2.2/5.2: the credential a SIP tab needs. [password] is never kept as
     *  a field anywhere in memory beyond this call's own stack — callers
     *  needing it again call [decryptSipPassword]. */
    fun setSipCredential(context: Context, username: String, password: String) {
        val ctx = context.applicationContext
        prefs(ctx).edit().putString(PREF_SIP_USERNAME, username).apply()
        try {
            val secretKey = getOrCreateWrappingKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val encPassword = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
            val json = JSONObject().apply {
                put("version", CREDENTIAL_VERSION)
                put("sipUsername", username)
                put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
                put("encPassword", Base64.encodeToString(encPassword, Base64.NO_WRAP))
            }
            writeAtomic(File(ctx.filesDir, CREDENTIAL_FILE), json.toString())
        } catch (e: Exception) {
            Log.e("AccountStore", "setSipCredential: persist_failed:${e.javaClass.simpleName}:${e.message}")
        }
    }

    /** Null if no credential has ever been set, or if it can't be decrypted
     *  (e.g. Keystore key invalidated by a device lock-config change) —
     *  callers treat both the same way: "not signed in," never a crash. */
    fun decryptSipPassword(context: Context): String? {
        val file = File(context.applicationContext.filesDir, CREDENTIAL_FILE)
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            val iv = Base64.decode(json.getString("iv"), Base64.NO_WRAP)
            val encPassword = Base64.decode(json.getString("encPassword"), Base64.NO_WRAP)
            val secretKey = loadWrappingKey() ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(encPassword), Charsets.UTF_8)
        } catch (e: KeyPermanentlyInvalidatedException) {
            Log.w("AccountStore", "decryptSipPassword: keystore_key_invalidated")
            null
        } catch (e: KeyStoreException) {
            Log.w("AccountStore", "decryptSipPassword: keystore_exception:${e.message}")
            null
        } catch (e: Exception) {
            Log.w("AccountStore", "decryptSipPassword: ${e.javaClass.simpleName}:${e.message}")
            null
        }
    }

    fun clearSipCredential(context: Context) {
        val ctx = context.applicationContext
        prefs(ctx).edit().remove(PREF_SIP_USERNAME).apply()
        File(ctx.filesDir, CREDENTIAL_FILE).delete()
    }

    private fun loadWrappingKey(): SecretKey? = try {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey
    } catch (e: Exception) {
        Log.w("AccountStore", "loadWrappingKey: ${e.javaClass.simpleName}:${e.message}")
        null
    }

    private fun getOrCreateWrappingKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    /** Same temp-file + fsync + rename atomic-write pattern as
     *  [OfflineIdentity.writeAtomic] / MeshLedger / MeshCarrier. */
    private fun writeAtomic(target: File, content: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(tmp).use { fos ->
            fos.write(content.toByteArray(Charsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        }
        if (!tmp.renameTo(target)) throw IOException("atomic rename failed for ${target.name}")
    }
}
