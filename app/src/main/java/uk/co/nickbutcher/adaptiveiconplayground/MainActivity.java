/*
 * Copyright 2017 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.co.nickbutcher.adaptiveiconplayground;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Rect;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.support.animation.FloatPropertyCompat;
import android.support.animation.SpringAnimation;
import android.support.annotation.ColorInt;
import android.support.annotation.DrawableRes;
import android.support.annotation.FloatRange;
import android.support.annotation.NonNull;
import android.support.design.widget.BottomSheetBehavior;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.AsyncTaskLoader;
import android.support.v4.content.Loader;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.transition.ChangeBounds;
import android.transition.Fade;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.util.FloatProperty;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.ImageView;
import android.widget.SeekBar;

import java.util.ArrayList;
import java.util.List;

import static android.view.View.GONE;
import static android.widget.LinearLayout.HORIZONTAL;
import static android.widget.LinearLayout.VERTICAL;

public class MainActivity extends AppCompatActivity {

    private RecyclerView grid;
    private SeekBar damping, stiffness;
    private IconAdapter adapter;
    private VelocityTracker velocityTracker = VelocityTracker.obtain();
    private float[] corners;
    private int corner = 0;
    private Decor decor = Decor.Wallpaper;
    private int orientation = HORIZONTAL;

    private static final FloatProperty<MainActivity> ICON_CORNER_RADIUS
            = new FloatProperty<MainActivity>("iconCornerRadius") {
        @Override
        public void setValue(MainActivity activity, @FloatRange(from = 0f) float cornerRadius) {
            activity.setIconCornerRadius(cornerRadius);
        }

        @Override
        public Float get(MainActivity activity) {
            return activity.getIconCornerRadius();
        }
    };

    private static final FloatPropertyCompat<MainActivity> VELOCITY_X
            = new FloatPropertyCompat<MainActivity>("velocityX") {
        @Override
        public float getValue(MainActivity mainActivity) {
            return 0f;
        }

        @Override
        public void setValue(MainActivity mainActivity, float velocityX) {
            mainActivity.setVelocityX(velocityX);
        }
    };

    private static final FloatPropertyCompat<MainActivity> VELOCITY_Y
            = new FloatPropertyCompat<MainActivity>("velocityY") {
        @Override
        public float getValue(MainActivity mainActivity) {
            return 0f;
        }

        @Override
        public void setValue(MainActivity mainActivity, float velocityY) {
            mainActivity.setVelocityY(velocityY);
        }
    };

    private View.OnTouchListener gridTouch = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View view, MotionEvent motionEvent) {
            switch (motionEvent.getAction()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    velocityTracker.addMovement(motionEvent);
                    velocityTracker.computeCurrentVelocity(1000);
                    if (orientation == HORIZONTAL) {
                        setVelocityX(velocityTracker.getXVelocity());
                    } else {
                        setVelocityY(velocityTracker.getYVelocity());
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    velocityTracker.addMovement(motionEvent);
                    velocityTracker.computeCurrentVelocity(1000);
                    releaseVelocity(velocityTracker.getXVelocity(), velocityTracker.getYVelocity());
                    velocityTracker.clear();
                    break;
            }
            return false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        final Resources res = getResources();

        findViewById(android.R.id.content).setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        stiffness = findViewById(R.id.stiffness);
        damping = findViewById(R.id.damping);
        ((SeekBar) findViewById(R.id.foreground_parallax))
                .setOnSeekBarChangeListener(new SeekListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean b) {
                        setForegroundParallaxFactor(progress / 100f);
                    }
                });
        ((SeekBar) findViewById(R.id.background_parallax))
                .setOnSeekBarChangeListener(new SeekListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean b) {
                        setBackgroundParallaxFactor(progress / 100f);
                    }
                });
        ((SeekBar) findViewById(R.id.foreground_scale))
                .setOnSeekBarChangeListener(new SeekListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean b) {
                        setForegroundScaleFactor(progress / 100f);
                    }
                });
        ((SeekBar) findViewById(R.id.background_scale))
                .setOnSeekBarChangeListener(new SeekListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean b) {
                        setBackgroundScaleFactor(progress / 100f);
                    }
                });
        grid = findViewById(R.id.grid);
        grid.setHasFixedSize(true);
        grid.addItemDecoration(new CenteringDecoration(res.getInteger(R.integer.spans),
                res.getDimensionPixelSize(R.dimen.icon_size)));

        final float density = res.getDisplayMetrics().density;
        corners = new float[] { density * 36f, density * 30f, density * 16f, density * 4f };

        final Interpolator interpolator = AnimationUtils.loadInterpolator(
                this, android.R.interpolator.fast_out_slow_in);
        findViewById(R.id.mask).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                corner = ++corner % corners.length;
                ObjectAnimator animator = ObjectAnimator.ofFloat(
                        MainActivity.this,
                        ICON_CORNER_RADIUS,
                        corners[corner]);
                animator.setDuration(200L);
                animator.setInterpolator(interpolator);
                animator.start();
            }
        });

        final Reorient reorient = new Reorient(interpolator);
        findViewById(R.id.orientation).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                orientation ^= 1;
                view.animate()
                        .rotation(orientation == VERTICAL ? 90f : 0f)
                        .setDuration(160L)
                        .setInterpolator(interpolator)
                        .start();
                TransitionManager.beginDelayedTransition(grid, reorient);
                ((GridLayoutManager) grid.getLayoutManager()).setOrientation(orientation);
            }
        });

        final ImageView background = findViewById(R.id.background);
        background.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                decor = decor.next();
                grid.setBackgroundResource(decor.background);
                getWindow().setStatusBarColor(decor.status);
                background.setImageResource(decor.icon);

                if (decor.darkStatusIcons) {
                    background.setSystemUiVisibility(background.getSystemUiVisibility()
                            | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
                } else {
                    background.setSystemUiVisibility(background.getSystemUiVisibility()
                            & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
                }
            }
        });

        BottomSheetBehavior.from(findViewById(R.id.settings_sheet)).setBottomSheetCallback(
                new BottomSheetBehavior.BottomSheetCallback() {
            @Override public void onStateChanged(@NonNull View bottomSheet, int newState) { }

            @Override
            public void onSlide(@NonNull View bottomSheet, float slideOffset) {
                // make the sheet more opaque [80%–95%] as it slides up
                int alpha = 204 + (int) (38 * slideOffset);
                int color = (0xccffffff & 0x00ffffff) | (alpha << 24);
                bottomSheet.setBackgroundColor(color);
            }
        });

        getSupportLoaderManager().initLoader(0, null,
                new LoaderManager.LoaderCallbacks<List<AdaptiveIconDrawable>>() {
            @Override
            public Loader<List<AdaptiveIconDrawable>> onCreateLoader(int id, Bundle args) {
                return new AdaptiveIconLoader(MainActivity.this);
            }

            @Override
            public void onLoadFinished(Loader<List<AdaptiveIconDrawable>> loader,
                                       List<AdaptiveIconDrawable> data) {
                findViewById(R.id.loading).setVisibility(GONE);
                adapter = new IconAdapter(data, corners[0]);
                grid.setAdapter(adapter);
                grid.setOnTouchListener(gridTouch);
            }

            @Override public void onLoaderReset(Loader<List<AdaptiveIconDrawable>> loader) { }
        });
    }

    private void setIconCornerRadius(@FloatRange(from = 0f) float iconCornerRadius) {
        adapter.setIconCornerRadius(iconCornerRadius);
        for (int i = 0; i < grid.getChildCount(); i++) {
            ((AdaptiveIconView) grid.getChildAt(i)).setCornerRadius(iconCornerRadius);
        }
    }

    private float getIconCornerRadius() {
        return adapter.getIconCornerRadius();
    }

    private void setVelocityX(float velocityX) {
        adapter.setVelocityX(velocityX);
        for (int i = 0; i < grid.getChildCount(); i++) {
            AdaptiveIconView icon = (AdaptiveIconView) grid.getChildAt(i);
            icon.setVelocityX(velocityX);
        }
    }

    private void setVelocityY(float velocityY) {
        adapter.setVelocityY(velocityY);
        for (int i = 0; i < grid.getChildCount(); i++) {
            AdaptiveIconView icon = (AdaptiveIconView) grid.getChildAt(i);
            icon.setVelocityY(velocityY);
        }
    }

    private void releaseVelocity(final float velocityX, float velocityY) {
        if (velocityX != 0) {
            SpringAnimation settleX = new SpringAnimation(this, VELOCITY_X, 0f);
            settleX.getSpring().setStiffness(getStiffness());
            settleX.getSpring().setDampingRatio(getDamping());
            settleX.setStartVelocity(velocityX);
            settleX.start();
        }
        if (velocityY != 0) {
            SpringAnimation settleY = new SpringAnimation(this, VELOCITY_Y, 0f);
            settleY.getSpring().setStiffness(getStiffness());
            settleY.getSpring().setDampingRatio(getDamping());
            settleY.setStartVelocity(velocityY);
            settleY.start();
        }
    }

    private void setForegroundParallaxFactor(float foregroundParallaxFactor) {
        if (adapter == null) return;
        adapter.setForegroundParallaxFactor(foregroundParallaxFactor);
        for (int i = 0; i < grid.getChildCount(); i++) {
            AdaptiveIconView icon = (AdaptiveIconView) grid.getChildAt(i);
            icon.setForegroundTranslateFactor(foregroundParallaxFactor);
        }
    }

    private void setBackgroundParallaxFactor(float backgroundParallaxFactor) {
        if (adapter == null) return;
        adapter.setBackgroundParallaxFactor(backgroundParallaxFactor);
        for (int i = 0; i < grid.getChildCount(); i++) {
            AdaptiveIconView icon = (AdaptiveIconView) grid.getChildAt(i);
            icon.setBackgroundTranslateFactor(backgroundParallaxFactor);
        }
    }

    private void setForegroundScaleFactor(float foregroundScaleFactor) {
        if (adapter == null) return;
        adapter.setForegroundScaleFactor(foregroundScaleFactor);
        for (int i = 0; i < grid.getChildCount(); i++) {
            AdaptiveIconView icon = (AdaptiveIconView) grid.getChildAt(i);
            icon.setForegroundScaleFactor(foregroundScaleFactor);
        }
    }

    private void setBackgroundScaleFactor(float backgroundScaleFactor) {
        if (adapter == null) return;
        adapter.setBackgroundScaleFactor(backgroundScaleFactor);
        for (int i = 0; i < grid.getChildCount(); i++) {
            AdaptiveIconView icon = (AdaptiveIconView) grid.getChildAt(i);
            icon.setBackgroundScaleFactor(backgroundScaleFactor);
        }
    }

    private float getStiffness() {
        return Math.max(stiffness.getProgress(), 50f);
    }

    private float getDamping() {
        return Math.max(damping.getProgress() / 100f, 0.05f);
    }

    static class AdaptiveIconLoader extends AsyncTaskLoader<List<AdaptiveIconDrawable>> {

        private List<AdaptiveIconDrawable> icons;

        AdaptiveIconLoader(@NonNull Context context) {
            super(context);
        }

        @Override
        protected void onStartLoading() {
            if (icons != null) {
                deliverResult(icons);
            } else {
                forceLoad();
            }
        }

        @Override
        public List<AdaptiveIconDrawable> loadInBackground() {
            PackageManager pm = getContext().getPackageManager();
            List<AdaptiveIconDrawable> adaptiveIcons = new ArrayList<>();
            Intent launcherIntent = new Intent();
            launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            for (ApplicationInfo applicationInfo : pm.getInstalledApplications(0)) {
                launcherIntent.setPackage(applicationInfo.packageName);
                // only show launch-able apps
                if (pm.queryIntentActivities(launcherIntent, 0).size() == 0) continue;
                Drawable icon = applicationInfo.loadUnbadgedIcon(pm);
                if (icon instanceof AdaptiveIconDrawable) {
                    adaptiveIcons.add((AdaptiveIconDrawable) icon);
                }
            }
            return adaptiveIcons;
        }

        @Override
        public void deliverResult(List<AdaptiveIconDrawable> data) {
            icons = data;
            super.deliverResult(data);
        }
    }

    static class IconAdapter extends RecyclerView.Adapter<IconViewHolder> {

        private static final int MIN_ICON_COUNT = 40;
        private final List<AdaptiveIconDrawable> adaptiveIcons;
        private float iconCornerRadius, velocityX, velocityY;
        private float foregroundParallaxFactor = 0.1f;
        private float backgroundParallaxFactor = 0.08f;
        private float foregroundScaleFactor = 0.2f;
        private float backgroundScaleFactor = 0.3f;

        IconAdapter(List<AdaptiveIconDrawable> adaptiveIcons,
                    @FloatRange(from = 0f) float cornerRadius) {
            this.adaptiveIcons = adaptiveIcons;
            this.iconCornerRadius = cornerRadius;
        }

        @Override
        public IconViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new IconViewHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.icon, parent, false));
        }

        @Override
        public void onBindViewHolder(IconViewHolder holder, int position) {
            AdaptiveIconView icon = holder.icon;
            icon.setIcon(adaptiveIcons.get(position % adaptiveIcons.size()));
            icon.setCornerRadius(iconCornerRadius);
            icon.setVelocityX(velocityX);
            icon.setVelocityY(velocityY);
            icon.setForegroundTranslateFactor(foregroundParallaxFactor);
            icon.setBackgroundTranslateFactor(backgroundParallaxFactor);
            icon.setForegroundScaleFactor(foregroundScaleFactor);
            icon.setBackgroundScaleFactor(backgroundScaleFactor);
        }

        @Override
        public int getItemCount() {
            return adaptiveIcons.size() > MIN_ICON_COUNT ? adaptiveIcons.size() : MIN_ICON_COUNT;
        }

        float getIconCornerRadius() {
            return iconCornerRadius;
        }

        void setIconCornerRadius(float iconCornerRadius) {
            this.iconCornerRadius = iconCornerRadius;
        }

        float getVelocityX() {
            return velocityX;
        }

        void setVelocityX(float velocityX) {
            this.velocityX = velocityX;
        }

        float getVelocityY() {
            return velocityY;
        }

        void setVelocityY(float velocityY) {
            this.velocityY = velocityY;
        }

        void setForegroundParallaxFactor(float foregroundParallaxFactor) {
            this.foregroundParallaxFactor = foregroundParallaxFactor;
        }

        void setBackgroundParallaxFactor(float backgroundParallaxFactor) {
            this.backgroundParallaxFactor = backgroundParallaxFactor;
        }

        void setForegroundScaleFactor(float foregroundScaleFactor) {
            this.foregroundScaleFactor = foregroundScaleFactor;
        }

        void setBackgroundScaleFactor(float backgroundScaleFactor) {
            this.backgroundScaleFactor = backgroundScaleFactor;
        }
    }

    static class IconViewHolder extends RecyclerView.ViewHolder {
        AdaptiveIconView icon;
        IconViewHolder(View itemView) {
            super(itemView);
            icon = (AdaptiveIconView) itemView;
        }
    }

    enum Decor {
        Wallpaper(R.drawable.wallpaper, 0x99000000, false, R.drawable.ic_wallpaper),
        Light(R.drawable.wallpaper_light, 0xb3eeeeee, true, R.drawable.ic_light),
        Dusk(R.drawable.wallpaper_dusk, 0xb3eeeeee, true, R.drawable.ic_dusk),
        Dark(R.drawable.wallpaper_dark, 0x99000000, false, R.drawable.ic_dark);

        final @DrawableRes int background;
        final @ColorInt int status;
        final boolean darkStatusIcons;
        final @DrawableRes int icon;

        Decor(@DrawableRes int background, @ColorInt int status, boolean darkStatusIcons,
              @DrawableRes  int icon) {
            this.background = background;
            this.status = status;
            this.darkStatusIcons = darkStatusIcons;
            this.icon = icon;
        }

        Decor next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    static class Reorient extends TransitionSet {
        Reorient(Interpolator interpolator) {
            setOrdering(ORDERING_TOGETHER);
            addTransition(new Fade(Fade.OUT));
            addTransition(new ChangeBounds());
            addTransition(new Fade(Fade.IN));
            setDuration(200L);
            setInterpolator(interpolator);
        }
    }

     static class CenteringDecoration extends RecyclerView.ItemDecoration {

        private final int spanCount, iconSize;
        private int left, top, right, bottom;
        private boolean initialized = false;

        CenteringDecoration(int spanCount, int iconSize) {
            this.spanCount = spanCount;
            this.iconSize = iconSize;
        }

        @Override
        public void getItemOffsets(Rect outRect, View view, RecyclerView parent,
                                   RecyclerView.State state) {
            if (!initialized) calculateOffsets(parent);
            outRect.set(left, top, right, bottom);
        }

        private void calculateOffsets(View parent) {
            int width = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
            left = right = (width - (spanCount * iconSize)) / (2 * spanCount);
            int height = parent.getHeight() - parent.getPaddingTop() - parent.getPaddingBottom();
            top = bottom = (height - (spanCount * iconSize)) / (2 * spanCount);
            initialized = true;
        }
    }

    static abstract class SeekListener implements SeekBar.OnSeekBarChangeListener {

        @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { }

        @Override public void onStartTrackingTouch(SeekBar seekBar) { }

        @Override public void onStopTrackingTouch(SeekBar seekBar) { }
    }
}
