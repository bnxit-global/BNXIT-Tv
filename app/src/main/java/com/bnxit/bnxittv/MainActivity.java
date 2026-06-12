package com.bnxit.bnxittv;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.media3.ui.PlayerView;

import java.util.List;

/**
 * Main activity for BNXIT TV IPTV player.
 *
 * Layout: [Categories] | [Channel List] | [Video Player]
 *
 * TV-first design:
 * - D-pad navigation between panels
 * - Focus-based highlighting
 * - Instant channel switching
 * - Auto-play last channel on startup
 * - Loading/error/placeholder overlays (alpha-animated to prevent scroll resets)
 * - Remote JSON loading from GitHub with cache + asset fallback
 * - Channel search with live filtering
 */
public class MainActivity extends AppCompatActivity implements
        PlayerManager.PlayerCallback,
        ChannelAdapter.OnChannelClickListener,
        CategoryAdapter.OnCategoryClickListener {

    private static final String TAG = "MainActivity";
    private static final long NOW_PLAYING_HIDE_DELAY = 4000;
    private static final long AUTO_HIDE_DELAY_MS = 10000;

    // UI Components
    private RecyclerView rvCategories;
    private RecyclerView rvChannels;
    private PlayerView playerView;
    private TextView tvCategoryTitle;
    private TextView tvChannelCount;
    private TextView tvNowPlayingName;
    private LinearLayout nowPlayingBar;
    private View loadingOverlay;
    private View errorOverlay;
    private View panelsContainer;
    private View startupLoader;

    // Search Components
    private View searchPanel;
    private EditText etSearch;
    private TextView tvSearchCount;
    private RecyclerView rvSearchResults;
    private TextView btnSearch;
    private ChannelAdapter searchAdapter;

    // Controls HUD Components
    private View controlsHud;
    private TextView btnQuality;
    private TextView btnAspect;
    private TextView btnRefresh;
    private TextView btnMenu;
    private TextView btnDeveloper;
    private View developerInfoOverlay;
    private TextView btnCloseDeveloper;
    private ScrollView bioScrollView;
    private boolean isDeveloperOverlayVisible = false;

    // Adapters
    private CategoryAdapter categoryAdapter;
    private ChannelAdapter channelAdapter;

    // Managers
    private PlayerManager playerManager;
    private PreferenceManager prefManager;
    private JsonLoader jsonLoader;

    // State
    private String currentCategory = "All";
    private boolean isPanelVisible = true;
    private boolean isInitialLoadDone = false;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoHideRunnable = () -> {
        togglePanels(false);
        hideControlsHud();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep screen on during playback
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);

        initViews();
        initAdapters();
        initPlayer();
        initControlListeners();
        initSearch();

        // Screen tap toggles controls HUD or hides side panels
        playerView.setOnClickListener(v -> {
            if (isPanelVisible) {
                togglePanels(false);
            } else {
                if (isControlsHudVisible()) {
                    hideControlsHud();
                } else {
                    showControlsHud();
                }
            }
        });

        loadChannelData();
    }

    private void initViews() {
        rvCategories = findViewById(R.id.rv_categories);
        rvChannels = findViewById(R.id.rv_channels);
        playerView = findViewById(R.id.player_view);
        tvCategoryTitle = findViewById(R.id.tv_category_title);
        tvChannelCount = findViewById(R.id.tv_channel_count);
        tvNowPlayingName = findViewById(R.id.tv_now_playing_name);
        nowPlayingBar = findViewById(R.id.now_playing_bar);
        loadingOverlay = findViewById(R.id.loading_overlay);
        errorOverlay = findViewById(R.id.error_overlay);
        panelsContainer = findViewById(R.id.panels_container);
        startupLoader = findViewById(R.id.startup_loader);

        // Search views
        searchPanel = findViewById(R.id.search_panel);
        etSearch = findViewById(R.id.et_search);
        tvSearchCount = findViewById(R.id.tv_search_count);
        rvSearchResults = findViewById(R.id.rv_search_results);
        btnSearch = findViewById(R.id.btn_search);

        controlsHud = findViewById(R.id.player_controls_hud);
        btnQuality = findViewById(R.id.btn_quality);
        btnAspect = findViewById(R.id.btn_aspect);
        btnRefresh = findViewById(R.id.btn_refresh);
        btnMenu = findViewById(R.id.btn_menu);
        btnDeveloper = findViewById(R.id.btn_developer);
        developerInfoOverlay = findViewById(R.id.developer_info_overlay);
        btnCloseDeveloper = findViewById(R.id.btn_close_developer);
        bioScrollView = findViewById(R.id.bio_scrollview);

        // Make overlays non-focusable so they never steal focus from VerticalGridView
        loadingOverlay.setFocusable(false);
        loadingOverlay.setFocusableInTouchMode(false);
        errorOverlay.setFocusable(false);
        errorOverlay.setFocusableInTouchMode(false);

        // Start overlays as invisible (alpha=0) instead of GONE to prevent layout passes
        loadingOverlay.setAlpha(0f);
        loadingOverlay.setVisibility(View.VISIBLE);
        errorOverlay.setAlpha(0f);
        errorOverlay.setVisibility(View.VISIBLE);
    }

    private void initAdapters() {
        // Category adapter
        categoryAdapter = new CategoryAdapter();
        categoryAdapter.setOnCategoryClickListener(this);

        rvCategories.setAdapter(categoryAdapter);
        rvCategories.setHasFixedSize(true);
        rvCategories.setItemAnimator(null);
        rvCategories.setItemViewCacheSize(15);

        // Channel adapter
        channelAdapter = new ChannelAdapter();
        channelAdapter.setOnChannelClickListener(this);

        rvChannels.setAdapter(channelAdapter);
        rvChannels.setHasFixedSize(true);
        rvChannels.setItemAnimator(null);
        rvChannels.setItemViewCacheSize(30);
    }

    private void initPlayer() {
        prefManager = new PreferenceManager(this);
        playerManager = new PlayerManager(this);
        playerManager.setCallback(this);
        playerManager.init(playerView);
    }

    private void initSearch() {
        // Search adapter (reuse ChannelAdapter for results)
        searchAdapter = new ChannelAdapter();
        searchAdapter.setOnChannelClickListener((channel, position) -> {
            // Play the selected search result
            playChannel(channel);
            // Close search and panels
            hideSearch();
            togglePanels(false);
        });

        rvSearchResults.setAdapter(searchAdapter);
        rvSearchResults.setHasFixedSize(true);
        rvSearchResults.setItemAnimator(null);
        rvSearchResults.setItemViewCacheSize(20);

        // Search button opens search panel
        if (btnSearch != null) {
            btnSearch.setOnClickListener(v -> showSearch());
        }

        // Live filtering as user types
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                performSearch(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
    }

    private void showSearch() {
        if (searchPanel == null) return;

        // Hide channel panel, show search panel
        findViewById(R.id.channel_panel).setVisibility(View.GONE);
        searchPanel.setVisibility(View.VISIBLE);

        // Clear previous search
        etSearch.setText("");
        tvSearchCount.setText("Type to search channels...");
        searchAdapter.setChannels(null);

        // Focus on the EditText
        etSearch.requestFocus();

        // Show on-screen keyboard for TV
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT);
        }

        cancelAutoHideTimer();
    }

    private void hideSearch() {
        if (searchPanel == null) return;
        searchPanel.setVisibility(View.GONE);

        // Hide keyboard
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
        }
    }

    private void performSearch(String query) {
        if (jsonLoader == null) return;

        List<ChannelModel> results = jsonLoader.searchChannels(query);
        searchAdapter.setChannels(results);

        if (query.trim().isEmpty()) {
            tvSearchCount.setText("Type to search channels...");
        } else if (results.isEmpty()) {
            tvSearchCount.setText("No channels found for \"" + query + "\"");
        } else {
            tvSearchCount.setText(results.size() + " channels found");
        }
    }

    /**
     * Load channel data asynchronously.
     * First loads from cache/assets (instant), then refreshes from GitHub.
     */
    private void loadChannelData() {
        jsonLoader = new JsonLoader();

        // Show loading state on channel panel
        tvChannelCount.setText("Loading channels…");

        jsonLoader.loadAsync(this, new JsonLoader.LoadCallback() {
            @Override
            public void onLoaded(int channelCount, int categoryCount) {
                if (startupLoader != null) {
                    startupLoader.setVisibility(View.GONE);
                }
                Log.d(TAG, "Channels loaded: " + channelCount + ", categories: " + categoryCount);

                if (!isInitialLoadDone) {
                    // FIRST LOAD: set up adapters and auto-play
                    isInitialLoadDone = true;

                    // Set categories
                    List<String> cats = jsonLoader.getCategories();
                    categoryAdapter.setCategories(cats);

                    // Restore last category
                    String lastCategory = prefManager.getLastCategory();
                    int catPos = categoryAdapter.findPosition(lastCategory);
                    if (catPos >= 0) {
                        categoryAdapter.setSelectedPosition(catPos);
                        currentCategory = lastCategory;
                        rvCategories.scrollToPosition(catPos);
                    }

                    // Load channels for current category
                    updateChannelList(currentCategory);

                    // Auto-play last channel or first channel fallback
                    if (!playerManager.isPlaying()) {
                        String lastUrl = prefManager.getLastChannelUrl();
                        ChannelModel targetChannel = null;
                        if (lastUrl != null) {
                            targetChannel = jsonLoader.findByUrl(lastUrl);
                        }
                        
                        if (targetChannel == null) {
                            List<ChannelModel> currentChannels = jsonLoader.getChannelsForCategory(currentCategory);
                            if (currentChannels != null && !currentChannels.isEmpty()) {
                                targetChannel = currentChannels.get(0);
                            }
                        }
                        
                        if (targetChannel != null) {
                            playChannel(targetChannel);

                            int chPos = channelAdapter.findPositionByUrl(targetChannel.url);
                            if (chPos >= 0) {
                                channelAdapter.setSelectedPosition(chPos);
                                rvChannels.scrollToPosition(chPos);
                            }
                        }
                    }

                    rvChannels.requestFocus();
                } else {
                    // SUBSEQUENT LOADS (remote refresh, link checker):
                    // Update adapters to keep data fresh, but preserve scroll and selection!
                    tvChannelCount.setText(channelCount + " channels total");
                    Log.d(TAG, "Background refresh: updating adapters while preserving scroll and selection.");

                    // 1. Preserve category selection and focus
                    boolean categoryHadFocus = rvCategories.hasFocus();
                    List<String> cats = jsonLoader.getCategories();
                    String activeCategory = currentCategory;
                    
                    categoryAdapter.setCategories(cats);
                    
                    int catPos = categoryAdapter.findPosition(activeCategory);
                    if (catPos >= 0) {
                        categoryAdapter.setSelectedPosition(catPos);
                        if (rvCategories instanceof androidx.leanback.widget.VerticalGridView) {
                            ((androidx.leanback.widget.VerticalGridView) rvCategories).setSelectedPosition(catPos);
                        } else {
                            rvCategories.scrollToPosition(catPos);
                        }
                    }
                    if (categoryHadFocus) {
                        rvCategories.requestFocus();
                    }

                    // 2. Preserve channel selection and focus
                    boolean channelsHadFocus = rvChannels.hasFocus();
                    int lastSelectedPos = channelAdapter.getSelectedPosition();
                    ChannelModel lastSelectedChannel = null;
                    if (lastSelectedPos >= 0 && lastSelectedPos < channelAdapter.getItemCount()) {
                        lastSelectedChannel = channelAdapter.getChannels().get(lastSelectedPos);
                    }

                    List<ChannelModel> channels = jsonLoader.getChannelsForCategory(currentCategory);
                    channelAdapter.setChannels(channels);
                    
                    String playingUrl = prefManager.getLastChannelUrl();
                    if (playingUrl != null) {
                        channelAdapter.setCurrentPlayingUrl(playingUrl);
                    }
                    
                    int newChPos = -1;
                    if (lastSelectedChannel != null) {
                        newChPos = channelAdapter.findPositionByUrl(lastSelectedChannel.url);
                    }
                    if (newChPos < 0 && playingUrl != null) {
                        newChPos = channelAdapter.findPositionByUrl(playingUrl);
                    }
                    
                    if (newChPos >= 0 && newChPos < channels.size()) {
                        channelAdapter.setSelectedPosition(newChPos);
                        if (rvChannels instanceof androidx.leanback.widget.VerticalGridView) {
                            ((androidx.leanback.widget.VerticalGridView) rvChannels).setSelectedPosition(newChPos);
                        } else {
                            rvChannels.scrollToPosition(newChPos);
                        }
                    }
                    if (channelsHadFocus) {
                        rvChannels.requestFocus();
                    }
                }
            }

            @Override
            public void onError(String message) {
                if (startupLoader != null) {
                    startupLoader.setVisibility(View.GONE);
                }
                Log.e(TAG, "Channel load error: " + message);
                tvChannelCount.setText("Failed to load channels");
                Toast.makeText(MainActivity.this, "Failed to load channels", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void updateChannelList(String category) {
        currentCategory = category;
        List<ChannelModel> channels = jsonLoader.getChannelsForCategory(category);
        channelAdapter.setChannels(channels);

        // ALWAYS reset channel scroll to top when changing categories
        if (rvChannels instanceof androidx.leanback.widget.VerticalGridView) {
            ((androidx.leanback.widget.VerticalGridView) rvChannels).setSelectedPosition(0);
        } else {
            rvChannels.scrollToPosition(0);
        }

        tvCategoryTitle.setText(category);
        tvChannelCount.setText(channels.size() + " channels");

        prefManager.saveLastCategory(category);
    }

    // ---- Channel/Category click handlers ----

    @Override
    public void onChannelClick(ChannelModel channel, int position) {
        playChannel(channel);
    }

    @Override
    public void onCategoryClick(String category, int position) {
        updateChannelList(category);
        
        // Move focus directly to the channels list
        focusChannels();
    }

    @Override
    public void onCategorySelected(String category, int position) {
        updateChannelList(category);
    }

    private void playChannel(ChannelModel channel) {
        if (channel == null) return;

        showNowPlaying(channel.name);
        channelAdapter.setCurrentPlayingUrl(channel.url);
        prefManager.saveLastChannel(channel.url);
        playerManager.playChannel(channel);
    }

    private void showNowPlaying(String channelName) {
        tvNowPlayingName.setText(channelName);
        nowPlayingBar.setVisibility(View.VISIBLE);

        uiHandler.removeCallbacks(hideNowPlayingRunnable);
        uiHandler.postDelayed(hideNowPlayingRunnable, NOW_PLAYING_HIDE_DELAY);
    }

    private final Runnable hideNowPlayingRunnable = () -> {
        nowPlayingBar.setVisibility(View.GONE);
    };

    // ---- Player callbacks ----
    // Use alpha animation instead of VISIBLE/GONE to prevent VerticalGridView layout resets.

    @Override
    public void onBuffering() {
        runOnUiThread(() -> {
            loadingOverlay.animate().alpha(1f).setDuration(200).start();
            errorOverlay.animate().alpha(0f).setDuration(150).start();
        });
    }

    @Override
    public void onPlaying() {
        runOnUiThread(() -> {
            loadingOverlay.animate().alpha(0f).setDuration(200).start();
            errorOverlay.animate().alpha(0f).setDuration(150).start();
            resetAutoHideTimer();
        });
    }

    @Override
    public void onError(String message) {
        runOnUiThread(() -> {
            loadingOverlay.animate().alpha(0f).setDuration(150).start();
            errorOverlay.animate().alpha(1f).setDuration(200).start();
        });
    }

    @Override
    public void onIdle() {
        // No action needed
    }

    // ---- D-pad key handling ----
    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        resetAutoHideTimer();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        resetAutoHideTimer();

        // If developer overlay is visible, let it consume BACK to close
        if (isDeveloperOverlayVisible) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                onBackPressed();
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }

        // 1. If panels are hidden (fullscreen)
        if (!isPanelVisible) {
            if (isControlsHudVisible()) {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    onBackPressed();
                    return true;
                }
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        hideControlsHud();
                        return true;
                }
                // Let other keys pass through to controls HUD buttons
                return super.onKeyDown(keyCode, event);
            } else {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    onBackPressed();
                    return true;
                }
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                    case KeyEvent.KEYCODE_CHANNEL_UP:
                    case KeyEvent.KEYCODE_PAGE_UP:
                        navigateChannel(-1);
                        return true;

                    case KeyEvent.KEYCODE_DPAD_DOWN:
                    case KeyEvent.KEYCODE_CHANNEL_DOWN:
                    case KeyEvent.KEYCODE_PAGE_DOWN:
                        navigateChannel(1);
                        return true;

                    case KeyEvent.KEYCODE_DPAD_LEFT:
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                    case KeyEvent.KEYCODE_MENU:
                    case KeyEvent.KEYCODE_INFO:
                        showControlsHud();
                        return true;
                }
                return super.onKeyDown(keyCode, event);
            }
        }

        // 2. If panels are visible
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (errorOverlay.getAlpha() > 0.5f) {
                    playerManager.retry();
                    return true;
                }
                break;

            case KeyEvent.KEYCODE_BACK:
                onBackPressed();
                return true;

            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (rvCategories.hasFocus()) {
                    focusChannels();
                    return true;
                }
                break;

            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (rvChannels.hasFocus() || (btnSearch != null && btnSearch.hasFocus()) ||
                    (rvSearchResults != null && rvSearchResults.hasFocus()) || (etSearch != null && etSearch.hasFocus())) {
                    focusCategories();
                    return true;
                }
                break;

            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_INFO:
                togglePanels(false);
                return true;

            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
                navigateChannel(-1);
                return true;

            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                navigateChannel(1);
                return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        // 1. If Developer overlay is visible -> hide it (no popup)
        if (isDeveloperOverlayVisible) {
            if (developerInfoOverlay != null) {
                developerInfoOverlay.setVisibility(View.GONE);
                isDeveloperOverlayVisible = false;
                showControlsHud();
                if (btnDeveloper != null) {
                    btnDeveloper.requestFocus();
                }
            }
            return;
        }

        // 2. If controls HUD menu is visible -> hide it (no popup)
        if (isControlsHudVisible()) {
            hideControlsHud();
            return;
        }

        // 3. If side panels (categories/channels/search) are visible -> handle navigation
        if (isPanelVisible) {
            View channelPanel = findViewById(R.id.channel_panel);
            View categoryPanel = findViewById(R.id.category_panel);
            
            // If search panel is visible -> back to channels
            if (searchPanel != null && searchPanel.getVisibility() == View.VISIBLE) {
                hideSearch();
                channelPanel.setVisibility(View.VISIBLE);
                
                // Focus on search button
                if (btnSearch != null) {
                    btnSearch.requestFocus();
                }
                return;
            }

            // In side-by-side layout, both are visible. 
            // If focus is in channels, back button goes to categories.
            if (rvChannels.hasFocus() || (btnSearch != null && btnSearch.hasFocus())) {
                focusCategories();
                return;
            }

            // If focus is already in categories, back button hides panels
            togglePanels(false);
            return;
        }

        // 4. If nothing else is visible (pure fullscreen video player) -> show exit popup
        showExitDialog();
    }

    private void showExitDialog() {
        new androidx.appcompat.app.AlertDialog.Builder(this, R.style.TvDialogTheme)
                .setTitle("Exit App")
                .setMessage("Do you want to exit BNXIT TV?")
                .setPositiveButton("Yes", (dialog, which) -> finish())
                .setNegativeButton("No", null)
                .show();
    }

    private void navigateChannel(int direction) {
        ChannelModel current = playerManager.getCurrentChannel();
        if (current == null) return;

        List<ChannelModel> channels = jsonLoader.getChannelsForCategory(currentCategory);
        if (channels.isEmpty()) return;

        int currentPos = -1;
        for (int i = 0; i < channels.size(); i++) {
            if (current.url.equals(channels.get(i).url)) {
                currentPos = i;
                break;
            }
        }

        if (currentPos < 0) return;

        int newPos = currentPos + direction;
        if (newPos < 0) newPos = channels.size() - 1;
        if (newPos >= channels.size()) newPos = 0;

        ChannelModel nextChannel = channels.get(newPos);
        channelAdapter.setSelectedPosition(newPos);
        rvChannels.scrollToPosition(newPos);
        playChannel(nextChannel);
    }

    private void focusCategories() {
        int selectedCat = categoryAdapter.getSelectedPosition();
        if (selectedCat >= 0 && rvCategories instanceof androidx.leanback.widget.VerticalGridView) {
            ((androidx.leanback.widget.VerticalGridView) rvCategories).setSelectedPosition(selectedCat);
        }
        rvCategories.requestFocus();
    }

    private void focusChannels() {
        if (searchPanel != null && searchPanel.getVisibility() == View.VISIBLE) {
            if (rvSearchResults != null && rvSearchResults.getVisibility() == View.VISIBLE && searchAdapter.getItemCount() > 0) {
                rvSearchResults.requestFocus();
            } else if (etSearch != null) {
                etSearch.requestFocus();
            }
        } else {
            int selectedChan = channelAdapter.getSelectedPosition();
            if (selectedChan >= 0 && rvChannels instanceof androidx.leanback.widget.VerticalGridView) {
                ((androidx.leanback.widget.VerticalGridView) rvChannels).setSelectedPosition(selectedChan);
            }
            rvChannels.requestFocus();
        }
    }

    private void togglePanels(boolean visible) {
        isPanelVisible = visible;

        if (panelsContainer != null) {
            panelsContainer.setVisibility(visible ? View.VISIBLE : View.GONE);
        }

        if (visible) {
            cancelAutoHideTimer();
            if (controlsHud != null) {
                controlsHud.setVisibility(View.GONE);
            }

            // Hide search panel when opening panels
            if (searchPanel != null) {
                searchPanel.setVisibility(View.GONE);
            }
            
            // Initial state for side-by-side: Both Category and Channels are visible
            findViewById(R.id.category_panel).setVisibility(View.VISIBLE);
            findViewById(R.id.channel_panel).setVisibility(View.VISIBLE);
            
            int selectedCat = categoryAdapter.getSelectedPosition();
            if (selectedCat >= 0) {
                if (rvCategories instanceof androidx.leanback.widget.VerticalGridView) {
                    ((androidx.leanback.widget.VerticalGridView) rvCategories).setSelectedPosition(selectedCat);
                } else {
                    rvCategories.scrollToPosition(selectedCat);
                }
            }
            rvCategories.requestFocus();
        } else {
            cancelAutoHideTimer();

            // Hide search when closing panels
            if (searchPanel != null) {
                hideSearch();
            }

            playerView.requestFocus();

            ChannelModel current = playerManager.getCurrentChannel();
            if (current != null) {
                showNowPlaying(current.name);
            }
        }
    }

    private void resetAutoHideTimer() {
        cancelAutoHideTimer();
        if (isPanelVisible || isControlsHudVisible()) {
            uiHandler.postDelayed(autoHideRunnable, AUTO_HIDE_DELAY_MS);
        }
    }

    private void cancelAutoHideTimer() {
        uiHandler.removeCallbacks(autoHideRunnable);
    }

    // ---- Lifecycle ----

    @Override
    protected void onPause() {
        super.onPause();
        cancelAutoHideTimer();
        if (playerManager != null) {
            playerManager.pause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (playerManager != null) {
            playerManager.resume();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (playerManager != null) {
            playerManager.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        uiHandler.removeCallbacksAndMessages(null);
        if (playerManager != null) {
            playerManager.release();
            playerManager = null;
        }
        if (jsonLoader != null) {
            jsonLoader.shutdown();
            jsonLoader = null;
        }
    }

    // ---- Fullscreen Controls HUD & Quality dialog ----

    private void initControlListeners() {
        View.OnFocusChangeListener focusListener = (v, hasFocus) -> {
            if (hasFocus) {
                v.animate().scaleX(1.08f).scaleY(1.08f).translationZ(6f).setDuration(150).start();
                if (v instanceof TextView) {
                    ((TextView) v).setTextColor(0xFFFFFFFF);
                }
            } else {
                v.animate().scaleX(1.0f).scaleY(1.0f).translationZ(0f).setDuration(150).start();
                if (v instanceof TextView) {
                    ((TextView) v).setTextColor(0xFFC4C4D0);
                }
            }
            resetAutoHideTimer();
        };

        btnQuality.setOnFocusChangeListener(focusListener);
        btnAspect.setOnFocusChangeListener(focusListener);
        if (btnRefresh != null) {
            btnRefresh.setOnFocusChangeListener(focusListener);
        }
        btnMenu.setOnFocusChangeListener(focusListener);
        if (btnDeveloper != null) {
            btnDeveloper.setOnFocusChangeListener(focusListener);
        }
        if (btnCloseDeveloper != null) {
            btnCloseDeveloper.setOnFocusChangeListener(focusListener);
        }
        if (bioScrollView != null) {
            bioScrollView.setOnFocusChangeListener((v, hasFocus) -> resetAutoHideTimer());
        }

        btnQuality.setOnClickListener(v -> {
            showQualitySelectionDialog();
            resetAutoHideTimer();
        });
        btnAspect.setOnClickListener(v -> {
            String modeName = playerManager.toggleResizeMode();
            Toast.makeText(this, "Aspect Ratio: " + modeName, Toast.LENGTH_SHORT).show();
            resetAutoHideTimer();
        });

        btnMenu.setOnClickListener(v -> {
            hideControlsHud();
            togglePanels(true);
            resetAutoHideTimer();
        });

        if (btnDeveloper != null) {
            btnDeveloper.setOnClickListener(v -> {
                hideControlsHud();
                if (developerInfoOverlay != null) {
                    developerInfoOverlay.setVisibility(View.VISIBLE);
                    isDeveloperOverlayVisible = true;
                    cancelAutoHideTimer(); // Prevent auto-hide when reading developer bio
                    if (btnCloseDeveloper != null) {
                        btnCloseDeveloper.requestFocus();
                    }
                }
            });
        }

        if (btnCloseDeveloper != null) {
            btnCloseDeveloper.setOnClickListener(v -> {
                if (developerInfoOverlay != null) {
                    developerInfoOverlay.setVisibility(View.GONE);
                    isDeveloperOverlayVisible = false;
                    showControlsHud();
                    if (btnDeveloper != null) {
                        btnDeveloper.requestFocus();
                    }
                }
            });
        }

        if (btnRefresh != null) {
            btnRefresh.setOnClickListener(v -> {
                hideControlsHud();
                triggerManualRefresh();
            });
        }
    }

    private void triggerManualRefresh() {
        // Show loading overlay
        loadingOverlay.animate().alpha(1f).setDuration(200).start();
        tvChannelCount.setText("Refreshing channels from remote...");
        Toast.makeText(this, "Downloading latest channel data...", Toast.LENGTH_SHORT).show();

        jsonLoader.manualRefreshAsync(this, new JsonLoader.LoadCallback() {
            @Override
            public void onLoaded(int channelCount, int categoryCount) {
                runOnUiThread(() -> {
                    loadingOverlay.animate().alpha(0f).setDuration(200).start();
                    Toast.makeText(MainActivity.this, "Channels refreshed successfully!", Toast.LENGTH_SHORT).show();

                    // Update categories
                    List<String> cats = jsonLoader.getCategories();
                    String activeCategory = currentCategory;
                    categoryAdapter.setCategories(cats);
                    
                    int catPos = categoryAdapter.findPosition(activeCategory);
                    if (catPos >= 0) {
                        categoryAdapter.setSelectedPosition(catPos);
                        if (rvCategories instanceof androidx.leanback.widget.VerticalGridView) {
                            ((androidx.leanback.widget.VerticalGridView) rvCategories).setSelectedPosition(catPos);
                        } else {
                            rvCategories.scrollToPosition(catPos);
                        }
                    }

                    // Update channels preserving selection/focus position
                    int lastSelectedPos = channelAdapter.getSelectedPosition();
                    ChannelModel lastSelectedChannel = null;
                    if (lastSelectedPos >= 0 && lastSelectedPos < channelAdapter.getItemCount()) {
                        lastSelectedChannel = channelAdapter.getChannels().get(lastSelectedPos);
                    }

                    List<ChannelModel> channels = jsonLoader.getChannelsForCategory(currentCategory);
                    channelAdapter.setChannels(channels);

                    String playingUrl = prefManager.getLastChannelUrl();
                    if (playingUrl != null) {
                        channelAdapter.setCurrentPlayingUrl(playingUrl);
                    }

                    int newChPos = -1;
                    if (lastSelectedChannel != null) {
                        newChPos = channelAdapter.findPositionByUrl(lastSelectedChannel.url);
                    }
                    if (newChPos < 0 && playingUrl != null) {
                        newChPos = channelAdapter.findPositionByUrl(playingUrl);
                    }

                    if (newChPos >= 0 && newChPos < channels.size()) {
                        channelAdapter.setSelectedPosition(newChPos);
                        if (rvChannels instanceof androidx.leanback.widget.VerticalGridView) {
                            ((androidx.leanback.widget.VerticalGridView) rvChannels).setSelectedPosition(newChPos);
                        } else {
                            rvChannels.scrollToPosition(newChPos);
                        }
                    }

                    // Show panels
                    togglePanels(true);
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    loadingOverlay.animate().alpha(0f).setDuration(200).start();
                    Toast.makeText(MainActivity.this, "Refresh failed: " + message, Toast.LENGTH_LONG).show();
                    togglePanels(true);
                });
            }
        });
    }

    private void showQualitySelectionDialog() {
        List<PlayerManager.TrackInfo> qualities = playerManager.getVideoQualities();
        if (qualities.isEmpty()) {
            Toast.makeText(this, "No quality options available", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] names = new String[qualities.size()];
        int selectedIndex = 0;
        for (int i = 0; i < qualities.size(); i++) {
            names[i] = qualities.get(i).name;
            if (qualities.get(i).isSelected) {
                selectedIndex = i;
            }
        }

        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this, R.style.TvDialogTheme);
        builder.setTitle("Select Quality");
        builder.setSingleChoiceItems(names, selectedIndex, (dialog, which) -> {
            PlayerManager.TrackInfo selected = qualities.get(which);
            playerManager.setVideoQuality(selected);
            Toast.makeText(this, "Quality: " + selected.name, Toast.LENGTH_SHORT).show();
            dialog.dismiss();
            resetAutoHideTimer();
        });
        builder.show();
    }

    private void showControlsHud() {
        if (controlsHud == null) return;
        controlsHud.setVisibility(View.VISIBLE);

        btnMenu.requestFocus();
        resetAutoHideTimer();
    }

    private void hideControlsHud() {
        if (controlsHud == null) return;
        controlsHud.setVisibility(View.GONE);
        playerView.requestFocus();
    }

    private boolean isControlsHudVisible() {
        return controlsHud != null && controlsHud.getVisibility() == View.VISIBLE;
    }
}
