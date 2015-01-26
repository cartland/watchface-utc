/*
 * Copyright 2015 Chris Cartland. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.chriscartland.wearable.watchface;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import java.util.TimeZone;

/**
 * Watch face with an analog representation of UTC time and the local timezone.
 * On devices with low-bit ambient mode, the hands are drawn without anti-aliasing in ambient
 * mode. The watch face is drawn with less contrast in mute mode.
 *
 */
public class UTCWatchFaceService extends CanvasWatchFaceService {
    private static final String TAG = "UTCWatchFaceService";

    /**
     * Update rate in milliseconds for interactive mode. We update once a second to advance the
     * second hand.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = 50; // 20 fps

    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine {

        private static final int MSG_UPDATE_TIME = 0;

        private static final int ANIMATION_PIXELS_PER_SECOND = 200;

        private Paint mHourPaint;
        private Paint mHandPaint;
        private Paint mUTCLabelPaint;
        private Paint mCurrentHourPaint;

        private boolean mMute;
        private Time mTime;

        private float mWatchHeight;
        private long mLastUpdate = -1;
        private final Rect mCardBounds = new Rect();
        private float mLastDesiredHeight;
        private long mTimeOfDesiredHeightChanged;

        /** Handler to update the time once a second in interactive mode. */
        final Handler mUpdateTimeHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MSG_UPDATE_TIME:
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.v(TAG, "updating time");
                        }
                        invalidate();
                        if (shouldTimerBeRunning()) {
                            long timeMs = System.currentTimeMillis();
                            long delayMs = INTERACTIVE_UPDATE_RATE_MS
                                    - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                            mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
                        }
                        break;
                }
            }
        };

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };
        boolean mRegisteredTimeZoneReceiver = false;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        private boolean mIsRound;

        @Override
        public void onCreate(SurfaceHolder holder) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onCreate");
            }
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(UTCWatchFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());

            Resources resources = UTCWatchFaceService.this.getResources();

            mHourPaint = createTextPaint(resources.getColor(R.color.hour_default), BOLD_TYPEFACE);
            mHourPaint.setTextAlign(Paint.Align.CENTER);

            mHandPaint = new Paint();
            mHandPaint.setStyle(Paint.Style.STROKE);
            mHandPaint.setStrokeCap(Paint.Cap.ROUND);
            mHandPaint.setColor(resources.getColor(R.color.hand_color));
            mHandPaint.setStrokeWidth(4.f);

            mUTCLabelPaint = createTextPaint(resources.getColor(R.color.utc_label));

            mCurrentHourPaint = new Paint();
            mCurrentHourPaint.setColor(resources.getColor(R.color.current_hour));
            mCurrentHourPaint.setStrokeWidth(3f);
            mCurrentHourPaint.setStyle(Paint.Style.STROKE);
            mCurrentHourPaint.setStrokeCap(Paint.Cap.ROUND);

            mTime = new Time();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int defaultInteractiveColor) {
            return createTextPaint(defaultInteractiveColor, NORMAL_TYPEFACE);
        }

        private Paint createTextPaint(int defaultInteractiveColor, Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(defaultInteractiveColor);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onPropertiesChanged: low-bit ambient = " + mLowBitAmbient);
            }
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onTimeTick: ambient = " + isInAmbientMode());
            }
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onAmbientModeChanged: " + inAmbientMode);
            }
            if (mLowBitAmbient) {
                boolean antiAlias = !inAmbientMode;
                mHourPaint.setAntiAlias(antiAlias);
                mHandPaint.setAntiAlias(antiAlias);
                mCurrentHourPaint.setAntiAlias(antiAlias);
                mUTCLabelPaint.setAntiAlias(antiAlias);
            }

            if (inAmbientMode) {
                mHandPaint.setColor(getResources().getColor(R.color.white));
                mCurrentHourPaint.setColor(getResources().getColor(R.color.white));
            } else {
                mHandPaint.setColor(getResources().getColor(R.color.hand_color));
                mCurrentHourPaint.setColor(getResources().getColor(R.color.current_hour));
            }

            invalidate();

            // Whether the timer should be running depends on whether we're in ambient mode (as well
            // as whether we're visible), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onInterruptionFilterChanged(int interruptionFilter) {
            super.onInterruptionFilterChanged(interruptionFilter);
            boolean inMuteMode = (interruptionFilter == WatchFaceService.INTERRUPTION_FILTER_NONE);
            if (mMute != inMuteMode) {
                mMute = inMuteMode;
                mHourPaint.setAlpha(inMuteMode ? 100 : 255);
                mHandPaint.setAlpha(inMuteMode ? 100 : 255);
                mCurrentHourPaint.setAlpha(inMuteMode ? 80 : 255);
                mUTCLabelPaint.setAlpha(inMuteMode ? 80 : 180);
                invalidate();
            }
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            long now = System.currentTimeMillis();
            mTime.set(now);

            int width = bounds.width();
            int boundsHeight = bounds.height();

            drawBackground(canvas, width, boundsHeight);

            int desiredHeight = calculateDesiredHeight(boundsHeight);

            updateWatchHeight(desiredHeight, now);

            // Find the center. Ignore the window insets so that, on round watches with a
            // "chin", the watch face is centered on the entire screen, not just the usable
            // portion.
            float centerX = width / 2f;
            float centerY = mWatchHeight / 2f;

            float radius = Math.min(centerX, centerY);

            // Draw the minutes.
            drawMinuteLine(canvas, mTime.minute, mCurrentHourPaint, radius, centerX, centerY,
                    mHourPaint);

            float hourFloat = (float) mTime.hour + (float) mTime.minute / 60f;
            drawHourLine(canvas, hourFloat, mHandPaint, radius, centerX, centerY,
                    mHourPaint);

            // Calculate GMT hour.
            String zone = mTime.timezone;
            String gmtZone = "GMT";
            mTime.switchTimezone(gmtZone);
            int gmtHour = mTime.hour;
            mTime.switchTimezone(zone);

            // Draw the hours in a spiral.
            for (int hour = 0; hour < 24; hour++) {
                if (hour == mTime.hour || hour == gmtHour) {
                    float outerTextBuffer = getTextHeight(Integer.toString(hour), mHourPaint);
                    float hourRadius = calculateHourRadius(radius, hour, outerTextBuffer);

                    // Bigger.
                    float size = mHourPaint.getTextSize();
                    float bigHourTextSize = getResources().getDimension(mIsRound
                            ? R.dimen.utc_big_hour_text_size_round : R.dimen.utc_big_hour_text_size);
                    mHourPaint.setTextSize(bigHourTextSize);

                    // Color.
                    if (hour == mTime.hour) {
                        int color = mHourPaint.getColor();
                        if (isInAmbientMode()) {
                            mHourPaint.setColor(getResources().getColor(R.color.white));
                        } else {
                            mHourPaint.setColor(getResources().getColor(R.color.current_hour));
                        }
                        drawHour(canvas, hour, mHourPaint, hourRadius, centerX, centerY);
                        mHourPaint.setColor(color);
                    } else if (hour == gmtHour) {
                        int color = mHourPaint.getColor();
                        if (isInAmbientMode()) {
                            mHourPaint.setColor(getResources().getColor(R.color.white));
                        } else {
                            mHourPaint.setColor(getResources().getColor(R.color.gmt_hour));
                        }
                        drawHour(canvas, hour, mHourPaint, hourRadius, centerX, centerY);
                        mHourPaint.setColor(color);
                    } else {
                        drawHour(canvas, hour, mHourPaint, hourRadius, centerX, centerY);
                    }
                    mHourPaint.setTextSize(size);
                } else {
                    float textHeight = getTextHeight(Integer.toString(hour), mHourPaint);
                    float hourRadius = calculateHourRadius(radius, hour, textHeight);
                    drawHour(canvas, hour, mHourPaint, hourRadius, centerX, centerY);
                }
            }

            // Draw the UTC diff.
            TimeZone tz = TimeZone.getTimeZone(mTime.timezone);
            long milliDiff = tz.getOffset(mTime.toMillis(false));
            float hourDiff = milliDiff / 1000f / 60f / 60f;

            String hourDiffString = formatUTCDiff(hourDiff);
            float hourDiffWidth = mUTCLabelPaint.measureText(hourDiffString);

            if (mIsRound) {
                mUTCLabelPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(hourDiffString, centerX, centerY, mUTCLabelPaint);
            } else {
                float x = bounds.right - (hourDiffWidth) - 20;
                canvas.drawText(hourDiffString, x, 30, mUTCLabelPaint);
            }
        }

        private void drawBackground(Canvas canvas, int width, int height) {
            canvas.drawColor(getResources().getColor(R.color.background));
        }

        private void drawTextCenterVertical(Canvas canvas, String text, float x, float y, Paint paint) {
            Rect bounds = new Rect();
            paint.getTextBounds(text, 0, text.length(), bounds);
            float startY = y + bounds.height() / 2;
            canvas.drawText(text, x, startY, paint);
        }

        private int calculateDesiredHeight(int max) {
            if (mCardBounds != null && mCardBounds.top > 0) {
                return Math.min(mCardBounds.top, max);
            } else {
                return max;
            }
        }

        private void updateWatchHeight(int desiredHeight, long now) {
            if (desiredHeight != mLastDesiredHeight) {
                mTimeOfDesiredHeightChanged = now;
            }
            if (isInAmbientMode()) {
                mWatchHeight = desiredHeight;
            } else if (now - mTimeOfDesiredHeightChanged > 200) {
                // Animate watch changing size.
                if (mLastUpdate < 0) {
                    mWatchHeight = desiredHeight;
                } else if (desiredHeight != (int) mWatchHeight) {
                    float elapseMs = now - mLastUpdate;
                    int diff = desiredHeight - (int) mWatchHeight;
                    float velocityPx = ANIMATION_PIXELS_PER_SECOND * elapseMs / 1000;
                    if (diff > 0) {
                        velocityPx *= 1.5f;
                    }
                    if (Math.abs(diff) <= velocityPx) {
                        mWatchHeight = desiredHeight;
                    } else if (diff > 0) {
                        mWatchHeight += velocityPx * 1.5;
                    } else if (diff < 0) {
                        mWatchHeight -= velocityPx;
                    } else {
                        mWatchHeight = desiredHeight;
                    }
                }
            }
            mLastDesiredHeight = desiredHeight;
            mWatchHeight = Math.max(1, mWatchHeight);
            mLastUpdate = now;
        }

        private void drawMinuteLine(Canvas canvas, float minute, Paint paint, float radius,
                                    float centerX, float centerY, Paint textPaint) {
            String text = "24";

            float textHeight = getTextHeight(text, textPaint);
            float rot = calculateMinuteRot(minute);
            float minuteRadius = calculateMinuteArmRadius(radius, textHeight);
            float offsetX = calculateXComponent(rot, minuteRadius);
            float offsetY = calculateYComponent(rot, minuteRadius);

            float endX = centerX + offsetX;
            float endY = centerY + offsetY;
            canvas.drawLine(centerX, centerY, endX, endY, paint);
        }

        private void drawHourLine(Canvas canvas, float hour, Paint paint, float radius,
                                    float centerX, float centerY, Paint textPaint) {
            String text = "24";

            float textHeight = getTextHeight(text, textPaint);
            float rot = calculateHourRot(hour);
            float minuteRadius = calculateHourArmRadius(radius, textHeight);
            float offsetX = calculateXComponent(rot, minuteRadius);
            float offsetY = calculateYComponent(rot, minuteRadius);

            float endX = centerX + offsetX;
            float endY = centerY + offsetY;
            canvas.drawLine(centerX, centerY, endX, endY, paint);
        }

        private void drawHour(Canvas canvas, float hour, Paint paint, float hourRadius,
                              float centerX, float centerY) {
            String text = Integer.toString((int)hour);

            float rot = calculateHourRot(hour);
            float offsetX = calculateXComponent(rot, hourRadius);
            float offsetY = calculateYComponent(rot, hourRadius);

            float textCenterX = centerX + offsetX;
            float textCenterY = centerY + offsetY;
            drawTextCenterVertical(canvas, text, textCenterX, textCenterY, paint);
        }

        private float calculateXComponent(float rot, float radius) {
            return (float) Math.sin(rot) * radius;
        }

        private float calculateYComponent(float rot, float radius) {
            return (float) -Math.cos(rot) * radius;
        }

        private float getTextHeight(String t, Paint paint) {
            Rect textBounds = new Rect();
            paint.getTextBounds(t, 0, 1, textBounds);
            return (float)textBounds.height();
        }

        private float calculateHourRot(float hour) {
            return (float) (hour * Math.PI * 2 / 12);
        }

        private float calculateMinuteRot(float min) {
            return (float) (min * Math.PI * 2 / 60);
        }

        private float calculateHourRadius(float radius, float hour, float textHeight) {
            float inset = (hour > 11) ? (textHeight * 3f) : 0;
            return radius - 10 - textHeight - inset;
        }

        private float calculateMinuteArmRadius(float radius, float textHeight) {
            float inset = textHeight * 5f;
            return radius - 10 - textHeight - inset;
        }

        private float calculateHourArmRadius(float radius, float textHeight) {
            return calculateMinuteArmRadius(radius, textHeight) / 2;
        }

        private String formatUTCDiff(float hour) {
            int truncateAfter = (int) (hour * 2);
            int truncateBefore = ((int) hour) * 2;

            if (truncateAfter == truncateBefore) {
                if (hour >= 0) {
                    return "UTC+" + Math.abs((int)hour);
                } else {
                    return "UTC-" + Math.abs((int)hour);
                }
            } else {
                if (hour >= 0) { // must check float, not int
                    return "UTC+" + String.format("%d.5", Math.abs((int)hour));
                } else {
                    return "UTC-" + String.format("%d.5", Math.abs((int)hour));
                }
            }
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onVisibilityChanged: " + visible);
            }

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            UTCWatchFaceService.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            UTCWatchFaceService.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onApplyWindowInsets: " + (insets.isRound() ? "round" : "square"));
            }
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = UTCWatchFaceService.this.getResources();
            mIsRound = insets.isRound();
            float hourTextSize = resources.getDimension(mIsRound
                    ? R.dimen.utc_hour_text_size_round : R.dimen.utc_hour_text_size);

            mHourPaint.setTextSize(hourTextSize);
            mUTCLabelPaint.setTextSize(hourTextSize);
        }

        @Override
        public void onPeekCardPositionUpdate(Rect bounds) {
            super.onPeekCardPositionUpdate(bounds);
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onPeekCardPositionUpdate: " + bounds);
            }
            super.onPeekCardPositionUpdate(bounds);
            if (!bounds.equals(mCardBounds)) {
                mCardBounds.set(bounds);
                invalidate();
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "updateTimer");
            }
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

    }
}
