package com.github.libretube.ui.fragments

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.constraintlayout.motion.widget.TransitionAdapter
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.github.libretube.R
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.IntentData
import com.github.libretube.databinding.FragmentAudioPlayerBinding
import com.github.libretube.extensions.normalize
import com.github.libretube.extensions.seekBy
import com.github.libretube.extensions.toID
import com.github.libretube.extensions.togglePlayPauseState
import com.github.libretube.extensions.updateIfChanged
import com.github.libretube.helpers.AudioHelper
import com.github.libretube.helpers.BackgroundHelper
import com.github.libretube.helpers.ClipboardHelper
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.NavBarHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.helpers.ThemeHelper
import com.github.libretube.services.AbstractPlayerService
import com.github.libretube.services.OfflinePlayerService
import com.github.libretube.services.OnlinePlayerService
import com.github.libretube.ui.activities.MainActivity
import com.github.libretube.ui.base.BaseActivity
import com.github.libretube.ui.interfaces.AudioPlayerOptions
import com.github.libretube.ui.listeners.AudioPlayerThumbnailListener
import com.github.libretube.ui.models.ChaptersViewModel
import com.github.libretube.ui.models.CommonPlayerViewModel
import com.github.libretube.ui.sheets.ChaptersBottomSheet
import com.github.libretube.ui.sheets.PlaybackOptionsSheet
import com.github.libretube.ui.sheets.PlayingQueueSheet
import com.github.libretube.ui.sheets.SleepTimerSheet
import com.github.libretube.ui.sheets.VideoOptionsBottomSheet
import com.github.libretube.util.DataSaverMode
import com.github.libretube.util.PlayingQueue
import kotlinx.coroutines.launch
import kotlin.math.abs

@UnstableApi
class AudioPlayerFragment : Fragment(), AudioPlayerOptions {
    private var _binding: FragmentAudioPlayerBinding? = null
    val binding get() = _binding!!

    private lateinit var audioHelper: AudioHelper
    private val activity get() = context as BaseActivity
    private val mainActivity get() = activity as? MainActivity
    private val viewModel: CommonPlayerViewModel by activityViewModels()
    private val chaptersModel: ChaptersViewModel by activityViewModels()

    // for the transition
    private var transitionStartId = 0
    private var transitionEndId = 0

    private var handler = Handler(Looper.getMainLooper())
    private var isPaused = !PlayerHelper.playAutomatically

    private var playerService: AbstractPlayerService? = null

    /** Defines callbacks for service binding, passed to bindService()  */
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            // We've bound to LocalService, cast the IBinder and get LocalService instance
            val binder = service as AbstractPlayerService.LocalBinder
            playerService = binder.getService()
            handleServiceConnection()
            handleServiceConnection()
        }

        override fun onServiceDisconnected(arg0: ComponentName) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        audioHelper = AudioHelper(requireContext())

        val isOffline = requireArguments().getBoolean(IntentData.offlinePlayer)

        val serviceClass =
            if (isOffline) OfflinePlayerService::class.java else OnlinePlayerService::class.java
        Intent(activity, serviceClass).also { intent ->
            activity.bindService(intent, connection, 0)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAudioPlayerBinding.inflate(inflater)
        return binding.root
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (activity as? MainActivity)?.getBottomNavColor()?.let { color ->
            binding.audioPlayerContainer.setBackgroundColor(color)
        }

        initializeTransitionLayout()

        // select the title TV in order for it to automatically scroll
        binding.title.isSelected = true
        binding.uploader.isSelected = true

        binding.title.setOnLongClickListener {
            ClipboardHelper.save(requireContext(), text = binding.title.text.toString())
            true
        }

        binding.minimizePlayer.setOnClickListener {
            mainActivity?.binding?.mainMotionLayout?.transitionToStart()
            binding.playerMotionLayout.transitionToEnd()
        }

        binding.autoPlay.isChecked = PlayerHelper.autoPlayEnabled
        binding.autoPlay.setOnCheckedChangeListener { _, isChecked ->
            PlayerHelper.autoPlayEnabled = isChecked
        }

        binding.prev.setOnClickListener {
            PlayingQueue.navigatePrev()
        }

        binding.next.setOnClickListener {
            PlayingQueue.navigateNext()
        }

        listOf(binding.forwardTV, binding.rewindTV).forEach {
            it.text = (PlayerHelper.seekIncrement / 1000).toString()
        }
        binding.rewindFL.setOnClickListener {
            playerService?.player?.seekBy(-PlayerHelper.seekIncrement)
        }
        binding.forwardFL.setOnClickListener {
            playerService?.player?.seekBy(PlayerHelper.seekIncrement)
        }

        binding.openQueue.setOnClickListener {
            PlayingQueueSheet().show(childFragmentManager)
        }

        binding.playbackOptions.setOnClickListener {
            playerService?.player?.let {
                PlaybackOptionsSheet(it)
                    .show(childFragmentManager)
            }
        }

        binding.sleepTimer.setOnClickListener {
            SleepTimerSheet().show(childFragmentManager)
        }

        binding.openVideo.setOnClickListener {
            BackgroundHelper.stopBackgroundPlay(requireContext())
            killFragment()
            NavigationHelper.navigateVideo(
                context = requireContext(),
                videoUrlOrId = PlayingQueue.getCurrent()?.url,
                timestamp = playerService?.player?.currentPosition?.div(1000) ?: 0,
                keepQueue = true,
                forceVideo = true
            )
        }

        childFragmentManager.setFragmentResultListener(
            ChaptersBottomSheet.SEEK_TO_POSITION_REQUEST_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            playerService?.player?.seekTo(bundle.getLong(IntentData.currentPosition))
        }

        binding.openChapters.setOnClickListener {
            val playerService = playerService ?: return@setOnClickListener
            chaptersModel.chaptersLiveData.value = playerService.getChapters()

            ChaptersBottomSheet()
                .apply {
                    arguments = bundleOf(
                        IntentData.duration to playerService.player?.duration?.div(1000)
                    )
                }
                .show(childFragmentManager)
        }

        binding.miniPlayerClose.setOnClickListener {
            activity.unbindService(connection)
            BackgroundHelper.stopBackgroundPlay(requireContext())
            killFragment()
        }

        val listener = AudioPlayerThumbnailListener(requireContext(), this)
        binding.thumbnail.setOnTouchListener(listener)

        binding.playPause.setOnClickListener {
            playerService?.player?.togglePlayPauseState()
        }

        binding.miniPlayerPause.setOnClickListener {
            playerService?.player?.togglePlayPauseState()
        }

        binding.showMore.setOnClickListener {
            onLongTap()
        }

        // load the stream info into the UI
        updateStreamInfo()

        // update the currently shown volume
        binding.volumeProgressBar.let { bar ->
            bar.progress = audioHelper.getVolumeWithScale(bar.max)
        }

        if (!PlayerHelper.playAutomatically) updatePlayPauseButton()

        updateChapterIndex()
    }

    private fun killFragment() {
        viewModel.isFullscreen.value = false
        binding.playerMotionLayout.transitionToEnd()
        activity.supportFragmentManager.commit {
            remove(this@AudioPlayerFragment)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initializeTransitionLayout() {
        if (mainActivity == null) return

        mainActivity!!.binding.container.isVisible = true
        val mainMotionLayout = mainActivity!!.binding.mainMotionLayout
        mainMotionLayout.progress = 0F

        binding.playerMotionLayout.addTransitionListener(object : TransitionAdapter() {
            override fun onTransitionChange(
                motionLayout: MotionLayout?,
                startId: Int,
                endId: Int,
                progress: Float
            ) {
                if (NavBarHelper.hasTabs()) {
                    mainMotionLayout.progress = abs(progress)
                }
                transitionEndId = endId
                transitionStartId = startId
            }

            override fun onTransitionCompleted(motionLayout: MotionLayout?, currentId: Int) {
                if (currentId == transitionEndId) {
                    viewModel.isMiniPlayerVisible.value = true
                    if (NavBarHelper.hasTabs()) {
                        mainMotionLayout.progress = 1F
                    }
                } else if (currentId == transitionStartId) {
                    viewModel.isMiniPlayerVisible.value = false
                    mainMotionLayout.progress = 0F
                }
            }
        })

        if (arguments?.getBoolean(IntentData.minimizeByDefault, false) != true) {
            binding.playerMotionLayout.progress = 1f
            binding.playerMotionLayout.transitionToStart()
        } else {
            binding.playerMotionLayout.progress = 0f
            binding.playerMotionLayout.transitionToEnd()
        }
    }

    /**
     * Load the information from a new stream into the UI
     */
    private fun updateStreamInfo(stream: StreamItem? = null) {
        val binding = _binding ?: return

        val current = stream ?: PlayingQueue.getCurrent() ?: return

        binding.title.text = current.title
        binding.miniPlayerTitle.text = current.title

        binding.uploader.text = current.uploaderName
        binding.uploader.setOnClickListener {
            NavigationHelper.navigateChannel(requireContext(), current.uploaderUrl?.toID())
        }

        current.thumbnail?.let { updateThumbnailAsync(it) }

        initializeSeekBar()
    }

    private fun updateThumbnailAsync(thumbnailUrl: String) {
        if (DataSaverMode.isEnabled(requireContext())) {
            binding.progress.isVisible = false
            binding.thumbnail.setImageResource(R.drawable.ic_launcher_monochrome)
            val primaryColor = ThemeHelper.getThemeColor(
                requireContext(),
                androidx.appcompat.R.attr.colorPrimary
            )
            binding.thumbnail.setColorFilter(primaryColor)
            return
        }

        binding.progress.isVisible = true
        binding.thumbnail.isGone = true
        // reset color filter if data saver mode got toggled or conditions for it changed
        binding.thumbnail.setColorFilter(Color.TRANSPARENT)

        lifecycleScope.launch {
            val binding = _binding ?: return@launch
            val bitmap = ImageHelper.getImage(requireContext(), thumbnailUrl)
            binding.thumbnail.setImageBitmap(bitmap)
            binding.miniPlayerThumbnail.setImageBitmap(bitmap)
            binding.thumbnail.isVisible = true
            binding.progress.isGone = true
        }
    }

    private fun initializeSeekBar() {
        binding.timeBar.addOnChangeListener { _, value, fromUser ->
            if (fromUser) playerService?.seekToPosition(value.toLong() * 1000)
        }
        updateSeekBar()
    }

    /**
     * Update the position, duration and text views belonging to the seek bar
     */
    private fun updateSeekBar() {
        val binding = _binding ?: return
        val duration = playerService?.getDuration()?.takeIf { it > 0 } ?: let {
            // if there's no duration available, clear everything
            binding.timeBar.value = 0f
            binding.duration.text = ""
            binding.currentPosition.text = ""
            handler.postDelayed(this::updateSeekBar, 100)
            return
        }
        val currentPosition = playerService?.getCurrentPosition()?.toFloat() ?: 0f

        // set the text for the indicators
        binding.duration.text = DateUtils.formatElapsedTime(duration / 1000)
        binding.currentPosition.text = DateUtils.formatElapsedTime(
            (currentPosition / 1000).toLong()
        )

        // update the time bar current value and maximum value
        binding.timeBar.valueTo = (duration / 1000).toFloat()
        binding.timeBar.value = minOf(
            currentPosition / 1000,
            binding.timeBar.valueTo
        )

        handler.postDelayed(this::updateSeekBar, 200)
    }

    private fun updatePlayPauseButton() {
        playerService?.player?.let {
            val binding = _binding ?: return

            val iconRes = PlayerHelper.getPlayPauseActionIcon(it)
            binding.playPause.setIconResource(iconRes)
            binding.miniPlayerPause.setImageResource(iconRes)
        }
    }

    private fun handleServiceConnection() {
        playerService?.onStateOrPlayingChanged = { isPlaying ->
            updatePlayPauseButton()
            isPaused = !isPlaying
        }
        playerService?.onNewVideoStarted = { streamItem ->
            updateStreamInfo(streamItem)
            _binding?.openChapters?.isVisible = !playerService?.getChapters().isNullOrEmpty()
        }
        initializeSeekBar()

        if (playerService is OfflinePlayerService) {
            binding.openVideo.isGone = true
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        // unregister all listeners and the connected [playerService]
        playerService?.onStateOrPlayingChanged = null
        runCatching {
            activity.unbindService(connection)
        }

        super.onDestroy()
    }

    override fun onSingleTap() {
        playerService?.player?.togglePlayPauseState()
    }

    override fun onLongTap() {
        val current = PlayingQueue.getCurrent() ?: return
        VideoOptionsBottomSheet()
            .apply {
                arguments = bundleOf(
                    IntentData.streamItem to current,
                    IntentData.isCurrentlyPlaying to true
                )
            }
            .show(childFragmentManager)
    }

    override fun onSwipe(distanceY: Float) {
        if (!PlayerHelper.swipeGestureEnabled) return

        binding.volumeControls.isVisible = true
        updateVolume(distanceY)
    }

    override fun onSwipeEnd() {
        if (!PlayerHelper.swipeGestureEnabled) return

        binding.volumeControls.isGone = true
    }

    private fun updateVolume(distance: Float) {
        val bar = binding.volumeProgressBar
        binding.volumeControls.apply {
            if (visibility == View.GONE) {
                isVisible = true
                // Volume could be changed using other mediums, sync progress
                // bar with new value.
                bar.progress = audioHelper.getVolumeWithScale(bar.max)
            }
        }

        if (bar.progress == 0) {
            binding.volumeImageView.setImageResource(
                when {
                    distance > 0 -> R.drawable.ic_volume_up
                    else -> R.drawable.ic_volume_off
                }
            )
        }
        bar.incrementProgressBy(distance.toInt() / 3)
        audioHelper.setVolumeWithScale(bar.progress, bar.max)

        binding.volumeTextView.text = "${bar.progress.normalize(0, bar.max, 0, 100)}"
    }

    private fun updateChapterIndex() {
        if (_binding == null) return
        handler.postDelayed(this::updateChapterIndex, 100)

        val player = playerService?.player ?: return

        val currentIndex =
            PlayerHelper.getCurrentChapterIndex(player.currentPosition, chaptersModel.chapters)
        chaptersModel.currentChapterIndex.updateIfChanged(currentIndex ?: return)
    }
}
