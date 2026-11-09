package com.dsh.dydl;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36";
    private static final String PREFS = "dy_history";
    private static final int TIMEOUT_MS = 15000;

    private EditText etLink;
    private TextView tvStatus;
    private TextView tvInfo;
    private ProgressBar pb;
    private ImageView ivCover;
    private Button btnSave;
    private Button btnCopy;
    private LinearLayout llHistory;
    private LinearLayout rootLayout;
    private TextView tvLog;
    private TextView tvUrl;
    private LinearLayout llItems;
    private final java.util.List<DouyinParser.MediaItem> items = new java.util.ArrayList<>();
    private final StringBuilder logBuf = new StringBuilder();
    private WebView wv;

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private DouyinParser.Video current;
    private String lastLink = "";

    private volatile String capturedJson;
    private volatile String pageUrl = "";
    private boolean parsing;
    private boolean diag = true;
    private boolean downloading;
    private String pendingSrc;
    private String targetId;
    private String lastTried;
    private final java.util.List<String> captures = java.util.Collections.synchronizedList(
            new java.util.ArrayList<String>());
    private String lastHtml = "";
    private int ticks;

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildUi());
        setupWebView();
        applyDiag();
        renderHistory();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (wv != null) {
            try {
                wv.destroy();
            } catch (Exception ignored) {
            }
        }
        pool.shutdownNow();
        super.onDestroy();
    }

    private void handleIntent(Intent it) {
        if (it == null) return;
        String action = it.getAction();
        if (Intent.ACTION_SEND.equals(action) || Intent.ACTION_VIEW.equals(action)) {
            String txt = it.getStringExtra(Intent.EXTRA_TEXT);
            if (txt == null && it.getData() != null) txt = it.getDataString();
            if (!TextUtils.isEmpty(txt)) {
                etLink.setText(txt);
                startParse();
            }
        }
    }

    private View buildUi() {
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(18);
        root.setPadding(p, p, p, p);
        sv.addView(root, new ViewGroup.LayoutParams(-1, -2));
        rootLayout = root;

        TextView title = new TextView(this);
        title.setText("抖音视频提取");
        title.setTextSize(22);
        title.setTypeface(null, 1);
        title.setTextColor(0xFF111111);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("v2.3.2 · 图集对齐 · 复制分享链接 → 粘贴 → 解析 → 保存");
        sub.setTextSize(13);
        sub.setTextColor(0xFF888888);
        sub.setPadding(0, dp(4), 0, dp(12));
        root.addView(sub);

        etLink = new EditText(this);
        etLink.setHint("粘贴分享链接或分享口令");
        etLink.setTextSize(15);
        etLink.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        etLink.setGravity(Gravity.TOP | Gravity.START);
        etLink.setMinLines(2);
        etLink.setPadding(dp(12), dp(10), dp(12), dp(10));
        etLink.setBackgroundColor(0xFFF3F3F5);
        root.addView(etLink, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(10), 0, 0);
        Button btnPaste = new Button(this);
        btnPaste.setText("粘贴");
        Button btnParse = new Button(this);
        btnParse.setText("解析");
        row.addView(btnPaste, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(btnParse, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(row);

        pb = new ProgressBar(this);
        pb.setVisibility(View.GONE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(32), dp(32));
        plp.gravity = Gravity.CENTER_HORIZONTAL;
        plp.topMargin = dp(12);
        root.addView(pb, plp);

        tvStatus = new TextView(this);
        tvStatus.setTextSize(13);
        tvStatus.setTextColor(0xFF666666);
        tvStatus.setPadding(0, dp(10), 0, 0);
        root.addView(tvStatus);

        tvLog = new TextView(this);
        tvLog.setTextSize(11);
        tvLog.setTextColor(0xFF555555);
        tvLog.setTypeface(android.graphics.Typeface.MONOSPACE);
        tvLog.setPadding(0, dp(8), 0, 0);
        tvLog.setVisibility(View.GONE);
        tvLog.setTextIsSelectable(true);
        root.addView(tvLog);

        ivCover = new ImageView(this);
        ivCover.setVisibility(View.GONE);
        ivCover.setAdjustViewBounds(true);
        ivCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ivCover.setBackgroundColor(0xFFEEEEEE);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-1, dp(200));
        ilp.topMargin = dp(12);
        root.addView(ivCover, ilp);

        tvInfo = new TextView(this);
        tvInfo.setTextSize(15);
        tvInfo.setTextColor(0xFF222222);
        tvInfo.setPadding(0, dp(10), 0, 0);
        tvInfo.setTextIsSelectable(true);
        root.addView(tvInfo);

        tvUrl = new TextView(this);
        tvUrl.setTextSize(11);
        tvUrl.setTextColor(0xFF1A73E8);
        tvUrl.setPadding(0, dp(6), 0, 0);
        tvUrl.setTextIsSelectable(true);
        root.addView(tvUrl);

        llItems = new LinearLayout(this);
        llItems.setOrientation(LinearLayout.VERTICAL);
        root.addView(llItems);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, dp(10), 0, 0);
        btnSave = new Button(this);
        btnSave.setText("保存视频");
        btnSave.setEnabled(false);
        btnCopy = new Button(this);
        btnCopy.setText("复制直链");
        btnCopy.setEnabled(false);
        row2.addView(btnSave, new LinearLayout.LayoutParams(0, -2, 1f));
        row2.addView(btnCopy, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(row2);

        TextView histTitle = new TextView(this);
        histTitle.setText("最近解析");
        histTitle.setTextSize(13);
        histTitle.setTextColor(0xFF888888);
        histTitle.setPadding(0, dp(22), 0, dp(6));
        root.addView(histTitle);

        llHistory = new LinearLayout(this);
        llHistory.setOrientation(LinearLayout.VERTICAL);
        root.addView(llHistory);

        btnPaste.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || cm.getPrimaryClip() == null || cm.getPrimaryClip().getItemCount() == 0) {
                toast("剪贴板是空的");
                return;
            }
            CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
            if (t == null || t.length() == 0) {
                toast("剪贴板是空的");
                return;
            }
            etLink.setText(t.toString());
        });

        btnParse.setOnClickListener(v -> startParse());
        btnParse.setOnLongClickListener(v -> {
            diag = !diag;
            applyDiag();
            toast(diag ? "诊断模式：能看到页面和日志了" : "诊断模式已关");
            return true;
        });

        btnSave.setOnClickListener(v -> save());

        btnCopy.setOnClickListener(v -> {
            if (current == null) return;
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("video", current.url));
                toast("直链已复制");
            }
        });
        return sv;
    }

    // ---------- 隐藏 WebView：让抖音自己的页面脚本去取数据，我们只旁听 ----------


    /** 在页面脚本之前挂钩子，把它自己请求到的接口数据收起来 */
    private static final String JS_HOOK = "(function(){"
            + "if(window.__dshHooked)return;window.__dshHooked=1;window.__dshCaps=[];"
            + "window.__dshKeep=function(t){try{if(t&&t.charAt(0)=='{'&&(t.indexOf('play_addr')>=0||t.indexOf('images')>=0)&&window.__dshCaps.length<3){"
            + "window.__dshCaps.push(String(t).slice(0,3000000));}}catch(e){}};"
            + "try{var X=window.XMLHttpRequest;if(X&&X.prototype){var op=X.prototype.open,se=X.prototype.send;"
            + "X.prototype.open=function(m,u){this.__dshUrl=u;return op.apply(this,arguments)};"
            + "X.prototype.send=function(){var q=this;try{q.addEventListener('load',function(){"
            + "try{if(!q.responseType||q.responseType==='text')window.__dshKeep(q.responseText);}catch(e){}});}catch(e){}"
            + "return se.apply(this,arguments)};}}catch(e){}"
            + "try{var f=window.fetch;if(f){window.fetch=function(){return f.apply(this,arguments).then(function(r){"
            + "try{r.clone().text().then(function(t){window.__dshKeep(t)},function(){});}catch(e){}return r});}}"
            + "}catch(e){}"
            + "})();";


    private static final String DOM_JS = "(function(){try{var out=[];var seen={};"
            + "function add(k,u){if(!u||u.indexOf('http')!==0||seen[u])return;seen[u]=1;out.push(k+'|'+u);}"
            + "var v=document.querySelectorAll('video');"
            + "for(var i=0;i<v.length;i++){add('V',v[i].currentSrc||v[i].src||'')}"
            + "var im=document.querySelectorAll('img');"
            + "for(var j=0;j<im.length;j++){var e=im[j];"
            + "add('I',e.currentSrc||e.src||e.getAttribute('data-src')||'');"
            + "var ss=e.getAttribute('srcset');if(ss){add('I',ss.split(',')[0].split(' ')[0]);}}"
            + "return out.slice(0,40).join('\\n');}catch(e){return ''}})()";

    private void applyDiag() {
        if (wv != null) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) wv.getLayoutParams();
            lp.height = diag ? dp(320) : dp(1);
            wv.setLayoutParams(lp);
            wv.setVisibility(diag ? View.VISIBLE : View.INVISIBLE);
        }
        if (tvLog != null) tvLog.setVisibility(diag ? View.VISIBLE : View.GONE);
        if (tvUrl != null) tvUrl.setVisibility(diag ? View.VISIBLE : View.GONE);
    }

    private void setupWebView() {
        wv = new WebView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(1));
        rootLayout.addView(wv, lp);
        wv.setVisibility(View.INVISIBLE);

        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(UA);
        s.setLoadsImagesAutomatically(true);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(wv, true);

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                pageUrl = url;
                log("start " + shortUrl(url));
                view.evaluateJavascript(JS_HOOK, null);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                try {
                    final String url = req.getUrl().toString();
                    if (url.contains("/aweme/v1/") || url.contains("/web/api/") || url.contains("iteminfo")) {
                        log("req " + shortUrl(url));
                        pool.execute(() -> {
                            String body = httpText(url, pageUrl);
                            String t = body == null ? "" : body.trim();
                            if (t.startsWith("{") && captures.size() < 8
                                    && (t.contains("play_addr") || t.contains("\"images\"")
                                        || t.contains("aweme_list"))) {
                                captures.add(t);
                            }
                        });
                    }
                } catch (Exception ignored) {
                }
                return null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return blockNonHttp(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageUrl = url;
                log("done  " + shortUrl(url));
            }

            @Override
            public void onReceivedError(WebView view, int code, String desc, String failingUrl) {
                log("ERR " + code + " " + desc + " " + shortUrl(failingUrl));
            }
        });
    }

    /** 拦掉 douyin:// intent:// snssdk:// 之类，绝不唤起抖音 App */
    private boolean blockNonHttp(String url) {
        if (url == null) return true;
        String low = url.toLowerCase();
        if (low.startsWith("http://") || low.startsWith("https://")) return false;
        log("block " + shortUrl(url));
        return true;
    }

    private String httpText(String url, String referer) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "application/json, text/plain, */*");
            String ck = CookieManager.getInstance().getCookie(url);
            if (!TextUtils.isEmpty(ck)) c.setRequestProperty("Cookie", ck);
            if (!TextUtils.isEmpty(referer)) c.setRequestProperty("Referer", referer);
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }


    private static String shortUrl(String u) {
        if (u == null) return "null";
        return u.length() > 90 ? u.substring(0, 90) + "…" : u;
    }

    /** 问页面：钩子收没收到数据 */
    private void grabFromPage() {
        final WebView view = wv;
        if (view == null) return;
        view.evaluateJavascript("(window.__dshCaps&&window.__dshCaps.length)?String(window.__dshCaps[0]):''",
                value -> {
                    if (value == null || value.length() < 40) return;
                    String json = unquoteJs(value);
                    if (json.contains("play_addr") && capturedJson == null) capturedJson = json;
                });
    }

    private static String unquoteJs(String v) {
        if (v.startsWith("\"") && v.endsWith("\"") && v.length() > 1) {
            v = v.substring(1, v.length() - 1);
        }
        return v.replace("\\u003C", "<").replace("\\\"", "\"").replace("\\/", "/");
    }

    private void startParse() {
        String raw = etLink.getText() == null ? "" : etLink.getText().toString().trim();
        if (raw.isEmpty()) {
            toast("先粘贴链接");
            return;
        }
        String link = DouyinParser.extractUrl(raw);
        if (link == null) {
            setBusy(false, "没找到链接，重新复制一次抖音分享口令");
            return;
        }
        hideKeyboard();
        lastLink = raw;
        capturedJson = null;
        pendingSrc = null;
        lastTried = null;
        captures.clear();
        targetId = DouyinParser.idOf(link);
        parsing = true;
        ticks = 0;
        current = null;
        ivCover.setVisibility(View.GONE);
        tvInfo.setText("");
        tvUrl.setText("");
        btnSave.setEnabled(false);
        btnCopy.setEnabled(false);
        log("parse " + link);
        setBusy(true, "正在打开分享页，等播放地址…");
        if (wv != null) wv.loadUrl(link);
        ui.removeCallbacks(poll);
        ui.postDelayed(poll, 700);
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!parsing) return;
            ticks++;
            if (pageUrl != null && pageUrl.length() > 20) {
                String pid = DouyinParser.idOf(pageUrl);
                if (pid != null) targetId = pid;
            }
            String best = pickBest();
            if (best != null && !best.equals(lastTried)) {
                lastTried = best;
                if (finishParse(best)) return;
            }
            if (ticks == 6 && wv != null) snapshotHtml();
            if (ticks % 2 == 0 && wv != null) {
                probeVideo();
                grabFromPage();
            }
            if (pendingSrc != null && ticks >= 12) {
                log("dom check before single src");
                domItems();
                return;
            }
            if (ticks * 700 >= TIMEOUT_MS) {
                fallback();
                return;
            }
            ui.postDelayed(this, 700);
        }
    };

    /** 直接问页面：video 元素有没有真实地址 */
    private void probeVideo() {
        WebView view = wv;
        if (view == null || !parsing) return;
        view.evaluateJavascript(
                "(function(){try{var v=document.querySelector('video');"
                        + "if(!v)return '';var s=v.currentSrc||v.src||'';"
                        + "return s.indexOf('http')===0?s:'';}catch(e){return ''}})()",
                value -> {
                    if (!parsing) return;
                    String u = unquoteJs(value == null ? "" : value);
                    if (u.startsWith("http") && u.length() > 30) {
                        pendingSrc = u;
                    }
                });
    }

    private void onGotUrl(String url) {
        if (!parsing) return;
        parsing = false;
        java.util.List<DouyinParser.MediaItem> list = new java.util.ArrayList<>();
        DouyinParser.MediaItem m = new DouyinParser.MediaItem();
        m.kind = DouyinParser.MediaItem.VIDEO;
        m.url = url;
        list.add(m);
        showItems("抖音视频", list);
        setBusy(false, "已拿到直链，点「保存视频」");
    }

    private String pickBest() {
        String best = null;
        int bestScore = -9999;
        synchronized (captures) {
            for (String c : captures) {
                int sc = 0;
                if (c.contains("aweme_detail")) sc += 100;
                if (c.contains("images")) sc += 60;
                if (c.contains("douyinpic")) sc += 30;
                if (c.contains("aweme_list")) sc -= 80;
                if (sc > bestScore) {
                    bestScore = sc;
                    best = c;
                }
            }
        }
        return best;
    }

    private void snapshotHtml() {
        if (wv == null) return;
        wv.evaluateJavascript(
                "(function(){try{return document.documentElement.innerHTML;}catch(e){return ''}})()",
                value -> {
                    String h = unquoteJs(value == null ? "" : value);
                    if (h.length() < 200) return;
                    dumpDebug(null, h);
                    if (!parsing) return;
                    try {
                        java.util.List<DouyinParser.MediaItem> found =
                                DouyinParser.imagesFromHtml(h);
                        if (!found.isEmpty()) {
                            parsing = false;
                            showItems(DouyinParser.titleFromHtml(h), found);
                        }
                    } catch (Exception e) {
                        log("html items fail " + e.getMessage());
                    }
                });
    }

    /** 解析成功返回 true；失败返回 false，让轮询继续等别的路子 */
    private boolean finishParse(String json) {
        dumpDebug(json, null);
        try {
            java.util.List<DouyinParser.MediaItem> list = DouyinParser.itemsFromJson(json, targetId);
            if (list.isEmpty()) return false;
            String title = DouyinParser.titleOf(json);
            parsing = false;
            showItems(title, list);
            if (wv != null) wv.loadUrl("about:blank");
            return true;
        } catch (Exception e) {
            log("json fail: " + e.getMessage());
            return false;
        }
    }

    private void showItems(String title, java.util.List<DouyinParser.MediaItem> list) {
        clearLog();
        items.clear();
        items.addAll(list);
        llItems.removeAllViews();
        ivCover.setVisibility(View.GONE);
        tvInfo.setText(title);
        btnSave.setEnabled(true);
        btnCopy.setEnabled(true);
        if (list.size() == 1 && list.get(0).kind == DouyinParser.MediaItem.VIDEO) {
            DouyinParser.Video v = new DouyinParser.Video();
            v.url = list.get(0).url;
            v.title = title;
            v.cover = list.get(0).thumb;
            current = v;
            btnSave.setEnabled(true);
            btnCopy.setEnabled(true);
            if (!TextUtils.isEmpty(v.cover)) {
                ivCover.setVisibility(View.VISIBLE);
                pool.execute(() -> {
                    Bitmap bm = loadBitmap(v.cover);
                    if (bm != null) ui.post(() -> ivCover.setImageBitmap(bm));
                });
            }
            setBusy(false, "解析成功，点「保存视频」开始下载");
            return;
        }
        current = null;
        int n = list.size();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            final DouyinParser.MediaItem it = list.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, dp(8));

            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(0xFFEEEEEE);
            row.addView(iv, new LinearLayout.LayoutParams(dp(84), dp(84)));

            TextView label = new TextView(this);
            String kind = it.kind == DouyinParser.MediaItem.ANIM ? "动图"
                    : it.kind == DouyinParser.MediaItem.IMAGE ? "图片" : "视频";
            label.setText("第 " + (i + 1) + " 项 · " + kind);
            label.setTextSize(14);
            label.setPadding(dp(10), 0, dp(6), 0);
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));

            Button b = new Button(this);
            b.setText("保存");
            b.setOnClickListener(v -> saveItem(idx));
            row.addView(b, new LinearLayout.LayoutParams(-2, -2));

            llItems.addView(row);
            if (!TextUtils.isEmpty(it.thumb)) {
                pool.execute(() -> {
                    Bitmap bm = loadBitmap(it.thumb);
                    if (bm != null) ui.post(() -> iv.setImageBitmap(bm));
                });
            }
        }
        llItems.requestLayout();
        rootLayout.requestLayout();
        log("rows=" + llItems.getChildCount() + " n=" + n);
        setBusy(false, "这是图文作品，共 " + n + " 项，点右边的「保存」选择要存的");
        pickerDialog(n);
    }

    /** 弹窗列出全部素材，每项一个保存按钮 */
    private void pickerDialog(int n) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(10);
        box.setPadding(p, dp(4), p, dp(4));
        for (int i = 0; i < items.size(); i++) {
            final int idx = i;
            final DouyinParser.MediaItem it = items.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));

            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(0xFFEEEEEE);
            row.addView(iv, new LinearLayout.LayoutParams(dp(64), dp(64)));

            TextView label = new TextView(this);
            String kind = it.kind == DouyinParser.MediaItem.ANIM ? "动图"
                    : it.kind == DouyinParser.MediaItem.IMAGE ? "图片" : "视频";
            label.setText("第 " + (i + 1) + " 项 · " + kind);
            label.setTextSize(14);
            label.setPadding(dp(10), 0, dp(6), 0);
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));

            Button b = new Button(this);
            b.setText("保存");
            b.setOnClickListener(v -> saveItem(idx));
            row.addView(b, new LinearLayout.LayoutParams(-2, -2));

            box.addView(row);
            if (!TextUtils.isEmpty(it.thumb)) {
                pool.execute(() -> {
                    Bitmap bm = loadBitmap(it.thumb);
                    if (bm != null) ui.post(() -> iv.setImageBitmap(bm));
                });
            }
        }
        ScrollView sc = new ScrollView(this);
        sc.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("共 " + n + " 项，选择要保存的")
                .setView(sc)
                .setNegativeButton("关闭", null)
                .show();
    }

    private void saveItem(final int idx) {
        if (idx < 0 || idx >= items.size() || downloading) return;
        final DouyinParser.MediaItem it = items.get(idx);
        final String title = tvInfo.getText().toString();
        String base = sanitize(title);
        if (base.isEmpty()) base = "douyin";
        final String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        final boolean image = it.kind == DouyinParser.MediaItem.IMAGE;
        final String fileName = base + "_" + (idx + 1) + "_" + stamp + (image ? ".jpg" : ".mp4");
        final String url = DouyinParser.dewatermark(it.url);
        downloading = true;
        setBusy(true, "开始保存第 " + (idx + 1) + " 项…");
        pool.execute(() -> {
            try {
                final long size = downloadTo(url, fileName, it.url, image);
                ui.post(() -> {
                    downloading = false;
                    setBusy(false, "第 " + (idx + 1) + " 项已保存（"
                            + (image ? "相册 Pictures/Douyin" : "相册 Movies/Douyin") + "）");
                });
            } catch (final Exception e) {
                final String m = e.getMessage();
                ui.post(() -> {
                    downloading = false;
                    setBusy(false, "第 " + (idx + 1) + " 项保存失败：" + (m == null ? e.getClass().getSimpleName() : m));
                });
            }
        });
    }

    /** 兜底：从页面 HTML 里找内嵌数据 */
    private void fallback() {
        if (wv == null) {
            parsing = false;
            setBusy(false, "解析失败：页面没返回数据");
            return;
        }
        setBusy(true, "再试一次页面内容…");
        wv.evaluateJavascript(
                "(function(){try{return document.documentElement.innerHTML.slice(0,300000);}catch(e){return ''}})()",
                value -> {
                    parsing = false;
                    String s = unquoteJs(value == null ? "" : value);
                    if (s.length() > 20) {
                        lastHtml = s;
                        dumpDebug(null, s);
                        try {
                            java.util.List<DouyinParser.MediaItem> found =
                                    DouyinParser.imagesFromHtml(s);
                            if (!found.isEmpty()) {
                                showItems(DouyinParser.titleFromHtml(s), found);
                                return;
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    domItems();
                });
    }

    /** 最后兜底：直接扫页面里的 video / img */
    private void domItems() {
        if (wv == null) {
            setBusy(false, "解析失败：没拿到视频数据，稍后再试");
            return;
        }
        wv.evaluateJavascript(DOM_JS, value -> {
            String s = unquoteJs(value == null ? "" : value);
            java.util.List<DouyinParser.MediaItem> list = new java.util.ArrayList<>();
            if (s.length() > 3) {
                for (String line : s.split("\n")) {
                    if (line.startsWith("V|") && line.length() > 10) {
                        DouyinParser.MediaItem m = new DouyinParser.MediaItem();
                        m.kind = DouyinParser.MediaItem.VIDEO;
                        m.url = line.substring(2);
                        list.add(m);
                    } else if (line.startsWith("I|") && line.length() > 10) {
                        DouyinParser.MediaItem m = new DouyinParser.MediaItem();
                        m.kind = DouyinParser.MediaItem.IMAGE;
                        m.url = line.substring(2);
                        m.thumb = m.url;
                        list.add(m);
                    }
                }
            }
            if (list.isEmpty() && pendingSrc != null) {
                onGotUrl(pendingSrc);
                return;
            }
            if (list.isEmpty()) {
                setBusy(false, "解析失败：没拿到视频数据，稍后再试");
            } else {
                showItems("抖音作品", list);
            }
        });
    }

    private void render(final DouyinParser.Video v) {
        StringBuilder sb = new StringBuilder();
        sb.append(v.title);
        if (!TextUtils.isEmpty(v.author)) sb.append("\n@").append(v.author);
        tvInfo.setText(sb.toString());
        tvUrl.setText(v.url);
        btnSave.setEnabled(true);
        btnCopy.setEnabled(true);
        if (TextUtils.isEmpty(v.cover)) return;
        ivCover.setVisibility(View.VISIBLE);
        pool.execute(() -> {
            Bitmap bm = loadBitmap(v.cover);
            if (bm != null) ui.post(() -> ivCover.setImageBitmap(bm));
        });
    }

    private Bitmap loadBitmap(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Referer", "https://www.douyin.com/");
            InputStream in = c.getInputStream();
            Bitmap b = BitmapFactory.decodeStream(in);
            in.close();
            return b;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void save() {
        if (current == null || downloading) return;
        final String url = DouyinParser.dewatermark(current.url);
        final String title = current.title;
        String name = sanitize(title);
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        final String fileName = (name.isEmpty() ? "video" : name) + "_" + stamp + ".mp4";
        final String fallbackUrl = current.url;
        downloading = true;
        btnSave.setEnabled(false);
        setBusy(true, "开始下载…");
        pool.execute(() -> {
            try {
                final long size = downloadTo(url, fileName, fallbackUrl, false);
                ui.post(() -> {
                    downloading = false;
                    btnSave.setEnabled(true);
                    setBusy(false, "已保存到相册 Movies/Douyin（" + Math.max(1, size / 1048576) + " MB）");
                    addHistory(title, url, lastLink);
                });
            } catch (final Exception e) {
                final String m = e.getMessage();
                ui.post(() -> {
                    downloading = false;
                    btnSave.setEnabled(true);
                    setBusy(false, "下载失败：" + (m == null ? e.getClass().getSimpleName() : m));
                });
            }
        });
    }

    /** 自己下载：能带 Cookie/Referer，也能报进度 */
    private long downloadTo(String url, String fileName, String fallbackUrl, boolean image) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Referer", "https://www.iesdouyin.com/");
        String ck = CookieManager.getInstance().getCookie("https://www.iesdouyin.com/");
        if (!TextUtils.isEmpty(ck)) c.setRequestProperty("Cookie", ck);
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            if (fallbackUrl != null && !fallbackUrl.equals(url)) {
                log("play 失败 " + code + "，退回原地址");
                return downloadTo(fallbackUrl, fileName, null, image);
            }
            throw new IOException("HTTP " + code + "（直链可能过期，重新解析一次）");
        }
        long total = c.getContentLength();
        InputStream in = c.getInputStream();
        byte[] buf = new byte[65536];
        int n = in.read(buf);
        if (n <= 0) throw new IOException("服务器没返回内容");

        String ext;
        boolean isImage;
        if (isMp4(buf, n)) {
            ext = ".mp4";
            isImage = false;
        } else if (isJpeg(buf, n)) {
            ext = ".jpg";
            isImage = true;
        } else if (buf[0] == (byte) 0x89 && buf[1] == 'P') {
            ext = ".png";
            isImage = true;
        } else if (buf[0] == 'R' && n > 12) {
            ext = ".webp";
            isImage = true;
        } else {
            in.close();
            c.disconnect();
            throw new IOException("返回的不是视频/图片，多半是网页或错误页");
        }

        String name = stripExt(fileName) + ext;
        OutputStream os = openOutput(name, isImage);
        long done = 0;
        int lastShown = -1;
        try {
            os.write(buf, 0, n);
            done += n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                done += n;
                if (total > 0) {
                    int pct = (int) (done * 100 / total);
                    if (pct != lastShown && pct % 10 == 0) {
                        lastShown = pct;
                        final int p = pct;
                        ui.post(() -> setBusy(true, "下载中 " + p + "%"));
                    }
                }
            }
        } finally {
            try {
                os.close();
            } catch (Exception ignored) {
            }
            try {
                in.close();
            } catch (Exception ignored) {
            }
            c.disconnect();
        }
        log("saved " + name + " bytes=" + done);
        return done;
    }

    private static boolean isMp4(byte[] b, int n) {
        return n > 8 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p';
    }

    private static boolean isJpeg(byte[] b, int n) {
        return n > 3 && b[0] == (byte) 0xFF && b[1] == (byte) 0xD8 && b[2] == (byte) 0xFF;
    }

    private static String stripExt(String s) {
        return s.replaceAll("\\.(mp4|jpg|jpeg|png|webp)$", "");
    }

    private void dumpDebug(String json, String html) {
        if (json != null) writeDoc("dy-last.json", "application/json", json);
        if (html != null) writeDoc("dy-last.html", "text/html", html);
    }

    /** 写到 下载/DSH-Go/dy-debug，方便我直接读 */
    private void writeDoc(String name, String mime, String content) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put("_display_name", name);
                cv.put("mime_type", mime);
                cv.put("relative_path", "Download/DSH-Go/dy-debug");
                Uri uri = getContentResolver().insert(
                        Uri.parse("content://media/external/downloads"), cv);
                if (uri != null) {
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    if (os != null) {
                        os.write(content.getBytes("UTF-8"));
                        os.close();
                        return;
                    }
                }
            }
            File dir = getExternalFilesDir(null);
            if (dir != null) writeText(new File(dir, name), content);
        } catch (Exception e) {
            log("dump fail " + e.getMessage());
        }
    }

    private static void writeText(File f, String s) throws Exception {
        FileOutputStream fos = new FileOutputStream(f);
        fos.write(s.getBytes("UTF-8"));
        fos.close();
    }

    private OutputStream openOutput(String fileName, boolean image) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ContentValues cv = new ContentValues();
                cv.put("_display_name", fileName);
                cv.put("mime_type", image ? "image/jpeg" : "video/mp4");
                cv.put("relative_path", image ? "Pictures/Douyin" : "Movies/Douyin");
                Uri collection = image ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                Uri uri = getContentResolver().insert(collection, cv);
                if (uri != null) {
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    if (os != null) return os;
                }
            } catch (Exception ignored) {
            }
        }
        File dir = new File(getExternalFilesDir(image ? Environment.DIRECTORY_PICTURES
                : Environment.DIRECTORY_MOVIES), "Douyin");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("建不了目录");
        return new FileOutputStream(new File(dir, fileName));
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        String out = s.replaceAll("[\\\\/:*?\"<>|\\r\\n]", " ").trim();
        if (out.length() > 40) out = out.substring(0, 40);
        return out;
    }

    private void setBusy(boolean busy, String msg) {
        pb.setVisibility(busy ? View.VISIBLE : View.GONE);
        tvStatus.setText(msg);
        tvStatus.setTextColor(busy ? 0xFF666666 : (msg.startsWith("解析失败") ? 0xFFD93025 : 0xFF1A7F37));
    }

    private void hideKeyboard() {
        InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(etLink.getWindowToken(), 0);
    }

    private void log(String s) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            final String line = s;
            ui.post(() -> log(line));
            return;
        }
        logBuf.append(s).append('\n');
        if (logBuf.length() > 4000) logBuf.delete(0, logBuf.length() - 4000);
        if (tvLog != null) tvLog.setText(logBuf.toString());
    }

    private void clearLog() {
        logBuf.setLength(0);
        if (tvLog != null) tvLog.setText("");
    }

    private void copyLog() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("dy-log", logBuf.toString()));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---------- 历史记录 ----------

    private void addHistory(String title, String url, String source) {
        try {
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            JSONArray arr = new JSONArray(sp.getString("list", "[]"));
            JSONArray out = new JSONArray();
            JSONObject item = new JSONObject();
            item.put("t", title);
            item.put("u", url);
            item.put("s", source == null ? "" : source);
            out.put(item);
            for (int i = 0; i < arr.length() && out.length() < 8; i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && !url.equals(o.optString("u"))) out.put(o);
            }
            sp.edit().putString("list", out.toString()).apply();
            renderHistory();
        } catch (Exception ignored) {
        }
    }

    private void renderHistory() {
        if (llHistory == null) return;
        llHistory.removeAllViews();
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        JSONArray arr;
        try {
            arr = new JSONArray(sp.getString("list", "[]"));
        } catch (Exception e) {
            arr = new JSONArray();
        }
        if (arr.length() == 0) {
            TextView t = new TextView(this);
            t.setText("还没有记录");
            t.setTextSize(13);
            t.setTextColor(0xFFAAAAAA);
            llHistory.addView(t);
            return;
        }
        for (int i = 0; i < arr.length(); i++) {
            final JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            TextView t = new TextView(this);
            String title = o.optString("t", "视频");
            if (title.length() > 30) title = title.substring(0, 30) + "…";
            t.setText("· " + title);
            t.setTextSize(14);
            t.setTextColor(0xFF1A73E8);
            t.setPadding(0, dp(8), 0, dp(8));
            t.setOnClickListener(v -> {
                String src = o.optString("s", "");
                etLink.setText(src.isEmpty() ? o.optString("u", "") : src);
                startParse();
            });
            llHistory.addView(t);
        }
    }
}
