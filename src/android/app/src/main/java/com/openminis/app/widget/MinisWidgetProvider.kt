package com.openminis.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.openminis.app.MainActivity
import com.openminis.app.R

/**
 * B5: home-screen widget — one row of quick actions mirroring the app-icon
 * long-press shortcuts (DeepLinkHandler "action" routes, same as iOS
 * QuickActionRouter): open the app, new chat, voice chat, camera chat.
 *
 * Every button is a plain ACTION_VIEW PendingIntent on a minis:// URI, so a
 * tap lands in MainActivity.handleDeepLink exactly like a shortcut tap; the
 * widget itself holds no state and never talks to the network.
 */
class MinisWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id)
        }
    }

    companion object {
        private const val ACTION_OPEN = 0
        private const val ACTION_NEW_CHAT = 1
        private const val ACTION_VOICE = 2
        private const val ACTION_CAMERA = 3

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_minis)
            views.setOnClickPendingIntent(R.id.widget_open, actionIntent(context, ACTION_OPEN, null))
            views.setOnClickPendingIntent(R.id.widget_new_chat, actionIntent(context, ACTION_NEW_CHAT, "minis://action/new_chat"))
            views.setOnClickPendingIntent(R.id.widget_voice, actionIntent(context, ACTION_VOICE, "minis://action/voice_chat"))
            views.setOnClickPendingIntent(R.id.widget_camera, actionIntent(context, ACTION_CAMERA, "minis://action/camera_chat"))
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun actionIntent(context: Context, requestCode: Int, uri: String?): PendingIntent {
            val intent = if (uri == null) {
                Intent(context, MainActivity::class.java)
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(uri), context, MainActivity::class.java)
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
