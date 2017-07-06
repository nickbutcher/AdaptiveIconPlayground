/*
 *  Copyright 2017 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package uk.co.nickbutcher.adaptiveiconplayground

import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Bundle
import android.support.animation.FloatPropertyCompat
import android.support.animation.SpringAnimation
import android.support.annotation.ColorInt
import android.support.annotation.DrawableRes
import android.support.annotation.FloatRange
import android.support.design.widget.BottomSheetBehavior
import android.support.v4.app.LoaderManager
import android.support.v4.content.AsyncTaskLoader
import android.support.v4.content.Loader
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.GridLayoutManager
import android.support.v7.widget.RecyclerView
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.util.FloatProperty
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.View.GONE
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.LinearLayout.HORIZONTAL
import android.widget.LinearLayout.VERTICAL
import android.widget.SeekBar

class MainActivity : AppCompatActivity() {

    private val grid by lazy(LazyThreadSafetyMode.NONE) {
        findViewById<RecyclerView>(R.id.grid)
    }
    private val damping by lazy(LazyThreadSafetyMode.NONE) {
        findViewById<SeekBar>(R.id.damping)
    }
    private val stiffness by lazy(LazyThreadSafetyMode.NONE) {
        findViewById<SeekBar>(R.id.stiffness)
    }
    private val velocityTracker = VelocityTracker.obtain()
    private val corners: FloatArray by lazy(LazyThreadSafetyMode.NONE) {
        val density = resources.displayMetrics.density
        floatArrayOf(density * 36f, density * 30f, density * 16f, density * 4f)
    }
    private val gridTouch = View.OnTouchListener { _, motionEvent ->
        when (motionEvent.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                velocityTracker += motionEvent
                when (orientation) {
                    HORIZONTAL -> velocityX = velocityTracker.xVelocity
                    VERTICAL -> velocityY = velocityTracker.yVelocity
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocityTracker += motionEvent
                releaseVelocity(velocityTracker.xVelocity, velocityTracker.yVelocity)
                velocityTracker.clear()
            }
        }
        false
    }
    private var corner = 0
    private var decor = Decor.Wallpaper
    private var orientation = HORIZONTAL
    private var adapter: IconAdapter? = null

    private var springStiffness = 500f
        get() = Math.max(stiffness.progress.toFloat(), 50f)

    private var springDamping = 0.3f
        get() = Math.max(damping.progress / 100f, 0.05f)

    private var iconCornerRadius
        get() = adapter?.iconCornerRadius ?: 0f
        set(value) {
            applyGridProperty(
                    { ad -> ad.iconCornerRadius = value },
                    { iv -> iv.cornerRadius = value })
        }

    private var velocityX = 0f
        set(value) {
            applyGridProperty(
                    { ad -> ad.velocityX = value },
                    { iv -> iv.velocityX = value })
        }

    private var velocityY = 0f
        set(value) {
            applyGridProperty(
                    { ad -> ad.velocityY = value },
                    { iv -> iv.velocityY = value })
        }

    private var foregroundTranslateFactor
        get() = adapter?.foregroundTranslateFactor ?: DEF_FOREGROUND_TRANSLATE_FACTOR
        set(value) {
            applyGridProperty(
                    { ad -> ad.foregroundTranslateFactor = value },
                    { iv -> iv.foregroundTranslateFactor = value })
        }

    private var backgroundTranslateFactor
        get() = adapter?.backgroundTranslateFactor ?: DEF_BACKGROUND_TRANSLATE_FACTOR
        set(value) {
            applyGridProperty(
                    { ad -> ad.backgroundTranslateFactor = value },
                    { iv -> iv.backgroundTranslateFactor = value })
        }

    private var foregroundScaleFactor
        get() = adapter?.foregroundScaleFactor ?: DEF_FOREGROUND_SCALE_FACTOR
        set(value) {
            applyGridProperty(
                    { ad -> ad.foregroundScaleFactor = value },
                    { iv -> iv.foregroundScaleFactor = value })
        }

    private var backgroundScaleFactor
        get() = adapter?.backgroundScaleFactor ?: DEF_BACKGROUND_SCALE_FACTOR
        set(value) {
            applyGridProperty(
                    { ad -> ad.backgroundScaleFactor = value },
                    { iv -> iv.backgroundScaleFactor = value })
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val res = resources

        findViewById<View>(android.R.id.content).systemUiVisibility =
                (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
        grid.setHasFixedSize(true)
        grid.addItemDecoration(CenteringDecoration(res.getInteger(R.integer.spans),
                res.getDimensionPixelSize(R.dimen.icon_size)))

        val fastOutSlowIn = AnimationUtils.loadInterpolator(
                this, android.R.interpolator.fast_out_slow_in)
        findViewById<View>(R.id.mask).setOnClickListener {
            corner = ++corner % corners.size
            with(ObjectAnimator.ofFloat(
                    this@MainActivity,
                    ICON_CORNER_RADIUS,
                    corners[corner])) {
                duration = 200L
                interpolator = fastOutSlowIn
                start()
            }
        }

        val reorient = TransitionSet().apply {
            ordering = TransitionSet.ORDERING_TOGETHER
            addTransition(Fade(Fade.OUT))
            addTransition(ChangeBounds())
            addTransition(Fade(Fade.IN))
            duration = 200L
            interpolator = fastOutSlowIn
        }
        findViewById<View>(R.id.orientation).setOnClickListener { view ->
            orientation = orientation xor 1
            view.animate()
                    .rotation(if (orientation == VERTICAL) 90f else 0f)
                    .setDuration(160L)
                    .setInterpolator(fastOutSlowIn)
                    .start()
            TransitionManager.beginDelayedTransition(grid, reorient)
            (grid.layoutManager as GridLayoutManager).orientation = orientation
        }

        with(findViewById<ImageView>(R.id.background)) {
            setOnClickListener {
                decor = decor.next()
                grid.setBackgroundResource(decor.background)
                window.statusBarColor = decor.status
                setImageResource(decor.icon)

                if (decor.darkStatusIcons) {
                    systemUiVisibility = systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                } else {
                    systemUiVisibility =
                            systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                }
            }
        }

        findViewById<SeekBar>(R.id.foreground_parallax)
                .onSeek { progress -> foregroundTranslateFactor = progress / 100f }
        findViewById<SeekBar>(R.id.background_parallax)
                .onSeek { progress -> backgroundTranslateFactor = progress / 100f }
        findViewById<SeekBar>(R.id.foreground_scale)
                .onSeek { progress -> foregroundScaleFactor = progress / 100f }
        findViewById<SeekBar>(R.id.background_scale)
                .onSeek { progress -> backgroundScaleFactor = progress / 100f }

        BottomSheetBehavior.from(findViewById<View>(R.id.settings_sheet)).setBottomSheetCallback(
                object : BottomSheetBehavior.BottomSheetCallback() {
                    override fun onStateChanged(bottomSheet: View, newState: Int) {}

                    override fun onSlide(bottomSheet: View, slideOffset: Float) {
                        // make the sheet more opaque [80%–95%] as it slides up
                        val alpha = 204 + (38 * slideOffset).toInt()
                        val color = 0xccffffff.toInt() and 0x00ffffff or (alpha shl 24)
                        bottomSheet.setBackgroundColor(color)
                    }
                })

        supportLoaderManager.initLoader(0, Bundle.EMPTY,
                object : LoaderManager.LoaderCallbacks<List<AdaptiveIconDrawable>> {
                    override fun onCreateLoader(id: Int, args: Bundle) =
                            AdaptiveIconLoader(applicationContext)

                    override fun onLoadFinished(loader: Loader<List<AdaptiveIconDrawable>>,
                                                data: List<AdaptiveIconDrawable>) {
                        findViewById<View>(R.id.loading).visibility = GONE
                        adapter = IconAdapter(data, corners[0])
                        grid.adapter = adapter
                        grid.setOnTouchListener(gridTouch)
                    }

                    override fun onLoaderReset(loader: Loader<List<AdaptiveIconDrawable>>) {}
                })
    }

    private fun releaseVelocity(releaseVelocityX: Float, releaseVelocityY: Float) {
        if (releaseVelocityX != 0f) {
            with(SpringAnimation(this, VELOCITY_X, 0f)) {
                spring.stiffness = springStiffness
                spring.dampingRatio = springDamping
                setStartVelocity(releaseVelocityX)
                start()
            }
        }
        if (releaseVelocityY != 0f) {
            with(SpringAnimation(this, VELOCITY_Y, 0f)) {
                spring.stiffness = springStiffness
                spring.dampingRatio = springDamping
                setStartVelocity(releaseVelocityY)
                start()
            }
        }
    }

    /**
     * Helper function for setting a property on both the adapter and on all views in the grid
     */
    private inline fun applyGridProperty(
            adapterAction: (IconAdapter) -> Unit,
            iconViewAction: (AdaptiveIconView) -> Unit) {
        adapter?.let {
            adapterAction(it)
            (0 until grid.childCount)
                    .map { grid.getChildAt(it) as AdaptiveIconView }
                    .forEach { iconViewAction(it) }
        }
    }

    private class AdaptiveIconLoader(context: Context)
        : AsyncTaskLoader<List<AdaptiveIconDrawable>>(context) {

        private val icons = ArrayList<AdaptiveIconDrawable>()

        override fun onStartLoading() {
            if (icons.isNotEmpty()) {
                deliverResult(icons)
            } else {
                forceLoad()
            }
        }

        override fun loadInBackground(): List<AdaptiveIconDrawable>? {
            val pm = context.packageManager
            val adaptiveIcons = ArrayList<AdaptiveIconDrawable>()
            val launcherIntent = Intent().apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            pm.getInstalledApplications(0).forEach { appInfo ->
                launcherIntent.`package` = appInfo.packageName
                // only show launch-able apps
                if (pm.queryIntentActivities(launcherIntent, 0).size > 0) {
                    val icon = appInfo.loadUnbadgedIcon(pm)
                    if (icon is AdaptiveIconDrawable) {
                        adaptiveIcons += icon
                    }
                }
            }
            adaptiveIcons += context.getDrawable(R.drawable.ic_launcher_alt) as AdaptiveIconDrawable
            return adaptiveIcons
        }

        override fun deliverResult(data: List<AdaptiveIconDrawable>) {
            icons += data
            super.deliverResult(data)
        }
    }

    private class IconAdapter(
            private val adaptiveIcons: List<AdaptiveIconDrawable>,
            var iconCornerRadius: Float
    ) : RecyclerView.Adapter<IconViewHolder>() {

        var velocityX = 0f
        var velocityY = 0f
        var foregroundTranslateFactor = DEF_FOREGROUND_TRANSLATE_FACTOR
        var backgroundTranslateFactor = DEF_BACKGROUND_TRANSLATE_FACTOR
        var foregroundScaleFactor = DEF_FOREGROUND_SCALE_FACTOR
        var backgroundScaleFactor = DEF_BACKGROUND_SCALE_FACTOR

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                IconViewHolder(LayoutInflater.from(parent.context)
                        .inflate(R.layout.icon, parent, false))

        override fun onBindViewHolder(holder: IconViewHolder, position: Int) {
            holder.icon.apply {
                setIcon(adaptiveIcons[position % adaptiveIcons.size])
                cornerRadius = iconCornerRadius
                velocityX = this@IconAdapter.velocityX
                velocityY = this@IconAdapter.velocityY
                foregroundTranslateFactor = this@IconAdapter.foregroundTranslateFactor
                backgroundTranslateFactor = this@IconAdapter.backgroundTranslateFactor
                foregroundScaleFactor = this@IconAdapter.foregroundScaleFactor
                backgroundScaleFactor = this@IconAdapter.backgroundScaleFactor
            }
        }

        override fun getItemCount() = Math.max(adaptiveIcons.size, MIN_ICON_COUNT)

        companion object {
            private const val MIN_ICON_COUNT = 40
        }
    }

    private class IconViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        var icon = itemView as AdaptiveIconView
    }

    private enum class Decor(
            @DrawableRes val background: Int,
            @ColorInt val status: Int,
            val darkStatusIcons: Boolean,
            @DrawableRes val icon: Int) {

        Wallpaper(R.drawable.wallpaper, 0x99000000.toInt(), false, R.drawable.ic_wallpaper),
        Light(R.drawable.wallpaper_light, 0xb3eeeeee.toInt(), true, R.drawable.ic_light),
        Dusk(R.drawable.wallpaper_dusk, 0xb3eeeeee.toInt(), true, R.drawable.ic_dusk),
        Dark(R.drawable.wallpaper_dark, 0x99000000.toInt(), false, R.drawable.ic_dark);

        operator fun next() = values()[(ordinal + 1) % values().size]
    }

    private class CenteringDecoration(
            private val spanCount: Int,
            private val iconSize: Int
    ) : RecyclerView.ItemDecoration() {

        private val offsets = Rect()
        private var initialized = false

        override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView,
                                    state: RecyclerView.State?) {
            if (!initialized) calculateOffsets(parent)
            outRect.set(offsets)
        }

        private fun calculateOffsets(parent: View) {
            val width = parent.width - parent.paddingLeft - parent.paddingRight
            offsets.left = (width - spanCount * iconSize) / (2 * spanCount)
            offsets.right = offsets.left
            val height = parent.height - parent.paddingTop - parent.paddingBottom
            offsets.top = (height - spanCount * iconSize) / (2 * spanCount)
            offsets.bottom = offsets.top
            initialized = true
        }
    }

    private fun SeekBar.onSeek(progressChanged: (Int) -> Unit) {

        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) =
                    progressChanged(progress)
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
    }

    private operator fun VelocityTracker.plusAssign(motionEvent: MotionEvent) {
        addMovement(motionEvent)
        computeCurrentVelocity(1000)
    }

    companion object {

        private const val DEF_FOREGROUND_TRANSLATE_FACTOR = 0.1f
        private const val DEF_BACKGROUND_TRANSLATE_FACTOR = 0.08f
        private const val DEF_FOREGROUND_SCALE_FACTOR = 0.2f
        private const val DEF_BACKGROUND_SCALE_FACTOR = 0.3f

        private val ICON_CORNER_RADIUS = object : FloatProperty<MainActivity>("iconCornerRadius") {
            override fun get(activity: MainActivity) = activity.iconCornerRadius

            override fun setValue(activity: MainActivity,
                                  @FloatRange(from = 0.0) cornerRadius: Float) {
                activity.iconCornerRadius = cornerRadius
            }
        }

        private val VELOCITY_X = object : FloatPropertyCompat<MainActivity>("velocityX") {
            override fun getValue(activity: MainActivity) = activity.velocityX

            override fun setValue(activity: MainActivity, velocityX: Float) {
                activity.velocityX = velocityX
            }
        }

        private val VELOCITY_Y = object : FloatPropertyCompat<MainActivity>("velocityY") {
            override fun getValue(activity: MainActivity) = activity.velocityY

            override fun setValue(activity: MainActivity, velocityY: Float) {
                activity.velocityY = velocityY
            }
        }
    }
}
