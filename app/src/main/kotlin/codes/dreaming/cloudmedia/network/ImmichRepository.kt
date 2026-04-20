package codes.dreaming.cloudmedia.network

import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Point
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
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
  val isImage: Boolean,
  val originalFileName: String? = null
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

  // Track asset IDs returned by the main sync so album-only assets
  // (e.g. shared by other users) can be appended on the last page.
  private val mainSyncAssetIds = mutableSetOf<String>()

  // Buffered sync stream results from detectAndApplyChanges()
  // consumed by queryDeletedAssets() to avoid duplicate HTTP calls
  private var pendingDeleteIds = mutableListOf<String>()
  private var pendingAckIds = mutableListOf<String>()

  // Local MediaStore lookup for deduplication: (displayName, sizeBytes) -> MediaStore URI
  private var localMediaLookup: Map<Pair<String, Long>, Uri>? = null
  private var localMediaLookupTime: Long = 0
  private const val LOCAL_MEDIA_CACHE_TTL_MS = 2 * 60 * 1000L

  private const val CURRENT_COLLECTION_VERSION = "immich-cloud-v7"

  fun initialize(context: Context) {
    appContext = context.applicationContext
    syncPrefs = appContext.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
    syncGeneration = syncPrefs.getLong("sync_generation", 0)

    // Force-migrate collection ID to trigger a full re-sync when
    // upgrading from older versions that didn't include album assets.
    val storedId = syncPrefs.getString("media_collection_id", null)
    if (storedId != null && storedId != CURRENT_COLLECTION_VERSION) {
      syncPrefs.edit().putString("media_collection_id", CURRENT_COLLECTION_VERSION).apply()
      Log.d(TAG, "Migrated media_collection_id from $storedId to $CURRENT_COLLECTION_VERSION")
    }

    ApiClient.initialize(appContext)
  }

  val isConfigured: Boolean get() = ApiClient.isLoggedIn

  fun getMediaCollectionId(): String =
    syncPrefs.getString("media_collection_id", CURRENT_COLLECTION_VERSION) ?: CURRENT_COLLECTION_VERSION

  fun getLastSyncGeneration(): Long {
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

  fun detectAndApplyChanges(): Boolean {
    return try {
      val (deletedIds, ackIds) = consumeSyncStream(listOf("AssetsV1"))
      val hasChanges = deletedIds.isNotEmpty() || ackIds.isNotEmpty()
      if (hasChanges) {
        pendingDeleteIds = deletedIds.toMutableList()
        pendingAckIds = ackIds.toMutableList()
        incrementSyncGeneration()
        Log.d(TAG, "detectAndApplyChanges: sync stream has ${deletedIds.size} deletes, ${ackIds.size} acks, syncGen=$syncGeneration")
      }
      hasChanges
    } catch (e: Exception) {
      Log.e(TAG, "detectAndApplyChanges error", e)
      false
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
      val deletedIds: List<String>
      val ackIds: List<String>
      if (pendingDeleteIds.isNotEmpty() || pendingAckIds.isNotEmpty()) {
        deletedIds = pendingDeleteIds.toList()
        ackIds = pendingAckIds.toList()
        pendingDeleteIds.clear()
        pendingAckIds.clear()
      } else {
        val (d, a) = consumeSyncStream(listOf("AssetsV1"))
        deletedIds = d
        ackIds = a
      }
      if (ackIds.isNotEmpty()) ackSyncEvents(ackIds)
      Log.d(TAG, "queryDeletedAssets: found ${deletedIds.size} deleted assets via sync stream")
      deletedIds
    } catch (e: Exception) {
      Log.e(TAG, "queryDeletedAssets sync stream error, falling back to snapshot diff", e)
      try {
        val currentIds = fetchAllAssetIds()
        val previousIds = loadTrackedAssetIds()
        val deleted = previousIds - currentIds
        Log.d(TAG, "queryDeletedAssets fallback: previous=${previousIds.size}, current=${currentIds.size}, deleted=${deleted.size}")
        deleted.toList()
      } catch (e2: Exception) {
        Log.e(TAG, "queryDeletedAssets fallback error", e2)
        emptyList()
      }
    }
  }

  private fun fetchAllAssetIds(): Set<String> {
    val ids = mutableSetOf<String>()
    val pageSize = 1000
    var page = 1
    while (true) {
      val searchUrl = ApiClient.buildUrl("/search/metadata") ?: break
      val body = JSONObject().apply {
        put("page", page)
        put("size", pageSize)
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
      if (items.length() < pageSize) break
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

  private fun consumeSyncStream(types: List<String>, reset: Boolean = false): Pair<List<String>, List<String>> {
    val url = ApiClient.buildUrl("/sync/stream") ?: return Pair(emptyList(), emptyList())
    val bodyJson = JSONObject().apply {
      put("types", JSONArray(types))
      if (reset) put("reset", true)
    }
    val request = Request.Builder()
      .url(url)
      .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
      .build()

    val response = ApiClient.getClient().newCall(request).execute()
    if (!response.isSuccessful) {
      val code = response.code
      response.close()
      throw Exception("Sync stream HTTP $code")
    }

    val deletedIds = mutableListOf<String>()
    val ackIds = mutableListOf<String>()

    val responseText = response.body?.string() ?: ""
    response.close()

    for (line in responseText.lineSequence()) {
      if (line.isBlank()) continue
      try {
        val event = JSONObject(line)
        val type = event.optString("type")
        val data = event.optJSONObject("data")

        if (data != null) {
          if (type.contains("Delete", ignoreCase = true)) {
            val assetId = data.optString("assetId")
            if (assetId.isNotBlank()) deletedIds.add(assetId)
          } else if (type.contains("Asset", ignoreCase = true)) {
            val deletedAt = data.optString("deletedAt")
            if (deletedAt.isNotBlank() && deletedAt != "null") {
              val id = data.optString("id")
              if (id.isNotBlank()) deletedIds.add(id)
            }
          }
          val dataAck = data.optString("ack")
          if (dataAck.isNotBlank()) ackIds.add(dataAck)
        }

        val eventAck = event.optString("ack")
        if (eventAck.isNotBlank()) ackIds.add(eventAck)
      } catch (e: Exception) {
        Log.w(TAG, "Failed to parse sync event: ${line.take(200)}", e)
      }
    }
    Log.d(TAG, "consumeSyncStream: ${deletedIds.size} deletes, ${ackIds.size} acks")
    return Pair(deletedIds, ackIds)
  }

  private fun ackSyncEvents(ackIds: List<String>) {
    if (ackIds.isEmpty()) return
    try {
      val url = ApiClient.buildUrl("/sync/ack") ?: return
      ackIds.chunked(1000).forEach { batch ->
        val body = JSONObject().apply {
          put("acks", JSONArray(batch))
        }
        val request = Request.Builder()
          .url(url)
          .post(body.toString().toRequestBody("application/json".toMediaType()))
          .build()
        val response = ApiClient.getClient().newCall(request).execute()
        if (!response.isSuccessful) {
          Log.e(TAG, "ackSyncEvents failed: ${response.code}")
        }
        response.close()
      }
    } catch (e: Exception) {
      Log.e(TAG, "ackSyncEvents error", e)
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
      if (page == 1) mainSyncAssetIds.clear()
      val url = ApiClient.buildUrl("/search/metadata") ?: return QueryResult(emptyList(), null)
      val body = JSONObject().apply {
        put("page", page)
        put("size", pageSize)
        put("order", "desc")
        put("withExif", true)
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

      val assets = mutableListOf<ImmichAsset>()
      for (i in 0 until items.length()) {
        assets.add(assetFromApiJson(items.getJSONObject(i)))
      }
      assets.forEach { mainSyncAssetIds.add(it.id) }

      // Use items count vs pageSize to determine if more pages exist.
      // The API's "total" field is unreliable (equals page count, not global total).
      var nextToken = if (items.length() >= pageSize) (page + 1).toString() else null

      // When the main sync is complete, fetch all album assets and
      // include any that weren't in the /search/metadata results
      // (e.g. shared album items from other users).
      if (nextToken == null) {
        val albumAssets = fetchAllAlbumOnlyAssets()
        if (albumAssets.isNotEmpty()) {
          Log.d(TAG, "queryAllAssets: appending ${albumAssets.size} album-only assets to main sync")
          assets.addAll(albumAssets)
          albumAssets.forEach { mainSyncAssetIds.add(it.id) }
        }
      }

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
        put("withExif", true)
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
      val assets = mutableListOf<ImmichAsset>()
      for (i in 0 until items.length()) {
        assets.add(assetFromApiJson(items.getJSONObject(i)))
      }
      val nextToken = if (items.length() >= pageSize) (page + 1).toString() else null
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
        put("withExif", true)
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
      val assets = mutableListOf<ImmichAsset>()
      for (i in 0 until items.length()) {
        assets.add(assetFromApiJson(items.getJSONObject(i)))
      }
      val nextToken = if (items.length() >= pageSize) (page + 1).toString() else null
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

  fun openMediaStreaming(assetId: String): ParcelFileDescriptor? {
    Log.d(TAG, "openMediaStreaming: assetId=$assetId")
    if (assetId.startsWith("person:")) {
      val personId = assetId.removePrefix("person:")
      val url = ApiClient.buildUrl("/people/$personId/thumbnail") ?: return null
      return downloadToTempFile(Request.Builder().url(url).get().build(), "person_$personId")
    }
    val url = ApiClient.buildUrl("/assets/$assetId/original") ?: return null
    val request = Request.Builder().url(url).get().build()
    return try {
      val pipe = ParcelFileDescriptor.createPipe()
      val readFd = pipe[0]
      val writeFd = pipe[1]
      Thread {
        try {
          val response = ApiClient.getClient().newCall(request).execute()
          if (!response.isSuccessful) {
            Log.e(TAG, "openMediaStreaming: HTTP ${response.code} for $assetId")
            response.close()
            writeFd.close()
            return@Thread
          }
          ParcelFileDescriptor.AutoCloseOutputStream(writeFd).use { output ->
            response.body?.byteStream()?.use { input ->
              input.copyTo(output, 65536)
            }
          }
          response.close()
          Log.d(TAG, "openMediaStreaming: completed streaming $assetId")
        } catch (e: Exception) {
          Log.e(TAG, "openMediaStreaming: error streaming $assetId", e)
          try { writeFd.close() } catch (_: Exception) {}
        }
      }.start()
      readFd
    } catch (e: Exception) {
      Log.e(TAG, "openMediaStreaming: pipe creation failed for $assetId", e)
      null
    }
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
    val originalMimeType = a.optString("originalMimeType", "")
    val originalFileName = a.optString("originalFileName", "").let {
      if (it.isNotBlank() && it != "null") it else null
    }
    val exifInfo = a.optJSONObject("exifInfo")
    val fileSize = exifInfo?.optLong("fileSizeInByte", 1) ?: 1L
    val orientation = exifInfo?.optString("orientation", "0")?.toIntOrNull() ?: 0
    val width = exifInfo?.optInt("exifImageWidth", 0) ?: 0
    val height = exifInfo?.optInt("exifImageHeight", 0) ?: 0
    val duration = a.optString("duration", "")
    val durationMillis = parseDuration(duration)

    val mimeType = when {
      originalMimeType.isNotBlank() && originalMimeType != "null" -> originalMimeType
      isImage -> "image/jpeg"
      else -> "video/mp4"
    }

    return ImmichAsset(
      id = id,
      mimeType = mimeType,
      dateTakenMillis = parseIso8601(createdAt),
      width = width, height = height,
      sizeBytes = if (fileSize > 0) fileSize else 1L,
      durationMillis = durationMillis,
      isFavorite = a.optBoolean("isFavorite", false),
      orientation = orientation,
      isImage = isImage,
      originalFileName = originalFileName
    )
  }

  private fun fetchAllAlbumOnlyAssets(): List<ImmichAsset> {
    return try {
      val albums = queryAlbums()
      val albumOnlyAssets = mutableListOf<ImmichAsset>()
      for (album in albums) {
        val url = ApiClient.buildUrl("/albums/${album.id}") ?: continue
        val request = Request.Builder().url(url).get().build()
        val response = ApiClient.getClient().newCall(request).execute()
        if (!response.isSuccessful) { response.close(); continue }
        val body = response.body?.string() ?: "{}"
        response.close()
        val obj = JSONObject(body)
        val assetsArr = obj.optJSONArray("assets") ?: continue
        for (i in 0 until assetsArr.length()) {
          val asset = assetFromApiJson(assetsArr.getJSONObject(i))
          if (asset.id !in mainSyncAssetIds) {
            albumOnlyAssets.add(asset)
            mainSyncAssetIds.add(asset.id)
          }
        }
      }
      Log.d(TAG, "fetchAllAlbumOnlyAssets: found ${albumOnlyAssets.size} assets across ${albums.size} albums")
      albumOnlyAssets
    } catch (e: Exception) {
      Log.e(TAG, "fetchAllAlbumOnlyAssets error", e)
      emptyList()
    }
  }

  fun findLocalMediaStoreUri(asset: ImmichAsset): Uri? {
    val fileName = asset.originalFileName ?: return null
    val lookup = getLocalMediaLookup() ?: return null
    return lookup[Pair(fileName, asset.sizeBytes)]
  }

  private fun getLocalMediaLookup(): Map<Pair<String, Long>, Uri>? {
    val now = System.currentTimeMillis()
    localMediaLookup?.let { cached ->
      if (now - localMediaLookupTime < LOCAL_MEDIA_CACHE_TTL_MS) return cached
    }
    return try {
      buildLocalMediaLookup().also {
        localMediaLookup = it
        localMediaLookupTime = now
        Log.d(TAG, "Built local media lookup: ${it.size} entries")
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to build local media lookup", e)
      null
    }
  }

  private fun buildLocalMediaLookup(): Map<Pair<String, Long>, Uri> {
    val result = mutableMapOf<Pair<String, Long>, Uri>()
    val resolver = appContext.contentResolver

    val projection = arrayOf(
      MediaStore.MediaColumns._ID,
      MediaStore.MediaColumns.DISPLAY_NAME,
      MediaStore.MediaColumns.SIZE
    )

    val collections = arrayOf(
      MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
      MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    )

    for (collection in collections) {
      try {
        resolver.query(collection, projection, null, null, null)?.use { cursor ->
          val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
          val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
          val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)

          while (cursor.moveToNext()) {
            val id = cursor.getLong(idCol)
            val name = cursor.getString(nameCol) ?: continue
            val size = cursor.getLong(sizeCol)
            if (size <= 0) continue
            val uri = ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id)
            result[Pair(name, size)] = uri
          }
        }
      } catch (e: SecurityException) {
        Log.w(TAG, "No permission to query MediaStore: ${e.message}")
        return emptyMap()
      }
    }
    return result
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
