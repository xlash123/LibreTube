package com.github.libretube.db.obj

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.extensions.toMillis
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import java.nio.file.Path

@Entity(tableName = "download")
data class Download(
    @PrimaryKey(autoGenerate = false)
    val videoId: String,
    val title: String = "",
    val description: String = "",
    val uploader: String = "",
    @ColumnInfo(defaultValue = "NULL")
    val duration: Long? = null,
    val uploadDate: LocalDate? = null,
    // Epoch millis
    val uploadTimestamp: Long? = null,
    val thumbnailPath: Path? = null,
    val uploaderAvatarPath: Path? = null,
    val views: Long? = null,
    val uploaderVerified: Boolean? = null,
    val likes: Long? = null,
    val dislikes: Long? = null,
    val visibility: String? = null,
    val category: String? = null,
    val license: String? = null,
    val tags: List<String>? = null,
    val uploaderSubscriberCount: Long? = null,
) {
    fun toStreamItem() = StreamItem(
        url = videoId,
        title = title,
        shortDescription = description,
        thumbnail = thumbnailPath?.toUri()?.toString(),
        duration = duration,
        uploadedDate = uploadDate?.toString(),
        uploaded = uploadTimestamp ?: 0,
        uploaderName = uploader,
        uploaderAvatar = uploaderAvatarPath.toString(),
        views = views,
        uploaderVerified = uploaderVerified,
        likes = likes,
        dislikes = dislikes,
        visibility = visibility,
        category = category,
        license = license,
        tags = tags,
        uploaderSubscriberCount = uploaderSubscriberCount,
    )
}
