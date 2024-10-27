package com.github.libretube.ui.views

import android.annotation.SuppressLint
import android.content.Context
import android.text.util.Linkify
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.core.text.method.LinkMovementMethodCompat
import androidx.core.text.parseAsHtml
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.libretube.R
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.databinding.DescriptionLayoutBinding
import com.github.libretube.extensions.formatShort
import com.github.libretube.helpers.ClipboardHelper
import com.github.libretube.ui.activities.VideoTagsAdapter
import com.github.libretube.util.HtmlParser
import com.github.libretube.util.LinkHandler
import com.github.libretube.util.TextUtils
import java.util.Locale

class DescriptionLayout(
    context: Context,
    attributeSet: AttributeSet?
) : LinearLayout(context, attributeSet) {
    val binding = DescriptionLayoutBinding.inflate(LayoutInflater.from(context), this, true)
    private var streamItem: StreamItem? = null
    var handleLink: (link: String) -> Unit = {}

    init {
        binding.playerTitleLayout.setOnClickListener {
            toggleDescription()
        }
        binding.playerTitleLayout.setOnLongClickListener {
            streamItem?.title?.let { ClipboardHelper.save(context, text = it) }
            true
        }
    }

    @SuppressLint("SetTextI18n")
    fun setStreams(streamItem: StreamItem) {
        this.streamItem = streamItem

        val views = streamItem.views.formatShort()
        val date = TextUtils.formatRelativeDate(context, streamItem.uploaded ?: -1L)
        binding.run {
            playerViewsInfo.text = context.getString(R.string.normal_views, views, TextUtils.SEPARATOR + date)

            textLike.text = streamItem.likes.formatShort()
            textDislike.isVisible = streamItem.dislikes != null && streamItem.dislikes >= 0
            textDislike.text = streamItem.dislikes.formatShort()

            playerTitle.text = streamItem.title
            playerDescription.text = streamItem.description

            metaInfo.isVisible = streamItem.title != null || streamItem.description != null
            // generate a meta info text with clickable links using html
//            val metaInfoText = streams.metaInfo.joinToString("\n\n") { info ->
//                val text = info.description.takeIf { it.isNotBlank() } ?: info.title
//                val links = info.urls.mapIndexed { index, url ->
//                    "<a href=\"$url\">${info.urlTexts.getOrNull(index).orEmpty()}</a>"
//                }.joinToString(", ")
//                "$text $links"
//            }
            val metaInfoText = streamItem.description
            metaInfo.text = metaInfoText?.parseAsHtml()

            val visibility = when (streamItem.visibility) {
                "public" -> context?.getString(R.string.visibility_public)
                "unlisted" -> context?.getString(R.string.visibility_unlisted)
                // currently no other visibility could be returned, might change in the future however
                else -> streamItem.visibility?.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
                }
            }.orEmpty()
            additionalVideoInfo.text =
                "${context?.getString(R.string.category)}: ${streamItem.category}\n" +
                "${context?.getString(R.string.license)}: ${streamItem.license}\n" +
                "${context?.getString(R.string.visibility)}: $visibility"

            if (streamItem.tags?.isNotEmpty() == true) {
                binding.tagsRecycler.layoutManager =
                    LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
                binding.tagsRecycler.adapter = VideoTagsAdapter(streamItem.tags)
            }
            binding.tagsRecycler.isVisible = streamItem.tags?.isNotEmpty() == true

            if (streamItem.description != null) {
                setupDescription(streamItem.description)
            }
        }
    }

    /**
     * Set up the description text with video links and timestamps
     */
    private fun setupDescription(description: String) {
        val descTextView = binding.playerDescription
        // detect whether the description is html formatted
        if (description.contains("<") && description.contains(">")) {
            descTextView.movementMethod = LinkMovementMethodCompat.getInstance()
            descTextView.text = description.replace("</a>", "</a> ")
                .parseAsHtml(tagHandler = HtmlParser(LinkHandler(handleLink)))
        } else {
            // Links can be present as plain text
            descTextView.autoLinkMask = Linkify.WEB_URLS
            descTextView.text = description
        }
    }

    private fun toggleDescription() {
        val streams = streamItem ?: return

        val views = if (binding.descLinLayout.isVisible) {
            // show formatted short view count
            streams.views.formatShort()
        } else {
            // show exact view count
            "%,d".format(streams.views)
        }
        val date = TextUtils.formatRelativeDate(context, streams.uploaded ?: -1L)
        val viewInfo = context.getString(R.string.normal_views, views,  TextUtils.SEPARATOR + date)
        if (binding.descLinLayout.isVisible) {
            // hide the description and chapters
            binding.playerDescriptionArrow.animate().rotation(
                0F
            ).setDuration(ANIMATION_DURATION).start()

            binding.playerDescription.isGone = true

            binding.descLinLayout.isGone = true

            // limit the title height to two lines
            binding.playerTitle.maxLines = 2
        } else {
            // show the description and chapters
            binding.playerDescriptionArrow.animate().rotation(
                180F
            ).setDuration(ANIMATION_DURATION).start()

            binding.playerDescription.isVisible = true
            binding.descLinLayout.isVisible = true

            // show the whole title
            binding.playerTitle.maxLines = Int.MAX_VALUE
        }
        binding.playerViewsInfo.text = viewInfo
    }

    companion object {
        private const val ANIMATION_DURATION = 250L
    }
}
