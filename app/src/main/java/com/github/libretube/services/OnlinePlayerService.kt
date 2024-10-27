package com.github.libretube.services

import android.content.Intent
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import com.github.libretube.api.JsonHelper
import com.github.libretube.api.RetrofitInstance
import com.github.libretube.api.StreamsExtractor
import com.github.libretube.api.obj.ChapterSegment
import com.github.libretube.api.obj.Segment
import com.github.libretube.api.obj.Streams
import com.github.libretube.constants.IntentData
import com.github.libretube.db.DatabaseHelper
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.db.obj.DownloadWithItems
import com.github.libretube.enums.FileType
import com.github.libretube.extensions.parcelableExtra
import com.github.libretube.extensions.setMetadata
import com.github.libretube.extensions.toID
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.helpers.PlayerHelper.checkForSegments
import com.github.libretube.helpers.ProxyHelper
import com.github.libretube.obj.PlayerNotificationData
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.ui.activities.MainActivity
import com.github.libretube.util.PlayingQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlin.io.path.exists

/**
 * Loads the selected videos audio in background mode with a notification area.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class OnlinePlayerService : AbstractPlayerService() {
    override val isOfflinePlayer: Boolean = false
    override val intentActivity: Class<*> = MainActivity::class.java

    // PlaylistId/ChannelId for autoplay
    private var playlistId: String? = null
    private var channelId: String? = null
    private var startTimestamp: Long? = null

    /**
     * The response that gets when called the Api.
     */
    var streams: Streams? = null
        private set

    private var downloadedVideo: DownloadWithItems? = null

    // SponsorBlock Segment data
    private var sponsorBlockSegments = listOf<Segment>()
    private var sponsorBlockConfig = PlayerHelper.getSponsorBlockCategories()

    override suspend fun onServiceCreated(intent: Intent) {
        val playerData = intent.parcelableExtra<PlayerData>(IntentData.playerData)
        if (playerData == null) {
            stopSelf()
            return
        }

        // get the intent arguments
        videoId = playerData.videoId
        playlistId = playerData.playlistId
        startTimestamp = playerData.timestamp

        if (!playerData.keepQueue) PlayingQueue.clear()

        PlayingQueue.setOnQueueTapListener { streamItem ->
            streamItem.url?.toID()?.let { playNextVideo(it) }
        }
    }

    override suspend fun startPlaybackAndUpdateNotification() {
        val timestamp = startTimestamp ?: 0L
        startTimestamp = null

        isTransitioning = true

        val downloadedVideo = withContext(Dispatchers.IO) {
            Database.downloadDao().findById(videoId)
        }
        this.downloadedVideo = downloadedVideo

        if (downloadedVideo == null) {
            streams = withContext(Dispatchers.IO) {
                try {
                    StreamsExtractor.extractStreams(videoId)
                } catch (e: Exception) {
                    val errorMessage = StreamsExtractor.getExtractorErrorMessageString(this@OnlinePlayerService, e)
                    this@OnlinePlayerService.toastFromMainDispatcher(errorMessage)
                    return@withContext null
                }
            } ?: return
        }

        if (PlayingQueue.isEmpty()) {
            PlayingQueue.updateQueue(
                downloadedVideo?.download?.toStreamItem() ?: streams!!.toStreamItem(videoId),
                playlistId,
                channelId,
                streams?.relatedStreams ?: ArrayList(0)
            )
        } else if (streams != null && PlayingQueue.isLast() && playlistId == null && channelId == null) {
            PlayingQueue.insertRelatedStreams(streams!!.relatedStreams)
        }

        // save the current stream to the queue
        streams?.toStreamItem(videoId)?.let {
            PlayingQueue.updateCurrent(it)
        }

        withContext(Dispatchers.Main) {
            playAudio(timestamp)
        }
    }

    private fun playAudio(seekToPosition: Long) {
        lifecycleScope.launch(Dispatchers.IO) {
            setMediaItem()

            withContext(Dispatchers.Main) {
                // seek to the previous position if available
                if (seekToPosition != 0L) {
                    player?.seekTo(seekToPosition)
                } else if (PlayerHelper.watchPositionsAudio) {
                    PlayerHelper.getStoredWatchPosition(videoId, downloadedVideo?.download?.duration ?: streams?.duration)?.let {
                        player?.seekTo(it)
                    }
                }
            }
        }

        val playerNotificationData = PlayerNotificationData(
            downloadedVideo?.download?.title ?: streams?.title,
            downloadedVideo?.download?.uploader ?: streams?.uploader,
            downloadedVideo?.download?.thumbnailPath?.toString() ?: streams?.thumbnailUrl
        )
        nowPlayingNotification?.updatePlayerNotification(videoId, playerNotificationData)

        downloadedVideo?.download?.let { onNewVideoStarted?.invoke(it.toStreamItem()) }
        streams?.let { onNewVideoStarted?.invoke(it.toStreamItem(videoId)) }

        player?.apply {
            playWhenReady = PlayerHelper.playAutomatically
            prepare()
        }

        if (PlayerHelper.sponsorBlockEnabled) fetchSponsorBlockSegments()
    }

    /**
     * Plays the next video from the queue
     */
    private fun playNextVideo(nextId: String? = null) {
        if (nextId == null && PlayingQueue.repeatMode == Player.REPEAT_MODE_ONE) {
            player?.seekTo(0)
            return
        }

        saveWatchPosition()

        if (!PlayerHelper.isAutoPlayEnabled(playlistId != null) && nextId == null) return

        val nextVideo = nextId ?: PlayingQueue.getNext() ?: return

        // play new video on background
        this.videoId = nextVideo
        this.streams = null
        this.downloadedVideo = null
        this.sponsorBlockSegments = emptyList()

        lifecycleScope.launch {
            startPlaybackAndUpdateNotification()
        }
    }

    /**
     * Sets the [MediaItem] with the [streams] into the [player]
     */
    private suspend fun setMediaItem() {
        val streams = streams ?: return

        val (uri, mimeType) =
            if (downloadedVideo?.downloadItems?.any { it.type == FileType.AUDIO && it.path.exists() } == true) {
                val audio = downloadedVideo!!.downloadItems.firstOrNull() { it.type == FileType.AUDIO && it.path.exists() }!!
                audio.url?.toUri() to audio.format
            } else if (!PlayerHelper.useHlsOverDash && streams.audioStreams.isNotEmpty()) {
                PlayerHelper.createDashSource(streams, this) to MimeTypes.APPLICATION_MPD
            } else {
                ProxyHelper.unwrapStreamUrl(streams.hls.orEmpty())
                    .toUri() to MimeTypes.APPLICATION_M3U8
            }

        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMimeType(mimeType)
            .setMetadata(streams.toStreamItem(videoId))
            .build()
        withContext(Dispatchers.Main) { player?.setMediaItem(mediaItem) }
    }

    /**
     * fetch the segments for SponsorBlock
     */
    private fun fetchSponsorBlockSegments() {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                if (sponsorBlockConfig.isEmpty()) return@runCatching
                sponsorBlockSegments = RetrofitInstance.api.getSegments(
                    videoId,
                    JsonHelper.json.encodeToString(sponsorBlockConfig.keys)
                ).segments
                checkForSegments()
            }
        }
    }

    /**
     * check for SponsorBlock segments
     */
    private fun checkForSegments() {
        handler.postDelayed(this::checkForSegments, 100)

        player?.checkForSegments(this, sponsorBlockSegments, sponsorBlockConfig)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_ENDED -> {
                if (!isTransitioning) playNextVideo()
            }

            Player.STATE_IDLE -> {
                onDestroy()
            }

            Player.STATE_BUFFERING -> {}
            Player.STATE_READY -> {
                isTransitioning = false

                // save video to watch history when the video starts playing or is being resumed
                // waiting for the player to be ready since the video can't be claimed to be watched
                // while it did not yet start actually, but did buffer only so far
                lifecycleScope.launch(Dispatchers.IO) {
                    downloadedVideo?.download?.let { DatabaseHelper.addToWatchHistory(it.videoId, it.toStreamItem()) }
                    streams?.let { DatabaseHelper.addToWatchHistory(videoId, it) }
                }
            }
        }
    }

    override fun getChapters(): List<ChapterSegment> = streams?.chapters.orEmpty()
}
