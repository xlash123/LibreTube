package com.github.libretube.db.obj

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.github.libretube.api.obj.SegmentData
import kotlinx.serialization.json.Json

/**
 * SponsorBlock segments downloaded for offline use
 */
@Entity(tableName = "downloadSegments")
data class DownloadSegments(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoId: String,
    val segmentData: String,
) {
    fun toSegmentData(): SegmentData = Json.decodeFromString(this.segmentData)
}
