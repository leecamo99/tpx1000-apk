package com.tpx1000.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.webkit.WebView;

/**
 * 會「騙」Chromium 說自己永遠看得見的 WebView。
 *
 * 螢幕關閉或切到背景時，系統會把 window visibility 設成 GONE／INVISIBLE，
 * Chromium 收到後把頁面標成 hidden，接著做兩件致命的事：
 *   1. 暫停 HTML5 媒體播放 → 音檔／Cloud TTS 直接停掉
 *   2. 對 setTimeout / setInterval 做背景節流（最慢一分鐘一次）
 *      → 靠計時器自動跳下一個單字的流程會整個卡住
 *
 * 一律回報 VISIBLE，頁面就維持 visible，音訊繼續播、計時器不被節流。
 *
 * 注意：這只解決「頁面以為自己還醒著」。程序會不會被系統回收，
 * 是 PlaybackService 前台服務 + WakeLock 負責的，兩者缺一不可。
 */
public class BackgroundMediaWebView extends WebView {

    private boolean allowPause = false;

    public BackgroundMediaWebView(Context context) { super(context); }

    public BackgroundMediaWebView(Context context, AttributeSet attrs) { super(context, attrs); }

    public BackgroundMediaWebView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /** App 要結束時設 true，讓它恢復正常行為。 */
    public void setAllowPause(boolean allow) { this.allowPause = allow; }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        if (allowPause) {
            super.onWindowVisibilityChanged(visibility);
        } else {
            super.onWindowVisibilityChanged(View.VISIBLE);
        }
    }
}
