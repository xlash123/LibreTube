package com.github.libretube.helpers

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.core.content.getSystemService
import androidx.core.os.bundleOf
import androidx.core.os.postDelayed
import androidx.fragment.app.commitNow
import androidx.fragment.app.replace
import com.github.libretube.NavDirections
import com.github.libretube.R
import com.github.libretube.constants.IntentData
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.enums.PlaylistType
import com.github.libretube.extensions.toID
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.ui.activities.MainActivity
import com.github.libretube.ui.activities.NoInternetActivity
import com.github.libretube.ui.activities.ZoomableImageActivity
import com.github.libretube.ui.base.BaseActivity
import com.github.libretube.ui.fragments.AudioPlayerFragment
import com.github.libretube.ui.fragments.PlayerFragment
import com.github.libretube.ui.views.SingleViewTouchableMotionLayout

object NavigationHelper {
    private val handler = Handler(Looper.getMainLooper())

    fun navigateChannel(context: Context, channelUrlOrId: String?) {
        if (channelUrlOrId == null) return

        val activity = ContextHelper.unwrapActivity<MainActivity>(context)
        activity.navController.navigate(NavDirections.openChannel(channelUrlOrId.toID()))
        try {
            // minimize player if currently expanded
            if (activity.binding.mainMotionLayout.progress == 0f) {
                activity.binding.mainMotionLayout.transitionToEnd()
                activity.findViewById<SingleViewTouchableMotionLayout>(R.id.playerMotionLayout)
                    .transitionToEnd()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Navigate to the given video using the other provided parameters as well
     * If the audio only mode is enabled, play it in the background, else as a normal video
     */
    fun navigateVideo(
        context: Context,
        videoUrlOrId: String?,
        playlistId: String? = null,
        channelId: String? = null,
        keepQueue: Boolean = false,
        timestamp: Long = 0,
        forceVideo: Boolean = false
    ) {
        if (videoUrlOrId == null) return
        BackgroundHelper.stopBackgroundPlay(context)

        if (PreferenceHelper.getBoolean(PreferenceKeys.AUDIO_ONLY_MODE, false) && !forceVideo) {
            BackgroundHelper.playOnBackground(
                context,
                videoUrlOrId.toID(),
                timestamp,
                playlistId,
                channelId,
                keepQueue
            )
            handler.postDelayed(500) {
                startAudioPlayer(context)
            }
            return
        }

        val playerData =
            PlayerData(videoUrlOrId.toID(), playlistId, channelId, keepQueue, timestamp)
        val bundle = bundleOf(IntentData.playerData to playerData)

        val activity = ContextHelper.tryUnwrapActivity<MainActivity>(context) ?: ContextHelper.unwrapActivity<NoInternetActivity>(context)
        activity.supportFragmentManager.commitNow {
            replace<PlayerFragment>(R.id.container, args = bundle)
        }
    }

    fun navigatePlaylist(context: Context, playlistUrlOrId: String?, playlistType: PlaylistType) {
        if (playlistUrlOrId == null) return

        val activity = ContextHelper.unwrapActivity<MainActivity>(context)
        activity.navController.navigate(
            NavDirections.openPlaylist(playlistUrlOrId.toID(), playlistType)
        )
    }

    /**
     * Start the audio player fragment
     */
    fun startAudioPlayer(context: Context, offlinePlayer: Boolean = false, minimizeByDefault: Boolean = false) {
        val activity = ContextHelper.unwrapActivity<BaseActivity>(context)
        activity.supportFragmentManager.commitNow {
            val args = bundleOf(
                IntentData.minimizeByDefault to minimizeByDefault,
                IntentData.offlinePlayer to offlinePlayer
            )
            replace<AudioPlayerFragment>(R.id.container, args = args)
        }
    }

    /**
     * Open a large, zoomable image preview
     */
    fun openImagePreview(context: Context, url: String) {
        val intent = Intent(context, ZoomableImageActivity::class.java)
        intent.putExtra(IntentData.bitmapUrl, url)
        context.startActivity(intent)
    }

    /**
     * Needed due to different MainActivity Aliases because of the app icons
     */
    fun restartMainActivity(context: Context) {
        // kill player notification
        context.getSystemService<NotificationManager>()!!.cancelAll()
        // start a new Intent of the app
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(context.packageName)
        intent?.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK
        context.startActivity(intent)
        // kill the old application
        Process.killProcess(Process.myPid())
    }
}
