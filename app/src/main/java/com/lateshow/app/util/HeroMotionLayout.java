package com.lateshow.app.util;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.view.ViewParent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.motion.widget.MotionLayout;

/**
 * 跟手一镜到底用的 MotionLayout：在 progress 的基础上接管「下拉收起」触摸。
 *
 * <p>进度语义：0 = 展开（详情页铺满），1 = 收起（缩到列表卡片位置）。
 * 进入/退出动画由业务侧用 ValueAnimator 驱动 {@link #setProgress(float)}；
 * 这里只负责手指拖动时的 1:1 跟随，以及松手时把「位移 + 速度」交给回调判定。
 *
 * <p>拦截条件（三者同时满足才接管，避免误伤）：
 * <ol>
 *   <li>竖直方向位移超过 touchSlop，且大于水平位移的 1.6 倍（横向滑动交给子 View）；</li>
 *   <li>内容已经滚到顶部，或页面已经处于收起过程中（{@link OnDragCallback#canDragDown()}）；</li>
 *   <li>方向向下。</li>
 * </ol>
 */
public class HeroMotionLayout extends MotionLayout {

    public interface OnDragCallback {
        /** 当前能否开始下拉：滚动内容需在顶部 */
        boolean canDragDown();

        /** 手指按下并确认要接管：此时应取消进行中的进入/回弹动画 */
        void onDragStart();

        /** 拖动中的进度（0~1，已含按下时的基准进度） */
        void onDragProgress(float progress);

        /** 松手：交给业务侧决定回弹还是关闭（velocityY > 0 为向下甩） */
        void onRelease(float progress, float velocityY);
    }

    /** 手指划过屏幕高度的这个比例，等于从展开到收起走完一遍 */
    private static final float DRAG_RANGE_RATIO = 0.55f;

    private final int touchSlop;
    private OnDragCallback callback;
    private VelocityTracker tracker;

    private boolean tracking;          // 已按下，尚未判定方向
    private boolean dragging;          // 已接管，事件归本 View
    private float downX, downY;
    private float baseProgress;        // 按下瞬间的进度（支持从半途继续拖）

    public HeroMotionLayout(@NonNull Context context) {
        this(context, null);
    }

    public HeroMotionLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public HeroMotionLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public void setOnDragCallback(OnDragCallback cb) {
        this.callback = cb;
    }

    private float dragRange() {
        return Math.max(1f, getHeight() * DRAG_RANGE_RATIO);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (callback == null) return super.onInterceptTouchEvent(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                tracking = true;
                dragging = false;
                downX = ev.getX();
                downY = ev.getY();
                if (tracker == null) tracker = VelocityTracker.obtain();
                else tracker.clear();
                tracker.addMovement(ev);
                break;
            case MotionEvent.ACTION_MOVE:
                if (tracker != null) tracker.addMovement(ev);
                if (tracking && !dragging) {
                    float dy = ev.getY() - downY;
                    float dx = Math.abs(ev.getX() - downX);
                    if (dy > touchSlop && dy > dx * 1.6f
                            && (getProgress() > 0.002f || callback.canDragDown())) {
                        dragging = true;
                        tracking = false;
                        baseProgress = getProgress();
                        ViewParent p = getParent();
                        if (p != null) p.requestDisallowInterceptTouchEvent(true);
                        callback.onDragStart();
                        return true;   // 接管后子 View 会收到 ACTION_CANCEL
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                tracking = false;
                break;
            default:
                break;
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!dragging || callback == null) return super.onTouchEvent(ev);
        if (tracker != null) tracker.addMovement(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                float dy = ev.getY() - downY;
                float p = baseProgress + dy / dragRange();
                callback.onDragProgress(Math.max(0f, Math.min(1f, p)));
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                float vy = 0f;
                if (tracker != null) {
                    tracker.computeCurrentVelocity(1000);
                    vy = tracker.getYVelocity();
                    tracker.recycle();
                    tracker = null;
                }
                dragging = false;
                callback.onRelease(getProgress(), vy);
                return true;
            }
            default:
                return true;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (tracker != null) {
            tracker.recycle();
            tracker = null;
        }
        dragging = false;
        tracking = false;
        super.onDetachedFromWindow();
    }
}
