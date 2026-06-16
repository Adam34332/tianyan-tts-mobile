package com.adam.paseotts;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.InputType;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String TAG = "Tianyan";
    private static final String HOME_INTERNAL_URL = "https://tianyan.local/home";
    private static final String START_URL = HOME_INTERNAL_URL;
    private static final String PASEO_HOME_URL = "https://app.paseo.sh/";
    private static final String HOME_LABEL = "天眼连接页";
    private static final int SCAN_REQUEST = 42;
    private static final int MAX_TABS = 6;
    private static final int MAX_TEXT_CHARS = 12000;
    private static final int MAX_SEGMENT_CHARS = 520;
    private static final int HOME_OVERLAY_MAX_WAIT_MS = 8000;
    private static final int MAX_FAVORITE_URLS = 8;
    private static final int MAX_TITLE_HTML_CHARS = 65536;
    private static final String PREFS_NAME = "tianyan";
    private static final String PREF_FAVORITE_URLS = "favorite_urls";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object ttsLock = new Object();
    private final List<BrowserTab> tabs = new ArrayList<>();
    private WebView webView;
    private FrameLayout webFrame;
    private LinearLayout tabStrip;
    private EditText addressBar;
    private View homeLoadingOverlay;
    private TextView homeSubtitle;
    private EditText homeUrlInput;
    private LinearLayout homeFavoriteList;
    private TextView favoriteButton;
    private TextView readPageButton;
    private TextToSpeech tts;
    private String injectedScript;
    private boolean ttsReady;
    private volatile boolean readingPage;
    private int activeTabIndex = -1;
    private int batchId;
    private int readPageRequestId;
    private String lastUtteranceId = "";

    private final Runnable reinjectRunnable = new Runnable() {
        @Override
        public void run() {
            injectButtons();
            mainHandler.postDelayed(this, 1500);
        }
    };

    private final Runnable hideHomeOverlayRunnable = new Runnable() {
        @Override
        public void run() {
            if (homeLoadingOverlay != null) {
                homeLoadingOverlay.setVisibility(View.GONE);
                Log.i(TAG, "home overlay hide");
            }
        }
    };

    private final Runnable forceHomeOverlayHideRunnable = new Runnable() {
        @Override
        public void run() {
            if (homeLoadingOverlay != null) {
                showHomePanelReady("home panel timeout ready");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(248, 250, 252));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED);
        WebView.setWebContentsDebuggingEnabled(true);
        initTts();
        initWebView();
    }

    private void initWebView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        int topInset = statusBarHeight();
        root.addView(createToolbar(topInset), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(50) + topInset));
        root.addView(createTabBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(38)));
        webFrame = new FrameLayout(this);
        homeLoadingOverlay = createHomeLoadingOverlay();
        webFrame.addView(homeLoadingOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(webFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f));
        setContentView(root);

        boolean debugTest = getIntent().getBooleanExtra("debug_test", false);
        String initialUrl = initialUrlFromIntent(getIntent());
        Log.i(TAG, "initial debug=" + debugTest + " url=" + initialUrl);
        if (debugTest) {
            createNewTab("天眼测试页", false);
            webView.loadDataWithBaseURL("debug://tianyan/", debugHtml(), "text/html", "UTF-8", null);
            if (getIntent().getBooleanExtra("auto_ime_test", false)) {
                mainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        runAutoImeDebugTest();
                    }
                }, 2600);
            }
        } else {
            createNewTab(initialUrl, true);
        }
        mainHandler.postDelayed(reinjectRunnable, 1500);
    }

    private WebView createConfiguredWebView() {
        WebView view = new WebView(this);
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        view.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View touched, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN && clearChromeInputFocus()) {
                    touched.requestFocusFromTouch();
                }
                return false;
            }
        });
        view.addJavascriptInterface(new TtsBridge(), "TtsBrowserBridge");
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        view.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onReceivedTitle(WebView currentView, String title) {
                updateTabTitle(currentView, title);
            }
        });
        view.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView currentView, String url, android.graphics.Bitmap favicon) {
                Log.i(TAG, "page started url=" + url);
                updateTabUrl(currentView, url);
                rememberRecentUrl(url);
                if (currentView == webView) {
                    updateAddress(url);
                    if (isStartPage(url)) {
                        if (isInternalHome(url)) {
                            showHomePanelReady("internal home started");
                        } else {
                            showHomeLoadingOverlay();
                        }
                    } else {
                        hideHomeLoadingOverlayNow();
                    }
                }
            }

            @Override
            public void onPageFinished(WebView currentView, String url) {
                Log.i(TAG, "page finished url=" + url);
                updateTabUrl(currentView, url);
                if (currentView == webView) {
                    updateAddress(url);
                    if (isInternalHome(url)) showHomePanelReady("internal home finished");
                }
                injectButtons(currentView);
            }

            @Override
            public void doUpdateVisitedHistory(WebView currentView, String url, boolean isReload) {
                Log.i(TAG, "history url=" + url + " reload=" + isReload);
                updateTabUrl(currentView, url);
                rememberRecentUrl(url);
                if (currentView == webView) {
                    updateAddress(url);
                    if (isStartPage(url)) {
                        if (isInternalHome(url)) {
                            showHomePanelReady("internal home history");
                        } else {
                            showHomeLoadingOverlay();
                        }
                    } else {
                        hideHomeLoadingOverlayNow();
                    }
                }
            }
        });
        view.setVisibility(View.GONE);
        return view;
    }

    private LinearLayout createToolbar(int topInset) {
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(8), topInset + dp(6), dp(8), dp(6));
        toolbar.setBackgroundColor(Color.rgb(248, 250, 252));

        TextView back = toolbarAction("‹");
        back.setContentDescription("后退");
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                goBackOrHome();
            }
        });
        TextView reload = toolbarAction("↻");
        reload.setContentDescription("刷新");
        reload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                refreshCurrentPage();
            }
        });
        ImageView brand = toolbarBrand();

        addressBar = new EditText(this);
        addressBar.setSingleLine(true);
        addressBar.setTextSize(14);
        addressBar.setSelectAllOnFocus(true);
        addressBar.setHint("输入配对链接、ZCode 地址或网页链接");
        addressBar.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addressBar.setImeOptions(EditorInfo.IME_ACTION_GO);
        addressBar.setPadding(dp(12), 0, dp(12), 0);
        addressBar.setTextColor(Color.rgb(15, 23, 42));
        addressBar.setHintTextColor(Color.rgb(100, 116, 139));
        addressBar.setBackground(rounded(Color.WHITE, Color.rgb(203, 213, 225), 18));
        addressBar.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_UP;
                if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                    openAddress();
                    return true;
                }
                return false;
            }
        });

        TextView open = toolbarAction("打开");
        open.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openAddress();
            }
        });
        favoriteButton = toolbarAction("☆");
        favoriteButton.setTextSize(20);
        favoriteButton.setContentDescription("收藏当前网页");
        favoriteButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toggleCurrentFavorite();
            }
        });
        readPageButton = toolbarAction("朗读");
        readPageButton.setContentDescription("朗读本页");
        readPageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                readPage();
            }
        });

        toolbar.addView(back, new LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.MATCH_PARENT));
        toolbar.addView(reload, new LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.MATCH_PARENT));
        toolbar.addView(brand, new LinearLayout.LayoutParams(dp(34), ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams addressLayout = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        addressLayout.setMargins(dp(6), 0, dp(6), 0);
        toolbar.addView(addressBar, addressLayout);
        toolbar.addView(favoriteButton, new LinearLayout.LayoutParams(dp(38), ViewGroup.LayoutParams.MATCH_PARENT));
        toolbar.addView(readPageButton, new LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.MATCH_PARENT));
        toolbar.addView(open, new LinearLayout.LayoutParams(dp(46), ViewGroup.LayoutParams.MATCH_PARENT));
        return toolbar;
    }

    private View createTabBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(4), dp(8), dp(4));
        bar.setBackgroundColor(Color.rgb(248, 250, 252));

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        tabStrip = new LinearLayout(this);
        tabStrip.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(tabStrip, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView add = toolbarAction("+");
        add.setContentDescription("新建网页");
        add.setTextSize(20);
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                createNewTab(START_URL, true);
            }
        });

        bar.addView(scroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        LinearLayout.LayoutParams addLayout = new LinearLayout.LayoutParams(dp(38), ViewGroup.LayoutParams.MATCH_PARENT);
        addLayout.setMargins(dp(6), 0, 0, 0);
        bar.addView(add, addLayout);
        return bar;
    }

    private TextView toolbarAction(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.rgb(15, 23, 42));
        view.setTextSize(text.length() > 1 ? 13 : 22);
        view.setGravity(Gravity.CENTER);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setBackground(rounded(Color.rgb(241, 245, 249), Color.rgb(226, 232, 240), 12));
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    private ImageView toolbarBrand() {
        ImageView view = new ImageView(this);
        view.setImageResource(R.drawable.ic_tianyan_logo);
        view.setContentDescription("天眼");
        view.setPadding(dp(4), dp(4), dp(4), dp(4));
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        view.setClickable(true);
        view.setFocusable(true);
        view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View clicked) {
                goHome();
            }
        });
        return view;
    }

    private void createNewTab(String rawUrl, boolean loadUrl) {
        if (tabs.size() >= MAX_TABS) {
            toast("最多同时打开 " + MAX_TABS + " 个网页");
            return;
        }
        BrowserTab tab = new BrowserTab();
        tab.view = createConfiguredWebView();
        tab.url = loadUrl ? normalizeUrl(rawUrl) : rawUrl;
        tab.title = loadUrl ? titleForUrl(tab.url) : rawUrl;
        tab.button = tabButton(tab);
        tabs.add(tab);
        tabStrip.addView(tab.button, new LinearLayout.LayoutParams(dp(128), ViewGroup.LayoutParams.MATCH_PARENT));

        int overlayIndex = homeLoadingOverlay == null ? -1 : webFrame.indexOfChild(homeLoadingOverlay);
        int insertIndex = overlayIndex >= 0 ? overlayIndex : webFrame.getChildCount();
        webFrame.addView(tab.view, insertIndex, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        selectTab(tabs.size() - 1);
        if (loadUrl) {
            loadWebUrlAfterLayout(tab.view, rawUrl);
        } else {
            updateAddress(tab.title);
            updateTabsUi();
        }
    }

    private TextView tabButton(BrowserTab tab) {
        TextView button = new TextView(this);
        button.setSingleLine(true);
        button.setGravity(Gravity.CENTER);
        button.setTextSize(13);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setClickable(true);
        button.setFocusable(true);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                selectTab(tabs.indexOf(tab));
            }
        });
        button.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                closeTab(tabs.indexOf(tab));
                return true;
            }
        });
        return button;
    }

    private void selectTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        if (webView != null) webView.setVisibility(View.GONE);
        activeTabIndex = index;
        BrowserTab tab = tabs.get(index);
        webView = tab.view;
        webView.setVisibility(View.VISIBLE);
        if (homeLoadingOverlay != null) homeLoadingOverlay.bringToFront();
        if (isStartPage(tab.url)) {
            showHomePanelReady("home tab selected");
        } else {
            hideHomeLoadingOverlayNow();
        }
        updateAddress(tab.url);
        updateTabsUi();
        updateFavoriteButton();
        injectButtons();
    }

    private void closeTab(int index) {
        if (tabs.size() <= 1 || index < 0 || index >= tabs.size()) return;
        BrowserTab tab = tabs.remove(index);
        tabStrip.removeView(tab.button);
        webFrame.removeView(tab.view);
        tab.view.destroy();
        if (activeTabIndex >= tabs.size()) activeTabIndex = tabs.size() - 1;
        selectTab(Math.max(0, activeTabIndex));
    }

    private void updateTabUrl(WebView view, String url) {
        BrowserTab tab = tabFor(view);
        if (tab == null || url == null || url.startsWith("data:") || "about:blank".equals(url)) return;
        tab.url = url;
        if (tab.title == null || tab.title.isEmpty() || tab.title.startsWith("http")) {
            tab.title = titleForUrl(url);
        }
        updateTabsUi();
        if (view == webView) updateFavoriteButton();
    }

    private void updateTabTitle(WebView view, String title) {
        BrowserTab tab = tabFor(view);
        if (tab == null || title == null) return;
        String clean = title.trim();
        if (clean.isEmpty() || clean.startsWith("http") || "about:blank".equals(clean)) return;
        tab.title = clean.replace("Paseo", "天眼");
        updateFavoriteTitle(tab.url, tab.title);
        updateTabsUi();
        if (view == webView) updateFavoriteButton();
    }

    private BrowserTab tabFor(WebView view) {
        for (BrowserTab tab : tabs) {
            if (tab.view == view) return tab;
        }
        return null;
    }

    private void updateTabsUi() {
        for (int i = 0; i < tabs.size(); i += 1) {
            BrowserTab tab = tabs.get(i);
            boolean active = i == activeTabIndex;
            tab.button.setText(shortTitle(tab.title));
            tab.button.setTextColor(active ? Color.WHITE : Color.rgb(51, 65, 85));
            int bg = active ? Color.rgb(22, 101, 52) : Color.rgb(241, 245, 249);
            int stroke = active ? Color.rgb(22, 101, 52) : Color.rgb(226, 232, 240);
            tab.button.setBackground(rounded(bg, stroke, 10));
        }
    }

    private String titleForUrl(String url) {
        if (isStartPage(url)) return "天眼";
        if (url == null || url.trim().isEmpty()) return "新网页";
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            return host == null || host.isEmpty() ? url : host.replaceFirst("^www\\.", "");
        } catch (Exception ignored) {
            return url;
        }
    }

    private String shortTitle(String title) {
        String clean = title == null || title.trim().isEmpty() ? "新网页" : title.trim();
        return clean.length() > 10 ? clean.substring(0, 10) + "..." : clean;
    }

    private View createHomeLoadingOverlay() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.WHITE);
        scroll.setVisibility(View.GONE);
        scroll.setClickable(true);

        LinearLayout overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setGravity(Gravity.CENTER_HORIZONTAL);
        overlay.setPadding(dp(28), dp(72), dp(28), dp(28));
        scroll.addView(overlay, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_tianyan_logo);
        logo.setContentDescription("天眼");
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        overlay.addView(logo, new LinearLayout.LayoutParams(dp(112), dp(112)));

        TextView title = new TextView(this);
        title.setText("天眼连接页");
        title.setTextColor(Color.rgb(15, 23, 42));
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLayout = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLayout.setMargins(0, dp(14), 0, 0);
        overlay.addView(title, titleLayout);

        TextView subtitle = new TextView(this);
        homeSubtitle = subtitle;
        subtitle.setText("正在刷新");
        subtitle.setTextColor(Color.rgb(100, 116, 139));
        subtitle.setTextSize(14);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleLayout = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        subtitleLayout.setMargins(0, dp(6), 0, 0);
        overlay.addView(subtitle, subtitleLayout);

        homeUrlInput = new EditText(this);
        homeUrlInput.setSingleLine(true);
        homeUrlInput.setTextSize(15);
        homeUrlInput.setHint("粘贴配对链接或网页地址");
        homeUrlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        homeUrlInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        homeUrlInput.setPadding(dp(14), 0, dp(14), 0);
        homeUrlInput.setTextColor(Color.rgb(15, 23, 42));
        homeUrlInput.setHintTextColor(Color.rgb(100, 116, 139));
        homeUrlInput.setBackground(rounded(Color.WHITE, Color.rgb(203, 213, 225), 14));
        homeUrlInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_UP;
                if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                    openHomeInput();
                    return true;
                }
                return false;
            }
        });
        LinearLayout.LayoutParams inputLayout = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48));
        inputLayout.setMargins(0, dp(28), 0, 0);
        overlay.addView(homeUrlInput, inputLayout);

        TextView open = homeActionButton("打开链接", true);
        open.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openHomeInput();
            }
        });
        LinearLayout.LayoutParams openLayout = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48));
        openLayout.setMargins(0, dp(12), 0, 0);
        overlay.addView(open, openLayout);

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView addFavorite = homeActionButton("添加收藏", false);
        addFavorite.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                addHomeFavorite();
            }
        });
        LinearLayout.LayoutParams addLayout = new LinearLayout.LayoutParams(
                0,
                dp(48),
                1f);
        actionRow.addView(addFavorite, addLayout);

        TextView scan = homeActionButton("扫码配对", false);
        scan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                scanPair();
            }
        });
        LinearLayout.LayoutParams scanLayout = new LinearLayout.LayoutParams(
                0,
                dp(48),
                1f);
        scanLayout.setMargins(dp(10), 0, 0, 0);
        actionRow.addView(scan, scanLayout);

        LinearLayout.LayoutParams rowLayout = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48));
        rowLayout.setMargins(0, dp(10), 0, 0);
        overlay.addView(actionRow, rowLayout);

        homeFavoriteList = new LinearLayout(this);
        homeFavoriteList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams favoriteLayout = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        favoriteLayout.setMargins(0, dp(20), 0, 0);
        overlay.addView(homeFavoriteList, favoriteLayout);

        return scroll;
    }

    private TextView homeActionButton(String text, boolean primary) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        button.setClickable(true);
        button.setFocusable(true);
        button.setTextColor(primary ? Color.WHITE : Color.rgb(15, 23, 42));
        int bg = primary ? Color.rgb(22, 101, 52) : Color.WHITE;
        int stroke = primary ? Color.rgb(22, 101, 52) : Color.rgb(203, 213, 225);
        button.setBackground(rounded(bg, stroke, 14));
        return button;
    }

    private void openHomeInput() {
        if (homeUrlInput == null) return;
        String rawUrl = homeUrlInput.getText().toString().trim();
        hideKeyboard(homeUrlInput);
        homeUrlInput.clearFocus();
        if (rawUrl.isEmpty()) {
            toast("请先粘贴链接");
            return;
        }
        loadWebUrl(rawUrl);
    }

    private void addHomeFavorite() {
        if (homeUrlInput == null) return;
        String rawUrl = homeUrlInput.getText().toString().trim();
        if (rawUrl.isEmpty() && webView != null && shouldRememberUrl(webView.getUrl())) {
            rawUrl = webView.getUrl();
        }
        if (rawUrl.isEmpty()) {
            toast("请先粘贴要收藏的链接");
            return;
        }
        String url = normalizeUrl(rawUrl);
        if (!shouldRememberUrl(url)) {
            toast("只能收藏网页链接");
            return;
        }
        addFavoriteUrl(url, titleForFavorite(url));
        homeUrlInput.setText("");
        refreshHomeFavorites();
        updateFavoriteButton();
        toast("已添加收藏");
    }

    private void toggleCurrentFavorite() {
        String url = currentPageUrl();
        if (!shouldRememberUrl(url)) {
            toast("当前页面不能收藏");
            updateFavoriteButton();
            return;
        }
        if (isFavoriteUrl(url)) {
            removeFavoriteUrl(url);
            refreshHomeFavorites();
            updateFavoriteButton();
            toast("已取消收藏");
        } else {
            addFavoriteUrl(url, titleForFavorite(url));
            refreshHomeFavorites();
            updateFavoriteButton();
            toast("已收藏");
        }
    }

    private String currentPageUrl() {
        BrowserTab tab = tabFor(webView);
        if (tab != null && shouldRememberUrl(tab.url)) return tab.url;
        return webView == null ? "" : webView.getUrl();
    }

    private void refreshHomeFavorites() {
        if (homeFavoriteList == null) return;
        homeFavoriteList.removeAllViews();
        List<FavoriteEntry> favorites = favoriteList();
        homeFavoriteList.setVisibility(View.VISIBLE);

        TextView title = new TextView(this);
        title.setText("收藏网页");
        title.setTextColor(Color.rgb(71, 85, 105));
        title.setTextSize(13);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        homeFavoriteList.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        if (favorites.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("粘贴链接后点添加收藏");
            empty.setTextColor(Color.rgb(100, 116, 139));
            empty.setTextSize(13);
            empty.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams emptyLayout = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            emptyLayout.setMargins(0, dp(10), 0, 0);
            homeFavoriteList.addView(empty, emptyLayout);
            return;
        }

        for (FavoriteEntry favorite : favorites) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView item = homeActionButton(shortFavoriteLabel(favorite), false);
            item.setTextSize(14);
            item.setContentDescription(favorite.url);
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    loadWebUrl(favorite.url);
                }
            });
            LinearLayout.LayoutParams itemLayout = new LinearLayout.LayoutParams(
                    0,
                    dp(44),
                    1f);
            itemLayout.setMargins(0, 0, dp(8), 0);
            row.addView(item, itemLayout);

            TextView remove = homeActionButton("删除", false);
            remove.setTextSize(13);
            remove.setTextColor(Color.rgb(185, 28, 28));
            remove.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    removeFavoriteUrl(favorite.url);
                    refreshHomeFavorites();
                    updateFavoriteButton();
                    toast("已删除收藏");
                }
            });
            row.addView(remove, new LinearLayout.LayoutParams(dp(76), dp(44)));

            LinearLayout.LayoutParams rowLayout = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(44));
            rowLayout.setMargins(0, dp(8), 0, 0);
            homeFavoriteList.addView(row, rowLayout);
        }
    }

    private GradientDrawable rounded(int color, int stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private void openAddress() {
        if (addressBar == null) return;
        String rawUrl = addressBar.getText().toString().trim();
        hideKeyboard(addressBar);
        addressBar.clearFocus();
        if (rawUrl.isEmpty() || HOME_LABEL.equals(rawUrl)) {
            Log.i(TAG, "toolbar open home raw=" + rawUrl);
            showInternalHomePage();
            return;
        }
        Log.i(TAG, "toolbar open raw=" + rawUrl);
        loadWebUrl(rawUrl);
    }

    private void goHome() {
        Log.i(TAG, "toolbar home");
        showInternalHomePage();
    }

    private void goBackOrHome() {
        if (webView == null) return;
        boolean canGoBack = webView.canGoBack();
        Log.i(TAG, "toolbar back canGoBack=" + canGoBack);
        if (canGoBack) {
            webView.goBack();
        } else {
            showInternalHomePage();
        }
    }

    private void refreshCurrentPage() {
        if (webView == null) return;
        String currentUrl = webView.getUrl();
        Log.i(TAG, "toolbar refresh url=" + currentUrl);
        if (currentUrl == null || currentUrl.trim().isEmpty() || currentUrl.startsWith("data:") || isStartPage(currentUrl)) {
            showInternalHomePage();
        } else {
            webView.reload();
        }
    }

    private void readPage() {
        if (readingPage) {
            stopTts();
            return;
        }
        readingPage = true;
        final int requestId = readPageRequestId + 1;
        readPageRequestId = requestId;
        updateReadPageButton(true);
        injectButtons();
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!readingPage || requestId != readPageRequestId) return;
                if (webView != null) {
                    webView.evaluateJavascript(
                            "Boolean(window.__ttsBrowserReadPage && window.__ttsBrowserReadPage())",
                            new android.webkit.ValueCallback<String>() {
                                @Override
                                public void onReceiveValue(String value) {
                                    if (!"true".equals(value) && requestId == readPageRequestId) {
                                        readingPage = false;
                                        updateReadPageButton(false);
                                    }
                                }
                            });
                } else {
                    readingPage = false;
                    updateReadPageButton(false);
                }
            }
        }, 150);
    }

    private String initialUrlFromIntent(Intent intent) {
        String explicitUrl = explicitUrlFromIntent(intent);
        if (!explicitUrl.isEmpty()) return explicitUrl;
        return START_URL;
    }

    private String explicitUrlFromIntent(Intent intent) {
        if (intent != null) {
            Uri uri = intent.getData();
            if (uri != null) return uri.toString();
            String extraUrl = intent.getStringExtra("url");
            if (extraUrl != null && !extraUrl.trim().isEmpty()) return extraUrl.trim();
        }
        return "";
    }

    private void loadWebUrl(String rawUrl) {
        if (webView == null) {
            createNewTab(rawUrl, true);
            return;
        }
        clearChromeInputFocus();
        String url = normalizeUrl(rawUrl);
        Log.i(TAG, "load url=" + url + " raw=" + rawUrl);
        if (isStartPage(url)) {
            showInternalHomePage();
            return;
        }
        hideHomeLoadingOverlayNow();
        BrowserTab tab = tabFor(webView);
        if (tab != null) {
            tab.url = url;
            tab.title = titleForUrl(url);
            updateTabsUi();
        }
        updateAddress(url);
        updateFavoriteButton();
        rememberRecentUrl(url);
        webView.loadUrl(url);
    }

    private void loadWebUrlAfterLayout(final WebView target, final String rawUrl) {
        if (target == null) {
            loadWebUrl(rawUrl);
            return;
        }
        target.post(new Runnable() {
            int attempts = 0;

            @Override
            public void run() {
                attempts += 1;
                if (target.getParent() == null) return;
                if (target.getWidth() > 0 && target.getHeight() > 0) {
                    BrowserTab tab = tabFor(target);
                    if (tab == null) return;
                    if (target != webView) selectTab(tabs.indexOf(tab));
                    Log.i(TAG, "load after layout w=" + target.getWidth() + " h=" + target.getHeight());
                    loadWebUrl(rawUrl);
                    return;
                }
                if (attempts < 40) {
                    target.postDelayed(this, 50);
                    return;
                }
                Log.i(TAG, "load before layout timeout");
                loadWebUrl(rawUrl);
            }
        });
    }

    private void rememberRecentUrl(String rawUrl) {
        // Homepage quick links are explicit favorites only.
    }

    private boolean shouldRememberUrl(String url) {
        if (url == null) return false;
        if (url.isEmpty() || isStartPage(url)) return false;
        if (url.startsWith("data:") || url.startsWith("about:") || url.startsWith("debug:")) return false;
        try {
            String scheme = Uri.parse(url).getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void addFavoriteUrl(String rawUrl, String title) {
        String url = rawUrl == null ? "" : rawUrl.trim();
        if (!shouldRememberUrl(url)) return;
        String cleanTitle = cleanFavoriteTitle(title);
        List<FavoriteEntry> favorites = favoriteList();
        for (int i = favorites.size() - 1; i >= 0; i -= 1) {
            if (sameUrl(favorites.get(i).url, url)) favorites.remove(i);
        }
        favorites.add(0, new FavoriteEntry(url, cleanTitle));
        while (favorites.size() > MAX_FAVORITE_URLS) favorites.remove(favorites.size() - 1);
        saveFavorites(favorites);
        if (cleanTitle.isEmpty()) fetchFavoriteTitle(url);
    }

    private void removeFavoriteUrl(String rawUrl) {
        String url = rawUrl == null ? "" : rawUrl.trim();
        List<FavoriteEntry> favorites = favoriteList();
        for (int i = favorites.size() - 1; i >= 0; i -= 1) {
            if (sameUrl(favorites.get(i).url, url)) favorites.remove(i);
        }
        saveFavorites(favorites);
    }

    private boolean isFavoriteUrl(String rawUrl) {
        String url = rawUrl == null ? "" : rawUrl.trim();
        if (!shouldRememberUrl(url)) return false;
        for (FavoriteEntry favorite : favoriteList()) {
            if (sameUrl(favorite.url, url)) return true;
        }
        return false;
    }

    private void updateFavoriteTitle(String rawUrl, String title) {
        String url = rawUrl == null ? "" : rawUrl.trim();
        String cleanTitle = cleanFavoriteTitle(title);
        if (!shouldRememberUrl(url) || cleanTitle.isEmpty()) return;
        List<FavoriteEntry> favorites = favoriteList();
        boolean changed = false;
        for (FavoriteEntry favorite : favorites) {
            if (sameUrl(favorite.url, url) && !cleanTitle.equals(favorite.title)) {
                favorite.title = cleanTitle;
                changed = true;
            }
        }
        if (changed) {
            saveFavorites(favorites);
            refreshHomeFavorites();
        }
    }

    private List<FavoriteEntry> favoriteList() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String saved = prefs.getString(PREF_FAVORITE_URLS, "");
        List<FavoriteEntry> favorites = new ArrayList<>();
        if (saved == null || saved.trim().isEmpty()) return favorites;
        String[] lines = saved.split("\\n");
        for (String line : lines) {
            FavoriteEntry favorite = parseFavoriteLine(line);
            if (shouldRememberUrl(favorite.url) && favorites.size() < MAX_FAVORITE_URLS) {
                favorites.add(favorite);
            }
        }
        return favorites;
    }

    private List<String> favoriteUrlList() {
        List<String> urls = new ArrayList<>();
        for (FavoriteEntry favorite : favoriteList()) urls.add(favorite.url);
        return urls;
    }

    private void saveFavorites(List<FavoriteEntry> favorites) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(PREF_FAVORITE_URLS, joinFavorites(favorites))
                .apply();
    }

    private FavoriteEntry parseFavoriteLine(String line) {
        String raw = line == null ? "" : line.trim();
        int split = raw.indexOf('\t');
        if (split < 0) return new FavoriteEntry(raw, "");
        String url = raw.substring(0, split).trim();
        String title = "";
        try {
            title = Uri.decode(raw.substring(split + 1));
        } catch (Exception ignored) {
            title = "";
        }
        return new FavoriteEntry(url, cleanFavoriteTitle(title));
    }

    private String joinFavorites(List<FavoriteEntry> favorites) {
        StringBuilder builder = new StringBuilder();
        for (FavoriteEntry favorite : favorites) {
            if (builder.length() > 0) builder.append('\n');
            builder.append(favorite.url).append('\t').append(Uri.encode(favorite.title == null ? "" : favorite.title));
        }
        return builder.toString();
    }

    private String titleForFavorite(String url) {
        for (BrowserTab tab : tabs) {
            if (sameUrl(tab.url, url)) return cleanFavoriteTitle(tab.title);
        }
        if (webView != null && sameUrl(webView.getUrl(), url)) {
            return cleanFavoriteTitle(webView.getTitle());
        }
        return "";
    }

    private String cleanFavoriteTitle(String title) {
        String clean = title == null ? "" : title.trim().replace("Paseo", "天眼");
        if (clean.isEmpty() || "about:blank".equals(clean) || HOME_LABEL.equals(clean)) return "";
        if (clean.startsWith("http://") || clean.startsWith("https://")) return "";
        return clean;
    }

    private void fetchFavoriteTitle(final String url) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String title = readRemoteTitle(url);
                if (title.isEmpty()) return;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        updateFavoriteTitle(url, title);
                    }
                });
            }
        }).start();
    }

    private String readRemoteTitle(String rawUrl) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(rawUrl).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 Tianyan");
            int code = connection.getResponseCode();
            if (code >= 400) return "";
            InputStream input = connection.getInputStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1 && total < MAX_TITLE_HTML_CHARS) {
                int keep = Math.min(read, MAX_TITLE_HTML_CHARS - total);
                output.write(buffer, 0, keep);
                total += keep;
            }
            input.close();
            String html = output.toString(StandardCharsets.UTF_8.name());
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("(?is)<title[^>]*>(.*?)</title>")
                    .matcher(html);
            if (!matcher.find()) return "";
            String title = android.text.Html.fromHtml(matcher.group(1)).toString();
            return cleanFavoriteTitle(title.replaceAll("\\s+", " "));
        } catch (Exception error) {
            Log.i(TAG, "favorite title fetch failed url=" + rawUrl + " error=" + error.getClass().getSimpleName());
            return "";
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String shortFavoriteLabel(FavoriteEntry favorite) {
        String label = favorite.title == null || favorite.title.trim().isEmpty()
                ? titleForUrl(favorite.url)
                : favorite.title.trim();
        return label.length() > 34 ? label.substring(0, 31) + "..." : label;
    }

    private boolean sameUrl(String first, String second) {
        return trimTrailingSlash(first).equalsIgnoreCase(trimTrailingSlash(second));
    }

    private String trimTrailingSlash(String url) {
        String clean = url == null ? "" : url.trim();
        while (clean.endsWith("/") && clean.length() > "https://x".length()) {
            clean = clean.substring(0, clean.length() - 1);
        }
        return clean;
    }

    private String favoriteUrlsJson() {
        List<String> urls = favoriteUrlList();
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < urls.size(); i += 1) {
            if (i > 0) builder.append(',');
            builder.append('"').append(jsonEscape(urls.get(i))).append('"');
        }
        builder.append(']');
        return builder.toString();
    }

    private String favoriteItemsJson() {
        List<FavoriteEntry> favorites = favoriteList();
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < favorites.size(); i += 1) {
            FavoriteEntry favorite = favorites.get(i);
            if (i > 0) builder.append(',');
            builder.append("{\"url\":\"")
                    .append(jsonEscape(favorite.url))
                    .append("\",\"title\":\"")
                    .append(jsonEscape(favorite.title == null ? "" : favorite.title))
                    .append("\"}");
        }
        builder.append(']');
        return builder.toString();
    }

    private String jsonEscape(String value) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i += 1) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\':
                    builder.append("\\\\");
                    break;
                case '"':
                    builder.append("\\\"");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                case '\t':
                    builder.append("\\t");
                    break;
                default:
                    if (ch < 0x20) {
                        String hex = Integer.toHexString(ch);
                        builder.append("\\u");
                        for (int pad = hex.length(); pad < 4; pad += 1) builder.append('0');
                        builder.append(hex);
                    } else {
                        builder.append(ch);
                    }
                    break;
            }
        }
        return builder.toString();
    }

    private boolean isStartPage(String url) {
        if (url == null) return false;
        String trimmed = url.trim();
        String welcomeUrl = PASEO_HOME_URL + "welcome";
        return isInternalHome(trimmed)
                || PASEO_HOME_URL.equals(trimmed)
                || "https://app.paseo.sh".equals(trimmed)
                || welcomeUrl.equals(trimmed)
                || trimmed.startsWith(welcomeUrl + "?")
                || trimmed.startsWith(welcomeUrl + "#");
    }

    private boolean isInternalHome(String url) {
        if (url == null) return false;
        String trimmed = url.trim();
        return HOME_INTERNAL_URL.equals(trimmed)
                || trimmed.startsWith(HOME_INTERNAL_URL + "?")
                || trimmed.startsWith(HOME_INTERNAL_URL + "#");
    }

    private void showInternalHomePage() {
        if (webView == null) return;
        Log.i(TAG, "show internal home");
        BrowserTab tab = tabFor(webView);
        if (tab != null) {
            tab.url = START_URL;
            tab.title = titleForUrl(START_URL);
            updateTabsUi();
        }
        updateAddress(START_URL);
        updateFavoriteButton();
        showHomePanelReady("internal home panel ready");
        webView.loadDataWithBaseURL(HOME_INTERNAL_URL, homeHtml(), "text/html", "UTF-8", null);
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                showHomePanelReady("internal home ready");
            }
        }, 150);
    }

    private String homeHtml() {
        return "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>天眼</title><style>html,body{margin:0;min-height:100%;background:#f8fafc}</style></head>"
                + "<body><script>setTimeout(function(){try{TtsBrowserBridge.homeReady()}catch(e){}},0)</script></body></html>";
    }

    private void showHomeLoadingOverlay() {
        if (homeLoadingOverlay == null) return;
        mainHandler.removeCallbacks(hideHomeOverlayRunnable);
        mainHandler.removeCallbacks(forceHomeOverlayHideRunnable);
        if (homeSubtitle != null) homeSubtitle.setText("正在刷新");
        refreshHomeFavorites();
        homeLoadingOverlay.setVisibility(View.VISIBLE);
        homeLoadingOverlay.bringToFront();
        mainHandler.postDelayed(forceHomeOverlayHideRunnable, HOME_OVERLAY_MAX_WAIT_MS);
        Log.i(TAG, "home overlay show");
    }

    private void hideHomeLoadingOverlayAfterReady() {
        mainHandler.removeCallbacks(hideHomeOverlayRunnable);
        mainHandler.removeCallbacks(forceHomeOverlayHideRunnable);
        showHomePanelReady("home panel ready");
    }

    private void showHomePanelReady(String logMessage) {
        if (homeLoadingOverlay == null) return;
        if (homeSubtitle != null) homeSubtitle.setText("粘贴链接，添加收藏后快速打开");
        refreshHomeFavorites();
        homeLoadingOverlay.setVisibility(View.VISIBLE);
        homeLoadingOverlay.bringToFront();
        Log.i(TAG, logMessage);
    }

    private void hideHomeLoadingOverlayNow() {
        mainHandler.removeCallbacks(hideHomeOverlayRunnable);
        mainHandler.removeCallbacks(forceHomeOverlayHideRunnable);
        if (homeLoadingOverlay != null) homeLoadingOverlay.setVisibility(View.GONE);
    }

    private String normalizeUrl(String rawUrl) {
        String url = rawUrl == null ? "" : rawUrl.trim();
        url = url.replace('。', '.').replace('．', '.').replace('｡', '.');
        if (url.isEmpty() || url.equals(HOME_LABEL) || url.equals("天眼测试页") || url.startsWith("debug://")) return START_URL;
        if (!url.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) {
            url = "https://" + url;
        }
        return url;
    }

    private void scanPair() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                Intent intent = new Intent("miui.intent.action.scanbarcode");
                intent.setPackage("com.xiaomi.scanner");
                intent.addCategory(Intent.CATEGORY_DEFAULT);
                intent.putExtra("isBackToThirdApp", true);
                try {
                    startActivityForResult(intent, SCAN_REQUEST);
                    Log.i(TAG, "scanner launched");
                } catch (Exception firstError) {
                    Intent fallback = new Intent(Intent.ACTION_MAIN);
                    fallback.setPackage("com.xiaomi.scanner");
                    fallback.addCategory(Intent.CATEGORY_LAUNCHER);
                    try {
                        startActivity(fallback);
                        toast("扫码后选择用天眼打开配对链接");
                        Log.i(TAG, "scanner launcher opened");
                    } catch (Exception secondError) {
                        Log.e(TAG, "scanner unavailable", secondError);
                        toast("未找到可用扫码器");
                    }
                }
            }
        });
    }

    private String resultTextFromIntent(Intent data) {
        if (data == null) return "";
        String directResult = data.getStringExtra("result");
        if (directResult != null && !directResult.trim().isEmpty()) return directResult;
        Uri uri = data.getData();
        if (uri != null) return uri.toString();
        Bundle extras = data.getExtras();
        if (extras == null) return "";
        StringBuilder builder = new StringBuilder();
        for (String key : extras.keySet()) {
            Object value = extras.get(key);
            if (value != null) {
                if (builder.length() > 0) builder.append('\n');
                builder.append(key).append('=').append(value);
            }
        }
        return builder.toString();
    }

    private String firstUrl(String text) {
        if (text == null) return "";
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(https?://[^\\s\"'<>]+|www\\.[^\\s\"'<>]+)")
                .matcher(text);
        if (!matcher.find()) return "";
        return matcher.group(1);
    }

    private void updateAddress(String url) {
        if (addressBar == null || url == null || url.startsWith("data:")) return;
        if (!addressBar.hasFocus()) {
            addressBar.setText(isStartPage(url) ? HOME_LABEL : url);
        }
        updateFavoriteButton();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int statusBarHeight() {
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return resourceId > 0 ? getResources().getDimensionPixelSize(resourceId) : 0;
    }

    private boolean clearChromeInputFocus() {
        View focused = getCurrentFocus();
        if (addressBar != null && focused == addressBar) {
            hideKeyboard(addressBar);
            addressBar.clearFocus();
            return true;
        }
        if (homeUrlInput != null && focused == homeUrlInput) {
            hideKeyboard(homeUrlInput);
            homeUrlInput.clearFocus();
            return true;
        }
        return false;
    }

    private void hideKeyboard(View view) {
        if (view == null) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    private void initTts() {
        tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status != TextToSpeech.SUCCESS) {
                    Log.e(TAG, "TTS init failed: " + status);
                    toast("语音引擎初始化失败");
                    return;
                }
                int language = tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
                if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
                    language = tts.setLanguage(Locale.CHINA);
                }
                tts.setSpeechRate(1.2f);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {
                        Log.i(TAG, "TTS start " + utteranceId);
                        sendState("playing");
                    }

                    @Override
                    public void onDone(String utteranceId) {
                        Log.i(TAG, "TTS done " + utteranceId);
                        if (utteranceId.equals(lastUtteranceId)) sendState("done");
                    }

                    @Override
                    public void onError(String utteranceId) {
                        Log.e(TAG, "TTS error " + utteranceId);
                        if (utteranceId.equals(lastUtteranceId)) sendState("error");
                    }
                });
                synchronized (ttsLock) {
                    ttsReady = true;
                }
                Log.i(TAG, "TTS ready language=" + language + " rate=1.2");
            }
        });
    }

    private void injectButtons() {
        if (webView == null) return;
        injectButtons(webView);
    }

    private void injectButtons(WebView target) {
        if (target == null) return;
        String script = getInjectedScript();
        if (script == null || script.isEmpty()) return;
        target.evaluateJavascript(script, null);
    }

    private String getInjectedScript() {
        if (injectedScript != null) return injectedScript;
        try (InputStream stream = getAssets().open("paseo_tts.js");
             BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
            injectedScript = builder.toString();
            return injectedScript;
        } catch (Exception error) {
            Log.e(TAG, "Cannot load injected script", error);
            return "";
        }
    }

    private boolean speakText(String raw) {
        String text = normalizeText(raw);
        if (text.isEmpty()) return false;
        synchronized (ttsLock) {
            if (!ttsReady || tts == null) {
                toast("语音引擎还没准备好");
                return false;
            }
            batchId += 1;
            int currentBatch = batchId;
            List<String> segments = splitSegments(text, MAX_SEGMENT_CHARS);
            lastUtteranceId = utteranceId(currentBatch, segments.size() - 1);
            for (int i = 0; i < segments.size(); i += 1) {
                Bundle params = new Bundle();
                String utteranceId = utteranceId(currentBatch, i);
                params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);
                int queueMode = i == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
                int result = tts.speak(segments.get(i), queueMode, params, utteranceId);
                if (result != TextToSpeech.SUCCESS) {
                    Log.e(TAG, "TTS speak failed segment=" + i + " result=" + result);
                    sendState("error");
                    return false;
                }
            }
            Log.i(TAG, "TTS queued chars=" + text.length() + " segments=" + segments.size());
            return true;
        }
    }

    private boolean speakPageText(String raw) {
        readingPage = true;
        updateReadPageButton(true);
        boolean ok = speakText(raw);
        if (!ok) {
            readingPage = false;
            updateReadPageButton(false);
        }
        return ok;
    }

    private void stopTts() {
        readPageRequestId += 1;
        readingPage = false;
        updateReadPageButton(false);
        synchronized (ttsLock) {
            batchId += 1;
            lastUtteranceId = "";
            if (tts != null) tts.stop();
        }
        sendState("idle");
        Log.i(TAG, "TTS stopped");
    }

    private String utteranceId(int currentBatch, int index) {
        return "paseo-" + currentBatch + "-" + index;
    }

    private String normalizeText(String raw) {
        String text = raw == null ? "" : raw.replaceAll("\\s+", " ").trim();
        if (text.length() > MAX_TEXT_CHARS) {
            text = text.substring(0, MAX_TEXT_CHARS).trim() + "。内容较长，后续省略。";
        }
        return text;
    }

    private List<String> splitSegments(String text, int maxChars) {
        List<String> segments = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxChars, text.length());
            if (end < text.length()) {
                int split = findSplitPoint(text, start, end);
                if (split > start) end = split;
            }
            String segment = text.substring(start, end).trim();
            if (!segment.isEmpty()) segments.add(segment);
            start = end;
        }
        return segments;
    }

    private int findSplitPoint(String text, int start, int end) {
        int floor = start + Math.max(80, (end - start) / 2);
        for (int i = end - 1; i >= floor; i -= 1) {
            char ch = text.charAt(i);
            if ("。！？!?；;，,、 ".indexOf(ch) >= 0) return i + 1;
        }
        return end;
    }

    private void sendState(String state) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if ("idle".equals(state) || "done".equals(state) || "error".equals(state)) {
                    readingPage = false;
                    updateReadPageButton(false);
                } else if ("playing".equals(state) && readingPage) {
                    updateReadPageButton(true);
                }
                if (webView == null) return;
                String escaped = state.replace("\\", "\\\\").replace("'", "\\'");
                webView.evaluateJavascript(
                        "window.__ttsBrowserSetState && window.__ttsBrowserSetState('" + escaped + "')",
                        null);
            }
        });
    }

    private void updateReadPageButton(final boolean active) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    updateReadPageButton(active);
                }
            });
            return;
        }
        if (readPageButton == null) return;
        readPageButton.setText(active ? "停止" : "朗读");
        readPageButton.setContentDescription(active ? "停止朗读" : "朗读本页");
        readPageButton.setTextColor(active ? Color.WHITE : Color.rgb(15, 23, 42));
        int bg = active ? Color.rgb(185, 28, 28) : Color.rgb(241, 245, 249);
        int stroke = active ? Color.rgb(185, 28, 28) : Color.rgb(226, 232, 240);
        readPageButton.setBackground(rounded(bg, stroke, 12));
    }

    private void updateFavoriteButton() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    updateFavoriteButton();
                }
            });
            return;
        }
        if (favoriteButton == null) return;
        String url = currentPageUrl();
        boolean canFavorite = shouldRememberUrl(url);
        boolean favorite = canFavorite && isFavoriteUrl(url);
        favoriteButton.setText(favorite ? "★" : "☆");
        favoriteButton.setContentDescription(favorite ? "取消收藏当前网页" : "收藏当前网页");
        favoriteButton.setTextColor(favorite ? Color.WHITE : (canFavorite ? Color.rgb(15, 23, 42) : Color.rgb(148, 163, 184)));
        int bg = favorite ? Color.rgb(22, 101, 52) : Color.rgb(241, 245, 249);
        int stroke = favorite ? Color.rgb(22, 101, 52) : Color.rgb(226, 232, 240);
        favoriteButton.setBackground(rounded(bg, stroke, 12));
    }

    private void toast(String message) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private String debugHtml() {
        String text1 = "这是第一段测试文字，用来验证段落下面的播放按钮可以开始播放并正常结束。";
        String text2 = "第二段包含网页链接 https://app.paseo.sh/some/very/long/path 和文件路径 "
                + "/workspace/project/tools/server.py，朗读时应该读成链接和文件名，不应该逐字读完整路径。";
        String text3 = "第三段继续延长内容，用来验证较长文本会进入分段队列。英文单词应该按完整单词朗读，比如 Paseo, Android, WebView, Text to Speech。"
                + "下面故意放一小段命令：`python3 tools/test.py --url https://example.com/a/b`。播放结束后按钮应该自动恢复为播放语音。";
        String text4 = "你好，有什么要做的？";
        String text5 = "如果我是创作页面的第二段内容，第一段问候语下面也应该有播放按钮。";
        String text6 = "这是用户自己发送出去的内容，下面不应该出现播放语音按钮。";
        String text7 = "这是助手回复内容，下面应该出现播放语音按钮，而且按钮不能挤进正文布局里。";
        return "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>body{margin:24px;font:16px sans-serif;background:#111827;color:#e5e7eb}"
                + "p,.message div{line-height:1.7;padding:0;max-width:760px}.message{margin-bottom:20px}"
                + "main{padding-bottom:190px}.bottom-composer{position:fixed;left:24px;right:24px;bottom:18px;padding:12px;border:1px solid #334155;border-radius:14px;background:#0f172a}"
                + "input,textarea{display:block;width:100%;box-sizing:border-box;margin:0 0 16px;padding:14px;border-radius:10px;border:1px solid #64748b;background:#020617;color:#e5e7eb;font:16px sans-serif}"
                + ".bottom-composer textarea{margin:0}</style></head>"
                + "<body><main><input aria-label='输入法测试' placeholder='输入法测试'><textarea aria-label='多行输入法测试' placeholder='多行输入法测试'></textarea>"
                + "<section class='message'><div>" + text4 + "</div><div>" + text5 + "</div></section>"
                + "<section class='message user' data-debug-user data-message-author-role='user'><p>" + text6 + "</p></section>"
                + "<section class='message assistant' data-debug-assistant data-message-author-role='assistant'><p>" + text7 + "</p></section>"
                + "<p>" + text1 + "</p><p>" + text2 + "</p><p>" + text3 + "</p>"
                + "<div class='bottom-composer'><textarea aria-label='底部输入法测试' placeholder='底部输入法测试'></textarea></div></main></body></html>";
    }

    private void runAutoImeDebugTest() {
        if (webView == null) return;
        Log.i(TAG, "ime test run");
        clearChromeInputFocus();
        webView.requestFocusFromTouch();
        webView.requestFocus();
        webView.evaluateJavascript(
                "(function(){"
                        + "var target=document.querySelector('[aria-label=\"底部输入法测试\"]');"
                        + "if(!target)return 'missing';"
                        + "target.focus();"
                        + "return 'started';"
                        + "})()",
                new android.webkit.ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        Log.i(TAG, "ime test start=" + value);
                    }
                });
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (webView == null) return;
                webView.evaluateJavascript(
                        "(function(){"
                                + "var target=document.querySelector('[aria-label=\"底部输入法测试\"]');"
                                + "if(!target)return 'missing';"
                                + "var rect=target.getBoundingClientRect();"
                                + "var fixed=target.closest('.bottom-composer');"
                                + "var box=fixed?fixed.getBoundingClientRect():rect;"
                                + "var vv=window.visualViewport;"
                                + "var bottom=vv?vv.offsetTop+vv.height:window.innerHeight;"
                                + "var user=document.querySelector('[data-debug-user]');"
                                + "var assistant=document.querySelector('[data-debug-assistant]');"
                                + "var userButtons=user?user.querySelectorAll('[data-testid=\"tts-browser-play\"]').length:-1;"
                                + "var assistantButtons=assistant?assistant.querySelectorAll('[data-testid=\"tts-browser-play\"]').length:-1;"
                                + "return JSON.stringify({"
                                + "inputTop:Math.round(rect.top),"
                                + "inputBottom:Math.round(rect.bottom),"
                                + "containerTop:Math.round(box.top),"
                                + "containerBottom:Math.round(box.bottom),"
                                + "viewportBottom:Math.round(bottom),"
                                + "innerHeight:Math.round(window.innerHeight),"
                                + "userButtons:userButtons,"
                                + "assistantButtons:assistantButtons,"
                                + "visible:rect.bottom<=bottom-16"
                                + "});"
                                + "})()",
                        new android.webkit.ValueCallback<String>() {
                            @Override
                            public void onReceiveValue(String value) {
                                Log.i(TAG, "ime test metrics=" + value);
                            }
                        });
            }
        }, 3000);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String explicitUrl = explicitUrlFromIntent(intent);
        if (explicitUrl.isEmpty()) {
            Log.i(TAG, "launcher resume keeps current page");
            return;
        }
        if (intent != null && intent.getData() != null) {
            createNewTab(explicitUrl, true);
        } else {
            loadWebUrl(explicitUrl);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != SCAN_REQUEST) return;
        String result = resultTextFromIntent(data);
        String url = firstUrl(result);
        Log.i(TAG, "scanner resultCode=" + resultCode + " result=" + result);
        if (!url.isEmpty()) {
            loadWebUrl(url);
        } else {
            toast("扫码完成后未收到配对链接");
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(reinjectRunnable);
        mainHandler.removeCallbacks(hideHomeOverlayRunnable);
        mainHandler.removeCallbacks(forceHomeOverlayHideRunnable);
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        for (BrowserTab tab : tabs) {
            if (tab.view != null) tab.view.destroy();
        }
        super.onDestroy();
    }

    private static class BrowserTab {
        WebView view;
        TextView button;
        String url;
        String title;
    }

    private static class FavoriteEntry {
        String url;
        String title;

        FavoriteEntry(String url, String title) {
            this.url = url == null ? "" : url.trim();
            this.title = title == null ? "" : title.trim();
        }
    }

    public class TtsBridge {
        @JavascriptInterface
        public boolean speak(String text) {
            readPageRequestId += 1;
            readingPage = false;
            updateReadPageButton(false);
            return speakText(text);
        }

        @JavascriptInterface
        public boolean speakPage(String text) {
            return speakPageText(text);
        }

        @JavascriptInterface
        public void stop() {
            stopTts();
        }

        @JavascriptInterface
        public void scanPair() {
            MainActivity.this.scanPair();
        }

        @JavascriptInterface
        public String recentUrls() {
            return favoriteUrlsJson();
        }

        @JavascriptInterface
        public String favoriteUrls() {
            return favoriteUrlsJson();
        }

        @JavascriptInterface
        public String favoriteItems() {
            return favoriteItemsJson();
        }

        @JavascriptInterface
        public void openUrl(String url) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    loadWebUrl(url);
                }
            });
        }

        @JavascriptInterface
        public void homeReady() {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    Log.i(TAG, "home ready from js");
                    hideHomeLoadingOverlayAfterReady();
                }
            });
        }
    }
}
