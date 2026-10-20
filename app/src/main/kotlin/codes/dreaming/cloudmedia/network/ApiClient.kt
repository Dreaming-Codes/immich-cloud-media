package codes.dreaming.cloudmedia.network

import android.content.Context
import android.content.SharedPreferences
import android.security.KeyChain
import android.util.Log
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

private const val TAG = "ApiClient"
private const val PREFS_NAME = "immich_cloud_media"
private const val KEY_SERVER_URL = "server_url"
private const val KEY_ACCESS_TOKEN = "access_token"
private const val KEY_API_KEY = "api_key"
private const val KEY_ACCOUNT_NAME = "account_name"
private const val KEY_CERTIFICATE_ALIAS = "certificate_alias"
private const val KEY_OAUTH_STATE = "oauth_state"
private const val KEY_OAUTH_VERIFIER = "oauth_code_verifier"

object ApiClient {
  private const val CACHE_SIZE_BYTES = 100L * 1024 * 1024

  private lateinit var prefs: SharedPreferences
  @Volatile private lateinit var client: OkHttpClient
  private lateinit var keyManager: KeyChainKeyManager
  private lateinit var cache: Cache
  private var initialized = false

  fun initialize(context: Context) {
    if (initialized) return
    synchronized(this) {
      if (initialized) return

      val appContext = context.applicationContext
      prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      keyManager = KeyChainKeyManager(appContext)

      cache = Cache(File(appContext.cacheDir, "okhttp_api").apply { mkdirs() }, CACHE_SIZE_BYTES)
      client = buildClient()
      initialized = true
    }
  }

  fun getClient(): OkHttpClient = client

  private fun buildClient(): OkHttpClient {
    return OkHttpClient.Builder()
      .clientCertificates(keyManager)
      .cookieJar(TokenCookieJar())
      .addInterceptor { chain ->
        val original = chain.request()
        val builder = original.newBuilder()
        getApiKey()?.let { builder.header("x-api-key", it) }
        getAccessToken()?.let {
          builder.header("Cookie", "immich_access_token=$it; immich_is_authenticated=true")
        }
        chain.proceed(builder.build())
      }
      .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
      .dispatcher(Dispatcher().apply { maxRequestsPerHost = 32 })
      .cache(cache)
      .build()
  }

  // A new SSLContext drops TLS sessions resumed with the previous client certificate.
  private fun replaceClient() {
    val old = client
    client = buildClient()
    old.connectionPool.evictAll()
  }

  // Call off the main thread.
  fun hasUsableCertificate(): Boolean = certificateAlias?.let { keyManager.isUsable(it) } ?: true

  var serverUrl: String?
    get() = prefs.getString(KEY_SERVER_URL, null)?.trimEnd('/')
    set(value) = prefs.edit().putString(KEY_SERVER_URL, value?.trimEnd('/')).apply()

  var accountName: String?
    get() = prefs.getString(KEY_ACCOUNT_NAME, null)
    set(value) = prefs.edit().putString(KEY_ACCOUNT_NAME, value).apply()

  var certificateAlias: String?
    get() = prefs.getString(KEY_CERTIFICATE_ALIAS, null)
    set(value) {
      prefs.edit().putString(KEY_CERTIFICATE_ALIAS, value).apply()
      keyManager.forget()
      replaceClient()
    }

  val isLoggedIn: Boolean
    get() = serverUrl != null && (getAccessToken() != null || getApiKey() != null)

  fun getAccessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)

  fun getApiKey(): String? = prefs.getString(KEY_API_KEY, null)

  fun saveAccessToken(token: String) {
    prefs.edit().putString(KEY_ACCESS_TOKEN, token).apply()
  }

  fun saveApiKey(key: String) {
    prefs.edit().putString(KEY_API_KEY, key).apply()
  }

  fun logout() {
    prefs.edit()
      .remove(KEY_ACCESS_TOKEN)
      .remove(KEY_API_KEY)
      .remove(KEY_ACCOUNT_NAME)
      .remove(KEY_CERTIFICATE_ALIAS)
      .apply()
    keyManager.forget()
    replaceClient()
  }

  fun buildUrl(path: String): HttpUrl? {
    val base = serverUrl ?: return null
    val clean = base.removeSuffix("/api").removeSuffix("/")
    return "$clean/api$path".toHttpUrlOrNull()
  }

  fun loginWithCredentials(serverUrl: String, email: String, password: String): Result<String> {
    return try {
      this.serverUrl = serverUrl
      val url = buildUrl("/auth/login") ?: return Result.failure(Exception("Invalid server URL"))
      val body = JSONObject().apply {
        put("email", email)
        put("password", password)
      }
      val request = Request.Builder()
        .url(url)
        .post(body.toString().toRequestBody("application/json".toMediaType()))
        .build()
      val response = client.newCall(request).execute()
      val responseBody = response.body?.string() ?: "{}"
      response.close()
      if (!response.isSuccessful) {
        val msg = try { JSONObject(responseBody).optString("message", "Unknown error") } catch (_: Exception) { "HTTP ${response.code}" }
        return Result.failure(Exception(msg))
      }
      val json = JSONObject(responseBody)
      val token = json.optString("accessToken", "")
      if (token.isBlank()) {
        return Result.failure(Exception("No access token in response"))
      }
      saveAccessToken(token)
      fetchAndSaveAccountName()
      Result.success(token)
    } catch (e: Exception) {
      Log.e(TAG, "loginWithCredentials error", e)
      Result.failure(e)
    }
  }

  fun loginWithApiKey(serverUrl: String, apiKey: String): Result<String> {
    return try {
      this.serverUrl = serverUrl
      saveApiKey(apiKey)
      val url = buildUrl("/users/me") ?: return Result.failure(Exception("Invalid server URL"))
      val request = Request.Builder().url(url).get().build()
      val response = client.newCall(request).execute()
      val responseBody = response.body?.string() ?: "{}"
      response.close()
      if (!response.isSuccessful) {
        saveApiKey("")
        prefs.edit().remove(KEY_API_KEY).apply()
        val msg = try { JSONObject(responseBody).optString("message", "Unknown error") } catch (_: Exception) { "HTTP ${response.code}" }
        return Result.failure(Exception(msg))
      }
      val json = JSONObject(responseBody)
      val name = json.optString("name", "")
      val email = json.optString("email", "")
      accountName = when {
        name.isNotBlank() && email.isNotBlank() -> "$name ($email)"
        email.isNotBlank() -> email
        name.isNotBlank() -> name
        else -> serverUrl
      }
      Result.success(apiKey)
    } catch (e: Exception) {
      Log.e(TAG, "loginWithApiKey error", e)
      Result.failure(e)
    }
  }


  private const val OAUTH_REDIRECT_URI = "app.immich:///oauth-callback"

  // The browser step can outlive this process, so the state and PKCE verifier live in prefs.
  fun startOAuthLogin(serverUrl: String): Result<String> {
    return try {
      this.serverUrl = serverUrl
      val url = buildUrl("/oauth/authorize") ?: return Result.failure(Exception("Invalid server URL"))
      val state = randomUrlSafe()
      val codeVerifier = randomUrlSafe()
      prefs.edit().putString(KEY_OAUTH_STATE, state).putString(KEY_OAUTH_VERIFIER, codeVerifier).apply()

      val body = JSONObject()
        .put("redirectUri", OAUTH_REDIRECT_URI)
        .put("state", state)
        .put("codeChallenge", pkceChallenge(codeVerifier))
      val request = Request.Builder()
        .url(url)
        .post(body.toString().toRequestBody("application/json".toMediaType()))
        .build()

      client.newCall(request).execute().use { response ->
        val responseBody = response.body?.string() ?: "{}"
        if (!response.isSuccessful) return Result.failure(Exception(errorMessage(responseBody, response.code)))
        val authUrl = JSONObject(responseBody).optString("url", "")
        if (authUrl.isBlank()) Result.failure(Exception("No URL returned from server")) else Result.success(authUrl)
      }
    } catch (e: Exception) {
      Log.e(TAG, "startOAuthLogin error", e)
      Result.failure(e)
    }
  }

  fun finishOAuthLogin(callbackUrl: String): Result<String> {
    val state = prefs.getString(KEY_OAUTH_STATE, null)
    val codeVerifier = prefs.getString(KEY_OAUTH_VERIFIER, null)
    if (state == null || codeVerifier == null) return Result.failure(Exception("No SSO login in progress"))
    if (queryParameter(callbackUrl, "state") != state) {
      return Result.failure(Exception("SSO state does not match"))
    }
    prefs.edit().remove(KEY_OAUTH_STATE).remove(KEY_OAUTH_VERIFIER).apply()
    return try {
      val url = buildUrl("/oauth/callback") ?: return Result.failure(Exception("Invalid server URL"))
      val body = JSONObject()
        .put("url", callbackUrl.replace("app.immich:/oauth-callback", OAUTH_REDIRECT_URI))
        .put("state", state)
        .put("codeVerifier", codeVerifier)
      val request = Request.Builder()
        .url(url)
        .post(body.toString().toRequestBody("application/json".toMediaType()))
        .build()

      client.newCall(request).execute().use { response ->
        val responseBody = response.body?.string() ?: "{}"
        if (!response.isSuccessful) return Result.failure(Exception(errorMessage(responseBody, response.code)))
        val token = JSONObject(responseBody).optString("accessToken", "")
        if (token.isBlank()) return Result.failure(Exception("No access token in response"))
        saveAccessToken(token)
        fetchAndSaveAccountName()
        Result.success(token)
      }
    } catch (e: Exception) {
      Log.e(TAG, "finishOAuthLogin error", e)
      Result.failure(e)
    }
  }

  private fun queryParameter(url: String, name: String): String? =
    url.substringAfter('?', "").split('&').map { it.split('=', limit = 2) }
      .firstOrNull { it[0] == name }?.getOrNull(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }

  private fun errorMessage(body: String, code: Int): String =
    try { JSONObject(body).optString("message", "HTTP $code") } catch (_: Exception) { "HTTP $code" }

  private fun randomUrlSafe(): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

  private fun pkceChallenge(verifier: String): String =
    Base64.getUrlEncoder().withoutPadding()
      .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

  private fun fetchAndSaveAccountName() {
    try {
      val url = buildUrl("/users/me") ?: return
      val request = Request.Builder().url(url).get().build()
      val response = client.newCall(request).execute()
      if (response.isSuccessful) {
        val json = JSONObject(response.body?.string() ?: "{}")
        val name = json.optString("name", "")
        val email = json.optString("email", "")
        accountName = when {
          name.isNotBlank() && email.isNotBlank() -> "$name ($email)"
          email.isNotBlank() -> email
          name.isNotBlank() -> name
          else -> serverUrl ?: "Immich"
        }
      }
      response.close()
    } catch (e: Exception) {
      Log.e(TAG, "fetchAndSaveAccountName error", e)
    }
  }

  // KeyChain blocks and must not run on the main thread, so the key loads during the TLS handshake.
  private class KeyChainKeyManager(private val context: Context) : X509KeyManager {
    @Volatile private var loaded: Pair<String, Pair<PrivateKey, Array<X509Certificate>>?>? = null

    private fun entry(alias: String): Pair<PrivateKey, Array<X509Certificate>>? {
      loaded?.let { (cached, value) -> if (cached == alias) return value }
      val value = try {
        val key = KeyChain.getPrivateKey(context, alias)
        val chain = KeyChain.getCertificateChain(context, alias)
        if (key != null && chain != null) key to chain else null
      } catch (e: Exception) {
        Log.e(TAG, "Failed to load client certificate", e)
        null
      }
      if (value != null) loaded = alias to value
      return value
    }

    fun isUsable(alias: String): Boolean = entry(alias) != null

    fun forget() { loaded = null }

    // A redirect can lead to another host, so only the configured server gets the certificate.
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? {
      val peerHost = (socket as? SSLSocket)?.handshakeSession?.peerHost ?: socket?.inetAddress?.hostName
      if (peerHost == null || !peerHost.equals(serverUrl?.toHttpUrlOrNull()?.host, ignoreCase = true)) return null
      return certificateAlias?.takeIf { entry(it) != null }
    }

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

    override fun getPrivateKey(alias: String?): PrivateKey? = alias?.let { entry(it)?.first }

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? = alias?.let { entry(it)?.second }

    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
  }

  private fun OkHttpClient.Builder.clientCertificates(keyManager: X509KeyManager): OkHttpClient.Builder {
    val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    trustManagerFactory.init(null as KeyStore?)
    val trustManager = trustManagerFactory.trustManagers.filterIsInstance<X509TrustManager>().first()
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(arrayOf(keyManager), arrayOf(trustManager), null)
    return sslSocketFactory(sslContext.socketFactory, trustManager)
  }

  private class TokenCookieJar : CookieJar {
    private val store = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
      store.removeAll { existing ->
        cookies.any { it.name == existing.name && it.domain == existing.domain }
      }
      store.addAll(cookies)

      cookies.find { it.name == "immich_access_token" }?.let { cookie ->
        if (cookie.value.isNotBlank()) {
          saveAccessToken(cookie.value)
        }
      }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
      val now = System.currentTimeMillis()
      store.removeAll { it.expiresAt < now }
      return store.filter { it.matches(url) }
    }
  }
}
