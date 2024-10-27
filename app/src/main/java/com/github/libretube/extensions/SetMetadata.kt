package com.github.libretube.extensions

import android.content.res.Resources
import android.graphics.BitmapFactory
import android.support.v4.media.MediaMetadataCompat
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.github.libretube.R
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.api.obj.Streams
import com.github.libretube.db.obj.DownloadWithItems

fun MediaItem.Builder.setMetadata(streams: StreamItem) = apply {
    val appIcon = BitmapFactory.decodeResource(
        Resources.getSystem(),
        R.drawable.ic_launcher_monochrome
    )
    val extras = bundleOf(
        MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON to appIcon,
        MediaMetadataCompat.METADATA_KEY_TITLE to streams.title,
        MediaMetadataCompat.METADATA_KEY_ARTIST to streams.uploaderName
    )
    setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(streams.title)
            .setArtist(streams.uploaderName)
            .setArtworkUri(streams.thumbnail?.toUri())
            .setExtras(extras)
            .build()
    )
}

