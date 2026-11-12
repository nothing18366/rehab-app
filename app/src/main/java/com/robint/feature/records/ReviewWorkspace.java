package com.robint.feature.records;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.net.Uri;
import android.os.Build;
import android.view.View;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.robint.data.local.SessionStore;
import com.robint.ui.component.UiComponents;
import org.json.JSONObject;

/** Review content stays inside the existing App navigation and uses its staff session. */
public class ReviewWorkspace extends LinearLayout {
    public interface OnPageChanged { void select(String page); }
    private final Activity activity;
    private WebView web;
    private TextView status;
    private TextView heading;
    private ProgressBar progress;
    private String page;
    private final String base;
    private boolean ready, closed, leaving, reusable;
    private int loginCheck;

    public ReviewWorkspace(Activity owner, String run, String initialPage, OnPageChanged onPageChanged, Runnable onExit) {
        super(owner);
        activity = owner;
        page = initialPage;
        base = owner.getSharedPreferences("rehab", 0).getString("server_url", "");
        setOrientation(VERTICAL);
        setBackgroundColor(UiComponents.BACKGROUND);
        int gutter = UiComponents.dp(owner, UiComponents.portrait(owner) ? 18 : 24);
        setPadding(gutter, UiComponents.dp(owner, 16), gutter, 0);
        LinearLayout toolbar = UiComponents.row(owner);
        toolbar.setBaselineAligned(false);
        toolbar.setPadding(0, 0, 0, UiComponents.dp(owner, 24));
        heading = UiComponents.heading(owner, page);
        toolbar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        toolbar.addView(UiComponents.compact(owner, "复制网页地址", false, v -> {
            ((ClipboardManager) owner.getSystemService(android.content.Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("康复师工作台", base + "/review/#" + ("康复训练单".equals(page) ? "sheet" : "review")));
            android.widget.Toast.makeText(owner, "网页地址已复制", android.widget.Toast.LENGTH_SHORT).show();
        }));
        toolbar.addView(UiComponents.compact(owner, "返回", false, v -> onExit.run()));
        addView(toolbar);
        progress = new ProgressBar(owner, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(UiComponents.PRIMARY));
        progress.setContentDescription("正在加载" + page);
        addView(progress, new LinearLayout.LayoutParams(-1, UiComponents.dp(owner, 3)));
        status = UiComponents.text(owner, "", 14, UiComponents.DANGER);
        status.setVisibility(View.GONE);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        addView(status);
        web = new WebView(owner); web.setContentDescription(page);
        web.setVisibility(View.INVISIBLE);
        addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setTextZoom(Math.round(100 * getResources().getConfiguration().fontScale));
        if (Build.VERSION.SDK_INT >= 29) settings.setForceDark(WebSettings.FORCE_DARK_OFF);
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(owner).setMessage(message)
                    .setPositiveButton("确认", (d, w) -> result.confirm()).setNegativeButton("取消", (d, w) -> result.cancel())
                    .setOnCancelListener(d -> result.cancel()).show(); return true;
            }
            @Override public boolean onJsBeforeUnload(WebView view, String url, String message, JsResult result) {
                return onJsConfirm(view, url, "有未保存的输入，确定离开吗？", result);
            }
        });
        web.setWebViewClient(new WebViewClient() {
            private boolean blocked(String value) { return !trusted(value); }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) { return blocked(url); }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return blocked(request.getUrl().toString()); }
            @Override public void doUpdateVisitedHistory(WebView view, String url, boolean reload) {
                if (!trusted(url) || closed) return;
                String fragment = Uri.parse(url).getFragment();
                if (!"review".equals(fragment) && !"sheet".equals(fragment)) return;
                String selected = "sheet".equals(fragment) ? "康复训练单" : "人工复核";
                if (!selected.equals(page)) onPageChanged.select(selected);
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (!trusted(url) || closed || owner.isFinishing()) return;
                String token = SessionStore.token(owner);
                if (token.isEmpty()) { SessionStore.login(owner); return; }
                final int check = ++loginCheck;
                // The desktop login form never appears inside the App, even while authentication is pending.
                view.evaluateJavascript("var nativeStyle=document.createElement('style');nativeStyle.textContent='#loginScreen{display:none!important}';document.head.append(nativeStyle);"
                    + "if(!window.__rehabNativeLoginState || window.__rehabNativeLoginState==='error'){window.__rehabNativeLoginState='loading';"
                    + "if(window.rehabLogin){Promise.resolve(window.rehabLogin(" + JSONObject.quote(token) + ",true)).then(function(){"
                    + "window.__rehabNativeLoginState=document.getElementById('workspace').hidden?'error':'ready';"
                    + "},function(){window.__rehabNativeLoginState='error'});}else{window.__rehabNativeLoginState='error'}}", null);
                waitForLogin(check, 0);
            }
            @Override public void onReceivedError(WebView view, int code, String description, String failingUrl) {
                if (!trusted(failingUrl) || closed) return;
                progress.setVisibility(View.GONE);
                status.setText("工作台加载失败，请检查服务连接后返回重试。"); status.setVisibility(View.VISIBLE);
            }
        });
        Uri.Builder target = Uri.parse(base + "/review/").buildUpon().appendQueryParameter("embedded", "1");
        if (run != null && run.matches("[a-f0-9]{32}")) target.appendQueryParameter("run_id", run);
        target.fragment("康复训练单".equals(page) ? "sheet" : "review");
        web.loadUrl(target.build().toString());
    }

    public void showPage(String selected) {
        page = selected; heading.setText(page); web.setContentDescription(page);
        web.evaluateJavascript("window.rehabShowPage && window.rehabShowPage(" + JSONObject.quote("康复训练单".equals(page) ? "sheet" : "review") + ")", null);
    }

    private void waitForLogin(int check, int attempt) {
        if (closed || check != loginCheck) return;
        web.evaluateJavascript("window.__rehabNativeLoginState", value -> {
            if (closed || check != loginCheck) return;
            if ("\"ready\"".equals(value)) {
                ready = true; progress.setVisibility(View.GONE); status.setVisibility(View.GONE); web.setVisibility(View.VISIBLE);
                showPage(page);
            } else if ("\"error\"".equals(value) || attempt >= 150) {
                progress.setVisibility(View.GONE);
                status.setText("无法使用当前 App 登录状态加载工作台。请检查服务连接，或返回 App 登录页重新登录。");
                status.setVisibility(View.VISIBLE);
            } else web.postDelayed(() -> waitForLogin(check, attempt + 1), 100);
        });
    }

    private boolean trusted(String value) {
        Uri target = Uri.parse(value), origin = Uri.parse(base);
        return origin.getScheme() != null && origin.getScheme().equals(target.getScheme())
            && origin.getAuthority() != null && origin.getAuthority().equals(target.getAuthority()) && "/review/".equals(target.getPath());
    }

    public void requestLeave(Runnable action) {
        if (closed || leaving) return;
        reusable = false;
        if (!ready) { action.run(); return; }
        leaving = true;
        final boolean[] answered = {false};
        Runnable timeout = () -> {
            if (!answered[0] && !closed) {
                answered[0] = true;
                confirmLeave("页面暂未响应，离开可能丢失未保存的输入，确定离开吗？", action);
            }
        };
        web.postDelayed(timeout, 1500);
        web.evaluateJavascript("Boolean(window.rehabHasUnsavedChanges && window.rehabHasUnsavedChanges())", value -> {
            if (closed || answered[0]) return;
            answered[0] = true; web.removeCallbacks(timeout);
            if ("true".equals(value)) confirmLeave("有尚未保存的复核结论或训练单，确定离开吗？", action);
            else { leaving = false; reusable = true; action.run(); }
        });
    }

    public boolean canReuse() { return ready && !closed && reusable; }

    public void refresh() {
        ready = false; progress.setVisibility(View.VISIBLE); web.setVisibility(View.INVISIBLE);
        web.evaluateJavascript("window.__rehabNativeLoginState='loading';Promise.resolve(window.rehabRefresh && window.rehabRefresh()).then(function(){"
            + "window.__rehabNativeLoginState=document.getElementById('workspace').hidden?'error':'ready';"
            + "},function(){window.__rehabNativeLoginState='error'})", null);
        waitForLogin(++loginCheck, 0);
    }

    private void confirmLeave(String message, Runnable action) {
        new AlertDialog.Builder(activity).setMessage(message)
            .setPositiveButton("放弃并离开", (d, w) -> { leaving = false; action.run(); })
            .setNegativeButton("继续填写", (d, w) -> leaving = false)
            .setOnCancelListener(d -> leaving = false).show();
    }

    public void resume() { if (!closed) web.onResume(); }
    public void pause() { if (!closed) { web.evaluateJavascript("document.querySelectorAll('video').forEach(function(v){v.pause()})", null); web.onPause(); } }
    public void close() {
        if (closed) return;
        closed = true; loginCheck++;
        web.stopLoading(); removeView(web); web.destroy(); web = null;
    }
}
