package com.github.libretube.api.obj

import android.os.Parcelable
import com.github.libretube.db.obj.LocalPlaylistItem
import com.github.libretube.extensions.toID
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Serializable
@Parcelize
data class StreamItem(
    val url: String? = null,
    val type: String? = null,
    var title: String? = null,
    var thumbnail: String? = null,
    val uploaderName: String? = null,
    val uploaderUrl: String? = null,
    val uploaderAvatar: String? = null,
    val uploadedDate: String? = null,
    val duration: Long? = null,
    val views: Long? = null,
    val uploaderVerified: Boolean? = null,
    val uploaded: Long = 0,
    val shortDescription: String? = null,
    val isShort: Boolean = false,
    val likes: Long? = null,
    val dislikes: Long? = null,
    val description: String? = null,
    val visibility: String? = null,
    val category: String? = null,
    val license: String? = null,
    val tags: List<String>? = null,
    val uploaderSubscriberCount: Long? = null,
) : Parcelable {
    val isLive get() = (duration != null) && (duration <= 0L)

    fun toLocalPlaylistItem(playlistId: String): LocalPlaylistItem {
        return LocalPlaylistItem(
            playlistId = playlistId.toInt(),
            videoId = url!!.toID(),
            title = title,
            thumbnailUrl = thumbnail,
            uploader = uploaderName,
            uploaderUrl = uploaderUrl,
            uploaderAvatar = uploaderAvatar,
            uploadDate = uploadedDate,
            duration = duration
        )
    }

    companion object {
        const val TYPE_STREAM = "stream"
        const val TYPE_CHANNEL = "channel"
        const val TYPE_PLAYLIST = "playlist"
    }
}
