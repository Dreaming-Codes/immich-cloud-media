package codes.dreaming.cloudmedia.provider

import android.content.ContentResolver
import android.content.Context
import android.graphics.Point
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.CloudMediaProvider.CloudMediaSurfaceController
import android.provider.CloudMediaProvider.CloudMediaSurfaceStateChangedCallback
import android.provider.CloudMediaProviderContract
import android.util.Log
import android.view.Surface
import codes.dreaming.cloudmedia.network.ImmichRepository
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "ImmichSurface"

class ImmichSurfaceController(
  private val context: Context,
  private val config: Bundle,
  private val callback: CloudMediaSurfaceStateChangedCallback
) : CloudMediaSurfaceController() {

  private data class PlayerState(
    var surface: Surface? = null,
    var mediaId: String? = null,
    var player: MediaPlayer? = null,
    var isPrepared: Boolean = false
  )

  private val players = ConcurrentHashMap<Int, PlayerState>()

  @Volatile
  private var audioMuted = config.getBoolean(
    CloudMediaProviderContract.EXTRA_SURFACE_CONTROLLER_AUDIO_MUTE_ENABLED, true
  )
  private val loopingEnabled = config.getBoolean(
    CloudMediaProviderContract.EXTRA_LOOPING_PLAYBACK_ENABLED, true
  )

  override fun onPlayerCreate() {}

  override fun onPlayerRelease() {
    for ((_, state) in players) releasePlayer(state)
    players.clear()
  }

  override fun onSurfaceCreated(surfaceId: Int, surface: Surface, mediaId: String) {
    val state = players.getOrPut(surfaceId) { PlayerState() }
    state.surface = surface
    state.mediaId = mediaId
    preparePlayer(surfaceId, state)
  }

  override fun onSurfaceChanged(surfaceId: Int, format: Int, width: Int, height: Int) {}

  override fun onSurfaceDestroyed(surfaceId: Int) {
    val state = players.remove(surfaceId) ?: return
    releasePlayer(state)
  }

  override fun onMediaPlay(surfaceId: Int) {
    val state = players[surfaceId] ?: return
    if (state.isPrepared) {
      try {
        state.player?.start()
        callback.setPlaybackState(surfaceId, CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_STARTED, null)
      } catch (e: Exception) {
        Log.e(TAG, "Error starting playback for surface $surfaceId", e)
        reportError(surfaceId)
      }
    }
  }

  override fun onMediaPause(surfaceId: Int) {
    val state = players[surfaceId] ?: return
    try {
      state.player?.pause()
      callback.setPlaybackState(surfaceId, CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_PAUSED, null)
    } catch (e: Exception) {
      Log.e(TAG, "Error pausing playback for surface $surfaceId", e)
    }
  }

  override fun onMediaSeekTo(surfaceId: Int, timestampMillis: Long) {
    val state = players[surfaceId] ?: return
    try {
      state.player?.seekTo(timestampMillis.toInt())
    } catch (e: Exception) {
      Log.e(TAG, "Error seeking for surface $surfaceId", e)
    }
  }

  override fun onConfigChange(config: Bundle) {
    val newMuted = config.getBoolean(CloudMediaProviderContract.EXTRA_SURFACE_CONTROLLER_AUDIO_MUTE_ENABLED, true)
    audioMuted = newMuted
    val volume = if (newMuted) 0f else 1f
    for ((_, state) in players) {
      try { state.player?.setVolume(volume, volume) } catch (e: Exception) { Log.e(TAG, "Error setting volume", e) }
    }
  }

  override fun onDestroy() = onPlayerRelease()

  private fun preparePlayer(surfaceId: Int, state: PlayerState) {
    val mediaId = state.mediaId ?: return
    val surface = state.surface ?: return
    releasePlayer(state)

    callback.setPlaybackState(surfaceId, CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_BUFFERING, null)

    Thread {
      try {
        val tempFile = File(context.cacheDir, "video_$mediaId.tmp")
        val fd = ImmichRepository.openMedia(mediaId)
        if (fd == null) {
          reportError(surfaceId)
          return@Thread
        }
        ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
          tempFile.outputStream().use { output -> input.copyTo(output, 65536) }
        }
        if (!tempFile.exists() || tempFile.length() == 0L) {
          reportError(surfaceId)
          return@Thread
        }

        val player = MediaPlayer()
        player.setAudioAttributes(
          AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()
        )
        player.setSurface(surface)
        player.setDataSource(tempFile.absolutePath)
        player.isLooping = loopingEnabled
        val volume = if (audioMuted) 0f else 1f
        player.setVolume(volume, volume)

        player.setOnPreparedListener { mp ->
          state.isPrepared = true
          val sizeBundle = Bundle().apply {
            putParcelable(ContentResolver.EXTRA_SIZE, Point(mp.videoWidth, mp.videoHeight))
          }
          callback.setPlaybackState(surfaceId, CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_MEDIA_SIZE_CHANGED, sizeBundle)
          callback.setPlaybackState(surfaceId, CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_READY, null)
        }
        player.setOnErrorListener { _, what, extra ->
          Log.e(TAG, "MediaPlayer error: what=$what extra=$extra for $mediaId")
          reportError(surfaceId)
          true
        }
        player.setOnCompletionListener {
          if (!loopingEnabled) {
            callback.setPlaybackState(surfaceId, CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_COMPLETED, null)
          }
        }

        state.player = player
        player.prepareAsync()
      } catch (e: Exception) {
        Log.e(TAG, "Error preparing player for surface $surfaceId", e)
        reportError(surfaceId)
      }
    }.start()
  }

  private fun releasePlayer(state: PlayerState) {
    try { state.player?.release() } catch (e: Exception) { Log.e(TAG, "Error releasing player", e) }
    state.player = null
    state.isPrepared = false
  }

  private fun reportError(surfaceId: Int) {
    try {
      callback.setPlaybackState(surfaceId, CloudMediaSurfaceStateChangedCallback.PLAYBACK_STATE_ERROR_RETRIABLE_FAILURE, null)
    } catch (e: Exception) {
      Log.e(TAG, "Error reporting playback error", e)
    }
  }
}
