package com.joanzapata.tilesview;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.ColorDrawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Interpolator;
import com.joanzapata.tilesview.internal.Tile;
import com.joanzapata.tilesview.internal.TilePool;
import com.joanzapata.tilesview.util.ScrollAndZoomDetector;

import java.util.ArrayList;
import java.util.List;

public class TilesView extends View implements ScrollAndZoomDetector.ScrollAndZoomListener, TilePool.TilePoolListener {

    public static final int TILE_SIZE = 256;
    // Zoom level starts at 10, must be 10 plus a power of 2
    private static final int MAX_ZOOM_LEVEL = 10 + (int) Math.pow(2, 8);
    private static final int MIN_ZOOM_LEVEL = 5;
    private static final int DOUBLE_TAP_DURATION = 400;
    private static final int ANIMATE_TO_DURATION = 800;
    private static final Interpolator DOUBLE_TAP_INTERPOLATOR = new AccelerateDecelerateInterpolator();
    private static final long SCALE_ADJUSTMENT_DURATION = 200;
    public static final int SCALE_TYPE_FLOOR = 1;
    public static final int SCALE_TYPE_CEIL = 2;
    public static final int SCALE_TYPE_ROUND = 3;

    /**
     * 5-9 = content is smaller than the screen
     * 10 = fit the screen
     * > 10 = zoomed in, should be a power of two
     */
    private int userMinZoomLevel = 5, userMaxZoomLevel = (int) Math.pow(2, 8);

    /**
     * Add padding to the content.
     */
    private int contentPaddingLeft, contentPaddingTop, contentPaddingRight, contentPaddingBottom;

    /** X and Y offset of the top left corner of the screen in the global image */
    private float offsetX, offsetY;

    /** Initial scale is 1, scale can't be < 1 */
    private float scale;

    /** Zoom level is scale * 10 rounded to the nearest integer (e.g. 12 for x1,23) */
    private int zoomLevel, zoomLevelWithUserBounds;

    /** Retains all tiles in memory */
    private final TilePool tilePool;

    private final Paint debugPaint;
    private final Paint backgroundPaint;

    private ScrollAndZoomDetector scrollAndZoomDetector;

    private OnContentTappedListener onContentTappedListener;

    private RectF reusableRectF = new RectF();
    private Rect reusableRect = new Rect();
    private List<Layer> layers;
    private boolean debug = false;
    private ValueAnimator currentAnimator;
    private OnZoomLevelChangedListener onZoomLevelChangedListener;

    public TilesView(Context context, AttributeSet attrs) {
        super(context, attrs);

        int backgroundColor = Color.BLACK;
        if (getBackground() instanceof ColorDrawable)
            backgroundColor = ((ColorDrawable) getBackground()).getColor();
        this.tilePool = new TilePool(backgroundColor, this);
        this.layers = new ArrayList<Layer>();
        this.contentPaddingLeft = 0;
        this.contentPaddingTop = 0;
        this.contentPaddingRight = 0;
        this.contentPaddingBottom = 0;
        this.scrollAndZoomDetector = new ScrollAndZoomDetector(context, this, this);

        clear();

        debugPaint = new Paint();
        debugPaint.setAntiAlias(true);
        debugPaint.setColor(Color.GRAY);
        debugPaint.setTextSize(40);
        debugPaint.setTextAlign(Paint.Align.CENTER);
        debugPaint.setStyle(Paint.Style.STROKE);
        backgroundPaint = new Paint();
        backgroundPaint.setColor(backgroundColor);
        backgroundPaint.setStyle(Paint.Style.FILL);
        setBackground(null);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        clear();
    }

    public TilesView clear() {
        if (currentAnimator != null) currentAnimator.cancel();
        layers.clear();
        tilePool.setTileRenderer(null, true);
        scale = 1f;
        zoomLevelWithUserBounds = 10;
        zoomLevel = zoomLevelForScale(scale, SCALE_TYPE_ROUND);
        offsetX = -getPaddingLeft() - getContentPaddingLeft();
        offsetY = -getPaddingTop() - getContentPaddingTop();
        postInvalidate();
        return this;
    }

    public TilesView addLayer(Layer layer) {
        layers.add(layer);
        return this;
    }

    public TilesView setDebug(boolean debug) {
        this.debug = debug;
        invalidate();
        return this;
    }

    public TilesView setMinZoomLevel(int minZoomLevel) {
        if (minZoomLevel > 10) {
            minZoomLevel = 10 + (int) Math.round(Math.pow(2, (minZoomLevel - 10)));
        }
        this.userMinZoomLevel = minZoomLevel;
        if (isSized()) {
            applyScaleBounds();
        }
        return this;
    }

    public TilesView setMaxZoomLevel(int maxZoomLevel) {
        if (maxZoomLevel > 10) {
            maxZoomLevel = 10 + (int) Math.round(Math.pow(2, (maxZoomLevel - 10)));
        }
        this.userMaxZoomLevel = maxZoomLevel;
        if (isSized()) {
            applyScaleBounds();
        }
        return this;
    }

    public TilesView setOnZoomLevelChangedListener(OnZoomLevelChangedListener onZoomLevelChangedListener) {
        this.onZoomLevelChangedListener = onZoomLevelChangedListener;
        return this;
    }

    public TilesView setOnContentTappedListener(OnContentTappedListener onContentTappedListener) {
        this.onContentTappedListener = onContentTappedListener;
        return this;
    }

    public TilesView setContentPadding(int left, int top, int right, int bottom) {
        this.contentPaddingLeft = left;
        this.contentPaddingTop = top;
        this.contentPaddingRight = right;
        this.contentPaddingBottom = bottom;
        this.offsetX = -getPaddingLeft() - getContentPaddingLeft();
        this.offsetY = -getPaddingTop() - getContentPaddingTop();
        return this;
    }

    public int getMinZoomLevel() {
        int minZoomLevel = this.userMinZoomLevel;
        if (minZoomLevel > 10)
            minZoomLevel = (int) (10 + Math.log(minZoomLevel - 10) / Math.log(2));
        return minZoomLevel;
    }

    public int getMaxZoomLevel() {
        int maxZoomLevel = this.userMaxZoomLevel;
        if (maxZoomLevel > 10)
            maxZoomLevel = (int) (10 + Math.log(maxZoomLevel - 10) / Math.log(2));
        return maxZoomLevel;
    }

    public int getZoomLevel() {
        int zoomLevel = this.zoomLevelWithUserBounds;
        if (zoomLevel > 10)
            zoomLevel = (int) (10 + Math.log(zoomLevel - 10) / Math.log(2));
        return zoomLevel;
    }

    public int getContentPaddingLeft() {
        return contentPaddingLeft;
    }

    public int getContentPaddingTop() {
        return contentPaddingTop;
    }

    public int getContentPaddingRight() {
        return contentPaddingRight;
    }

    public int getContentPaddingBottom() {
        return contentPaddingBottom;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float contentWidth = getContentWidth();
        float contentHeight = getContentHeight();

        canvas.drawRect(0, 0, getPaddingLeft(), getHeight() - getPaddingBottom(), backgroundPaint);
        canvas.drawRect(getPaddingLeft(), 0, getWidth(), getPaddingTop(), backgroundPaint);
        canvas.drawRect(getWidth() - getPaddingRight(), getPaddingTop(), getWidth(), getHeight(), backgroundPaint);
        canvas.drawRect(0, getHeight() - getPaddingBottom(), getWidth() - getPaddingRight(), getHeight(), backgroundPaint);

        canvas.clipRect(getPaddingLeft(), getPaddingTop(), getWidth() - getPaddingRight(), getHeight() - getPaddingBottom());

        canvas.save();
        canvas.translate((int) -offsetX, (int) -offsetY);

        // Retrieve a placeholder for tiles not yet rendered
        Bitmap placeholder = tilePool.getPlaceholder(contentWidth, contentHeight);
        float placeholderRatio = 0f;
        if (placeholder != null) {
            placeholderRatio = contentWidth / placeholder.getWidth();
        }

        // Find the top left index for the current scale and canvas size
        float zoomDiff = scale / (zoomLevel / 10f);
        float xOffsetOnContent = offsetX / scale;
        float yOffsetOnContent = offsetY / scale;
        float screenWidthOnContent = getWidth() / scale;
        float screenHeightOnContent = getHeight() / scale;
        float tileSizeOnContent = TILE_SIZE / (zoomLevel / 10f);
        int xIndexStart = (int) (xOffsetOnContent / tileSizeOnContent);
        int yIndexStart = (int) (yOffsetOnContent / tileSizeOnContent);
        int xIndexStop = (int) ((xOffsetOnContent + screenWidthOnContent) / tileSizeOnContent);
        int yIndexStop = (int) ((yOffsetOnContent + screenHeightOnContent) / tileSizeOnContent);

        // Adjustments for edge cases
        if (xOffsetOnContent < 0) xIndexStart--;
        if (yOffsetOnContent < 0) yIndexStart--;

        int xGridIndexStart = Math.max(0, xIndexStart);
        int yGridIndexStart = Math.max(0, yIndexStart);
        int xGridIndexStop = Math.min(xIndexStop, (int) Math.floor(contentWidth / tileSizeOnContent));
        int yGridIndexStop = Math.min(yIndexStop, (int) Math.floor(contentHeight / tileSizeOnContent));

        /*
         * Loop through the 2D grid. This loop is a little complex
         * because it starts at the top left corner and reach the
         * center in a spiral like movement. See https://goo.gl/r7As7V
         */
        int xIndex = xIndexStart;
        int yIndex = yIndexStart;
        while (xIndex <= xIndexStop || yIndex <= yIndexStop) {

            while (xIndex <= xIndexStop && xIndex >= xIndexStart) {

                drawTile(xIndex, yIndex, canvas,
                        placeholder, zoomDiff, placeholderRatio,
                        xGridIndexStart, xGridIndexStop,
                        yGridIndexStart, yGridIndexStop,
                        contentWidth, contentHeight);

                xIndex++;
            }
            xIndex = xIndexStop;
            yIndex++;
            yIndexStart++;
            if (xIndexStart > xIndexStop || yIndexStart > yIndexStop) break;

            while (yIndex <= yIndexStop && yIndex >= yIndexStart) {

                drawTile(xIndex, yIndex, canvas,
                        placeholder, zoomDiff, placeholderRatio,
                        xGridIndexStart, xGridIndexStop,
                        yGridIndexStart, yGridIndexStop,
                        contentWidth, contentHeight);

                yIndex++;
            }
            yIndex = yIndexStop;
            xIndexStop--;
            xIndex--;
            if (xIndexStart > xIndexStop || yIndexStart > yIndexStop) break;

            while (xIndex <= xIndexStop && xIndex >= xIndexStart) {

                drawTile(xIndex, yIndex, canvas,
                        placeholder, zoomDiff, placeholderRatio,
                        xGridIndexStart, xGridIndexStop,
                        yGridIndexStart, yGridIndexStop,
                        contentWidth, contentHeight);

                xIndex--;
            }
            xIndex = xIndexStart;
            yIndex--;
            yIndexStop--;
            if (xIndexStart > xIndexStop || yIndexStart > yIndexStop) break;

            while (yIndex <= yIndexStop && yIndex >= yIndexStart) {

                drawTile(xIndex, yIndex, canvas,
                        placeholder, zoomDiff, placeholderRatio,
                        xGridIndexStart, xGridIndexStop,
                        yGridIndexStart, yGridIndexStop,
                        contentWidth, contentHeight);

                yIndex--;
            }
            yIndex = yIndexStart;
            xIndexStart++;
            xIndex++;
            if (xIndexStart > xIndexStop || yIndexStart > yIndexStop) break;

        }

        // Render user layers
        for (int i = 0, size = layers.size(); i < size; i++) {
            Layer layer = layers.get(i);
            canvas.save();
            layer.renderLayer(canvas, scale, contentWidth, contentHeight);
            canvas.restore();
        }

        canvas.restore();

    }

    private void drawTile(
            int xIndex, int yIndex,
            Canvas canvas, Bitmap placeholder,
            float zoomDiff, float placeholderRatio,
            int xGridIndexStart, int xGridIndexStop,
            int yGridIndexStart, int yGridIndexStop,
            float contentWidth, float contentHeight) {

        // Compute the current tile position on canvas
        float spread = zoomDiff != 1f ? +1f : 0f;
        float left = xIndex * (float) TILE_SIZE * zoomDiff;
        float top = yIndex * (float) TILE_SIZE * zoomDiff;
        float right = left + TILE_SIZE * zoomDiff + spread;
        float bottom = top + TILE_SIZE * zoomDiff + spread;

        // If this tile is not outside the user content
        if (xIndex >= xGridIndexStart && xIndex <= xGridIndexStop &&
                yIndex >= yGridIndexStart && yIndex <= yGridIndexStop) {

            // Request the tile
            Bitmap tile = tilePool.getTile(zoomLevel, xIndex, yIndex, contentWidth, contentHeight);

            if (tile != null && !tile.isRecycled()) {
                // Draw the tile if any
                reusableRectF.set(left, top, right, bottom);
                canvas.drawBitmap(tile, null, reusableRectF, backgroundPaint);

            } else if (placeholder != null && xIndex >= 0 && yIndex >= 0) {
                // Draw the placeholder if any
                reusableRectF.set(left, top, right, bottom);
                float placeholderTileSize = TILE_SIZE / placeholderRatio / scale * zoomDiff;
                reusableRect.set(
                        (int) (xIndex * placeholderTileSize),
                        (int) (yIndex * placeholderTileSize),
                        (int) ((xIndex + 1f) * placeholderTileSize),
                        (int) ((yIndex + 1f) * placeholderTileSize));

                if (reusableRect.right > placeholder.getWidth()) {
                    float rightOffsetOnPlaceholderTile = reusableRect.right - placeholder.getWidth();
                    float rightOffset = rightOffsetOnPlaceholderTile * (TILE_SIZE * zoomDiff) / placeholderTileSize;
                    canvas.drawRect(
                            reusableRectF.right - rightOffset - 1, reusableRectF.top,
                            reusableRectF.right, reusableRectF.bottom,
                            backgroundPaint);
                    reusableRectF.right -= rightOffset;
                    reusableRect.right = placeholder.getWidth();
                }

                if (reusableRect.bottom > placeholder.getHeight()) {
                    float bottomOffsetOnPlaceholderTile = reusableRect.bottom - placeholder.getHeight();
                    float bottomOffset = bottomOffsetOnPlaceholderTile * (TILE_SIZE * zoomDiff) / placeholderTileSize;
                    canvas.drawRect(
                            reusableRectF.left, reusableRectF.bottom - bottomOffset - 1,
                            reusableRectF.right, reusableRectF.bottom,
                            backgroundPaint);
                    reusableRectF.bottom -= bottomOffset;
                    reusableRect.bottom = placeholder.getHeight();
                }

                canvas.drawBitmap(placeholder, reusableRect, reusableRectF, null);

            } else {
                // Draw the background otherwise
                canvas.drawRect(left, top, right, bottom, backgroundPaint);
            }

            if (debug) {
                int lineSize = 20;
                canvas.drawLine(left, top, left + lineSize, top, debugPaint);
                canvas.drawLine(left, top, left, top + lineSize, debugPaint);
                canvas.drawLine(left, bottom - lineSize, left, bottom, debugPaint);
                canvas.drawLine(left, bottom, left + lineSize, bottom, debugPaint);
                canvas.drawLine(right - lineSize, top, right, top, debugPaint);
                canvas.drawLine(right, top, right, top + lineSize, debugPaint);
                canvas.drawLine(right - lineSize, bottom, right, bottom, debugPaint);
                canvas.drawLine(right, bottom - lineSize, right, bottom, debugPaint);
                canvas.drawText(xIndex + "," + yIndex,
                        (left + right) / 2f,
                        (top + bottom) / 2f + debugPaint.getTextSize() / 4,
                        debugPaint);
                canvas.drawText(zoomLevel + "",
                        right - 30,
                        top + debugPaint.getTextSize() + 5,
                        debugPaint);
            }

        } else {

            // If the current tile is outside user content, draw placeholder
            canvas.drawRect(left, top, right, bottom, backgroundPaint);

        }
    }

    public TilesView setTileRenderer(TileRenderer tileRenderer) {
        return setTileRenderer(tileRenderer, true);
    }

    public TilesView setTileRenderer(TileRenderer tileRenderer, boolean threadSafe) {
        tilePool.setTileRenderer(tileRenderer, threadSafe);
        postInvalidate();
        return this;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        double tilesOnWidth = Math.ceil(w / (TILE_SIZE * 0.9f)) + 1;
        double tilesOnHeight = Math.ceil(h / (TILE_SIZE * 0.9f)) + 1;
        int maxTilesOnScreen = (int) (tilesOnWidth * tilesOnHeight);
        tilePool.setMaxTasks(maxTilesOnScreen);
        applyScaleBounds();
    }

    private void applyScaleBounds() {
        onScaleEnd(getWidth() / 2f, getHeight() / 2f, 1f);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return scrollAndZoomDetector.onTouchEvent(event);
    }

    @Override
    public void onDown() {
        if (currentAnimator != null)
            currentAnimator.cancel();
    }

    @Override
    public boolean onScroll(float distanceX, float distanceY) {
        offsetX += distanceX;
        offsetY += distanceY;

        float minOffsetX;
        float minOffsetY;
        float maxOffsetX;
        float maxOffsetY;

        float contentWidth = getContentWidth();
        float contentHeight = getContentHeight();

        if (scale >= 1) {
            minOffsetX = -getPaddingLeft() - getContentPaddingLeft();
            minOffsetY = -getPaddingTop() - getContentPaddingTop();
            maxOffsetX = contentWidth * scale + getPaddingRight() + getContentPaddingRight() - getWidth();
            maxOffsetY = contentHeight * scale + getPaddingBottom() + getContentPaddingBottom() - getHeight();
        } else {
            minOffsetX = contentWidth * scale + getPaddingRight() + getContentPaddingRight() - getWidth();
            minOffsetY = contentHeight * scale + getPaddingBottom() + getContentPaddingBottom() - getHeight();
            maxOffsetX = -getPaddingLeft() - getContentPaddingLeft();
            maxOffsetY = -getPaddingTop() - getContentPaddingTop();
        }

        offsetX = Math.min(Math.max(offsetX, minOffsetX), maxOffsetX);
        offsetY = Math.min(Math.max(offsetY, minOffsetY), maxOffsetY);

        invalidate();
        return true;
    }

    @Override
    public boolean onScale(float scaleFactor, float focusX, float focusY) {

        // Move offsets so that the focus point remains the same
        float newScale = scale * scaleFactor;
        float contentWidthBefore = getWidth() * scale;
        float contentWidthAfter = getWidth() * newScale;
        float contentFocusXBefore = offsetX + focusX;
        float contentFocusXBeforeRatio = contentFocusXBefore / contentWidthBefore;
        float contentFocusXAfter = contentFocusXBeforeRatio * contentWidthAfter;
        float contentHeightBefore = getHeight() * scale;
        float contentHeightAfter = getHeight() * newScale;
        float contentFocusYBefore = offsetY + focusY;
        float contentFocusYBeforeRatio = contentFocusYBefore / contentHeightBefore;
        float contentFocusYAfter = contentFocusYBeforeRatio * contentHeightAfter;

        scale = newScale;
        int newZoomLevelWithoutBounds = zoomLevelForScale(scale, SCALE_TYPE_ROUND);
        int newZoomLevelWithUserBounds = Math.max(Math.min(newZoomLevelWithoutBounds, userMaxZoomLevel), userMinZoomLevel);
        if (zoomLevelWithUserBounds != newZoomLevelWithUserBounds) {
            zoomLevelWithUserBounds = newZoomLevelWithUserBounds;

            if (onZoomLevelChangedListener != null) {
                onZoomLevelChangedListener.onZoomLevelChanged(getZoomLevel());
            }
        }

        onScroll(contentFocusXAfter - contentFocusXBefore, contentFocusYAfter - contentFocusYBefore);
        zoomLevel = Math.min(MAX_ZOOM_LEVEL, Math.max(MIN_ZOOM_LEVEL, newZoomLevelWithoutBounds));
        invalidate();
        return true;
    }

    @Override
    public boolean onDoubleTap(final float focusX, final float focusY) {
        animateScaleTo(zoomLevelForScale(scale * 2f, SCALE_TYPE_ROUND) / 10f, focusX, focusY, DOUBLE_TAP_DURATION);
        return true;
    }

    public void animateTo(final float x, final float y, int zoomLevel) {
        if (currentAnimator != null) currentAnimator.cancel();

        if (zoomLevel > 10)
            zoomLevel = (int) (10 + Math.pow(2, zoomLevel - 10));

        currentAnimator = ValueAnimator.ofFloat(0f, 1f);
        currentAnimator.setDuration(ANIMATE_TO_DURATION);
        currentAnimator.setInterpolator(DOUBLE_TAP_INTERPOLATOR);
        currentAnimator.start();
        final float xScreenCenterOnContent = (offsetX + getWidth() / 2f) / scale;
        final float yScreenCenterOnContent = (offsetY + getHeight() / 2f) / scale;
        final float targetScale = zoomLevel / 10f;
        final float scaleOrigin = scale;
        final float scaleDistance = targetScale - scale;

        Runnable animation = new Runnable() {
            @Override
            public void run() {

                Float animatedValue = (Float) currentAnimator.getAnimatedValue();
                float animatedValueForXY = DOUBLE_TAP_INTERPOLATOR.getInterpolation(animatedValue);
                float xCurrentOnContent = xScreenCenterOnContent + (x - xScreenCenterOnContent) * animatedValueForXY;
                float yCurrentOnContent = yScreenCenterOnContent + (y - yScreenCenterOnContent) * animatedValueForXY;
                onScale((scaleOrigin + scaleDistance * animatedValue) / scale, 0, 0);
                float xCurrentOffset = xCurrentOnContent * scale - getWidth() / 2f;
                float yCurrentOffset = yCurrentOnContent * scale - getHeight() / 2f;
                onScroll(xCurrentOffset - offsetX, yCurrentOffset - offsetY);

                if (currentAnimator.isRunning()) {
                    postOnAnimation(this);
                } else if (animatedValue != 1f) {
                    float xFinalOnContent = xScreenCenterOnContent + (x - xScreenCenterOnContent);
                    float yFinalOnContent = yScreenCenterOnContent + (y - yScreenCenterOnContent);
                    onScale((scaleOrigin + scaleDistance) / scale, 0, 0);
                    float xFinalOffset = xFinalOnContent * scale - getWidth() / 2f;
                    float yFinalOffset = yFinalOnContent * scale - getHeight() / 2f;
                    onScroll(xFinalOffset - offsetX, yFinalOffset - offsetY);
                }

                invalidate();
            }
        };

        postOnAnimation(animation);
    }

    public PointF getPositionInView(float x, float y) {
        return new PointF(x * scale - offsetX, y * scale - offsetY);
    }

    private void animateScaleTo(final float newScale, final float focusXOnScreen, final float focusYOnScreen, final long duration) {
        if (currentAnimator != null) currentAnimator.cancel();
        currentAnimator = ValueAnimator.ofFloat(scale, newScale);
        currentAnimator.setDuration(duration);
        currentAnimator.setInterpolator(DOUBLE_TAP_INTERPOLATOR);
        currentAnimator.start();
        Runnable animation = new Runnable() {
            @Override
            public void run() {
                Float animatedValue = (Float) currentAnimator.getAnimatedValue();
                onScale(animatedValue / scale, focusXOnScreen, focusYOnScreen);
                if (currentAnimator.isRunning()) {
                    postOnAnimation(this);
                } else {
                    if (animatedValue != newScale) {
                        onScale(newScale / scale, focusXOnScreen, focusYOnScreen);
                    }

                    onScaleEnd(focusXOnScreen, focusYOnScreen, 0f);
                }
                invalidate();
            }
        };

        postOnAnimation(animation);
    }

    @Override
    public void onScaleEnd(float focusX, float focusY, float lastScaleFactor) {
        int bestZoomLevel = zoomLevelForScale(scale, lastScaleFactor >= 1 ? SCALE_TYPE_CEIL : SCALE_TYPE_FLOOR);
        bestZoomLevel = Math.min(Math.max(bestZoomLevel, userMinZoomLevel), userMaxZoomLevel);
        if (scale != bestZoomLevel / 10f) {
            animateScaleTo(bestZoomLevel / 10f, focusX, focusY, SCALE_ADJUSTMENT_DURATION);
        }
    }

    @Override
    public void onSingleTap(float screenX, float screenY) {
        if (onContentTappedListener != null) {
            float contentWidth = getContentWidth();
            float contentHeight = getContentHeight();
            float contentX = (screenX + offsetX) / scale;
            float contentY = (screenY + offsetY) / scale;
            onContentTappedListener.onContentTapped(
                    contentX / contentWidth, contentY / contentHeight,
                    contentWidth, contentHeight, scale);
        }
    }

    /** Return an appropriate zoom level for the given scale */
    private int zoomLevelForScale(float scale, int scaleType) {
        double scaleFrom0x10 = Math.round(scale * 10) - 10d;
        double exactValue = Math.log(scaleFrom0x10) / Math.log(2);
        int roundedValue = (int) (scaleType == SCALE_TYPE_FLOOR ? Math.floor(exactValue) :
                scaleType == SCALE_TYPE_CEIL ? Math.ceil(exactValue) :
                        Math.round(exactValue));
        int result = (int) (10 + Math.pow(2, roundedValue));
        if (scale < 1f) {
            result = Math.round(scale * 10f);
        }
        return result;
    }

    @Override
    public void onTileRendered(Tile tile) {
        postInvalidate();
    }

    private boolean isSized() {
        return getWidth() != 0 && getHeight() != 0;
    }

    public void setZoomLevel(int zoomLevel) {
        if (zoomLevel < getMinZoomLevel() || zoomLevel > getMaxZoomLevel()) {
            throw new IllegalArgumentException("Zoom level should be between " + getMinZoomLevel() + " and " + getMaxZoomLevel() + ".");
        }
        if (zoomLevel > 10)
            zoomLevel = (int) (10 + Math.pow(2, zoomLevel - 10));
        animateScaleTo(zoomLevel / 10f, getWidth() / 2f, getHeight() / 2f, SCALE_ADJUSTMENT_DURATION);

    }

    public float getContentWidth() {
        return getWidth() - getPaddingLeft() - getPaddingRight() - getContentPaddingLeft() - getContentPaddingRight();
    }

    public float getContentHeight() {
        return getHeight() - getPaddingTop() - getPaddingBottom() - getContentPaddingTop() - getContentPaddingBottom();
    }
}
