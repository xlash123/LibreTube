package com.github.libretube.services

import android.app.NotificationManager
import android.app.PendingIntent.FLAG_CANCEL_CURRENT
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.SparseBooleanArray
import androidx.core.app.NotificationCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import androidx.core.util.remove
import androidx.core.util.set
import androidx.core.util.valueIterator
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.github.libretube.LibreTubeApp.Companion.DOWNLOAD_CHANNEL_NAME
import com.github.libretube.R
import com.github.libretube.api.CronetHelper
import com.github.libretube.api.JsonHelper
import com.github.libretube.api.RetrofitInstance
import com.github.libretube.api.StreamsExtractor
import com.github.libretube.api.obj.SegmentData
import com.github.libretube.constants.IntentData
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.db.obj.Download
import com.github.libretube.db.obj.DownloadChapter
import com.github.libretube.db.obj.DownloadItem
import com.github.libretube.db.obj.DownloadSegments
import com.github.libretube.enums.FileType
import com.github.libretube.enums.NotificationId
import com.github.libretube.extensions.formatAsFileSize
import com.github.libretube.extensions.getContentLength
import com.github.libretube.extensions.parcelableExtra
import com.github.libretube.extensions.toastFromMainThread
import com.github.libretube.helpers.DownloadHelper
import com.github.libretube.helpers.DownloadHelper.getNotificationId
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.helpers.ProxyHelper
import com.github.libretube.obj.DownloadStatus
import com.github.libretube.parcelable.DownloadData
import com.github.libretube.receivers.NotificationReceiver
import com.github.libretube.receivers.NotificationReceiver.Companion.ACTION_DOWNLOAD_PAUSE
import com.github.libretube.receivers.NotificationReceiver.Companion.ACTION_DOWNLOAD_RESUME
import com.github.libretube.receivers.NotificationReceiver.Companion.ACTION_DOWNLOAD_STOP
import com.github.libretube.ui.activities.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.Executors
import kotlin.io.path.createFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.fileSize
import kotlin.math.min

/**
 * Download service with custom implementation of downloading using [HttpURLConnection].
 */
class DownloadService : LifecycleService() {
    private val binder = LocalBinder()
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val coroutineContext = dispatcher + SupervisorJob()

    private lateinit var notificationManager: NotificationManager
    private lateinit var summaryNotificationBuilder: NotificationCompat.Builder

    private val downloadQueue = SparseBooleanArray()
    private val _downloadFlow = MutableSharedFlow<Pair<Int, DownloadStatus>>()
    val downloadFlow: SharedFlow<Pair<Int, DownloadStatus>> = _downloadFlow

    override fun onCreate() {
        super.onCreate()
        IS_DOWNLOAD_RUNNING = true
        notifyForeground()
        sendBroadcast(Intent(ACTION_SERVICE_STARTED))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val downloadId = intent?.getIntExtra("id", -1)
        when (intent?.action) {
            ACTION_DOWNLOAD_RESUME -> resume(downloadId!!)
            ACTION_DOWNLOAD_PAUSE -> pause(downloadId!!)
            ACTION_DOWNLOAD_STOP -> stop(downloadId!!)
        }

        val downloadData = intent?.parcelableExtra<DownloadData>(IntentData.downloadData)
            ?: return START_NOT_STICKY
        val (videoId, name) = downloadData
        val fileName = name.ifEmpty { videoId }

        lifecycleScope.launch(coroutineContext) {
            try {
                val streams = withContext(Dispatchers.IO) {
                    StreamsExtractor.extractStreams(videoId)
                }

                val thumbnailTargetPath = getDownloadPath(DownloadHelper.THUMBNAIL_DIR, fileName)
                val uploaderAvatarTargetPath = getDownloadPath(DownloadHelper.UPLOADER_AVATAR_DIR, fileName + "png")

                val download = Download(
                    videoId,
                    title = streams.title,
                    description = streams.description,
                    uploader = streams.uploader,
                    duration = streams.duration,
                    uploadDate = streams.uploadTimestamp?.toLocalDateTime(TimeZone.currentSystemDefault())?.date,
                    uploadTimestamp = streams.uploadTimestamp?.toEpochMilliseconds(),
                    thumbnailPath = thumbnailTargetPath,
                    uploaderAvatarPath = uploaderAvatarTargetPath,
                    views = streams.views,
                    uploaderVerified = streams.uploaderVerified,
                    likes = streams.likes,
                    dislikes = streams.dislikes,
                    visibility = streams.visibility,
                    category = streams.category,
                    license = streams.license,
                    tags = streams.tags,
                    uploaderSubscriberCount = streams.uploaderSubscriberCount,
                )
                Database.downloadDao().insertDownload(download)
                for (chapter in streams.chapters) {
                    val downloadChapter = DownloadChapter(
                        videoId = videoId,
                        name = chapter.title,
                        start = chapter.start,
                        thumbnailUrl = chapter.image
                    )
                    Database.downloadDao().insertDownloadChapter(downloadChapter)
                }

                // Download SponsorBlock segments
                runCatching {
                    val sbConfig = PlayerHelper.getSponsorBlockCategories()
                    val segments =
                        RetrofitInstance.api.getSegments(
                            videoId,
                            JsonHelper.json.encodeToString(sbConfig.keys)
                        ).segments
                    val segmentData = SegmentData(
                        hash = "",
                        segments = segments,
                        videoID = videoId
                    )
                    val downloadSegments = DownloadSegments(
                        videoId = videoId,
                        segmentData = Json.encodeToString(segmentData),
                    )
                    Database.downloadDao().insertDownloadSegments(downloadSegments)
                }

                ImageHelper.downloadImage(
                    this@DownloadService,
                    streams.thumbnailUrl,
                    thumbnailTargetPath
                )
                if (streams.uploaderAvatar != null) {
                    ImageHelper.downloadImage(
                        this@DownloadService,
                        streams.uploaderAvatar,
                        uploaderAvatarTargetPath,
                    )
                }

                val downloadItems = streams.toDownloadItems(downloadData.copy(fileName = fileName))
                downloadItems.forEach { start(it) }
            } catch (e: Exception) {
                return@launch
            }
        }

        return START_NOT_STICKY
    }

    /**
     * Initiate download [Job] using [DownloadItem] by creating file according to [FileType]
     * for the requested file.
     */
    private fun start(item: DownloadItem) {
        item.path = when (item.type) {
            FileType.AUDIO -> getDownloadPath(DownloadHelper.AUDIO_DIR, item.fileName)
            FileType.VIDEO -> getDownloadPath(DownloadHelper.VIDEO_DIR, item.fileName)
            FileType.SUBTITLE -> getDownloadPath(DownloadHelper.SUBTITLE_DIR, item.fileName)
        }.apply { deleteIfExists() }.createFile()

        lifecycleScope.launch(coroutineContext) {
            item.id = Database.downloadDao().insertDownloadItem(item).toInt()
            downloadFile(item)
        }
    }

    /**
     * Download file and emit [DownloadStatus] to the collectors of [downloadFlow]
     * and notification.
     */
    private suspend fun downloadFile(item: DownloadItem) {
        downloadQueue[item.id] = true
        val notificationBuilder = getNotificationBuilder(item)
        setResumeNotification(notificationBuilder, item)

        var totalRead = item.path.fileSize()
        val url = URL(ProxyHelper.unwrapStreamUrl(item.url ?: return))

        // only fetch the content length if it's not been returned by the API
        if (item.downloadSize <= 0L) {
            url.getContentLength()?.let { size ->
                item.downloadSize = size
                Database.downloadDao().updateDownloadItem(item)
            }
        }

        while (totalRead < item.downloadSize) {
            try {
                val con = startConnection(item, url, totalRead, item.downloadSize) ?: return

                @Suppress("NewApi") // The StandardOpenOption enum is desugared.
                val sink = item.path.sink(StandardOpenOption.APPEND).buffer()
                val sourceByte = con.inputStream.source()

                var lastTime = System.currentTimeMillis() / 1000
                var lastRead = 0L

                try {
                    // Check if downloading is still active and read next bytes.
                    while (downloadQueue[item.id] && sourceByte
                            .read(sink.buffer, DownloadHelper.DOWNLOAD_CHUNK_SIZE)
                            .also { lastRead = it } != -1L
                    ) {
                        sink.emit()
                        totalRead += lastRead
                        _downloadFlow.emit(
                            item.id to DownloadStatus.Progress(
                                lastRead,
                                totalRead,
                                item.downloadSize
                            )
                        )
                        if (item.downloadSize != -1L &&
                            System.currentTimeMillis() / 1000 > lastTime
                        ) {
                            notificationBuilder
                                .setContentText(
                                    totalRead.formatAsFileSize() + " / " +
                                        item.downloadSize.formatAsFileSize()
                                )
                                .setProgress(
                                    item.downloadSize.toInt(),
                                    totalRead.toInt(),
                                    false
                                )
                            notificationManager.notify(
                                item.getNotificationId(),
                                notificationBuilder.build()
                            )
                            lastTime = System.currentTimeMillis() / 1000
                        }
                    }
                } catch (_: CancellationException) {
                    break
                } catch (e: Exception) {
                    toastFromMainThread("${getString(R.string.download)}: ${e.message}")
                    _downloadFlow.emit(item.id to DownloadStatus.Error(e.message.toString(), e))
                    break
                }

                withContext(Dispatchers.IO) {
                    sink.flush()
                    sink.close()
                    sourceByte.close()
                    con.disconnect()
                }
            } catch (_: Exception) {
                break
            }
        }

        val completed = totalRead >= item.downloadSize
        if (completed) {
            _downloadFlow.emit(item.id to DownloadStatus.Completed)
        } else {
            _downloadFlow.emit(item.id to DownloadStatus.Paused)
        }

        setPauseNotification(notificationBuilder, item, completed)

        downloadQueue[item.id] = false

        if (_downloadFlow.firstOrNull { it.first == item.id }?.second == DownloadStatus.Stopped) {
            downloadQueue.remove(item.id, false)
        }

        stopServiceIfDone()
    }

    private suspend fun startConnection(
        item: DownloadItem,
        url: URL,
        alreadyRead: Long,
        readLimit: Long?
    ): HttpURLConnection? {
        // Set start range where last downloading was held.
        val con = CronetHelper.cronetEngine.openConnection(url) as HttpURLConnection
        con.requestMethod = "GET"
        val limit = if (readLimit == null) {
            ""
        } else {
            // generate a random byte distance to make it more difficult to fingerprint
            val nextBytesToReadSize = (BYTES_PER_REQUEST_MIN..BYTES_PER_REQUEST_MAX).random()
            min(readLimit, alreadyRead + nextBytesToReadSize)
        }
        con.setRequestProperty("Range", "bytes=$alreadyRead-$limit")
        con.connectTimeout = DownloadHelper.DEFAULT_TIMEOUT
        con.readTimeout = DownloadHelper.DEFAULT_TIMEOUT

        withContext(Dispatchers.IO) {
            // Retry connecting to server for n times.
            for (i in 1..DownloadHelper.DEFAULT_RETRY) {
                try {
                    con.connect()
                    break
                } catch (_: SocketTimeoutException) {
                    val message = getString(R.string.downloadfailed) + " " + i
                    _downloadFlow.emit(item.id to DownloadStatus.Error(message))
                    toastFromMainThread(message)
                }
            }
        }

        // If link is expired try to regenerate using available info.
        if (con.responseCode == 403) {
            regenerateLink(item)
            con.disconnect()
            downloadFile(item)
            return null
        } else if (con.responseCode !in 200..299) {
            val message = getString(R.string.downloadfailed) + ": " + con.responseMessage
            _downloadFlow.emit(item.id to DownloadStatus.Error(message))
            toastFromMainThread(message)
            con.disconnect()
            pause(item.id)
            return null
        }

        return con
    }

    /**
     * Resume download which may have been paused.
     */
    fun resume(id: Int) {
        // If file is already downloading then avoid new download job.
        if (downloadQueue[id]) {
            return
        }

        val downloadCount = downloadQueue.valueIterator().asSequence().count { it }
        if (downloadCount >= DownloadHelper.getMaxConcurrentDownloads()) {
            toastFromMainThread(getString(R.string.concurrent_downloads_limit_reached))
            lifecycleScope.launch(coroutineContext) {
                _downloadFlow.emit(id to DownloadStatus.Paused)
            }
            return
        }

        lifecycleScope.launch(coroutineContext) {
            val file = Database.downloadDao().findDownloadItemById(id) ?: return@launch
            downloadFile(file)
        }
    }

    /**
     * Pause downloading job for given [id]. If no downloads are active, stop the service.
     */
    fun pause(id: Int) {
        downloadQueue[id] = false

        lifecycleScope.launch(coroutineContext) {
            _downloadFlow.emit(id to DownloadStatus.Paused)
        }

        stopServiceIfDone()
    }

    /**
     * Stop downloading job for given [id]. If no downloads are active, stop the service.
     */
    private fun stop(id: Int) = lifecycleScope.launch(coroutineContext) {
        downloadQueue[id] = false
        _downloadFlow.emit(id to DownloadStatus.Stopped)

        val item = Database.downloadDao().findDownloadItemById(id) ?: return@launch
        notificationManager.cancel(item.getNotificationId())
        Database.downloadDao().deleteDownloadItemById(id)
        stopServiceIfDone()
    }

    /**
     * Stop service if no downloads are active
     */
    private fun stopServiceIfDone() {
        if (downloadQueue.valueIterator().asSequence().none { it }) {
            ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_DETACH)
            sendBroadcast(Intent(ACTION_SERVICE_STOPPED))
            stopSelf()
        }
    }

    /**
     * Regenerate stream url using available info format and quality.
     */
    private suspend fun regenerateLink(item: DownloadItem) {
        val streams = runCatching {
            StreamsExtractor.extractStreams(item.videoId)
        }.getOrNull() ?: return
        val stream = when (item.type) {
            FileType.AUDIO -> streams.audioStreams
            FileType.VIDEO -> streams.videoStreams
            else -> null
        }
        stream?.find {
            it.format == item.format && it.quality == item.quality && it.audioTrackLocale == item.language
        }?.let {
            item.url = it.url
        }
        Database.downloadDao().updateDownloadItem(item)
    }

    /**
     * Check whether the file downloading or not.
     */
    fun isDownloading(id: Int): Boolean {
        return downloadQueue[id]
    }

    private fun notifyForeground() {
        notificationManager = getSystemService()!!

        summaryNotificationBuilder = NotificationCompat
            .Builder(this, DOWNLOAD_CHANNEL_NAME)
            .setSmallIcon(R.drawable.ic_launcher_lockscreen)
            .setContentTitle(getString(R.string.downloading))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setGroup(DOWNLOAD_NOTIFICATION_GROUP)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setGroupSummary(true)

        startForeground(NotificationId.DOWNLOAD_IN_PROGRESS.id, summaryNotificationBuilder.build())
    }

    private fun getNotificationBuilder(item: DownloadItem): NotificationCompat.Builder {
        val intent = Intent(this@DownloadService, MainActivity::class.java)
            .putExtra("fragmentToOpen", "downloads")
        val activityIntent = PendingIntentCompat
            .getActivity(this@DownloadService, 0, intent, FLAG_CANCEL_CURRENT, false)

        return NotificationCompat
            .Builder(this, DOWNLOAD_CHANNEL_NAME)
            .setContentTitle("[${item.type}] ${item.fileName}")
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setContentIntent(activityIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setGroup(DOWNLOAD_NOTIFICATION_GROUP)
    }

    private fun setResumeNotification(
        notificationBuilder: NotificationCompat.Builder,
        item: DownloadItem
    ) {
        notificationBuilder
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setWhen(System.currentTimeMillis())
            .setOngoing(true)
            .clearActions()
            .addAction(getPauseAction(item.id))
            .addAction(getStopAction(item.id))

        notificationManager.notify(item.getNotificationId(), notificationBuilder.build())
    }

    private fun setPauseNotification(
        notificationBuilder: NotificationCompat.Builder,
        item: DownloadItem,
        isCompleted: Boolean = false
    ) {
        notificationBuilder
            .setProgress(0, 0, false)
            .setOngoing(false)
            .clearActions()

        if (isCompleted) {
            notificationBuilder
                .setSmallIcon(R.drawable.ic_done)
                .setContentText(getString(R.string.download_completed))
        } else {
            notificationBuilder
                .setSmallIcon(R.drawable.ic_pause)
                .setContentText(getString(R.string.download_paused))
                .addAction(getResumeAction(item.id))
                .addAction(getStopAction(item.id))
        }
        notificationManager.notify(item.getNotificationId(), notificationBuilder.build())
    }

    private fun getResumeAction(id: Int): NotificationCompat.Action {
        val intent = Intent(this, NotificationReceiver::class.java)
            .setAction(ACTION_DOWNLOAD_RESUME)
            .putExtra("id", id)

        return NotificationCompat.Action.Builder(
            R.drawable.ic_play,
            getString(R.string.resume),
            PendingIntentCompat.getBroadcast(this, id, intent, FLAG_UPDATE_CURRENT, false)
        ).build()
    }

    private fun getPauseAction(id: Int): NotificationCompat.Action {
        val intent = Intent(this, NotificationReceiver::class.java)
            .setAction(ACTION_DOWNLOAD_PAUSE)
            .putExtra("id", id)

        return NotificationCompat.Action.Builder(
            R.drawable.ic_pause,
            getString(R.string.pause),
            PendingIntentCompat.getBroadcast(this, id, intent, FLAG_UPDATE_CURRENT, false)
        ).build()
    }

    private fun getStopAction(id: Int): NotificationCompat.Action {
        val intent = Intent(this, NotificationReceiver::class.java).apply {
            action = ACTION_DOWNLOAD_STOP
            putExtra("id", id)
        }

        // the request code must differ from the one of the pause/resume action
        val requestCode = Int.MAX_VALUE / 2 - id
        return NotificationCompat.Action.Builder(
            R.drawable.ic_stop,
            getString(R.string.stop),
            PendingIntentCompat.getBroadcast(this, requestCode, intent, FLAG_UPDATE_CURRENT, false)
        ).build()
    }

    /**
     * Get a [File] from the corresponding download directory and the file name
     */
    private fun getDownloadPath(directory: String, fileName: String): Path {
        return DownloadHelper.getDownloadDir(this, directory) / fileName
    }

    override fun onDestroy() {
        downloadQueue.clear()
        IS_DOWNLOAD_RUNNING = false
        sendBroadcast(Intent(ACTION_SERVICE_STOPPED))
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        intent.getIntArrayExtra("ids")?.forEach { resume(it) }
        return binder
    }

    inner class LocalBinder : Binder() {
        fun getService(): DownloadService = this@DownloadService
    }

    companion object {
        private const val DOWNLOAD_NOTIFICATION_GROUP = "download_notification_group"
        const val ACTION_SERVICE_STARTED =
            "com.github.libretube.services.DownloadService.ACTION_SERVICE_STARTED"
        const val ACTION_SERVICE_STOPPED =
            "com.github.libretube.services.DownloadService.ACTION_SERVICE_STOPPED"

        // any values that are not in that range are strictly rate limited by YT or are very slow due
        // to the amount of requests that's being made
        private const val BYTES_PER_REQUEST_MIN = 500_000L
        private const val BYTES_PER_REQUEST_MAX = 3_000_000L

        var IS_DOWNLOAD_RUNNING = false
    }
}
