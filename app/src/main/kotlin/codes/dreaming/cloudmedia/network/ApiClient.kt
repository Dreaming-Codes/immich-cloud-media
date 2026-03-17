package codes.dreaming.cloudmedia.network

import android.content.Context
import android.content.SharedPreferences
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
import java.util.concurrent.TimeUnit

private const val TAG = "ApiClient"
private const val PREFS_NAME = "immich_cloud_media"
private const val KEY_SERVER_URL = "server_url"
private const val KEY_ACCESS_TOKEN = "access_token"
private const val KEY_API_KEY = "api_key"
private const val KEY_ACCOUNT_NAME = "account_name"

object ApiClient {
  private const val CACHE_SIZE_BYTES = 100L * 1024 * 1024

  private lateinit var prefs: SharedPreferences
  private lateinit var client: OkHttpClient
  private var initialized = false

  fun initialize(context: Context) {
    if (initialized) return
    synchronized(this) {
      if (initialized) return
      val appContext = context.applicationContext
      prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

      val cacheDir = File(appContext.cacheDir, "okhttp_api")
      client = OkHttpClient.Builder()
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
        .cache(Cache(cacheDir.apply { mkdirs() }, CACHE_SIZE_BYTES))
        .build()

      initialized = true
    }
  }

  fun getClient(): OkHttpClient = client

  var serverUrl: String?
    get() = prefs.getString(KEY_SERVER_URL, null)?.trimEnd('/')
    set(value) = prefs.edit().putString(KEY_SERVER_URL, value?.trimEnd('/')).apply()

  var accountName: String?
    get() = prefs.getString(KEY_ACCOUNT_NAME, null)
    set(value) = prefs.edit().putString(KEY_ACCOUNT_NAME, value).apply()

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
      .apply()
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
