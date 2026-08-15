package com.opencall.relay.offline

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * PHASE 7A: real cryptographic identity. PHASE 2's original version generated an
 * Ed25519 keypair and threw the private half away immediately — only the public
 * key was ever persisted, so nothing could be signed. This version persists
 * BOTH halves and actually signs/verifies.
 *
 * ED25519 PROVIDER — survey finding, not a guess: plain JCA `"Ed25519"` (backed
 * by Android's built-in Conscrypt provider) is only available from API 31
 * onward; this project's minSdk is 26. BouncyCastle (`bcprov-jdk18on`) is used
 * instead, via its lightweight API (`Ed25519PrivateKeyParameters`/
 * `Ed25519PublicKeyParameters`/`Ed25519Signer`) rather than registering it as a
 * JCA `Provider` — the lightweight API needs no provider registration and is
 * the standard way to reach for BC when only Ed25519 itself is needed. This
 * works identically on every API level 26-34, so there is no API-level branch
 * anywhere in this file.
 *
 * KEY-AT-REST PROTECTION: the Ed25519 PRIVATE key (32-byte seed) is encrypted
 * with AES-256-GCM using a key generated in, and which never leaves,
 * `AndroidKeyStore` (available since API 23, well within range). This is
 * option (i) from the survey — Ed25519 itself directly inside AndroidKeyStore
 * (option (ii)) only landed in API 33 and was rejected as insufficient
 * coverage. The wrapping key deliberately does NOT set
 * `setUserAuthenticationRequired(true)` — an SOS/mesh app cannot require a
 * fingerprint or PIN prompt just to sign a frame in the background, and that
 * flag is also the primary real-world trigger for
 * [KeyPermanentlyInvalidatedException] (still handled defensively below
 * regardless, since a device's lock configuration can still change keystore
 * state in rarer OEM-specific ways).
 *
 * NODE ID — UNCHANGED FORMULA, new canonical input: nodeId is still the first 8
 * bytes of SHA-256(pubkey) exactly as before. "pubkey" is now precisely defined
 * as the raw 32-byte Ed25519 public-key point (RFC 8032's canonical encoding —
 * what actually travels over the wire in HELLO, see MeshSigner), not whatever
 * DER/X.509 SubjectPublicKeyInfo blob a generic `java.security.PublicKey.
 * getEncoded()` used to produce. This is a genuine, unavoidable, and
 * DOCUMENTED break from any nodeId computed by the old PHASE 2 code path — see
 * the migration handling below for why that's fine.
 *
 * MIGRATION: an install with an old-format (PHASE 2, pubkey-only, no private
 * key) identity file cannot be upgraded in place — the private half required
 * to keep using that nodeId never existed anywhere to begin with. On finding
 * one, this generates a fresh keypair, logs the nodeId change LOUDLY (at
 * warning level, impossible to miss in a casual logcat skim), and never
 * silently keeps the old nodeId paired with a new key — that would let a
 * different physical device masquerade as the original identity from that
 * point on, i.e. exactly the kind of confusion Phase 7A exists to eliminate.
 */
object OfflineIdentity {
    private const val KEY_FILE = "offline_mesh_identity.dat"
    private const val NODE_ID_BYTES = 8
    private const val CURRENT_VERSION = 2
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEYSTORE_ALIAS = "opencall_identity_wrap_key"
    private const val GCM_TAG_BITS = 128
    private const val ED25519_PUBLIC_KEY_BYTES = 32
    const val SIGNATURE_BYTES = 64

    private class Identity(
        val private: Ed25519PrivateKeyParameters,
        val public: Ed25519PublicKeyParameters,
        val nodeId: ByteArray
    )

    @Volatile private var cached: Identity? = null
    private val lock = Any()

    /** 8-byte mesh node id, stable across app restarts (barring an unrecoverable
     *  migration — see class doc). Generates + persists the backing keypair on
     *  first call if none exists yet. */
    fun nodeId(context: Context): ByteArray = identity(context).nodeId

    /** Raw 32-byte Ed25519 public key point — this exact byte sequence is what
     *  travels in HELLO and what [verify] expects as its pubkey argument. */
    fun publicKeyBytes(context: Context): ByteArray = identity(context).public.encoded

    /** Ed25519-signs [bytes] with this device's own private key. 64 bytes. */
    fun sign(context: Context, bytes: ByteArray): ByteArray {
        val id = identity(context)
        val signer = Ed25519Signer()
        signer.init(true, id.private)
        signer.update(bytes, 0, bytes.size)
        return signer.generateSignature()
    }

    /** Stateless — verifies [sig] over [bytes] against an arbitrary [pubkey],
     *  never this device's own identity. Never throws; a malformed key or
     *  signature simply fails verification. */
    fun verify(pubkey: ByteArray, bytes: ByteArray, sig: ByteArray): Boolean {
        if (pubkey.size != ED25519_PUBLIC_KEY_BYTES || sig.size != SIGNATURE_BYTES) return false
        return try {
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(pubkey, 0))
            verifier.update(bytes, 0, bytes.size)
            verifier.verifySignature(sig)
        } catch (e: Exception) {
            false
        }
    }

    fun hex(id: ByteArray): String = id.joinToString("") { "%02x".format(it) }

    // ── PHASE 7A STEP 5: signed display name ────────────────────────────────
    // A user-chosen name, not the raw Wi-Fi Direct/Build.MODEL device name —
    // it travels inside the SIGNED material of HELLO/ROSTER (see MeshSigner),
    // so once verified a peer can trust it actually came from this nodeId.
    // Persisted separately from the keypair file above (plain SharedPreferences,
    // same "opencall" prefs every other local-only setting in this app uses —
    // this is not secret material, unlike the private key).

    private const val PREFS_NAME = "opencall"
    private const val PREF_DISPLAY_NAME = "display_name"
    const val MAX_DISPLAY_NAME_BYTES = 32

    /** Strips control characters and truncates to [MAX_DISPLAY_NAME_BYTES] UTF-8
     *  bytes without splitting a multi-byte codepoint. Returns "" if nothing
     *  usable remains — callers that require a non-empty result use
     *  [validateDisplayName] instead. */
    fun sanitizeDisplayName(raw: String): String {
        val stripped = raw.filterNot { it.isISOControl() }.trim()
        if (stripped.toByteArray(Charsets.UTF_8).size <= MAX_DISPLAY_NAME_BYTES) return stripped
        val out = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < stripped.length) {
            val cp = stripped.codePointAt(i)
            val cpStr = String(Character.toChars(cp))
            val cpBytes = cpStr.toByteArray(Charsets.UTF_8).size
            if (bytes + cpBytes > MAX_DISPLAY_NAME_BYTES) break
            out.append(cpStr)
            bytes += cpBytes
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    /** Cleaned name, or null if [raw] is empty/blank/all-control-characters —
     *  the caller (settings dialog) should reject the edit rather than persist
     *  an empty name. */
    fun validateDisplayName(raw: String): String? = sanitizeDisplayName(raw).ifBlank { null }

    /** Persisted display name, defaulting to (a sanitized) [android.os.Build.MODEL]
     *  ONLY on first call ever — every call after that returns whatever was last
     *  saved, even across a device-name change, an OS update, etc. */
    fun displayName(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(PREF_DISPLAY_NAME, null)?.let { return it }
        val default = validateDisplayName(android.os.Build.MODEL ?: "Phone") ?: "Phone"
        prefs.edit().putString(PREF_DISPLAY_NAME, default).apply()
        return default
    }

    /** Validates + persists [raw] as the new display name. Returns the name
     *  actually saved, or null (nothing saved) if [raw] failed validation. */
    fun setDisplayName(context: Context, raw: String): String? {
        val cleaned = validateDisplayName(raw) ?: return null
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(PREF_DISPLAY_NAME, cleaned).apply()
        return cleaned
    }

    private fun identity(context: Context): Identity {
        cached?.let { return it }
        synchronized(lock) {
            cached?.let { return it }
            val id = loadOrCreate(context.applicationContext)
            cached = id
            return id
        }
    }

    private fun loadOrCreate(context: Context): Identity {
        val file = File(context.filesDir, KEY_FILE)
        if (file.exists()) {
            tryLoad(file)?.let { loaded ->
                Log.d("OFFTRACE", "ID: keypair loaded nodeId=${hex(loaded.nodeId)} keystore=true")
                return loaded
            }
            // tryLoad already logged the specific reason. Either an old-format
            // (PHASE 2, pubkey-only) file or a corrupt/undecryptable one — either
            // way there is no usable private key to recover.
            val oldNodeIdHex = tryComputeOldNodeIdForLogging(file)
            return generateAndPersist(context, file, oldNodeIdHex)
        }
        return generateAndPersist(context, file, null)
    }

    private fun tryLoad(file: File): Identity? {
        return try {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            if (json.optInt("version") != CURRENT_VERSION) return null // old format
            val pubBytes = Base64.decode(json.getString("pubkey"), Base64.NO_WRAP)
            val iv = Base64.decode(json.getString("iv"), Base64.NO_WRAP)
            val encPriv = Base64.decode(json.getString("encPrivKey"), Base64.NO_WRAP)
            val secretKey = loadWrappingKey() ?: run {
                Log.w("OFFTRACE", "ID: key error reason=keystore_key_missing — regenerating")
                return null
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            val privBytes = cipher.doFinal(encPriv)
            val priv = Ed25519PrivateKeyParameters(privBytes, 0)
            val pub = Ed25519PublicKeyParameters(pubBytes, 0)
            // Defensive: the persisted pubkey must actually correspond to the
            // decrypted private key — if a file were ever corrupted/tampered such
            // that they no longer match, nodeId (derived from pubkey) would
            // silently stop matching what the private key can actually sign for.
            if (!pub.encoded.contentEquals(priv.generatePublicKey().encoded)) {
                Log.w("OFFTRACE", "ID: key error reason=pubkey_privkey_mismatch — regenerating")
                return null
            }
            Identity(priv, pub, computeNodeId(pubBytes))
        } catch (e: KeyPermanentlyInvalidatedException) {
            Log.w("OFFTRACE", "ID: key error reason=keystore_key_invalidated_device_lock_changed — regenerating")
            null
        } catch (e: KeyStoreException) {
            Log.w("OFFTRACE", "ID: key error reason=keystore_exception:${e.message} — regenerating")
            null
        } catch (e: Exception) {
            Log.w("OFFTRACE", "ID: key error reason=${e.javaClass.simpleName}:${e.message} — regenerating")
            null
        }
    }

    private fun loadWrappingKey(): SecretKey? {
        return try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey
        } catch (e: Exception) {
            Log.w("OFFTRACE", "ID: key error reason=keystore_load_failed:${e.javaClass.simpleName}:${e.message}")
            null
        }
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

    private fun generateAndPersist(context: Context, file: File, oldNodeIdHex: String?): Identity {
        val seed = ByteArray(32)
        SecureRandom().nextBytes(seed)
        val priv = Ed25519PrivateKeyParameters(seed, 0)
        val pub = priv.generatePublicKey()
        val nodeId = computeNodeId(pub.encoded)

        try {
            val secretKey = getOrCreateWrappingKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val encPriv = cipher.doFinal(priv.encoded)
            val json = JSONObject().apply {
                put("version", CURRENT_VERSION)
                put("pubkey", Base64.encodeToString(pub.encoded, Base64.NO_WRAP))
                put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
                put("encPrivKey", Base64.encodeToString(encPriv, Base64.NO_WRAP))
            }
            writeAtomic(file, json.toString())
        } catch (e: Exception) {
            Log.e(
                "OFFTRACE",
                "ID: key error reason=persist_failed:${e.javaClass.simpleName}:${e.message} " +
                    "— identity will not survive a restart"
            )
        }

        if (oldNodeIdHex != null) {
            Log.w("OFFTRACE", "ID: NEW KEYPAIR GENERATED — nodeId changed from $oldNodeIdHex to ${hex(nodeId)}")
        } else {
            Log.d("OFFTRACE", "ID: keypair loaded nodeId=${hex(nodeId)} keystore=true")
        }
        return Identity(priv, pub, nodeId)
    }

    /** Best-effort only, for the "changed from X to Y" log line — hashes
     *  whatever bytes were actually in the old file (PHASE 2's format was raw
     *  `PublicKey.getEncoded()`, a DER blob, not the raw 32-byte point) so the
     *  logged "old" id matches what that install's peers would have known it
     *  as, even though the real private key backing it is unrecoverable. */
    private fun tryComputeOldNodeIdForLogging(file: File): String? {
        return try {
            val bytes = file.readBytes()
            if (bytes.isEmpty()) null else hex(MessageDigest.getInstance("SHA-256").digest(bytes).copyOf(NODE_ID_BYTES))
        } catch (e: Exception) {
            null
        }
    }

    private fun computeNodeId(pubkeyBytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(pubkeyBytes).copyOf(NODE_ID_BYTES)

    /** Temp file + fsync + rename — same atomic-write pattern as MeshLedger/
     *  MeshCarrier; a battery pull mid-write must never leave a torn identity
     *  file (which would then be treated as corrupt and force a nodeId change). */
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
