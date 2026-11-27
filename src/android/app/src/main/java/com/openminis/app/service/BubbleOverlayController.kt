package com.openminis.app.service

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.deeplink.DeepLinkAction
import com.openminis.app.deeplink.DeepLinkHandler
import com.openminis.app.agent.SoulIcon
import com.openminis.app.agent.SoulStore
import kotlin.math.abs

/**
 * B5: pure visibility decision for the floating chat bubble.
 *
 * The orb shows ONLY when every condition holds: the "show floating chat
 * bubble" toggle is on, the SYSTEM_ALERT_WINDOW overlay permission is
 * granted, the app is backgrounded (never over its own UI), no tool is
 * running (a running tool belongs to the capsule), and no Dynamic Island
 * is active (the island replaces the overlay). Kept pure so unit tests
 * can drive the whole truth table without Android.
 */
object BubbleVisibility {
    fun shouldShow(
        bubbleEnabled: Boolean,
        overlayPermissionGranted: Boolean,
        appInForeground: Boolean,
        toolRunning: Boolean,
        dynamicIslandActive: Boolean,
    ): Boolean =
        bubbleEnabled &&
            overlayPermissionGranted &&
            !appInForeground &&
            !toolRunning &&
            !dynamicIslandActive
}

/**
 * Inputs the owning service feeds the bubble. The toggle and the overlay
 * permission are read by the controller itself, from the background
 * settings repository and the system.
 */
data class BubbleInput(
    val appInForeground: Boolean = false,
    val toolRunning: Boolean = false,
    val dynamicIslandActive: Boolean = false,
)

/** One quick-action chip: the label it shows and the deep link it fires. */
data class BubbleQuickAction(val label: String, val deepLink: String)

/**
 * B5: floating chat bubble (orb).
 *
 * A circular orb that floats over other apps while Minis is backgrounded,
 * giving one-tap access back into the app plus an expandable quick-action
 * row for the three chat entry points. Deliberately a separate controller
 * from [ToolOverlayController]: the capsule renders live tool status, the
 * orb is the idle, nothing-running surface. The owning service shows at
 * most one of them at a time, never both.
 *
 * Unwired: this controller owns no service, no setting and no manifest
 * entry. The owning service drives it through [updateInput], [refresh]
 * and [onAppForegrounded]; nothing here reads the activity tracker, the
 * island or the foreground service directly.
 *
 * Position is shared with the capsule through [OverlayPosition] and the
 * background settings repository: one remembered position per
 * orientation, whichever surface the user dragged last.
 */
class BubbleOverlayController(private val context: Context) {

    companion object {
        private const val TAG = "BubbleOverlay"
        private const val BUBBLE_SIZE_DP = 56
        private const val DRAG_SLOP_DP = 8
        private const val DEFAULT_MARGIN_DP = 10
        private const val DISMISS_ZONE_DP = 96
        private const val CHIP_WIDTH_DP = 96
        private const val ACTION_GAP_DP = 8
        private const val TOGGLE_SIZE_DP = 24

        /** The disc behind the Soul icon / launcher mark. */
        private const val BUBBLE_DISC_COLOR = 0xFF202020
        private const val CHIP_COLOR = 0xFF2B2B2B

        private const val MAIN_ACTIVITY = "com.openminis.app.MainActivity"

        /** The three chat entry points, as parsed by [DeepLinkHandler]. */
        val QUICK_ACTIONS = listOf(
            BubbleQuickAction("New chat", "minis://action/new_chat"),
            BubbleQuickAction("Voice", "minis://action/voice_chat"),
            BubbleQuickAction("Camera", "minis://action/camera_chat"),
        )
    }

    private val backgroundRepo =
        (context.applicationContext as MinisApp).backgroundSettingsRepository
    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density

    /** The same store as the capsule: one remembered position, shared. */
    private val positionStore = object : OverlayPosition.Store {
        override fun get(orientation: OverlayPosition.Orientation): OverlayPosition.Point? =
            backgroundRepo.getOverlayPosition(
                landscape = orientation == OverlayPosition.Orientation.LANDSCAPE,
            )?.let { OverlayPosition.Point(it.first, it.second) }

        override fun set(orientation: OverlayPosition.Orientation, point: OverlayPosition.Point) =
            backgroundRepo.setOverlayPosition(
                point.x,
                point.y,
                landscape = orientation == OverlayPosition.Orientation.LANDSCAPE,
            )
    }

    private var rootView: View? = null
    private var actionsRow: LinearLayout? = null
    private var toggleView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var shown = false
    private var actionsExpanded = false
    private var dismissedUntilForeground = false
    private var lastInput = BubbleInput()

    fun isShown(): Boolean = shown

    /** The owning service calls this whenever foreground / tool / island state changes. */
    fun updateInput(input: BubbleInput) {
        lastInput = input
        refresh()
    }

    /** Re-evaluate visibility from the toggle, the overlay permission and the last input. */
    fun refresh() {
        val show = !dismissedUntilForeground && BubbleVisibility.shouldShow(
            bubbleEnabled = backgroundRepo.bubbleEnabled.value,
            overlayPermissionGranted = Settings.canDrawOverlays(context),
            appInForeground = lastInput.appInForeground,
            toolRunning = lastInput.toolRunning,
            dynamicIslandActive = lastInput.dynamicIslandActive,
        )
        if (show && !shown) show()
        else if (!show && shown) hide()
    }

    /** The app came to the foreground: a dismissed bubble may show again next time the app backgrounds. */
    fun onAppForegrounded() {
        dismissedUntilForeground = false
        refresh()
    }

    private fun show() {
        mainHandler.post {
            if (shown) return@post
            try {
                val root = buildView()
                val params = buildLayoutParams()
                layoutParams = params
                rootView = root
                windowManager.addView(root, params)
                shown = true
            } catch (e: Throwable) {
                Log.w(TAG, "show failed: ${e.message}")
            }
        }
    }

    private fun hide() {
        mainHandler.post {
            if (!shown) return@post
            try {
                rootView?.let { windowManager.removeView(it) }
            } catch (e: Throwable) {
                Log.w(TAG, "hide failed: ${e.message}")
            }
            rootView = null
            actionsRow = null
            toggleView = null
            layoutParams = null
            actionsExpanded = false
            shown = false
        }
    }

    /** Hide until the app is next foregrounded (long-press / drag-to-dismiss). */
    private fun dismissUntilForeground() {
        dismissedUntilForeground = true
        hide()
    }

    private fun buildView(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        QUICK_ACTIONS.forEachIndexed { index, action ->
            if (index > 0) row.addView(gapView())
            row.addView(chipView(action))
        }
        actionsRow = row
        root.addView(row)

        val orbFrame = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(BUBBLE_SIZE_DP),
                dpToPx(BUBBLE_SIZE_DP),
            )
        }
        val orb = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            background = ovalDrawable(BUBBLE_DISC_COLOR.toInt())
            // The oval background supplies an oval outline, so the icon is clipped to the circle.
            clipToOutline = true
            contentDescription = "Open Minis"
            applySoulIdentityIcon(this)
        }
        orbFrame.addView(orb)

        val toggle = TextView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                dpToPx(TOGGLE_SIZE_DP),
                dpToPx(TOGGLE_SIZE_DP),
                Gravity.TOP or Gravity.END,
            )
            text = "+"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = ovalDrawable(CHIP_COLOR.toInt())
            contentDescription = "Show quick actions"
            setOnClickListener { toggleActions() }
        }
        toggleView = toggle
        orbFrame.addView(toggle)

        root.addView(orbFrame)
        attachTouchListener(root)
        return root
    }

    private fun gapView(): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(dpToPx(ACTION_GAP_DP), 1)
    }

    private fun chipView(action: BubbleQuickAction): View =
        TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(CHIP_WIDTH_DP),
                dpToPx(BUBBLE_SIZE_DP - 16),
            )
            text = action.label
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = roundedDrawable(CHIP_COLOR.toInt(), dpToPx(20))
            contentDescription = action.label
            setOnClickListener {
                collapseActions()
                fireDeepLink(action.deepLink)
            }
        }

    private fun toggleActions() {
        actionsExpanded = !actionsExpanded
        applyActionsExpanded()
    }

    private fun collapseActions() {
        if (!actionsExpanded) return
        actionsExpanded = false
        applyActionsExpanded()
    }

    private fun applyActionsExpanded() {
        actionsRow?.visibility = if (actionsExpanded) View.VISIBLE else View.GONE
        toggleView?.let {
            it.text = if (actionsExpanded) "\u00d7" else "+"
            it.contentDescription =
                if (actionsExpanded) "Hide quick actions" else "Show quick actions"
        }
        // The window wraps its content, so the layout has to be re-applied for the new width.
        val root = rootView ?: return
        val params = layoutParams ?: return
        try {
            windowManager.updateViewLayout(root, params)
        } catch (e: Throwable) {
            Log.w(TAG, "expand relayout failed: ${e.message}")
        }
    }

    private fun ovalDrawable(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    private fun roundedDrawable(color: Int, radiusPx: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx.toFloat()
            setColor(color)
        }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val dm = context.resources.displayMetrics
        val at = OverlayPosition.displayed(
            positionStore,
            defaultPosition(),
            dm.widthPixels,
            dm.heightPixels,
            currentContentWidthPx(),
            dpToPx(BUBBLE_SIZE_DP),
            avoidTop(),
        )
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = at.x
            y = at.y
        }
    }

    /** Never dragged: near the bottom-left, 10 dp from each edge (the capsule's default). */
    private fun defaultPosition(): OverlayPosition.Point {
        val dm = context.resources.displayMetrics
        return OverlayPosition.Point(
            dpToPx(DEFAULT_MARGIN_DP),
            dm.heightPixels - dpToPx(BUBBLE_SIZE_DP) - dpToPx(DEFAULT_MARGIN_DP),
        )
    }

    private fun avoidTop(): OverlayPosition.Avoid {
        val dm = context.resources.displayMetrics
        val orientation = OverlayPosition.Orientation.of(dm.widthPixels, dm.heightPixels)
        return OverlayPosition.avoidFor(
            orientation,
            left = 0,
            right = 0,
            bottom = 0,
            top = statusBarHeightPx(),
        )
    }

    private fun statusBarHeightPx(): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }

    /** Width the clamp uses: the orb alone, or orb + chips + gaps while expanded. */
    private fun currentContentWidthPx(): Int {
        val orb = dpToPx(BUBBLE_SIZE_DP)
        if (!actionsExpanded) return orb
        val chips = QUICK_ACTIONS.size * dpToPx(CHIP_WIDTH_DP)
        val gaps = QUICK_ACTIONS.size * dpToPx(ACTION_GAP_DP)
        return orb + gaps + chips
    }

    /**
     * The capsule's touch-listener shape: slop-gated drag, position saved
     * on drag end, snap to the clamped position, tap otherwise. Long-press
     * dismisses until the app is next foregrounded; so does dragging the
     * bubble into the bottom dismiss zone.
     */
    private fun attachTouchListener(target: View) {
        val slopPx = dpToPx(DRAG_SLOP_DP)
        var downX = 0f
        var downY = 0f
        var startParamX = 0
        var startParamY = 0
        var dragging = false
        var longPressFired = false
        val longPressRunnable = Runnable {
            longPressFired = true
            dismissUntilForeground()
        }
        target.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    val params = layoutParams
                    startParamX = params?.x ?: 0
                    startParamY = params?.y ?: 0
                    dragging = false
                    longPressFired = false
                    mainHandler.postDelayed(
                        longPressRunnable,
                        ViewConfiguration.getLongPressTimeout().toLong(),
                    )
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downX).toInt()
                    val dy = (ev.rawY - downY).toInt()
                    if (!dragging && (abs(dx) > slopPx || abs(dy) > slopPx)) {
                        dragging = true
                        mainHandler.removeCallbacks(longPressRunnable)
                    }
                    if (dragging) {
                        val params = layoutParams
                        if (params != null) {
                            params.x = startParamX + dx
                            params.y = startParamY + dy
                            try {
                                windowManager.updateViewLayout(target, params)
                            } catch (e: Throwable) {
                                Log.w(TAG, "updateViewLayout failed: ${e.message}")
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (longPressFired) {
                        true
                    } else if (dragging) {
                        val params = layoutParams
                        if (params != null) {
                            val dm = context.resources.displayMetrics
                            if (params.y + dpToPx(BUBBLE_SIZE_DP) >= dm.heightPixels - dpToPx(DISMISS_ZONE_DP)) {
                                // Dragged into the bottom dismiss zone: hide until next foreground.
                                dismissUntilForeground()
                            } else {
                                OverlayPosition.dragEnded(
                                    positionStore,
                                    dm.widthPixels,
                                    dm.heightPixels,
                                    OverlayPosition.Point(params.x, params.y),
                                )
                                val at = OverlayPosition.displayed(
                                    positionStore,
                                    defaultPosition(),
                                    dm.widthPixels,
                                    dm.heightPixels,
                                    currentContentWidthPx(),
                                    dpToPx(BUBBLE_SIZE_DP),
                                    avoidTop(),
                                )
                                if (at.x != params.x || at.y != params.y) {
                                    params.x = at.x
                                    params.y = at.y
                                    try {
                                        windowManager.updateViewLayout(target, params)
                                    } catch (e: Throwable) {
                                        Log.w(TAG, "drag snap relayout failed: ${e.message}")
                                    }
                                }
                            }
                        }
                        true
                    } else {
                        onTap()
                        true
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    dragging = false
                    true
                }
                else -> false
            }
        }
    }

    /** Tap = open the app. (The quick-action row has its own toggle on the orb.) */
    private fun onTap() {
        collapseActions()
        openApp()
    }

    private fun fireDeepLink(uriString: String) {
        val uri = Uri.parse(uriString)
        if (DeepLinkHandler.parse(uri) == DeepLinkAction.Unknown) {
            Log.w(TAG, "Ignoring unknown deep link: $uriString")
            return
        }
        openApp(uri)
    }

    private fun openApp(data: Uri? = null) {
        try {
            val intent = Intent(context, Class.forName(MAIN_ACTIVITY)).apply {
                data?.let { this.data = it }
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(intent)
        } catch (e: Throwable) {
            Log.w(TAG, "openApp failed: ${e.message}")
        }
    }

    /**
     * The capsule's applySoulIdentityIcon pattern: the cached Soul icon
     * when one is set, the launcher mark otherwise. (The capsule's plate
     * helper is private to it; here the oval disc background on the orb
     * plays that role.)
     */
    private fun applySoulIdentityIcon(iv: ImageView) {
        val icon = SoulStore.cachedMetadata.value.icon
        if (icon.isBlank()) {
            applyLauncherMark(iv)
            return
        }
        SoulIcon.decode(icon)?.let { bmp ->
            iv.setPadding(0, 0, 0, 0)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.clearColorFilter()
            iv.setImageBitmap(bmp)
            return
        }
        applyLauncherMark(iv)
    }

    private fun applyLauncherMark(iv: ImageView) {
        iv.setPadding(0, 0, 0, 0)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.clearColorFilter()
        iv.setImageResource(R.mipmap.ic_launcher)
    }

    private fun dpToPx(dp: Int): Int = (dp * density).toInt()
}
