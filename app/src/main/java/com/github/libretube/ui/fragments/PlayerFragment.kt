package com.github.libretube.ui.fragments

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.constraintlayout.motion.widget.TransitionAdapter
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.core.os.postDelayed
import androidx.core.view.SoftwareKeyboardControllerCompat
import androidx.core.view.WindowCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commit
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.SubtitleConfiguration
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cronet.CronetDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.libretube.R
import com.github.libretube.api.CronetHelper
import com.github.libretube.api.obj.ChapterSegment
import com.github.libretube.api.obj.Segment
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.api.obj.Streams
import com.github.libretube.api.obj.Subtitle
import com.github.libretube.compat.PictureInPictureCompat
import com.github.libretube.compat.PictureInPictureParamsCompat
import com.github.libretube.constants.IntentData
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.databinding.FragmentPlayerBinding
import com.github.libretube.db.DatabaseHelper
import com.github.libretube.db.DatabaseHolder.Database
import com.github.libretube.db.obj.DownloadWithItems
import com.github.libretube.enums.FileType
import com.github.libretube.enums.PlayerEvent
import com.github.libretube.enums.ShareObjectType
import com.github.libretube.extensions.formatShort
import com.github.libretube.extensions.parcelable
import com.github.libretube.extensions.serializableExtra
import com.github.libretube.extensions.setMetadata
import com.github.libretube.extensions.toAndroidUri
import com.github.libretube.extensions.toID
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.extensions.togglePlayPauseState
import com.github.libretube.extensions.updateIfChanged
import com.github.libretube.extensions.updateParameters
import com.github.libretube.helpers.BackgroundHelper
import com.github.libretube.helpers.DownloadHelper
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.IntentHelper
import com.github.libretube.helpers.NavBarHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.helpers.PlayerHelper.checkForSegments
import com.github.libretube.helpers.PlayerHelper.getVideoStats
import com.github.libretube.helpers.PlayerHelper.isInSegment
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.ProxyHelper
import com.github.libretube.helpers.ThemeHelper
import com.github.libretube.helpers.WindowHelper
import com.github.libretube.obj.PlayerNotificationData
import com.github.libretube.obj.ShareData
import com.github.libretube.obj.VideoResolution
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.ui.activities.MainActivity
import com.github.libretube.ui.adapters.VideosAdapter
import com.github.libretube.ui.base.BaseActivity
import com.github.libretube.ui.dialogs.AddToPlaylistDialog
import com.github.libretube.ui.dialogs.ShareDialog
import com.github.libretube.ui.extensions.animateDown
import com.github.libretube.ui.extensions.setupSubscriptionButton
import com.github.libretube.ui.interfaces.OnlinePlayerOptions
import com.github.libretube.ui.interfaces.TimeFrameReceiver
import com.github.libretube.ui.listeners.SeekbarPreviewListener
import com.github.libretube.ui.models.ChaptersViewModel
import com.github.libretube.ui.models.CommentsViewModel
import com.github.libretube.ui.models.CommonPlayerViewModel
import com.github.libretube.ui.models.PlayerViewModel
import com.github.libretube.ui.sheets.BaseBottomSheet
import com.github.libretube.ui.sheets.CommentsSheet
import com.github.libretube.ui.sheets.StatsSheet
import com.github.libretube.util.NowPlayingNotification
import com.github.libretube.util.OfflineTimeFrameReceiver
import com.github.libretube.util.OnlineTimeFrameReceiver
import com.github.libretube.util.PauseableTimer
import com.github.libretube.util.PlayingQueue
import com.github.libretube.util.TextUtils
import com.github.libretube.util.TextUtils.toTimeInSeconds
import com.github.libretube.util.YoutubeHlsPlaylistParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.io.path.exists
import kotlin.math.abs
import kotlin.math.ceil


@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerFragment : Fragment(), OnlinePlayerOptions {
    private var _binding: FragmentPlayerBinding? = null
    val binding get() = _binding!!

    private val playerBinding get() = binding.player.binding
    private val doubleTapOverlayBinding get() = binding.doubleTapOverlay.binding
    private val playerGestureControlsViewBinding get() = binding.playerGestureControlsView.binding

    private val commonPlayerViewModel: CommonPlayerViewModel by activityViewModels()
    private val viewModel: PlayerViewModel by viewModels { PlayerViewModel.Factory }
    private val commentsViewModel: CommentsViewModel by activityViewModels()
    private val chaptersViewModel: ChaptersViewModel by activityViewModels()

    // Video information passed by the intent
    private lateinit var videoId: String
    private var playlistId: String? = null
    private var channelId: String? = null
    private var keepQueue = false
    private var timeStamp = 0L
    private var isShort = false

    // Data and objects for an online video stored for the player
    private var streams: Streams? = null
    // Metadata about the video (online or offline)
    private lateinit var streamItem: StreamItem
    // Data about an downloaded video
    private var downloadedVideo: DownloadWithItems? = null
    private var isPlayerTransitioning = true

    // if null, it's been set to automatic
    private var fullscreenResolution: Int? = null

    // the resolution to use when the video is not played in fullscreen
    // if null, use same quality as fullscreen
    private var noFullscreenResolution: Int? = null

    private val cronetDataSourceFactory = CronetDataSource.Factory(
        CronetHelper.cronetEngine,
        Executors.newCachedThreadPool()
    )

    private val handler = Handler(Looper.getMainLooper())

    private var seekBarPreviewListener: SeekbarPreviewListener? = null

    // True when the video was closed through the close button on PiP mode
    private var closedVideo = false

    /**
     * The orientation of the `fragment_player.xml` that's currently used
     * This is needed in order to figure out if the current layout is the landscape one or not.
     */
    private var playerLayoutOrientation = Int.MIN_VALUE

    // Activity that's active during PiP, can be used for controlling its lifecycle.
    private var pipActivity: Activity? = null

    private val mainActivity get() = activity as MainActivity
    private val windowInsetsControllerCompat
        get() = WindowCompat
            .getInsetsController(mainActivity.window, mainActivity.window.decorView)

    private val fullscreenDialog by lazy {
        object : Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen) {
            @Deprecated("Deprecated in Java", ReplaceWith("onbackpressedispatcher and callback"))
            override fun onBackPressed() {
                unsetFullscreen()
            }

            override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
                if (_binding?.player?.onKeyUp(keyCode, event) == true) {
                    return true
                }

                return super.onKeyUp(keyCode, event)
            }
        }
    }

    /**
     * Receiver for all actions in the PiP mode
     */
    private val playerActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val event = intent.serializableExtra<PlayerEvent>(PlayerHelper.CONTROL_TYPE) ?: return

            if (PlayerHelper.handlePlayerAction(viewModel.player, event)) return

            when (event) {
                PlayerEvent.Next -> {
                    playNextVideo(PlayingQueue.getNext())
                }

                PlayerEvent.Prev -> {
                    playNextVideo(PlayingQueue.getPrev())
                }

                PlayerEvent.Background -> {
                    playOnBackground()
                    // wait some time in order for the service to get started properly
                    handler.postDelayed(500) {
                        pipActivity?.moveTaskToBack(false)
                        pipActivity = null
                    }
                }

                else -> Unit
            }
        }
    }

    // schedule task to save the watch position each second
    private val watchPositionTimer = PauseableTimer(
        onTick = this::saveWatchPosition,
        delayMillis = PlayerHelper.WATCH_POSITION_TIMER_DELAY_MS
    )

    private var bufferingTimeoutTask: Runnable? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (PlayerHelper.pipEnabled || PictureInPictureCompat.isInPictureInPictureMode(mainActivity)) {
                PictureInPictureCompat.setPictureInPictureParams(requireActivity(), pipParams)
            }

            if (isPlaying) {
                // Stop [BackgroundMode] service if it is running.
                BackgroundHelper.stopBackgroundPlay(requireContext())
            }

            // add the video to the watch history when starting to play the video
            if (isPlaying && PlayerHelper.watchHistoryEnabled) {
                lifecycleScope.launch(Dispatchers.IO) {
                    DatabaseHelper.addToWatchHistory(
                        videoId,
                        streamItem)
                }
            }

            if (isPlaying && PlayerHelper.sponsorBlockEnabled) {
                handler.postDelayed(
                    this@PlayerFragment::checkForSegments,
                    100
                )
            }

            // Start or pause watch position timer
            if (isPlaying) {
                watchPositionTimer.resume()
            } else {
                watchPositionTimer.pause()
            }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            super.onEvents(player, events)

            if (events.containsAny(
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_PLAY_WHEN_READY_CHANGED
                )
            ) {
                updatePlayPauseButton()
            }

            if (events.contains(Player.EVENT_TRACKS_CHANGED)) {
                PlayerHelper.setPreferredAudioQuality(
                    requireContext(),
                    viewModel.player,
                    viewModel.trackSelector
                )
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            saveWatchPosition()

            // set the playback speed to one if having reached the end of a livestream
            if (playbackState == Player.STATE_BUFFERING && binding.player.isLive &&
                viewModel.player.duration - viewModel.player.currentPosition < 700
            ) {
                viewModel.player.setPlaybackSpeed(1f)
            }

            // check if video has ended, next video is available and autoplay is enabled/the video is part of a played playlist.
            if (playbackState == Player.STATE_ENDED) {
                if (!isPlayerTransitioning && PlayerHelper.isAutoPlayEnabled(playlistId != null)) {
                    isPlayerTransitioning = true
                    if (PlayerHelper.autoPlayCountdown) {
                        showAutoPlayCountdown()
                    } else {
                        playNextVideo()
                    }
                } else {
                    binding.player.showControllerPermanently()
                }
            }

            if (playbackState == Player.STATE_READY) {
                // media actually playing
                isPlayerTransitioning = false
            }

            // listen for the stop button in the notification
            if (playbackState == PlaybackState.STATE_STOPPED && PlayerHelper.pipEnabled &&
                PictureInPictureCompat.isInPictureInPictureMode(requireActivity())
            ) {
                // finish PiP by finishing the activity
                activity?.finish()
            }

            // Buffering timeout after 10 Minutes
            if (playbackState == Player.STATE_BUFFERING) {
                if (bufferingTimeoutTask == null) {
                    bufferingTimeoutTask = Runnable {
                        viewModel.player.pause()
                    }
                }

                handler.postDelayed(bufferingTimeoutTask!!, PlayerHelper.MAX_BUFFER_DELAY)
            } else {
                bufferingTimeoutTask?.let { handler.removeCallbacks(it) }
            }

            super.onPlaybackStateChanged(playbackState)
        }

        /**
         * Catch player errors to prevent the app from stopping
         */
        override fun onPlayerError(error: PlaybackException) {
            super.onPlayerError(error)
            try {
                viewModel.player.play()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private val lockedOrientations = listOf(
        ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    )

    private var screenshotBitmap: Bitmap? = null
    private val openScreenshotFile =
        registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
            if (uri == null) {
                screenshotBitmap = null
                return@registerForActivityResult
            }

            context?.contentResolver?.openOutputStream(uri)?.use { outputStream ->
                screenshotBitmap?.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            }

            screenshotBitmap = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val playerData = requireArguments().parcelable<PlayerData>(IntentData.playerData)!!
        videoId = playerData.videoId
        playlistId = playerData.playlistId
        channelId = playerData.channelId
        keepQueue = playerData.keepQueue
        timeStamp = playerData.timestamp

        // broadcast receiver for PiP actions
        ContextCompat.registerReceiver(
            requireContext(),
            playerActionReceiver,
            IntentFilter(PlayerHelper.getIntentActionName(requireContext())),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        fullscreenResolution = PlayerHelper.getDefaultResolution(requireContext(), true)
        noFullscreenResolution = PlayerHelper.getDefaultResolution(requireContext(), false)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlayerBinding.inflate(inflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        SoftwareKeyboardControllerCompat(view).hide()

        // reset the callbacks of the playing queue
        PlayingQueue.resetToDefaults()

        // clear the playing queue
        if (!keepQueue) PlayingQueue.clear()

        changeOrientationMode()

        playerLayoutOrientation = resources.configuration.orientation

        createExoPlayer()
        initializeTransitionLayout()
        initializeOnClickActions()

        if (PlayerHelper.autoFullscreenEnabled && resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            setFullscreen()
        }

        chaptersViewModel.chaptersLiveData.observe(viewLifecycleOwner) {
            binding.player.setCurrentChapterName()
        }

        playVideo()

        showBottomBar()
    }

    /**
     * somehow the bottom bar is invisible on low screen resolutions, this fixes it
     */
    private fun showBottomBar() {
        if (_binding?.player?.isPlayerLocked == false) {
            playerBinding.bottomBar.isVisible = true
        }
        handler.postDelayed(this::showBottomBar, 100)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initializeTransitionLayout() {
        mainActivity.binding.container.isVisible = true
        val mainMotionLayout = mainActivity.binding.mainMotionLayout
        mainMotionLayout.progress = 0F

        var transitionStartId = 0
        var transitionEndId = 0

        binding.playerMotionLayout.addTransitionListener(object : TransitionAdapter() {
            override fun onTransitionChange(
                motionLayout: MotionLayout?,
                startId: Int,
                endId: Int,
                progress: Float
            ) {
                if (_binding == null) return

                if (NavBarHelper.hasTabs()) {
                    mainMotionLayout.progress = abs(progress)
                }
                disableController()
                commentsViewModel.setCommentSheetExpand(false)
                transitionEndId = endId
                transitionStartId = startId
            }

            override fun onTransitionCompleted(motionLayout: MotionLayout?, currentId: Int) {
                if (_binding == null) return

                if (currentId == transitionStartId) {
                    commonPlayerViewModel.isMiniPlayerVisible.value = false
                    // re-enable captions
                    updateCurrentSubtitle(viewModel.currentSubtitle)
                    binding.player.useController = true
                    commentsViewModel.setCommentSheetExpand(true)
                    mainMotionLayout.progress = 0F
                    changeOrientationMode()
                } else if (currentId == transitionEndId) {
                    commonPlayerViewModel.isMiniPlayerVisible.value = true
                    // disable captions temporarily
                    updateCurrentSubtitle(null)
                    disableController()
                    commentsViewModel.setCommentSheetExpand(null)
                    binding.sbSkipBtn.isGone = true
                    if (NavBarHelper.hasTabs()) {
                        mainMotionLayout.progress = 1F
                    }
                    (activity as MainActivity).requestOrientationChange()
                }

                updateMaxSheetHeight()
            }
        })

        binding.playerMotionLayout
            .addSwipeUpListener {
                if (this::streamItem.isInitialized && PlayerHelper.fullscreenGesturesEnabled) {
                    binding.player.hideController()
                    setFullscreen()
                }
            }
            .addSwipeDownListener {
                if (commonPlayerViewModel.isMiniPlayerVisible.value == true) {
                    closeMiniPlayer()
                }
            }

        binding.playerMotionLayout.progress = 1F
        binding.playerMotionLayout.transitionToStart()

        val activity = requireActivity()
        if (PlayerHelper.pipEnabled) {
            PictureInPictureCompat.setPictureInPictureParams(activity, pipParams)
        }
    }

    private fun closeMiniPlayer() {
        binding
            .playerMotionLayout
            .animateDown(
                duration = 300L,
                dy = 500F,
                onEnd = ::onManualPlayerClose
            )
    }

    private fun onManualPlayerClose() {
        PlayingQueue.clear()
        BackgroundHelper.stopBackgroundPlay(requireContext())
        killPlayerFragment()
    }

    // actions that don't depend on video information
    private fun initializeOnClickActions() {
        binding.closeImageView.setOnClickListener {
            onManualPlayerClose()
        }
        playerBinding.closeImageButton.setOnClickListener {
            onManualPlayerClose()
        }

        binding.playImageView.setOnClickListener {
            viewModel.player.togglePlayPauseState()
        }

        activity?.supportFragmentManager
            ?.setFragmentResultListener(
                CommentsSheet.HANDLE_LINK_REQUEST_KEY,
                viewLifecycleOwner
            ) { _, bundle ->
                bundle.getString(IntentData.url)?.let { handleLink(it) }
            }

        binding.commentsToggle.setOnClickListener {
            if (!this::streamItem.isInitialized) return@setOnClickListener
            // set the max height to not cover the currently playing video
            updateMaxSheetHeight()
            commentsViewModel.videoIdLiveData.updateIfChanged(videoId)
            CommentsSheet()
                .apply { arguments = bundleOf(IntentData.channelAvatar to streamItem.uploaderAvatar) }
                .show(childFragmentManager)
        }

        // FullScreen button trigger
        // hide fullscreen button if autorotation enabled
        playerBinding.fullscreen.setOnClickListener {
            toggleFullscreen()
        }

        // share button
        binding.relPlayerShare.setOnClickListener {
            if (!this::streamItem.isInitialized) return@setOnClickListener
            val bundle = bundleOf(
                IntentData.id to videoId,
                IntentData.shareObjectType to ShareObjectType.VIDEO,
                IntentData.shareData to ShareData(
                    currentVideo = streamItem.title,
                    currentPosition = viewModel.player.currentPosition / 1000
                )
            )
            val newShareDialog = ShareDialog()
            newShareDialog.arguments = bundle
            newShareDialog.show(childFragmentManager, ShareDialog::class.java.name)
        }

        binding.relPlayerExternalPlayer.setOnClickListener {
            if (streams?.hls == null) return@setOnClickListener

            val context = requireContext()
            lifecycleScope.launch {
                val hlsStream = withContext(Dispatchers.IO) {
                    ProxyHelper.unwrapStreamUrl(streams!!.hls!!).toUri()
                }
                IntentHelper.openWithExternalPlayer(
                    context,
                    hlsStream,
                    streamItem.title,
                    streamItem.uploaderName
                )
            }
        }

        binding.relPlayerBackground.setOnClickListener {
            // pause the current player
            viewModel.player.pause()

            // start the background mode
            playOnBackground()
        }

        binding.relPlayerPip.isVisible =
            PictureInPictureCompat.isPictureInPictureAvailable(requireContext())

        binding.relPlayerPip.setOnClickListener {
            PictureInPictureCompat.enterPictureInPictureMode(requireActivity(), pipParams)
        }

        binding.relatedRecView.layoutManager = LinearLayoutManager(
            context,
            if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                LinearLayoutManager.HORIZONTAL
            } else {
                LinearLayoutManager.VERTICAL
            },
            false
        )

        binding.relPlayerSave.setOnClickListener {
            AddToPlaylistDialog().apply {
                arguments = bundleOf(IntentData.videoInfo to streamItem)
            }.show(childFragmentManager, AddToPlaylistDialog::class.java.name)
        }

        playerBinding.skipPrev.setOnClickListener {
            playNextVideo(PlayingQueue.getPrev())
        }

        playerBinding.skipNext.setOnClickListener {
            playNextVideo(PlayingQueue.getNext())
        }

        binding.relPlayerDownload.setOnClickListener {
            if (streams == null) return@setOnClickListener

            if (streams!!.duration <= 0) {
                Toast.makeText(context, R.string.cannotDownload, Toast.LENGTH_SHORT).show()
            } else {
                DownloadHelper.startDownloadDialog(requireContext(), childFragmentManager, videoId)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            binding.relPlayerScreenshot.setOnClickListener {
                if (!this::streamItem.isInitialized) return@setOnClickListener
                val surfaceView =
                    binding.player.videoSurfaceView as? SurfaceView ?: return@setOnClickListener

                val bmp = Bitmap.createBitmap(
                    surfaceView.width,
                    surfaceView.height,
                    Bitmap.Config.ARGB_8888
                )

                PixelCopy.request(surfaceView, bmp, { _ ->
                    screenshotBitmap = bmp
                    val currentPosition = viewModel.player.currentPosition.toFloat() / 1000
                    openScreenshotFile.launch("${(streamItem.title)}-${currentPosition}.png")
                }, Handler(Looper.getMainLooper()))
            }
        } else {
            binding.relPlayerScreenshot.isGone = true
        }

        binding.playerChannel.setOnClickListener {
            if (this::streamItem.isInitialized && this.streamItem.uploaderUrl != null) return@setOnClickListener

            val activity = view?.context as MainActivity
            NavigationHelper.navigateChannel(requireContext(), streams!!.uploaderUrl)
            activity.binding.mainMotionLayout.transitionToEnd()
            binding.playerMotionLayout.transitionToEnd()
        }

        binding.descriptionLayout.handleLink = this::handleLink
    }

    private fun updateMaxSheetHeight() {
        val maxHeight = binding.root.height - binding.player.height
        commonPlayerViewModel.maxSheetHeightPx = maxHeight
        chaptersViewModel.maxSheetHeightPx = maxHeight
    }

    private fun playOnBackground() {
        BackgroundHelper.stopBackgroundPlay(requireContext())
        BackgroundHelper.playOnBackground(
            requireContext(),
            videoId,
            viewModel.player.currentPosition,
            playlistId,
            channelId,
            keepQueue = true,
            keepVideoPlayerAlive = true
        )
        killPlayerFragment()
        NavigationHelper.startAudioPlayer(requireContext())
    }

    private fun updateFullscreenOrientation() {
        if (PlayerHelper.autoFullscreenEnabled) return

        val height = streams?.videoStreams?.firstOrNull()?.height ?: viewModel.player.videoSize.height
        val width = streams?.videoStreams?.firstOrNull()?.width ?: viewModel.player.videoSize.width

        mainActivity.requestedOrientation = PlayerHelper.getOrientation(width, height)
    }

    private fun setFullscreen() {
        // set status bar icon color to white
        windowInsetsControllerCompat.isAppearanceLightStatusBars = false

        commonPlayerViewModel.isFullscreen.value = true

        updateFullscreenOrientation()

        commentsViewModel.setCommentSheetExpand(null)

        updateResolutionOnFullscreenChange(true)
        openOrCloseFullscreenDialog(true)

        binding.player.updateMarginsByFullscreenMode()
    }

    @SuppressLint("SourceLockedOrientationActivity")
    fun unsetFullscreen() {
        if (activity == null || _binding == null) return

        commonPlayerViewModel.isFullscreen.value = false

        if (!PlayerHelper.autoFullscreenEnabled) {
            mainActivity.requestedOrientation = mainActivity.screenOrientationPref
        }

        openOrCloseFullscreenDialog(false)
        updateResolutionOnFullscreenChange(false)

        binding.player.updateMarginsByFullscreenMode()

        // set status bar icon color back to theme color after fullscreen dialog closed!
        windowInsetsControllerCompat.isAppearanceLightStatusBars =
            !ThemeHelper.isDarkMode(requireContext())
    }

    /**
     * Enable or disable fullscreen depending on the current state
     */
    fun toggleFullscreen() {
        binding.player.hideController()

        if (commonPlayerViewModel.isFullscreen.value == false) {
            // go to fullscreen mode
            setFullscreen()
        } else {
            // exit fullscreen mode
            unsetFullscreen()
        }
    }

    private fun openOrCloseFullscreenDialog(open: Boolean) {
        val playerView = binding.player
        (playerView.parent as ViewGroup).removeView(playerView)

        if (open) {
            fullscreenDialog.addContentView(
                binding.player,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            )
            fullscreenDialog.show()
            playerView.currentWindow = fullscreenDialog.window
        } else {
            binding.playerMotionLayout.addView(playerView)
            playerView.currentWindow = null
            fullscreenDialog.dismiss()
        }

        WindowHelper.toggleFullscreen(fullscreenDialog.window!!, open)
    }

    override fun onPause() {
        // check whether the screen is on
        val isInteractive = requireContext().getSystemService<PowerManager>()!!.isInteractive

        // disable video stream since it's not needed when screen off
        if (!isInteractive) {
            viewModel.trackSelector.updateParameters {
                setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            }
        }

        // pause player if screen off and setting enabled
        if (!isInteractive && PlayerHelper.pausePlayerOnScreenOffEnabled) {
            viewModel.player.pause()
        }

        // the app was put somewhere in the background - remember to not automatically continue
        // playing on re-creation of the app
        // only run if the re-creation is not caused by an orientation change
        if (!viewModel.isOrientationChangeInProgress) {
            requireArguments().putBoolean(IntentData.wasIntentStopped, true)
        }

        super.onPause()
    }

    override fun onResume() {
        super.onResume()

        if (closedVideo) {
            closedVideo = false
            viewModel.nowPlayingNotification?.refreshNotification()
        }

        // re-enable and load video stream
        viewModel.trackSelector.updateParameters {
            setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        saveWatchPosition()

        viewModel.nowPlayingNotification?.destroySelf()
        viewModel.nowPlayingNotification = null
        watchPositionTimer.destroy()
        handler.removeCallbacksAndMessages(null)

        viewModel.player.removeListener(playerListener)
        viewModel.player.pause()

        if (PlayerHelper.pipEnabled) {
            // disable the auto PiP mode for SDK >= 32
            PictureInPictureCompat
                .setPictureInPictureParams(requireActivity(), pipParams)
        }

        runCatching {
            if (fullscreenDialog.isShowing) fullscreenDialog.dismiss()
        }

        runCatching {
            // unregister the receiver for player actions
            context?.unregisterReceiver(playerActionReceiver)
        }

        // restore the orientation that's used by the main activity
        (context as MainActivity).requestOrientationChange()

        _binding = null
    }

    /**
     * Manually kill the player fragment - call instead of using onDestroy directly
     */
    private fun killPlayerFragment() {
        binding.playerMotionLayout.transitionToEnd()

        commonPlayerViewModel.isMiniPlayerVisible.value = false

        if (commonPlayerViewModel.isFullscreen.value == true) {
            // wait for the mini player transition to finish
            // that guarantees that the navigation bar is shown properly
            // before we kill the player fragment
            binding.playerMotionLayout.addTransitionListener(object : TransitionAdapter() {
                override fun onTransitionCompleted(motionLayout: MotionLayout?, currentId: Int) {
                    super.onTransitionCompleted(motionLayout, currentId)

                    mainActivity.supportFragmentManager.commit {
                        remove(this@PlayerFragment)
                    }
                }
            })

            unsetFullscreen()
        } else {
            mainActivity.supportFragmentManager.commit {
                remove(this@PlayerFragment)
            }
        }
    }

    // save the watch position if video isn't finished and option enabled
    private fun saveWatchPosition() {
        if (!isPlayerTransitioning && PlayerHelper.watchPositionsVideo) {
            PlayerHelper.saveWatchPosition(viewModel.player, videoId)
        }
    }

    private fun checkForSegments() {
        if (!viewModel.player.isPlaying || !PlayerHelper.sponsorBlockEnabled) return

        handler.postDelayed(this::checkForSegments, 100)
        if (!viewModel.sponsorBlockEnabled || viewModel.segments.isEmpty()) return

        viewModel.player.checkForSegments(
            requireContext(),
            viewModel.segments,
            viewModel.sponsorBlockConfig
        )
            ?.let { segment ->
                if (commonPlayerViewModel.isMiniPlayerVisible.value == true) return@let
                binding.sbSkipBtn.isVisible = true
                binding.sbSkipBtn.setOnClickListener {
                    viewModel.player.seekTo((segment.segmentStartAndEnd.second * 1000f).toLong())
                    segment.skipped = true
                }
                return
            }
        if (!viewModel.player.isInSegment(viewModel.segments)) binding.sbSkipBtn.isGone = true
    }

    private fun playVideo() {
        // reset the player view
        playerBinding.exoProgress.clearSegments()
        playerBinding.sbToggle.isGone = true

        // reset the comments to become reloaded later
        commentsViewModel.reset()

        lifecycleScope.launch(Dispatchers.Main) {
            this@PlayerFragment.streams = null
            this@PlayerFragment.downloadedVideo = null

            val downloadedVideo = withContext(Dispatchers.IO) {
                Database.downloadDao().findById(videoId)
            }
            val isOnline = downloadedVideo == null

            val videoStreamItem = if (downloadedVideo != null) {
                this@PlayerFragment.downloadedVideo = downloadedVideo
                downloadedVideo.download.toStreamItem()
            } else {
                viewModel.fetchVideoInfo(requireContext(), videoId).let { (streams, errorMessage) ->
                    if (errorMessage != null) {
                        context?.toastFromMainDispatcher(errorMessage, Toast.LENGTH_LONG)
                        return@launch
                    }

                    this@PlayerFragment.streams = streams!!
                    streams.toStreamItem(videoId)
                }
            }
            this@PlayerFragment.streamItem = videoStreamItem

            val isFirstVideo = PlayingQueue.isEmpty()
            if (isFirstVideo) {
                PlayingQueue.updateQueue(videoStreamItem, playlistId, channelId)
            } else {
                PlayingQueue.updateCurrent(videoStreamItem)
            }
            val isLastVideo = !isFirstVideo && PlayingQueue.isLast()
            val isAutoQueue = playlistId == null && channelId == null

            if (isOnline) {
                if ((isFirstVideo || isLastVideo) && isAutoQueue) {
                    PlayingQueue.insertRelatedStreams(streams!!.relatedStreams)
                }

                val videoStream = streams!!.videoStreams.firstOrNull()
                isShort = PlayingQueue.getCurrent()?.isShort == true ||
                        (videoStream?.height ?: 0) > (videoStream?.width ?: 0)

                if (streams!!.category == Streams.categoryMusic) {
                    viewModel.player.setPlaybackSpeed(1f)
                }
            }

            PlayingQueue.setOnQueueTapListener { streamItem ->
                streamItem.url?.toID()?.let { playNextVideo(it) }
            }

            // hide the button to skip SponsorBlock segments manually
            binding.sbSkipBtn.isGone = true

            // set media sources for the player
            if (!viewModel.isOrientationChangeInProgress) initStreamSources()

            if (PreferenceHelper.getBoolean(PreferenceKeys.AUTO_FULLSCREEN_SHORTS, false) &&
                isShort && binding.playerMotionLayout.progress == 0f
            ) {
                setFullscreen()
            }

            binding.player.apply {
                useController = false
                player = viewModel.player
            }
            binding.relPlayerDownload.isVisible = isOnline

            initializePlayerView()

            // don't continue playback when the fragment is re-created after Android killed it
            val wasIntentStopped = requireArguments().getBoolean(IntentData.wasIntentStopped, false)
            viewModel.player.playWhenReady = PlayerHelper.playAutomatically && !wasIntentStopped
            requireArguments().putBoolean(IntentData.wasIntentStopped, false)

            viewModel.player.prepare()

            if (binding.playerMotionLayout.progress != 1.0f) {
                // show controllers when not in picture in picture mode
                val inPipMode = PlayerHelper.pipEnabled &&
                        PictureInPictureCompat.isInPictureInPictureMode(requireActivity())
                if (!inPipMode) {
                    binding.player.useController = true
                }
            }
            // show the player notification
            initializePlayerNotification()

            fetchSponsorBlockSegments()

            viewModel.isOrientationChangeInProgress = false
        }
    }

    private suspend fun fetchSponsorBlockSegments() {
        viewModel.sponsorBlockConfig = PlayerHelper.getSponsorBlockCategories()

        // Since the highlight is also a chapter, we need to fetch the other segments
        // first
        if (downloadedVideo?.downloadSegments != null) {
            viewModel.segments = downloadedVideo!!.downloadSegments!!
                .toSegmentData().segments
        } else {
            viewModel.fetchSponsorBlockSegments(videoId)
        }

        if (viewModel.segments.isEmpty()) return

        withContext(Dispatchers.Main) {
            playerBinding.exoProgress.setSegments(viewModel.segments)
            playerBinding.sbToggle.isVisible = true
        }
        viewModel.segments.firstOrNull { it.category == PlayerHelper.SPONSOR_HIGHLIGHT_CATEGORY }
            ?.let {
                initializeHighlight(it)
            }
    }

    /**
     * Can be used for autoplay and manually skipping to the next video.
     */
    private fun playNextVideo(nextId: String? = null) {
        if (nextId == null && PlayingQueue.repeatMode == Player.REPEAT_MODE_ONE) {
            viewModel.player.seekTo(0)
            return
        }

        if (!PlayerHelper.isAutoPlayEnabled(playlistId != null) && nextId == null) return

        // save the current watch position before starting the next video
        saveWatchPosition()

        videoId = nextId ?: PlayingQueue.getNext() ?: return
        isPlayerTransitioning = true

        // fix: if the fragment is recreated, play the current video, and not the initial one
        arguments?.run {
            val playerData = parcelable<PlayerData>(IntentData.playerData)!!.copy(videoId = videoId)
            putParcelable(IntentData.playerData, playerData)
            // make sure that autoplay continues without issues as the activity is obviously still alive
            // when starting to play the next video
            putBoolean(IntentData.wasIntentStopped, false)
        }

        // start to play the next video
        playVideo()

        // close comment bottom sheet if opened for next video
        activity?.supportFragmentManager?.fragments?.filterIsInstance<CommentsSheet>()
            ?.firstOrNull()?.dismiss()
    }

    @SuppressLint("SetTextI18n")
    private fun initializePlayerView() {
        // initialize the player view actions
        binding.player.initialize(
            doubleTapOverlayBinding,
            playerGestureControlsViewBinding,
            chaptersViewModel
        )
        binding.player.initPlayerOptions(
            viewModel,
            commonPlayerViewModel,
            viewLifecycleOwner,
            viewModel.trackSelector,
            this
        )

        binding.descriptionLayout.setStreams(streamItem)

        binding.apply {
            ImageHelper.loadImage(streamItem.uploaderAvatar, binding.playerChannelImage, true)
            playerChannelName.text = streamItem.uploaderName
            titleTextView.text = streamItem.title
            playerChannelSubCount.text = context?.getString(
                R.string.subscribers,
                streamItem.uploaderSubscriberCount.formatShort() ?: -1
            )
            player.isLive = streams?.livestream ?: false
        }
        playerBinding.exoTitle.text = streamItem.title

        // init the chapters recyclerview
        chaptersViewModel.chaptersLiveData.postValue(
            downloadedVideo?.downloadChapters?.map { c -> c.toChapterSegment() }
                ?: streams!!.chapters
        )

        if (PlayerHelper.relatedStreamsEnabled) {
            val relatedLayoutManager = binding.relatedRecView.layoutManager as LinearLayoutManager
            binding.relatedRecView.adapter = VideosAdapter(
                streams?.relatedStreams?.filter { !it.title.isNullOrBlank() }?.toMutableList() ?: ArrayList(),
                forceMode = if (relatedLayoutManager.orientation == LinearLayoutManager.HORIZONTAL) {
                    VideosAdapter.Companion.LayoutMode.RELATED_COLUMN
                } else {
                    VideosAdapter.Companion.LayoutMode.TRENDING_ROW
                }
            )
        }

        // update the subscribed state
        binding.playerSubscribe.setupSubscriptionButton(
            this.streams?.uploaderUrl?.toID(),
            streamItem.uploaderName
        )

        // seekbar preview setup
        playerBinding.seekbarPreview.isGone = true
        seekBarPreviewListener?.let { playerBinding.exoProgress.removeListener(it) }
        seekBarPreviewListener = createSeekbarPreviewListener().also {
            playerBinding.exoProgress.addSeekBarListener(it)
        }
    }

    private fun showAutoPlayCountdown() {
        if (!PlayingQueue.hasNext()) return

        disableController()
        binding.autoplayCountdown.setHideSelfListener {
            // could fail if the video already got closed before
            runCatching {
                binding.autoplayCountdown.isGone = true
                binding.player.useController = true
            }
        }
        binding.autoplayCountdown.startCountdown {
            runCatching {
                playNextVideo()
            }
        }
    }

    /**
     * Handle a link clicked in the description
     */
    private fun handleLink(link: String) {
        // get video id if the link is a valid youtube video link
        val uri = link.toUri()
        val videoId = TextUtils.getVideoIdFromUri(uri)

        if (videoId.isNullOrEmpty()) {
            // not a YouTube video link, thus handle normally
            val intent = Intent(Intent.ACTION_VIEW, uri)

            // start PiP mode if enabled
            onUserLeaveHint()
            startActivity(intent)

            return
        }

        // check if the video is the current video and has a valid time
        if (videoId == this.videoId) {
            // try finding the time stamp of the url and seek to it if found
            uri.getQueryParameter("t")?.toTimeInSeconds()?.let {
                viewModel.player.seekTo(it * 1000)
            }
        } else {
            // YouTube video link without time or not the current video, thus load in player
            playNextVideo(videoId)
        }
    }

    private fun updatePlayPauseButton() {
        binding.playImageView.setImageResource(PlayerHelper.getPlayPauseActionIcon(viewModel.player))
    }

    private suspend fun initializeHighlight(highlight: Segment) {
        val frameReceiver: TimeFrameReceiver = getTimeFrameReceiver()
        val highlightStart = highlight.segmentStartAndEnd.first.toLong()
        val frame = withContext(Dispatchers.IO) {
            frameReceiver.getFrameAtTime(highlightStart * 1000)
        }
        val highlightChapter = ChapterSegment(
            title = getString(R.string.chapters_videoHighlight),
            start = highlightStart,
            highlightDrawable = frame?.toDrawable(requireContext().resources)
        )
        chaptersViewModel.chaptersLiveData.postValue(
            chaptersViewModel.chapters.plus(highlightChapter).sortedBy { it.start }
        )
    }

    private fun getTimeFrameReceiver(): TimeFrameReceiver = if (downloadedVideo != null) {
        val downloadedFiles = downloadedVideo!!.downloadItems.filter { it.path.exists() }
        val video = downloadedFiles.firstOrNull { it.type == FileType.VIDEO }
        OfflineTimeFrameReceiver(requireContext(), video!!.path)
    } else {
        OnlineTimeFrameReceiver(requireContext(), streams!!.previewFrames)
    }

    private fun getSubtitleConfigs(): List<SubtitleConfiguration> {
        return if (downloadedVideo != null) {
            val downloadedFiles = downloadedVideo!!.downloadItems.filter { it.path.exists() }
            val subtitle = downloadedFiles.firstOrNull { it.type == FileType.SUBTITLE }
            val subtitleUri = subtitle?.path?.toAndroidUri()
            val subtitleConfigList: ArrayList<SubtitleConfiguration> = ArrayList()
            if (subtitleUri != null) {
                val subtitleConfig = SubtitleConfiguration.Builder(subtitleUri)
                    .setMimeType(MimeTypes.APPLICATION_TTML)
                    .setLanguage("en")
                    .build()
                subtitleConfigList.add(subtitleConfig)
            }
            subtitleConfigList
        } else {
            streams!!.subtitles.map {
                val roleFlags = getSubtitleRoleFlags(it)
                SubtitleConfiguration.Builder(it.url!!.toUri())
                    .setRoleFlags(roleFlags)
                    .setLanguage(it.code)
                    .setMimeType(it.mimeType).build()
            }
        }
    }

    private fun createMediaItem(uri: Uri, mimeType: String): MediaItem {
        val builder = MediaItem.Builder()
            .setUri(uri)
            .setMimeType(mimeType)
            .setMetadata(streamItem)
            .setSubtitleConfigurations(getSubtitleConfigs())
        return builder.build()
    }

    private fun setMediaSource(uri: Uri, mimeType: String) {
        val mediaItem = createMediaItem(uri, mimeType)
        viewModel.player.setMediaItem(mediaItem)
    }

    /**
     * Get all available player resolutions
     */
    private fun getAvailableResolutions(): List<VideoResolution> {
        val resolutions = viewModel.player.currentTracks.groups.asSequence()
            .flatMap { group ->
                (0 until group.length).map {
                    group.getTrackFormat(it).height
                }
            }
            .filter { it > 0 }
            .map { VideoResolution("${it}p", it) }
            .toSortedSet(compareByDescending { it.resolution })

        resolutions.add(VideoResolution(getString(R.string.auto_quality), Int.MAX_VALUE))
        return resolutions.toList()
    }

    private fun initStreamSources() {
        // use the video's default audio track when starting playback
        viewModel.trackSelector.updateParameters {
            setPreferredAudioRoleFlags(C.ROLE_FLAG_MAIN)
        }

        // set the default subtitle if available
        updateCurrentSubtitle(viewModel.currentSubtitle)

        // set media source and resolution in the beginning
        lifecycleScope.launch(Dispatchers.IO) {
            setStreamSource()

            withContext(Dispatchers.Main) {
                // support for time stamped links
                if (timeStamp != 0L) {
                    viewModel.player.seekTo(timeStamp * 1000)
                    // delete the time stamp because it already got consumed
                    timeStamp = 0L
                } else if (!streamItem.isLive) {
                    // seek to the saved watch position
                    PlayerHelper.getStoredWatchPosition(videoId, streamItem.duration)?.let {
                        viewModel.player.seekTo(it)
                    }
                }
            }
        }
    }

    private fun setPlayerResolution(resolution: Int, isSelectedByUser: Boolean = false) {
        val transformedResolution = if (!isSelectedByUser && isShort) {
            ceil(resolution * 16.0 / 9.0).toInt()
        } else {
            resolution
        }

        viewModel.trackSelector.updateParameters {
            setMaxVideoSize(Int.MAX_VALUE, transformedResolution)
            setMinVideoSize(Int.MIN_VALUE, transformedResolution)
        }
    }

    private fun updateResolutionOnFullscreenChange(isFullscreen: Boolean) {
        if (!isFullscreen && noFullscreenResolution != null) {
            setPlayerResolution(noFullscreenResolution!!)
        } else if (fullscreenResolution != null) {
            setPlayerResolution(fullscreenResolution ?: Int.MAX_VALUE)
        }
    }

    private suspend fun setStreamSource() {
        updateResolutionOnFullscreenChange(commonPlayerViewModel.isFullscreen.value == true)

        val (uri, mimeType) = when {
            downloadedVideo?.downloadItems?.any { it.path.exists() } == true -> {
                val videoUri = downloadedVideo!!.downloadItems.firstOrNull { it.path.exists() && it.type == FileType.VIDEO }
                    ?.path?.toAndroidUri()
                val audioUri = downloadedVideo!!.downloadItems.firstOrNull { it.path.exists() && it.type == FileType.AUDIO }
                    ?.path?.toAndroidUri()
                val videoItem = MediaItem.Builder()
                    .setUri(videoUri)
                    .build()
                val audioItem = MediaItem.Builder()
                    .setUri(audioUri)
                    .build()

                val videoSource = ProgressiveMediaSource.Factory(FileDataSource.Factory())
                    .createMediaSource(videoItem)

                val audioSource = ProgressiveMediaSource.Factory(FileDataSource.Factory())
                    .createMediaSource(audioItem)

                val mediaSource = MergingMediaSource(audioSource, videoSource)
                withContext(Dispatchers.Main) { viewModel.player.setMediaSource(mediaSource) }
                return
            }
            // LBRY HLS
            PreferenceHelper.getBoolean(
                PreferenceKeys.LBRY_HLS,
                false
            ) && streams?.videoStreams?.any {
                it.quality.orEmpty().contains("LBRY HLS")
            } == true -> {
                val lbryHlsUrl = streams!!.videoStreams.first {
                    it.quality!!.contains("LBRY HLS")
                }.url!!
                lbryHlsUrl.toUri() to MimeTypes.APPLICATION_M3U8
            }
            // DASH
            !PlayerHelper.useHlsOverDash && streams?.videoStreams?.isNotEmpty() == true -> {
                // only use the dash manifest generated by YT if either it's a livestream or no other source is available
                val dashUri =
                    if (streams!!.livestream && streams!!.dash != null) {
                        ProxyHelper.unwrapStreamUrl(
                            streams!!.dash!!
                        ).toUri()
                    } else {
                        // skip LBRY urls when checking whether the stream source is usable
                        PlayerHelper.createDashSource(streams!!, requireContext())
                    }

                dashUri to MimeTypes.APPLICATION_MPD
            }
            // HLS
            streams?.hls != null -> {
                val hlsMediaSourceFactory = HlsMediaSource.Factory(cronetDataSourceFactory)
                    .setPlaylistParserFactory(YoutubeHlsPlaylistParser.Factory())

                val mediaSource = hlsMediaSourceFactory.createMediaSource(
                    createMediaItem(
                        ProxyHelper.unwrapStreamUrl(streams!!.hls!!).toUri(),
                        MimeTypes.APPLICATION_M3U8
                    )
                )
                withContext(Dispatchers.Main) { viewModel.player.setMediaSource(mediaSource) }
                return
            }
            // NO STREAM FOUND
            else -> {
                context?.toastFromMainDispatcher(R.string.unknown_error)
                return
            }
        }
        withContext(Dispatchers.Main) { setMediaSource(uri, mimeType) }
    }

    private fun createExoPlayer() {
        viewModel.player.setWakeMode(C.WAKE_MODE_NETWORK)
        viewModel.player.addListener(playerListener)

        // control for the track sources like subtitles and audio source
        PlayerHelper.setPreferredCodecs(viewModel.trackSelector)
    }

    /**
     * show the [NowPlayingNotification] for the current video
     */
    private fun initializePlayerNotification() {
        if (viewModel.nowPlayingNotification == null) {
            viewModel.nowPlayingNotification = NowPlayingNotification(
                requireContext(),
                viewModel.player
            )
        }
        val playerNotificationData = PlayerNotificationData(
            streamItem.title,
            streamItem.uploaderName,
            streamItem.thumbnail
        )
        viewModel.nowPlayingNotification?.updatePlayerNotification(videoId, playerNotificationData)
    }

    /**
     * Use the sensor mode if auto fullscreen is enabled
     */
    @SuppressLint("SourceLockedOrientationActivity")
    private fun changeOrientationMode() {
        if (PlayerHelper.autoFullscreenEnabled) {
            // enable auto rotation
            mainActivity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        } else {
            // go to portrait mode
            mainActivity.requestedOrientation =
                (requireActivity() as BaseActivity).screenOrientationPref
        }
    }

    override fun onCaptionsClicked() {
        if (streams == null || streams!!.subtitles.isEmpty()) {
            Toast.makeText(context, R.string.no_subtitles_available, Toast.LENGTH_SHORT).show()
            return
        }

        val subtitles = listOf(Subtitle(name = getString(R.string.none))).plus(streams!!.subtitles)

        BaseBottomSheet()
            .setSimpleItems(
                subtitles.map { it.getDisplayName(requireContext()) },
                preselectedItem = subtitles.firstOrNull { it == viewModel.currentSubtitle }
                    ?.getDisplayName(requireContext()) ?: getString(R.string.none)
            ) { index ->
                val subtitle = subtitles.getOrNull(index) ?: return@setSimpleItems
                updateCurrentSubtitle(subtitle)
                viewModel.currentSubtitle = subtitle
            }
            .show(childFragmentManager)
    }

    private fun getSubtitleRoleFlags(subtitle: Subtitle?): Int {
        return if (subtitle?.autoGenerated != true) {
            C.ROLE_FLAG_CAPTION
        } else {
            PlayerHelper.ROLE_FLAG_AUTO_GEN_SUBTITLE
        }
    }

    override fun onQualityClicked() {
        // get the available resolutions
        val resolutions = getAvailableResolutions()
        val currentQuality = viewModel.trackSelector.parameters.maxVideoHeight

        // Dialog for quality selection
        BaseBottomSheet()
            .setSimpleItems(
                resolutions.map(VideoResolution::name),
                preselectedItem = resolutions.firstOrNull { it.resolution == currentQuality }?.name
            ) { which ->
                val newResolution = resolutions[which].resolution
                setPlayerResolution(newResolution, true)

                // save the selected resolution to update on fullscreen change
                if (noFullscreenResolution != null && commonPlayerViewModel.isFullscreen.value != true) {
                    noFullscreenResolution = newResolution
                } else {
                    fullscreenResolution = newResolution
                }
            }
            .show(childFragmentManager)
    }

    override fun onAudioStreamClicked() {
        val context = requireContext()
        val audioLanguagesAndRoleFlags = PlayerHelper.getAudioLanguagesAndRoleFlagsFromTrackGroups(
            viewModel.player.currentTracks.groups,
            false
        )
        val audioLanguages = audioLanguagesAndRoleFlags.map {
            PlayerHelper.getAudioTrackNameFromFormat(context, it)
        }
        val baseBottomSheet = BaseBottomSheet()

        if (audioLanguagesAndRoleFlags.isEmpty() || (audioLanguagesAndRoleFlags.size == 1 &&
                    audioLanguagesAndRoleFlags[0].first == null &&
                    !PlayerHelper.haveAudioTrackRoleFlagSet(
                        audioLanguagesAndRoleFlags[0].second
                    ))
        ) {
            // Regardless of audio format or quality, if there is only one audio stream which has
            // no language and no role flags, it should mean that there is only a single audio
            // track which has no language or track type set in the video played
            // Consider it as the default audio track (or unknown)
            baseBottomSheet.setSimpleItems(
                listOf(getString(R.string.default_or_unknown_audio_track)),
                preselectedItem = getString(R.string.default_or_unknown_audio_track),
                listener = null
            )
        } else {
            baseBottomSheet.setSimpleItems(
                audioLanguages,
                preselectedItem = audioLanguagesAndRoleFlags.firstOrNull {
                    val format = viewModel.player.audioFormat
                    format?.language == it.first && format?.roleFlags == it.second
                }?.let {
                    PlayerHelper.getAudioTrackNameFromFormat(context, it)
                },
            ) { index ->
                val selectedAudioFormat = audioLanguagesAndRoleFlags[index]
                viewModel.trackSelector.updateParameters {
                    setPreferredAudioLanguage(selectedAudioFormat.first)
                    setPreferredAudioRoleFlags(selectedAudioFormat.second)
                }
            }
        }

        baseBottomSheet.show(childFragmentManager)
    }

    override fun exitFullscreen() {
        unsetFullscreen()
    }

    override fun onStatsClicked() {
        if (streams == null) return
        val videoStats = getVideoStats(viewModel.player, videoId)
        StatsSheet()
            .apply { arguments = bundleOf(IntentData.videoStats to videoStats) }
            .show(childFragmentManager)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        if (isInPictureInPictureMode) {
            // hide and disable exoPlayer controls
            disableController()

            updateCurrentSubtitle(null)

            openOrCloseFullscreenDialog(true)
            pipActivity = activity
        } else {
            binding.player.useController = true

            // close button got clicked in PiP mode
            // pause the video and keep the app alive
            if (lifecycle.currentState == Lifecycle.State.CREATED) {
                viewModel.player.pause()
                viewModel.nowPlayingNotification?.cancelNotification()
                closedVideo = true
            }

            updateCurrentSubtitle(viewModel.currentSubtitle)

            // unset fullscreen if it's not been enabled before the start of PiP
            if (commonPlayerViewModel.isFullscreen.value != true) {
                openOrCloseFullscreenDialog(false)
            }
        }
    }

    private fun updateCurrentSubtitle(subtitle: Subtitle?) =
        viewModel.trackSelector.updateParameters {
            val roleFlags = if (subtitle?.code != null) getSubtitleRoleFlags(subtitle) else 0
            setPreferredTextRoleFlags(roleFlags)
            setPreferredTextLanguage(subtitle?.code)
        }

    fun onUserLeaveHint() {
        if (shouldStartPiP()) {
            PictureInPictureCompat.enterPictureInPictureMode(requireActivity(), pipParams)
        } else if (PlayerHelper.pauseOnQuit) {
            viewModel.player.pause()
        }
    }

    private val pipParams
        get() = PictureInPictureParamsCompat.Builder()
            .setActions(
                PlayerHelper.getPiPModeActions(
                    requireActivity(),
                    viewModel.player.isPlaying
                )
            )
            .setAutoEnterEnabled(PlayerHelper.pipEnabled && viewModel.player.isPlaying)
            .apply {
                if (viewModel.player.isPlaying) {
                    setAspectRatio(viewModel.player.videoSize)
                }
            }
            .build()

    private fun createSeekbarPreviewListener(): SeekbarPreviewListener {
        return SeekbarPreviewListener(
            getTimeFrameReceiver(),
            playerBinding,
            (streamItem.duration ?: 0) * 1000
        )
    }

    /**
     * Detect whether PiP is supported and enabled
     */
    private fun shouldUsePip(): Boolean {
        return PictureInPictureCompat.isPictureInPictureAvailable(requireContext()) && PlayerHelper.pipEnabled
    }

    private fun shouldStartPiP(): Boolean {
        return shouldUsePip() && viewModel.player.isPlaying &&
                !BackgroundHelper.isBackgroundServiceRunning(requireContext())
    }

    /**
     * Check if the activity needs to be recreated due to an orientation change
     * If true, the activity will be automatically restarted
     */
    private fun restartActivityIfNeeded() {
        if (mainActivity.screenOrientationPref in lockedOrientations || viewModel.isOrientationChangeInProgress) return

        val orientation = resources.configuration.orientation
        if (commonPlayerViewModel.isFullscreen.value != true && orientation != playerLayoutOrientation) {
            // remember the current position before recreating the activity
            arguments?.putLong(IntentData.timeStamp, viewModel.player.currentPosition / 1000)
            playerLayoutOrientation = orientation

            viewModel.isOrientationChangeInProgress = true
            activity?.recreate()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (_binding == null ||
            // If in PiP mode, orientation is given as landscape.
            PictureInPictureCompat.isInPictureInPictureMode(requireActivity())
        ) {
            return
        }

        if (PlayerHelper.autoFullscreenEnabled) {
            when (newConfig.orientation) {
                // go to fullscreen mode
                Configuration.ORIENTATION_LANDSCAPE -> setFullscreen()
                // exit fullscreen if not landscape
                else -> unsetFullscreen()
            }
        }

        restartActivityIfNeeded()
    }

    private fun disableController() {
        binding.player.useController = false
        binding.player.hideController()
    }

    fun onKeyUp(keyCode: Int): Boolean {
        return _binding?.player?.onKeyBoardAction(keyCode) ?: false
    }
}
