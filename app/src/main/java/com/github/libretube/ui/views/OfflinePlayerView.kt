package com.github.libretube.ui.views

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import com.github.libretube.R
import com.github.libretube.api.obj.ChapterSegment
import com.github.libretube.api.obj.Segment
import com.github.libretube.enums.PlayerCommand
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.services.AbstractPlayerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@UnstableApi
class OfflinePlayerView(
    context: Context,
    attributeSet: AttributeSet? = null
) : CustomExoPlayerView(context, attributeSet) {
    private var chapters: List<ChapterSegment> = emptyList()
    var segments: List<Segment> = emptyList()
        set(value) {
            onSegmentsChanged(value)
        }
    private var sponsorBlockAutoSkip = true

    fun init() {
        val updateSbImageResource = {
            binding.sbToggle.setImageResource(
                if (sponsorBlockAutoSkip) R.drawable.ic_sb_enabled else R.drawable.ic_sb_disabled
            )
        }
        updateSbImageResource()
        binding.sbToggle.setOnClickListener {
            sponsorBlockAutoSkip = !sponsorBlockAutoSkip
            (player as? MediaController)?.sendCustomCommand(
                AbstractPlayerService.runPlayerActionCommand, bundleOf(
                    PlayerCommand.SET_SB_AUTO_SKIP_ENABLED.name to sponsorBlockAutoSkip
                )
            )
            updateSbImageResource()
        }
    }

    private fun onSegmentsChanged(segments: List<Segment>) {
        binding.exoProgress.setSegments(segments)
        binding.sbToggle.isVisible = segments.isNotEmpty()
    }

    override fun hideController() {
        super.hideController()
        // hide the status bars when continuing to watch video
        toggleSystemBars(false)
    }

    override fun showController() {
        super.showController()
        // show status bar when showing player options
        toggleSystemBars(true)
    }

    override fun minimizeOrExitPlayer() {
        (context as AppCompatActivity).onBackPressedDispatcher.onBackPressed()
    }

    fun setChapters(chapters: List<ChapterSegment>) {
        this.chapters = chapters
        setCurrentChapterName()
    }

    override fun isFullscreen(): Boolean = true
}
