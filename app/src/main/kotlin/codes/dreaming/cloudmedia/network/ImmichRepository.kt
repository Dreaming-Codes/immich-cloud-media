package codes.dreaming.cloudmedia.network

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Point
import android.os.ParcelFileDescriptor
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val TAG = "ImmichRepo"
private const val SYNC_PREFS = "immich_cloud_sync"

data class ImmichAsset(
  val id: String,
  val mimeType: String,
  val dateTakenMillis: Long,
  val width: Int,
  val height: Int,
  val sizeBytes: Long,
  val durationMillis: Long,
  val isFavorite: Boolean,
  val orientation: Int,
  val isImage: Boolean
)

data class ImmichAlbum(
  val id: String,
  val displayName: String,
  val mediaCount: Int,
  val coverAssetId: String?,
  val dateTakenMillis: Long
)

data class ImmichPerson(
  val id: String,
  val name: String,
  val coverAssetId: String?
)

data class QueryResult(
  val assets: List<ImmichAsset>,
  val nextPageToken: String?
)

object ImmichRepository {
  private lateinit var appContext: Context
  private lateinit var syncPrefs: SharedPreferences
  private var syncGeneration: Long = 0

  private var cachedPeople: List<ImmichPerson>? = null
  private var peopleCacheTime: Long = 0
  private const val PEOPLE_CACHE_TTL_MS = 5 * 60 * 1000L

  fun initialize(context: Context) {
    appContext = context.applicationContext
    syncPrefs = appContext.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
    syncGeneration = syncPrefs.getLong("sync_generation", 0)
    ApiClient.initialize(appContext)
  }

  val isConfigured: Boolean get() = ApiClient.isLoggedIn

  fun getMediaCollectionId(): String =
    syncPrefs.getString("media_collection_id", "immich-cloud-v1") ?: "immich-cloud-v1"

  fun getLastSyncGeneration(): Long {
    if (syncGeneration == 0L) refreshSyncGeneration()
    return syncGeneration
  }

  fun getAccountName(): String = ApiClient.accountName ?: ApiClient.serverUrl ?: "Immich"

  fun incrementSyncGeneration() {
    syncGeneration++
    syncPrefs.edit().putLong("sync_generation", syncGeneration).apply()
  }

  fun updateMediaCollectionId(newId: String) {
    syncPrefs.edit().putString("media_collection_id", newId).apply()
  }

  private fun refreshSyncGeneration() {
    try {
      val result = queryAllAssets(pageSize = 1)
      val url = ApiClient.buildUrl("/assets/statistics") ?: return
      val request = Request.Builder().url(url).get().build()
      val response = ApiClient.getClient().newCall(request).execute()
      if (response.isSuccessful) {
        val json = JSONObject(response.body?.string() ?: "{}")
        val total = json.optLong("images", 0) + json.optLong("videos", 0)
        if (total > 0) {
          syncGeneration = total
          syncPrefs.edit().putLong("sync_generation", syncGeneration).apply()
        }
      }
      response.close()
    } catch (e: Exception) {
      Log.e(TAG, "refreshSyncGeneration error", e)
    }
  }

  fun detectAndApplyChanges() {
    try {
      val url = ApiClient.buildUrl("/assets/statistics") ?: return
      val request = Request.Builder().url(url).get().build()
      val response = ApiClient.getClient().newCall(request).execute()
      if (response.isSuccessful) {
        val json = JSONObject(response.body?.string() ?: "{}")
        val total = json.optLong("images", 0) + json.optLong("videos", 0)
        if (total != syncGeneration && total > 0) {
          syncGeneration = total
          syncPrefs.edit().putLong("sync_generation", syncGeneration).apply()
          Log.d(TAG, "detectAndApplyChanges: updated syncGen to $syncGeneration")
        }
      }
      response.close()
    } catch (e: Exception) {
      Log.e(TAG, "detectAndApplyChanges error", e)
    }
  }

  fun snapshotCurrentAssetIds() {
    // Fetch all asset IDs from API and save to tracking file
    try {
      val currentIds = fetchAllAssetIds()
      saveTrackedAssetIds(currentIds)
      Log.d(TAG, "snapshotCurrentAssetIds: saved ${currentIds.size} IDs")
    } catch (e: Exception) {
      Log.e(TAG, "snapshotCurrentAssetIds error", e)
    }
  }

  fun queryDeletedAssets(syncGeneration: Long): List<String> {
    return try {
      val currentIds = fetchAllAssetIds()
      val previousIds = loadTrackedAssetIds()
      val deleted = previousIds - currentIds
      Log.d(TAG, "queryDeletedAssets: previous=${previousIds.size}, current=${currentIds.size}, deleted=${deleted.size}")
      deleted.toList()
    } catch (e: Exception) {
      Log.e(TAG, "queryDeletedAssets error", e)
      emptyList()
    }
  }

  private fun fetchAllAssetIds(): Set<String> {
    val ids = mutableSetOf<String>()
    var page = 1
    while (true) {
      val url = ApiClient.buildUrl("/timeline/buckets") ?: break
      // Use search/metadata to page through all assets for IDs only
      val searchUrl = ApiClient.buildUrl("/search/metadata") ?: break
      val body = JSONObject().apply {
        put("page", page)
        put("size", 1000)
      }
      val request = Request.Builder()
        .url(searchUrl)
        .post(body.toString().toRequestBody("application/json".toMediaType()))
        .build()
      val response = ApiClient.getClient().newCall(request).execute()
      if (!response.isSuccessful) { response.close(); break }
      val json = JSONObject(response.body?.string() ?: "{}")
      response.close()
      val assetsObj = json.optJSONObject("assets") ?: break
      val items = assetsObj.optJSONArray("items") ?: break
      if (items.length() == 0) break
      for (i in 0 until items.length()) {
        ids.add(items.getJSONObject(i).getString("id"))
      }
      val total = assetsObj.optInt("total", 0)
      if (ids.size >= total) break
      page++
    }
    return ids
  }

  private fun getTrackingFile(): File = File(appContext.filesDir, "tracked_asset_ids.txt")

  private fun loadTrackedAssetIds(): Set<String> {
    val file = getTrackingFile()
    if (!file.exists()) return emptySet()
    return try {
      file.readLines().filter { it.isNotBlank() }.toSet()
    } catch (e: Exception) {
      Log.e(TAG, "loadTrackedAssetIds error", e)
      emptySet()
    }
  }

  private fun saveTrackedAssetIds(ids: Set<String>) {
    try {
      getTrackingFile().writeText(ids.joinToString("\n"))
    } catch (e: Exception) {
      Log.e(TAG, "saveTrackedAssetIds error", e)
    }
  }

  fun queryAllAssets(
    syncGeneration: Long? = null,
    pageSize: Int = 1000,
    pageToken: String? = null
  ): QueryResult {
    Log.d(TAG, "queryAllAssets: pageSize=$pageSize, pageToken=$pageToken")
    return try {
      val page = pageToken?.toIntOrNull() ?: 1
      val url = ApiClient.buildUrl("/search/metadata") ?: return QueryResult(emptyList(), null)
      val body = JSONObject().apply {
        put("page", page)
        put("size", pageSize)
        put("order", "desc")
      }
      val request = Request.Builder()
        .url(url)
        .post(body.toString().toRequestBody("application/json".toMediaType()))
        .build()
      val response = ApiClient.getClient().newCall(request).execute()
      if (!response.isSuccessful) {
        Log.e(TAG, "queryAllAssets API failed: ${response.code}")
        response.close()
        return QueryResult(emptyList(), null)
      }
      val responseBody = response.body?.string() ?: "{}"
      response.close()
      val json = JSONObject(responseBody)
      val assetsObj = json.optJSONObject("assets") ?: return QueryResult(emptyList(), null)
      val items = assetsObj.optJSONArray("items") ?: return QueryResult(emptyList(), null)
      val total = assetsObj.optInt("total", 0)

      val assets = mutableListOf<ImmichAsset>()
      for (i in 0 until items.length()) {
        assets.add(assetFromApiJson(items.getJSONObject(i)))
      }

      val fetched = (page - 1) * pageSize + assets.size
      val nextToken = if (fetched < total) (page + 1).toString() else null
      Log.d(TAG, "queryAllAssets: returning ${assets.size} assets, nextToken=$nextToken")
      QueryResult(assets, nextToken)
    } catch (e: Exception) {
      Log.e(TAG, "queryAllAssets error", e)
      QueryResult(emptyList(), null)
    }
  }

  fun queryAlbumAssets(
    albumId: String,
    pageSize: Int = 1000,
    pageToken: String? = null
  ): QueryResult {
    Log.d(TAG, "queryAlbumAssets: albumId=$albumId")
    return try {
      val url = ApiClient.buildUrl("/albums/$albumId") ?: return QueryResult(emptyList(), null)
      val request = Request.Builder().url(url).get().build()
      val response = ApiClient.getClient().newCall(request).execute()
      if (!response.isSuccessful) {
        Log.e(TAG, "queryAlbumAssets API failed: ${response.code}")
        response.close()
        return QueryResult(emptyList(), null)
      }
      val body = response.body?.string() ?: "{}"
      response.close()
      val obj = JSONObject(body)
      val assetsArr = obj.optJSONArray("assets") ?: return QueryResult(emptyList(), null)

      val offset = pageToken?.toIntOrNull() ?: 0
      val end = minOf(offset + pageSize, assetsArr.length())
      val assets = mutableListOf<ImmichAsset>()
      for (i in offset until end) {
        assets.add(assetFromApiJson(assetsArr.getJSONObject(i)))
      }

      val nextToken = if (end < assetsArr.length()) end.toString() else null
      QueryResult(assets, nextToken)
    } catch (e: Exception) {
      Log.e(TAG, "queryAlbumAssets error", e)
      QueryResult(emptyList(), null)
    }
  }

  fun queryAlbums(): List<ImmichAlbum> {
    return try {
      val url = ApiClient.buildUrl("/albums") ?: return emptyList()
      val request = Request.Builder().url(url).get().build()
      val response = ApiClient.getClient().newCall(request).execute()
      if (!response.isSuccessful) {
        response.close()
        return emptyList()
      }
      val body = response.body?.string() ?: "[]"
      response.close()
      val arr = JSONArray(body)
      val albums = mutableListOf<ImmichAlbum>()
      for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        val assetCount = obj.optInt("assetCount", 0)
        if (assetCount == 0) continue
        val thumbId = obj.optString("albumThumbnailAssetId", "")
        albums.add(
          ImmichAlbum(
            id = obj.getString("id"),
            displayName = obj.getString("albumName"),
            coverAssetId = if (thumbId.isNotEmpty() && thumbId != "null") thumbId else null,
            dateTakenMillis = parseIso8601(obj.optString("updatedAt", "")),
            mediaCount = assetCount
          )
        )
      }
      albums
    } catch (e: Exception) {
      Log.e(TAG, "queryAlbums error", e)
      emptyList()
    }
  }

  fun queryPeople(): List<ImmichPerson> {
    val now = System.currentTimeMillis()
    cachedPeople?.let { cached ->
      if (now - peopleCacheTime < PEOPLE_CACHE_TTL_MS) return cached
    }
    return try {
      val url = ApiClient.buildUrl("/people") ?: return emptyList()
      val request = Request.Builder().url(url).get().build()
      val response = ApiClient.getClient().newCall(request).execute()
      if (!response.isSuccessful) {
        response.close()
        return emptyList()
      }
      val body = response.body?.string() ?: "{}"
      response.close()
      val json = JSONObject(body)
      val arr = json.optJSONArray("people") ?: return emptyList()
      val people = mutableListOf<ImmichPerson>()
      for (i in 0 until arr.length()) {
        val p = arr.getJSONObject(i)
        val name = p.optString("name", "")
        if (name.isBlank()) continue
        val personId = p.getString("id")
        people.add(ImmichPerson(id = personId, name = name, coverAssetId = "person:$personId"))
      }
      cachedPeople = people
      peopleCacheTime = now
      people
    } catch (e: Exception) {
      Log.e(TAG, "queryPeople error", e)
      emptyList()
    }
  }

  fun queryPersonAssets(
    personId: String,
    pageSize: Int = 1000,
    pageToken: String? = null
  ): QueryResult {
    return try {
      val page = pageToken?.toIntOrNull() ?: 1
      val url = ApiClient.buildUrl("/search/metadata") ?: return QueryResult(emptyList(), null)
      val body = JSONObject().apply {
        put("personIds", JSONArray().put(personId))
        put("page", page)
        put("size", pageSize)
      }
      val request = Request.Builder()
        .url(url)
        .post(body.toString().toRequestBody("application/json".toMediaType()))
        .build()
      val response = ApiClient.getClient().newCall(request).execute()
      if (!response.isSuccessful) {
        response.close()
        return QueryResult(emptyList(), null)
      }
      val responseBody = response.body?.string() ?: "{}"
      response.close()
      val result = JSONObject(responseBody)
      val assetsObj = result.optJSONObject("assets") ?: return QueryResult(emptyList(), null)
      val items = assetsObj.optJSONArray("items") ?: return QueryResult(emptyList(), null)
      val total = assetsObj.optInt("total", 0)
      val assets = mutableListOf<ImmichAsset>()
      for (i in 0 until items.length()) {
        assets.add(assetFromApiJson(items.getJSONObject(i)))
      }
      val fetched = (page - 1) * pageSize + assets.size
      val nextToken = if (fetched < total) (page + 1).toString() else null
      QueryResult(assets, nextToken)
    } catch (e: Exception) {
      Log.e(TAG, "queryPersonAssets error", e)
      QueryResult(emptyList(), null)
    }
  }

  fun searchAssets(
    query: String,
    pageSize: Int = 100,
    pageToken: String? = null
  ): QueryResult {
    return try {
      val page = pageToken?.toIntOrNull() ?: 1
      val url = ApiClient.buildUrl("/search/smart") ?: return QueryResult(emptyList(), null)
      val body = JSONObject().apply {
        put("query", query)
        put("page", page)
        put("size", pageSize)
      }
      val request = Request.Builder()
        .url(url)
        .post(body.toString().toRequestBody("application/json".toMediaType()))
        .build()
      val response = ApiClient.getClient().newCall(request).execute()
      if (!response.isSuccessful) {
        response.close()
        return QueryResult(emptyList(), null)
      }
      val responseBody = response.body?.string() ?: "{}"
      response.close()
      val result = JSONObject(responseBody)
      val assetsObj = result.optJSONObject("assets") ?: return QueryResult(emptyList(), null)
      val items = assetsObj.optJSONArray("items") ?: return QueryResult(emptyList(), null)
      val total = assetsObj.optInt("total", 0)
      val assets = mutableListOf<ImmichAsset>()
      for (i in 0 until items.length()) {
        assets.add(assetFromApiJson(items.getJSONObject(i)))
      }
      val fetched = (page - 1) * pageSize + assets.size
      val nextToken = if (fetched < total) (page + 1).toString() else null
      QueryResult(assets, nextToken)
    } catch (e: Exception) {
      Log.e(TAG, "searchAssets error", e)
      QueryResult(emptyList(), null)
    }
  }

  fun openMedia(assetId: String): ParcelFileDescriptor? {
    if (assetId.startsWith("person:")) {
      val personId = assetId.removePrefix("person:")
      val url = ApiClient.buildUrl("/people/$personId/thumbnail") ?: return null
      return downloadToTempFile(Request.Builder().url(url).get().build(), "person_$personId")
    }
    val url = ApiClient.buildUrl("/assets/$assetId/original") ?: return null
    return downloadToTempFile(Request.Builder().url(url).get().build(), "media_$assetId")
  }

  fun openPreview(assetId: String, size: Point): ParcelFileDescriptor? {
    if (assetId.startsWith("person:")) {
      val personId = assetId.removePrefix("person:")
      val url = ApiClient.buildUrl("/people/$personId/thumbnail") ?: return null
      return downloadToTempFile(Request.Builder().url(url).get().build(), "person_$personId")
    }
    val sizeParam = if (size.x <= 250 && size.y <= 250) "thumbnail" else "preview"
    val url = ApiClient.buildUrl("/assets/$assetId/thumbnail") ?: return null
    val urlWithParams = url.newBuilder().addQueryParameter("size", sizeParam).build()
    return downloadToTempFile(Request.Builder().url(urlWithParams).get().build(), "preview_${assetId}_$sizeParam")
  }

  private fun downloadToTempFile(request: Request, prefix: String): ParcelFileDescriptor? {
    return try {
      val response = ApiClient.getClient().newCall(request).execute()
      if (!response.isSuccessful) {
        Log.e(TAG, "Download failed: ${response.code}")
        response.close()
        return null
      }
      val tempFile = File.createTempFile(prefix, null, appContext.cacheDir)
      response.body?.byteStream()?.use { input ->
        tempFile.outputStream().use { output -> input.copyTo(output, 65536) }
      }
      response.close()
      ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
    } catch (e: Exception) {
      Log.e(TAG, "downloadToTempFile error", e)
      null
    }
  }

  private fun assetFromApiJson(a: JSONObject): ImmichAsset {
    val id = a.getString("id")
    val type = a.optString("type", "IMAGE")
    val isImage = type == "IMAGE"
    val createdAt = a.optString("fileCreatedAt", a.optString("createdAt", ""))
    val exifInfo = a.optJSONObject("exifInfo")
    val fileSize = exifInfo?.optLong("fileSizeInByte", 1) ?: 1L
    val orientation = exifInfo?.optString("orientation", "0")?.toIntOrNull() ?: 0
    val width = exifInfo?.optInt("exifImageWidth", 0) ?: 0
    val height = exifInfo?.optInt("exifImageHeight", 0) ?: 0
    val duration = a.optString("duration", "")
    val durationMillis = parseDuration(duration)
    return ImmichAsset(
      id = id,
      mimeType = if (isImage) "image/jpeg" else "video/mp4",
      dateTakenMillis = parseIso8601(createdAt),
      width = width, height = height,
      sizeBytes = if (fileSize > 0) fileSize else 1L,
      durationMillis = durationMillis,
      isFavorite = a.optBoolean("isFavorite", false),
      orientation = orientation,
      isImage = isImage
    )
  }

  private fun parseDuration(duration: String): Long {
    if (duration.isBlank() || duration == "0:00:00.00000") return 0
    return try {
      val parts = duration.split(":")
      if (parts.size == 3) {
        val h = parts[0].toLong()
        val m = parts[1].toLong()
        val s = parts[2].toDouble()
        (h * 3600 + m * 60 + s.toLong()) * 1000
      } else 0
    } catch (_: Exception) { 0 }
  }

  private fun parseIso8601(dateStr: String): Long {
    return try {
      java.time.Instant.parse(dateStr).toEpochMilli()
    } catch (_: Exception) {
      try {
        java.time.LocalDateTime.parse(dateStr)
          .atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
      } catch (_: Exception) {
        System.currentTimeMillis()
      }
    }
  }
}
