package codes.dreaming.cloudmedia.network

import android.media.MediaDataSource
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

private const val BLOCK_SIZE = 1024 * 1024

// MediaPlayer reads a few KB at a time, so each request fetches the whole aligned block around the read.
class HttpRangeDataSource(private val client: OkHttpClient, private val url: HttpUrl) : MediaDataSource() {
  private var totalSize = -1L
  private var sizeKnown = false
  private var blockStart = 0L
  private var block = ByteArray(0)

  @Volatile private var call: Call? = null
  @Volatile private var closed = false

  @Synchronized
  override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
    if (size == 0) return 0
    if (sizeKnown && totalSize in 0..position) return -1
    if (position !in blockStart until blockStart + block.size) fetch(position)
    val start = (position - blockStart).toInt()
    if (start >= block.size) return -1
    val count = minOf(size, block.size - start)
    System.arraycopy(block, start, buffer, offset, count)
    return count
  }

  @Synchronized
  override fun getSize(): Long {
    if (!sizeKnown) fetch(0)
    return totalSize
  }

  override fun close() {
    closed = true
    call?.cancel()
  }

  private fun fetch(offset: Long) {
    if (closed) throw IOException("Data source closed")
    val position = offset - offset % BLOCK_SIZE
    val request = Request.Builder()
      .url(url)
      .header("Range", "bytes=$position-${position + BLOCK_SIZE - 1}")
      .build()
    val newCall = client.newCall(request)
    call = newCall
    newCall.execute().use { response ->
      val body = response.body ?: throw IOException("Empty response for ${url.encodedPath}")
      when (response.code) {
        206 -> {
          val range = response.header("Content-Range").orEmpty()
          if (range.removePrefix("bytes ").substringBefore('-').toLongOrNull() != position) {
            throw IOException("Unexpected Content-Range \"$range\" for offset $position")
          }
          totalSize = range.substringAfterLast('/').toLongOrNull() ?: -1L
          block = body.bytes()
        }
        200 -> throw IOException("Server ignored the Range header for ${url.encodedPath}")
        416 -> block = ByteArray(0)
        else -> throw IOException("HTTP ${response.code} for ${url.encodedPath}")
      }
      sizeKnown = true
      blockStart = position
    }
  }
}
