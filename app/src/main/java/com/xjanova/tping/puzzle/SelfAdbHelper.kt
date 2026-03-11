package com.xjanova.tping.puzzle

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.LocalServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Self-ADB: Embedded ADB client using libadb-android.
 *
 * Connects to the device's own ADB daemon via Wireless Debugging (localhost).
 * This allows executing `input swipe` commands with isTrusted=true touch events,
 * without needing the external Shizuku app.
 *
 * Setup flow (one-time):
 *   1. User enables Developer Options → Wireless Debugging
 *   2. User taps "Pair device with pairing code" in Wireless Debugging settings
 *   3. User enters the 6-digit pairing code + port in our app
 *   4. App pairs and connects via localhost
 *   5. Subsequent launches auto-connect (no re-pairing needed unless revoked)
 */
object SelfAdbHelper {

    private const val TAG = "SelfAdb"
    const val KEY_FILE = "adb_private.key"
    const val CERT_FILE = "adb_cert.pem"

    @Volatile
    private var manager: TpingAdbManager? = null

    @Volatile
    var isPaired = false
        private set

    @Volatile
    var isConnected = false
        private set

    /**
     * Initialize the ADB connection manager. Call once from Application.onCreate or HomeScreen.
     */
    fun init(context: Context) {
        if (manager != null) return
        try {
            manager = TpingAdbManager.getInstance(context.applicationContext)
            Log.d(TAG, "ADB manager initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init ADB manager: ${e.message}", e)
        }
    }

    /**
     * Pair with the device's Wireless Debugging using a 6-digit pairing code.
     * Must be called from a background thread.
     *
     * @param port The pairing port shown in Wireless Debugging settings
     * @param pairingCode The 6-digit pairing code
     * @return true if pairing succeeded
     */
    suspend fun pair(port: Int, pairingCode: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val mgr = manager ?: run {
                    Log.e(TAG, "pair: manager not initialized")
                    return@withContext false
                }
                Log.d(TAG, "Pairing with localhost:$port, code=$pairingCode")
                val result = mgr.pair("127.0.0.1", port, pairingCode)
                isPaired = result
                Log.d(TAG, "Pair result: $result")
                result
            } catch (e: Exception) {
                Log.e(TAG, "Pair failed: ${e.message}", e)
                isPaired = false
                false
            }
        }
    }

    /**
     * Connect to the device's ADB daemon.
     * Tries auto-connect first (mDNS discovery), then falls back to known port.
     */
    suspend fun connect(context: Context): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val mgr = manager ?: run {
                    Log.e(TAG, "connect: manager not initialized")
                    return@withContext false
                }

                // Try auto-connect (mDNS discovery)
                Log.d(TAG, "Attempting auto-connect...")
                try {
                    val result = mgr.autoConnect(context.applicationContext, 10_000)
                    if (result) {
                        isConnected = true
                        Log.d(TAG, "Auto-connect succeeded!")
                        return@withContext true
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Auto-connect failed: ${e.message}")
                }

                // Try direct connection to common ports
                for (port in listOf(5555, 5037)) {
                    try {
                        Log.d(TAG, "Trying connect to 127.0.0.1:$port")
                        val result = mgr.connect("127.0.0.1", port)
                        if (result) {
                            isConnected = true
                            Log.d(TAG, "Direct connect succeeded on port $port")
                            return@withContext true
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Connect to port $port failed: ${e.message}")
                    }
                }

                Log.w(TAG, "All connection attempts failed")
                isConnected = false
                false
            } catch (e: Exception) {
                Log.e(TAG, "Connect error: ${e.message}", e)
                isConnected = false
                false
            }
        }
    }

    /**
     * Execute a shell command via ADB.
     * Returns the command output, or "error: ..." on failure.
     */
    suspend fun execCommand(cmd: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val mgr = manager
                if (mgr == null || !isConnected) return@withContext "error: not connected"

                Log.d(TAG, "Exec: $cmd")
                val stream = mgr.openStream("shell:$cmd")
                val input = stream.openInputStream()
                val output = input.bufferedReader().use { it.readText() }
                stream.close()
                Log.d(TAG, "Exec result: ${output.take(100)}")
                output.trim()
            } catch (e: Exception) {
                Log.e(TAG, "Exec failed: ${e.message}", e)
                // Connection may be broken
                isConnected = false
                "error: ${e.message}"
            }
        }
    }

    /**
     * Execute `input swipe` via ADB shell.
     * Returns "ok" on success.
     */
    suspend fun inputSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): String {
        val result = execCommand("input swipe $x1 $y1 $x2 $y2 $durationMs")
        return if (result.isEmpty() || result == "" || !result.startsWith("error")) "ok" else result
    }

    /**
     * Execute `input tap` via ADB shell.
     * Returns "ok" on success.
     */
    suspend fun inputTap(x: Int, y: Int): String {
        val result = execCommand("input tap $x $y")
        return if (result.isEmpty() || result == "" || !result.startsWith("error")) "ok" else result
    }

    /**
     * Check if ADB connection is available and working.
     */
    fun isAvailable(): Boolean = isConnected

    /**
     * Get detailed status string for diagnostics.
     */
    fun getDetailedStatus(): String {
        return "connected=$isConnected, paired=$isPaired, manager=${manager != null}"
    }

    /**
     * Get Thai status text for UI display.
     */
    fun getStatusText(): String = when {
        isConnected -> "✅ ADB เชื่อมต่อแล้ว"
        isPaired -> "🔗 Pair แล้ว (ยังไม่ได้เชื่อมต่อ)"
        manager != null -> "⚙ พร้อม pair (เปิด Wireless Debugging ก่อน)"
        else -> "❌ ไม่พร้อม"
    }

    /**
     * Test if ADB connection works by running a simple command.
     */
    suspend fun testConnection(): Boolean {
        if (!isConnected) return false
        val result = execCommand("echo ok")
        return result.trim() == "ok"
    }

    /**
     * Disconnect and cleanup.
     */
    fun disconnect() {
        try {
            manager?.close()
        } catch (_: Exception) {}
        isConnected = false
        // Don't reset isPaired — pairing persists across connections
    }
}

// ============================================================
// === ADB Connection Manager Implementation ===
// ============================================================

/**
 * Concrete implementation of libadb-android's AbsAdbConnectionManager.
 * Manages RSA key pair for ADB authentication.
 */
class TpingAdbManager private constructor(context: Context) : AbsAdbConnectionManager() {

    companion object {
        @Volatile
        private var instance: TpingAdbManager? = null

        fun getInstance(context: Context): TpingAdbManager {
            return instance ?: synchronized(this) {
                instance ?: TpingAdbManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val mPrivateKey: PrivateKey
    private val mCertificate: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        setTimeout(15, TimeUnit.SECONDS)

        val keyFile = File(context.filesDir, SelfAdbHelper.KEY_FILE)
        val certFile = File(context.filesDir, SelfAdbHelper.CERT_FILE)

        if (keyFile.exists() && certFile.exists()) {
            Log.d("SelfAdb", "Loading existing ADB keys")
            mPrivateKey = loadPrivateKey(keyFile)
            mCertificate = loadCertificate(certFile)
        } else {
            Log.d("SelfAdb", "Generating new ADB keys")
            val pair = generateKeyPairAndCert()
            mPrivateKey = pair.first
            mCertificate = pair.second
            savePrivateKey(keyFile, mPrivateKey)
            saveCertificate(certFile, mCertificate)
        }
    }

    override fun getPrivateKey(): PrivateKey = mPrivateKey
    override fun getCertificate(): Certificate = mCertificate
    override fun getDeviceName(): String = "Tping"

    // === Key Generation ===

    @Suppress("PrivateApi")
    private fun generateKeyPairAndCert(): Pair<PrivateKey, Certificate> {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        val keyPair = kpg.generateKeyPair()

        val certificate = createSelfSignedCert(keyPair)
        return Pair(keyPair.private, certificate)
    }

    /**
     * Create a self-signed X.509 certificate for ADB authentication.
     * Uses sun.security.x509 classes available on Android runtime.
     */
    @Suppress("PrivateApi")
    private fun createSelfSignedCert(keyPair: java.security.KeyPair): Certificate {
        try {
            // Use sun.security.x509 classes (available on Android)
            val x509InfoClass = Class.forName("sun.security.x509.X509CertInfo")
            val certImplClass = Class.forName("sun.security.x509.X509CertImpl")
            val certValidityClass = Class.forName("sun.security.x509.CertificateValidity")
            val certSerialClass = Class.forName("sun.security.x509.CertificateSerialNumber")
            val certAlgoClass = Class.forName("sun.security.x509.CertificateAlgorithmId")
            val certSubjectClass = Class.forName("sun.security.x509.CertificateSubjectName")
            val certIssuerClass = Class.forName("sun.security.x509.CertificateIssuerName")
            val certKeyClass = Class.forName("sun.security.x509.CertificateX509Key")
            val certVersionClass = Class.forName("sun.security.x509.CertificateVersion")
            val x500NameClass = Class.forName("sun.security.x509.X500Name")
            val algoIdClass = Class.forName("sun.security.x509.AlgorithmId")

            val notBefore = Date()
            val notAfter = Date(System.currentTimeMillis() + 365L * 24 * 3600 * 1000) // 1 year

            // CertificateValidity(notBefore, notAfter)
            val validity = certValidityClass.getConstructor(Date::class.java, Date::class.java)
                .newInstance(notBefore, notAfter)

            // X500Name("CN=Tping ADB")
            val x500Name = x500NameClass.getConstructor(String::class.java)
                .newInstance("CN=Tping ADB")

            // AlgorithmId.get("SHA256withRSA")
            val algoId = algoIdClass.getMethod("get", String::class.java)
                .invoke(null, "SHA256withRSA")

            // X509CertInfo
            val certInfo = x509InfoClass.newInstance()
            val setMethod = x509InfoClass.getMethod("set", String::class.java, Any::class.java)

            setMethod.invoke(certInfo, "version",
                certVersionClass.getConstructor(Int::class.java).newInstance(2))
            setMethod.invoke(certInfo, "serialNumber",
                certSerialClass.getConstructor(Int::class.java)
                    .newInstance(SecureRandom().nextInt() and Int.MAX_VALUE))
            setMethod.invoke(certInfo, "algorithmID",
                certAlgoClass.getConstructor(algoIdClass).newInstance(algoId))
            setMethod.invoke(certInfo, "subject",
                certSubjectClass.getConstructor(x500NameClass).newInstance(x500Name))
            setMethod.invoke(certInfo, "key",
                certKeyClass.getConstructor(java.security.PublicKey::class.java)
                    .newInstance(keyPair.public))
            setMethod.invoke(certInfo, "validity", validity)
            setMethod.invoke(certInfo, "issuer",
                certIssuerClass.getConstructor(x500NameClass).newInstance(x500Name))

            // X509CertImpl(certInfo) + sign
            val certImpl = certImplClass.getConstructor(x509InfoClass).newInstance(certInfo)
            certImplClass.getMethod("sign", PrivateKey::class.java, String::class.java)
                .invoke(certImpl, keyPair.private, "SHA256withRSA")

            return certImpl as Certificate
        } catch (e: Exception) {
            Log.w("SelfAdb", "sun.security.x509 not available, using fallback: ${e.message}")
            return createSelfSignedCertFallback(keyPair)
        }
    }

    /**
     * Fallback certificate generation using minimal DER encoding.
     * Used when sun.security.x509 classes are not available (Android 15+).
     */
    private fun createSelfSignedCertFallback(keyPair: java.security.KeyPair): Certificate {
        // Use Bouncy Castle if available, or create minimal self-signed cert
        // For ADB, the cert just needs to wrap the public key — exact format is flexible
        val encoded = keyPair.public.encoded
        val signature = java.security.Signature.getInstance("SHA256withRSA")
        signature.initSign(keyPair.private)
        signature.update(encoded)
        val sig = signature.sign()

        // Create a minimal certificate factory approach
        // Generate a simple PEM-format self-signed cert
        val certFactory = CertificateFactory.getInstance("X.509")

        // Build DER-encoded X.509 certificate manually
        val derCert = buildMinimalX509DER(keyPair, sig)
        return certFactory.generateCertificate(derCert.inputStream())
    }

    /**
     * Build a minimal DER-encoded X.509 v3 certificate.
     */
    private fun buildMinimalX509DER(keyPair: java.security.KeyPair, signature: ByteArray): ByteArray {
        // This is a minimal X.509 certificate in DER format
        // For ADB authentication, the exact certificate content doesn't matter much —
        // what matters is that getPrivateKey() returns the matching private key.
        //
        // Use keytool-style generation as ultimate fallback
        val tmpKeyStore = java.security.KeyStore.getInstance("AndroidKeyStore")
        tmpKeyStore.load(null)

        // If we get here, just throw — the caller should handle this
        throw RuntimeException("Certificate generation fallback not implemented. " +
            "Please ensure sun.security.x509 classes are available.")
    }

    // === Key I/O ===

    private fun loadPrivateKey(file: File): PrivateKey {
        val bytes = file.readBytes()
        val keySpec = PKCS8EncodedKeySpec(bytes)
        return KeyFactory.getInstance("RSA").generatePrivate(keySpec)
    }

    private fun loadCertificate(file: File): Certificate {
        FileInputStream(file).use { fis ->
            return CertificateFactory.getInstance("X.509").generateCertificate(fis)
        }
    }

    private fun savePrivateKey(file: File, key: PrivateKey) {
        FileOutputStream(file).use { it.write(key.encoded) }
    }

    private fun saveCertificate(file: File, cert: Certificate) {
        FileOutputStream(file).use { fos ->
            val encoded = Base64.encodeToString(cert.encoded, Base64.DEFAULT)
            fos.write("-----BEGIN CERTIFICATE-----\n".toByteArray())
            fos.write(encoded.toByteArray())
            fos.write("-----END CERTIFICATE-----\n".toByteArray())
        }
    }
}

