package io.github.banjorecomp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.TextView;
import android.text.TextUtils;
import android.text.Editable;
import android.text.TextWatcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ModBrowserActivity extends Activity {
    public static final String EXTRA_MOD_PATH = "banjo_mod_server_path";
    private static final String TAG = "BanjoModServer";
    private static final String CATALOG_URL =
            "https://thunderstore.io/c/banjo-recompiled/api/v1/package/";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final long MAX_CATALOG_BYTES = 2L * 1024L * 1024L;
    private static final long MAX_MOD_BYTES = 512L * 1024L * 1024L;
    private static final long MAX_THUMBNAIL_BYTES = 4L * 1024L * 1024L;
    private static final String CATALOG_CACHE_NAME = "thunderstore-banjo-catalog.json";
    private static final String DOWNLOADED_PREFS = "thunderstore-downloaded";
    private static final String SUPPORTED_GAME_ID = "bk";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbnailExecutor = Executors.newFixedThreadPool(3);
    private LinearLayout modList;
    private TextView status;
    private ProgressBar progress;
    private EditText search;
    private JSONArray currentCatalog;
    private Spinner typeFilter;
    private CheckBox updatesOnly;
    private ArrayAdapter<String> typeAdapter;
    private boolean updatingTypeFilter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        refreshCatalog();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (modList != null && modList.getChildCount() > 0) {
            refreshCatalog();
        }
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        thumbnailExecutor.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        final int baseHorizontal = dp(16);
        final int baseTop = dp(12);
        final int baseBottom = dp(12);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(baseHorizontal, baseTop, baseHorizontal, baseBottom);
        root.setBackgroundColor(Color.rgb(13, 17, 23));
        root.setFitsSystemWindows(true);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left = insets.getSystemWindowInsetLeft();
            int top = insets.getSystemWindowInsetTop();
            int right = insets.getSystemWindowInsetRight();
            int bottom = insets.getSystemWindowInsetBottom();
            view.setPadding(baseHorizontal + left, baseTop + top,
                    baseHorizontal + right, baseBottom + bottom);
            return insets;
        });

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(10));

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText("Thunderstore Mods");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        heading.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Banjo-Recompiled community");
        subtitle.setTextColor(Color.rgb(139, 148, 158));
        subtitle.setTextSize(12f);
        heading.addView(subtitle);

        header.addView(heading, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button recover = new Button(this);
        recover.setText("Disable mods");
        recover.setAllCaps(false);
        recover.setMinWidth(0);
        recover.setMinimumWidth(0);
        recover.setMinHeight(dp(40));
        recover.setPadding(dp(12), 0, dp(12), 0);
        styleButton(recover, false);
        recover.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Disable all installed mods?")
                .setMessage("Use this if a mod crashes the game. The mod files stay installed and can be enabled again later.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Disable all", (dialog, which) -> {
                    try {
                        int disabled = BanjoSDLActivity.nativeDisableAllMods();
                        status.setText(disabled < 0
                                ? "Stop the running game before disabling mods."
                                : "Disabled " + disabled + (disabled == 1 ? " mod." : " mods."));
                        refreshCatalog();
                    } catch (UnsatisfiedLinkError error) {
                        status.setText("Could not disable mods: native bridge unavailable.");
                    }
                }).show());
        LinearLayout.LayoutParams recoverParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(40));
        recoverParams.setMargins(0, 0, dp(8), 0);
        header.addView(recover, recoverParams);

        Button refresh = new Button(this);
        refresh.setText("Refresh");
        refresh.setAllCaps(false);
        refresh.setMinWidth(0);
        refresh.setMinimumWidth(0);
        refresh.setMinHeight(dp(40));
        refresh.setPadding(dp(14), 0, dp(14), 0);
        styleButton(refresh, false);
        refresh.setOnClickListener(v -> refreshCatalog());
        header.addView(refresh);

        root.addView(header);

        search = new EditText(this);
        search.setHint("Search mods");
        search.setSingleLine(true);
        search.setTextColor(Color.WHITE);
        search.setHintTextColor(Color.rgb(139, 148, 158));
        search.setTextSize(16f);
        search.setPadding(dp(14), 0, dp(14), 0);
        search.setBackground(rounded(Color.rgb(22, 27, 34), Color.rgb(48, 54, 61), 1, 10));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (currentCatalog != null) showCatalog(currentCatalog, false);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        searchParams.setMargins(0, 0, 0, dp(8));
        root.addView(search, searchParams);

        LinearLayout filters = new LinearLayout(this);
        filters.setOrientation(LinearLayout.HORIZONTAL);
        filters.setGravity(Gravity.CENTER_VERTICAL);

        typeFilter = new Spinner(this);
        typeAdapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item,
                new ArrayList<>(Collections.singletonList("All"))) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                if (view instanceof TextView) {
                    TextView text = (TextView) view;
                    text.setTextColor(Color.WHITE);
                    text.setTextSize(14f);
                    text.setPadding(dp(12), 0, dp(12), 0);
                }
                return view;
            }
        };
        typeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        typeFilter.setAdapter(typeAdapter);
        typeFilter.setBackground(rounded(Color.rgb(22, 27, 34), Color.rgb(48, 54, 61), 1, 9));
        typeFilter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!updatingTypeFilter && currentCatalog != null) showCatalog(currentCatalog, false);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(
                0, dp(44), 1f);
        spinnerParams.setMargins(0, 0, dp(10), 0);
        filters.addView(typeFilter, spinnerParams);

        updatesOnly = new CheckBox(this);
        updatesOnly.setText("Updates only");
        updatesOnly.setTextColor(Color.rgb(230, 237, 243));
        updatesOnly.setTextSize(14f);
        updatesOnly.setButtonTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{Color.rgb(88, 166, 255), Color.rgb(139, 148, 158)}));
        updatesOnly.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (currentCatalog != null) showCatalog(currentCatalog, false);
        });
        filters.addView(updatesOnly, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)));

        root.addView(filters);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);

        status = new TextView(this);
        status.setTextColor(Color.rgb(139, 148, 158));
        status.setTextSize(13f);
        status.setPadding(0, dp(8), 0, dp(8));
        statusRow.addView(status, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(26), dp(26));
        progressParams.setMargins(dp(8), 0, 0, 0);
        statusRow.addView(progress, progressParams);

        root.addView(statusRow);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, dp(6));

        modList = new LinearLayout(this);
        modList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(modList, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        root.requestApplyInsets();
    }

    private void refreshCatalog() {
        progress.setVisibility(View.VISIBLE);
        status.setText("Loading mod catalog…");
        modList.removeAllViews();

        executor.execute(() -> {
            File cache = new File(getFilesDir(), CATALOG_CACHE_NAME);
            try {
                byte[] bytes = downloadBytes(CATALOG_URL, MAX_CATALOG_BYTES);
                JSONObject root = parseCatalog(bytes);
                writeBytesAtomically(cache, bytes);
                JSONArray mods = root.getJSONArray("mods");
                runOnUiThread(() -> showCatalog(mods, true));
            } catch (Exception networkError) {
                Log.e(TAG, "Catalog refresh failed", networkError);
                try {
                    byte[] cached = readLimitedFile(cache, MAX_CATALOG_BYTES);
                    JSONObject root = parseCatalog(cached);
                    JSONArray mods = root.getJSONArray("mods");
                    runOnUiThread(() -> {
                        showCatalog(mods, true);
                        status.setText("Offline catalog: showing last successful mod list.");
                    });
                } catch (Exception cacheError) {
                    runOnUiThread(() -> {
                        progress.setVisibility(View.GONE);
                        status.setText("Could not load the mod server: " + safeMessage(networkError));
                    });
                }
            }
        });
    }

    private void showCatalog(JSONArray mods, boolean updateStatus) {
        progress.setVisibility(View.GONE);
        currentCatalog = mods;
        updateTypeFilterOptions(mods);
        modList.removeAllViews();
        if (mods.length() == 0) {
            if (updateStatus) status.setText("The server is online, but no mods are published yet.");
            return;
        }

        String query = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.US);
        String selectedType = typeFilter == null || typeFilter.getSelectedItem() == null
                ? "All" : typeFilter.getSelectedItem().toString();
        int shown = 0;
        for (int i = 0; i < mods.length(); i++) {
            JSONObject mod = mods.optJSONObject(i);
            if (mod == null) continue;
            String modType = mod.optString("type", "mod").trim();
            if (!"All".equals(selectedType) && !selectedType.equalsIgnoreCase(modType)) {
                continue;
            }
            if (updatesOnly != null && updatesOnly.isChecked() && !hasCatalogUpdate(mod)) {
                continue;
            }
            if (!query.isEmpty()) {
                String haystack = (mod.optString("name", "") + "\n"
                        + displayAuthors(mod) + "\n"
                        + mod.optString("description", "") + "\n"
                        + modType).toLowerCase(Locale.US);
                if (!haystack.contains(query)) continue;
            }
            addModCard(mod);
            shown++;
        }
        boolean filtered = !query.isEmpty()
                || !"All".equals(selectedType)
                || (updatesOnly != null && updatesOnly.isChecked());
        if (updateStatus || filtered) {
            status.setText(filtered
                    ? shown + (shown == 1 ? " matching mod" : " matching mods")
                    : mods.length() + (mods.length() == 1 ? " mod available" : " mods available"));
        }
    }

    private int compareVersions(String installedVersion, String catalogVersion) {
        if (installedVersion == null || installedVersion.isEmpty()
                || catalogVersion == null || catalogVersion.isEmpty()) {
            return 2;
        }
        try {
            return BanjoSDLActivity.nativeCompareVersions(installedVersion, catalogVersion);
        } catch (UnsatisfiedLinkError error) {
            Log.w(TAG, "Native mod version comparison unavailable", error);
            return 2;
        }
    }

    private boolean hasCatalogUpdate(JSONObject mod) {
        String id = mod.optString("id", "").trim();
        String catalogVersion = mod.optString("version", "").trim();
        if (id.isEmpty() || catalogVersion.isEmpty()) return false;
        try {
            String installedVersion = BanjoSDLActivity.nativeGetInstalledModVersion(id);
            return compareVersions(installedVersion, catalogVersion) < 0;
        } catch (UnsatisfiedLinkError error) {
            Log.w(TAG, "Installed mod lookup unavailable while filtering updates", error);
            return false;
        }
    }

    private void updateTypeFilterOptions(JSONArray mods) {
        if (typeFilter == null || typeAdapter == null) return;

        String previousSelection = typeFilter.getSelectedItem() == null
                ? "All" : typeFilter.getSelectedItem().toString();
        ArrayList<String> types = new ArrayList<>();
        for (int i = 0; i < mods.length(); i++) {
            JSONObject mod = mods.optJSONObject(i);
            if (mod == null) continue;
            String type = mod.optString("type", "mod").trim();
            if (type.isEmpty()) type = "mod";

            boolean alreadyPresent = false;
            for (String existing : types) {
                if (existing.equalsIgnoreCase(type)) {
                    alreadyPresent = true;
                    break;
                }
            }
            if (!alreadyPresent) types.add(type);
        }
        Collections.sort(types, String.CASE_INSENSITIVE_ORDER);

        updatingTypeFilter = true;
        try {
            typeAdapter.clear();
            typeAdapter.add("All");
            typeAdapter.addAll(types);
            typeAdapter.notifyDataSetChanged();

            int selection = 0;
            for (int i = 1; i < typeAdapter.getCount(); i++) {
                if (typeAdapter.getItem(i).equalsIgnoreCase(previousSelection)) {
                    selection = i;
                    break;
                }
            }
            typeFilter.setSelection(selection, false);
        } finally {
            updatingTypeFilter = false;
        }
    }

    private void addModCard(JSONObject mod) {
        String id = mod.optString("id", "").trim();
        String name = mod.optString("name", id).trim();
        String author = displayAuthors(mod);
        String version = mod.optString("version", "").trim();
        String description = mod.optString("description", "").trim();
        String type = mod.optString("type", "mod").trim();
        String homepage = mod.optString("homepage", "").trim();
        String thumbnail = mod.optString("thumbnail", "").trim();
        String gameId = mod.optString("game_id", SUPPORTED_GAME_ID).trim();
        int minAppVersionCode = mod.optInt("min_app_version_code", 0);
        int downloads = mod.optInt("downloads", 0);
        String downloadUrl = mod.optString("download_url", "").trim();
        String fileName = mod.optString("file_name", "").trim();
        String packageType = mod.optString("package_type", "").trim();
        String sha256 = mod.optString("sha256", "").trim().toLowerCase(Locale.US);

        if (id.isEmpty() || name.isEmpty() || downloadUrl.isEmpty()) return;

        boolean compatible = SUPPORTED_GAME_ID.equals(gameId)
                && (minAppVersionCode <= 0 || BuildConfig.VERSION_CODE >= minAppVersionCode);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(10));
        card.setBackground(rounded(Color.rgb(22, 27, 34), Color.rgb(48, 54, 61), 1, 12));

        LinearLayout summary = new LinearLayout(this);
        summary.setOrientation(LinearLayout.HORIZONTAL);
        summary.setGravity(Gravity.TOP);

        ImageView thumbnailView = new ImageView(this);
        thumbnailView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbnailView.setContentDescription(name + " thumbnail");
        thumbnailView.setBackground(rounded(Color.rgb(33, 38, 45), Color.rgb(48, 54, 61), 1, 10));
        thumbnailView.setClipToOutline(true);
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(dp(88), dp(88));
        imageParams.setMargins(0, 0, dp(12), 0);
        summary.addView(thumbnailView, imageParams);
        if (!thumbnail.isEmpty()) loadThumbnail(thumbnail, thumbnailView, id);

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);

        TextView nameView = new TextView(this);
        nameView.setText(name);
        nameView.setTextColor(Color.WHITE);
        nameView.setTextSize(17f);
        nameView.setTypeface(null, android.graphics.Typeface.BOLD);
        nameView.setSingleLine(true);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        details.addView(nameView);

        TextView authorView = new TextView(this);
        String byline = "by " + author + (version.isEmpty() ? "" : "  •  v" + version);
        authorView.setText(byline);
        authorView.setTextColor(Color.rgb(139, 148, 158));
        authorView.setTextSize(12.5f);
        authorView.setSingleLine(true);
        authorView.setEllipsize(TextUtils.TruncateAt.END);
        details.addView(authorView);

        if (!description.isEmpty()) {
            TextView descView = new TextView(this);
            descView.setText(description);
            descView.setTextColor(Color.rgb(201, 209, 217));
            descView.setTextSize(13f);
            descView.setMaxLines(2);
            descView.setEllipsize(TextUtils.TruncateAt.END);
            descView.setPadding(0, dp(5), 0, 0);
            details.addView(descView);
        }

        TextView metaView = new TextView(this);
        StringBuilder meta = new StringBuilder(type.isEmpty() ? "mod" : type);
        if (downloads > 0) meta.append("  •  ").append(downloads).append(" downloads");
        if (!compatible) {
            meta.append(!SUPPORTED_GAME_ID.equals(gameId)
                    ? "  •  Incompatible game"
                    : "  •  Requires newer app");
        }
        metaView.setText(meta.toString());
        metaView.setTextColor(compatible
                ? Color.rgb(139, 148, 158)
                : Color.rgb(255, 166, 87));
        metaView.setTextSize(11.5f);
        metaView.setPadding(0, dp(5), 0, 0);
        details.addView(metaView);

        summary.addView(details, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(summary);

        String installedVersion = null;
        try {
            installedVersion = BanjoSDLActivity.nativeGetInstalledModVersion(id);
        } catch (UnsatisfiedLinkError error) {
            Log.w(TAG, "Installed mod lookup unavailable", error);
        }

        String downloadedVersion = getSharedPreferences(DOWNLOADED_PREFS, MODE_PRIVATE)
                .getString(id, null);

        int installedVsCatalog = 2;
        if (installedVersion != null && !installedVersion.isEmpty()) {
            installedVsCatalog = compareVersions(installedVersion, version);
            TextView statusView = new TextView(this);
            String state;
            if (installedVsCatalog == 0) state = "Up to date";
            else if (installedVsCatalog < 0) state = "Update available";
            else if (installedVsCatalog > 0 && installedVsCatalog != 2) state = "Installed version is newer";
            else state = "Version differs";
            statusView.setText("Installed " + installedVersion + "  •  " + state);
            statusView.setTextColor(installedVsCatalog < 0
                    ? Color.rgb(210, 168, 255)
                    : Color.rgb(139, 148, 158));
            statusView.setTextSize(12f);
            statusView.setPadding(0, dp(8), 0, 0);
            card.addView(statusView);
        }

        LinearLayout primaryActions = new LinearLayout(this);
        primaryActions.setOrientation(LinearLayout.HORIZONTAL);
        primaryActions.setGravity(Gravity.CENTER_VERTICAL);
        primaryActions.setPadding(0, dp(8), 0, 0);

        Button install = new Button(this);
        install.setAllCaps(false);
        install.setMinWidth(0);
        install.setMinimumWidth(0);
        install.setMinHeight(dp(42));
        install.setPadding(dp(12), 0, dp(12), 0);
        if (installedVersion != null && !installedVersion.isEmpty()) {
            if (installedVsCatalog == 0) install.setText("Reinstall");
            else if (installedVsCatalog < 0) install.setText("Update");
            else if (installedVsCatalog > 0 && installedVsCatalog != 2) install.setText("Install older");
            else install.setText("Replace");
        } else if (downloadedVersion != null && !downloadedVersion.isEmpty()) {
            install.setText(downloadedVersion.equals(version) ? "Download again" : "Download update");
        } else {
            install.setText("Download & install");
        }
        install.setEnabled(compatible);
        if (!compatible) install.setText("Not compatible");
        styleButton(install, true);
        install.setOnClickListener(v -> {
            install.setEnabled(false);
            downloadMod(id, name, version, downloadUrl, fileName, packageType, sha256, install);
        });
        primaryActions.addView(install, new LinearLayout.LayoutParams(
                0, dp(42), 1f));

        if (!homepage.isEmpty()) {
            try {
                URL homepageUrl = new URL(homepage);
                if ("https".equalsIgnoreCase(homepageUrl.getProtocol())) {
                    Button page = new Button(this);
                    page.setText("Page");
                    page.setAllCaps(false);
                    page.setMinWidth(0);
                    page.setMinimumWidth(0);
                    page.setPadding(dp(12), 0, dp(12), 0);
                    styleButton(page, false);
                    page.setOnClickListener(v -> {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(homepage)));
                        } catch (ActivityNotFoundException | SecurityException e) {
                            Log.w(TAG, "Could not open homepage for " + id, e);
                            status.setText("No app can open this mod page.");
                        }
                    });
                    LinearLayout.LayoutParams pageParams = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT, dp(42));
                    pageParams.setMargins(dp(8), 0, 0, 0);
                    primaryActions.addView(page, pageParams);
                }
            } catch (Exception ignored) {
                Log.w(TAG, "Ignoring invalid homepage URL for " + id);
            }
        }
        card.addView(primaryActions);

        if (installedVersion != null && !installedVersion.isEmpty()) {
            boolean enabled = false;
            boolean autoEnabled = false;
            try {
                enabled = BanjoSDLActivity.nativeIsModEnabled(id);
                autoEnabled = BanjoSDLActivity.nativeIsModAutoEnabled(id);
            } catch (UnsatisfiedLinkError error) {
                Log.w(TAG, "Installed mod state lookup unavailable", error);
            }

            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setPadding(0, dp(6), 0, 0);

            Button toggle = new Button(this);
            toggle.setAllCaps(false);
            toggle.setMinWidth(0);
            toggle.setMinimumWidth(0);
            toggle.setText(autoEnabled ? "Required" : enabled ? "Disable" : "Enable");
            toggle.setEnabled(!autoEnabled);
            styleButton(toggle, false);
            final boolean targetEnabled = !enabled;
            toggle.setOnClickListener(v -> {
                try {
                    BanjoSDLActivity.nativeSetModEnabled(id, targetEnabled);
                    refreshCatalog();
                } catch (UnsatisfiedLinkError error) {
                    status.setText("Could not change mod state: native bridge unavailable");
                }
            });
            actions.addView(toggle, new LinearLayout.LayoutParams(0, dp(40), 1f));

            Button uninstall = new Button(this);
            uninstall.setAllCaps(false);
            uninstall.setMinWidth(0);
            uninstall.setMinimumWidth(0);
            String uninstallBlockReason;
            try {
                uninstallBlockReason = BanjoSDLActivity.nativeGetModUninstallBlockReason(id);
            } catch (UnsatisfiedLinkError error) {
                uninstallBlockReason = "Native bridge unavailable";
            }

            if (uninstallBlockReason != null && !uninstallBlockReason.isEmpty()) {
                uninstall.setText("Uninstall blocked");
                uninstall.setEnabled(false);
                uninstall.setContentDescription(uninstallBlockReason);
            } else {
                uninstall.setText("Uninstall");
                uninstall.setOnClickListener(v -> new AlertDialog.Builder(this)
                        .setTitle("Uninstall " + name + "?")
                        .setMessage("This removes the installed mod package from BanjoRecomp.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Uninstall", (dialog, which) -> {
                            try {
                                boolean removed = BanjoSDLActivity.nativeUninstallMod(id);
                                if (removed) {
                                    getSharedPreferences(DOWNLOADED_PREFS, MODE_PRIVATE)
                                            .edit().remove(id).apply();
                                    status.setText("Uninstalled " + name);
                                    refreshCatalog();
                                } else {
                                    status.setText("Could not uninstall " + name + ".");
                                }
                            } catch (UnsatisfiedLinkError error) {
                                status.setText("Could not uninstall: native bridge unavailable");
                            }
                        }).show());
            }
            styleButton(uninstall, false);
            LinearLayout.LayoutParams uninstallParams = new LinearLayout.LayoutParams(
                    0, dp(40), 1f);
            uninstallParams.setMargins(dp(8), 0, 0, 0);
            actions.addView(uninstall, uninstallParams);
            card.addView(actions);
        }

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(10));
        modList.addView(card, params);
    }

    private void downloadMod(String id, String name, String version, String downloadUrl,
                             String fileName, String packageType, String expectedSha256, Button installButton) {
        progress.setVisibility(View.VISIBLE);
        status.setText("Downloading " + name + "…");

        executor.execute(() -> {
            File destination = null;
            try {
                File root = new File(getCacheDir(), "mod-server");
                if (!root.isDirectory() && !root.mkdirs() && !root.isDirectory()) {
                    throw new IllegalStateException("Could not create download directory");
                }
                String resolvedFileName = resolvePackageFileName(id, downloadUrl, fileName, packageType);
                destination = new File(root, resolvedFileName);
                downloadToFile(downloadUrl, destination, MAX_MOD_BYTES);
                if (!expectedSha256.isEmpty()) {
                    String actual = sha256(destination);
                    if (!actual.equals(expectedSha256)) {
                        throw new SecurityException("SHA-256 mismatch");
                    }
                }

                if ("zip".equalsIgnoreCase(packageType)
                        || destination.getName().toLowerCase(Locale.US).endsWith(".zip")) {
                    validateThunderstorePackageForAndroid(destination);
                }

                Intent result = new Intent();
                result.putExtra(EXTRA_MOD_PATH, destination.getAbsolutePath());
                getSharedPreferences(DOWNLOADED_PREFS, MODE_PRIVATE)
                        .edit().putString(id, version).apply();
                setResult(Activity.RESULT_OK, result);
                runOnUiThread(() -> {
                    status.setText("Downloaded " + name + ". Opening the Banjo mod installer…");
                    finish();
                });
            } catch (Exception e) {
                Log.e(TAG, "Mod download failed", e);
                if (destination != null && destination.exists() && !destination.delete()) {
                    Log.w(TAG, "Could not delete failed download " + destination);
                }
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    installButton.setEnabled(true);
                    status.setText("Download failed: " + safeMessage(e));
                });
            }
        });
    }

    private static void validateThunderstorePackageForAndroid(File archive) throws Exception {
        boolean foundPayload = false;
        ArrayList<String> nativeFiles = new ArrayList<>();

        try (ZipFile zip = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;

                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.contains("../")) {
                    throw new SecurityException("Unsafe path in Thunderstore package: " + name);
                }

                String lower = name.toLowerCase(Locale.US);
                if (lower.endsWith(".nrm") || lower.endsWith(".rtz")) {
                    foundPayload = true;
                }
                if (lower.endsWith(".dll") || lower.endsWith(".dylib")
                        || lower.endsWith(".so") || lower.matches(".*\\.so\\.[0-9].*")) {
                    nativeFiles.add(name);
                }
            }
        }

        if (!foundPayload) {
            throw new IllegalArgumentException(
                    "This Thunderstore package contains no Banjo .nrm or .rtz mod payload.");
        }
        if (!nativeFiles.isEmpty()) {
            throw new IllegalArgumentException(
                    "This mod includes desktop native code (" + nativeFiles.get(0)
                    + ") and is not Android-compatible yet.");
        }
    }

    private static JSONObject parseCatalog(byte[] bytes) throws Exception {
        String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8).trim();
        if (text.startsWith("[")) {
            return normalizeThunderstoreCatalog(new JSONArray(text));
        }

        JSONObject root = new JSONObject(text);
        if (root.optInt("schema", 0) != 1) {
            throw new IllegalArgumentException("Unsupported catalog schema");
        }
        JSONArray mods = root.optJSONArray("mods");
        if (mods == null) {
            throw new IllegalArgumentException("Catalog has no mods array");
        }
        validateNormalizedCatalog(mods);
        return root;
    }

    private static JSONObject normalizeThunderstoreCatalog(JSONArray packages) throws Exception {
        JSONObject root = new JSONObject();
        root.put("schema", 1);
        root.put("game", "banjo-recompiled");
        root.put("source", "Thunderstore");
        JSONArray mods = new JSONArray();

        for (int i = 0; i < packages.length(); i++) {
            JSONObject pkg = packages.optJSONObject(i);
            if (pkg == null || pkg.optBoolean("is_deprecated", false)
                    || pkg.optBoolean("has_nsfw_content", false)) {
                continue;
            }

            JSONArray categories = pkg.optJSONArray("categories");
            boolean modpack = false;
            String primaryType = "mod";
            if (categories != null) {
                for (int categoryIndex = 0; categoryIndex < categories.length(); categoryIndex++) {
                    String category = categories.optString(categoryIndex, "").trim();
                    if ("Modpacks".equalsIgnoreCase(category)) modpack = true;
                    if (!category.isEmpty() && !"Mods".equalsIgnoreCase(category)
                            && !"Modpacks".equalsIgnoreCase(category)) {
                        primaryType = category.toLowerCase(Locale.US);
                    }
                }
            }
            if (modpack) continue;

            JSONArray versions = pkg.optJSONArray("versions");
            if (versions == null || versions.length() == 0) continue;

            JSONObject latest = null;
            for (int versionIndex = 0; versionIndex < versions.length(); versionIndex++) {
                JSONObject candidate = versions.optJSONObject(versionIndex);
                if (candidate != null && candidate.optBoolean("is_active", true)) {
                    latest = candidate;
                    break;
                }
            }
            if (latest == null) continue;

            String owner = pkg.optString("owner", "").trim();
            String packageName = pkg.optString("name", "").trim();
            String version = latest.optString("version_number", "").trim();
            String download = latest.optString("download_url", "").trim();
            if (owner.isEmpty() || packageName.isEmpty() || version.isEmpty() || download.isEmpty()) {
                continue;
            }

            JSONObject mod = new JSONObject();
            String stableId = owner + "-" + packageName;
            mod.put("id", packageName);
            mod.put("name", packageName.replace('_', ' '));
            mod.put("author", owner);
            JSONArray authors = new JSONArray();
            authors.put(owner);
            mod.put("authors", authors);
            mod.put("version", version);
            mod.put("description", latest.optString("description", pkg.optString("description", "")));
            mod.put("type", primaryType);
            mod.put("game_id", SUPPORTED_GAME_ID);
            mod.put("min_app_version_code", 0);
            mod.put("download_url", download);
            mod.put("file_name", stableId + "-" + version + ".zip");
            mod.put("package_type", "zip");

            String packageUrl = pkg.optString("package_url", "").trim();
            String website = latest.optString("website_url", "").trim();
            mod.put("homepage", !packageUrl.isEmpty() ? packageUrl : website);
            mod.put("thumbnail", latest.optString("icon", "").trim());
            mod.put("thunderstore_id", stableId);
            mod.put("downloads", latest.optInt("downloads", 0));
            mod.put("dependencies", latest.optJSONArray("dependencies") == null
                    ? new JSONArray() : latest.optJSONArray("dependencies"));
            mods.put(mod);
        }

        validateNormalizedCatalog(mods);
        root.put("mods", mods);
        return root;
    }

    private static void validateNormalizedCatalog(JSONArray mods) throws Exception {
        for (int i = 0; i < mods.length(); i++) {
            JSONObject mod = mods.optJSONObject(i);
            if (mod == null) {
                throw new IllegalArgumentException("Catalog entry " + i + " is not an object");
            }
            String id = mod.optString("id", "").trim();
            String name = mod.optString("name", "").trim();
            String download = mod.optString("download_url", "").trim();
            String fileName = mod.optString("file_name", "").trim();
            String packageType = mod.optString("package_type", "").trim();
            if (id.isEmpty() || name.isEmpty() || download.isEmpty()) {
                throw new IllegalArgumentException("Catalog entry " + i + " is missing id, name, or download_url");
            }
            URL parsed = new URL(download);
            if (!"https".equalsIgnoreCase(parsed.getProtocol())) {
                throw new SecurityException("Catalog entry " + id + " does not use HTTPS");
            }
            if (resolvePackageExtension(download, fileName, packageType) == null) {
                throw new IllegalArgumentException("Catalog entry " + id
                        + " must identify a .zip, .nrm, or .rtz package");
            }
            String thumbnail = mod.optString("thumbnail", "").trim();
            if (!thumbnail.isEmpty()) {
                URL thumbnailUrl = new URL(thumbnail);
                if (!"https".equalsIgnoreCase(thumbnailUrl.getProtocol())) {
                    throw new SecurityException("Catalog entry " + id + " thumbnail does not use HTTPS");
                }
            }
        }
    }

    private static String displayAuthors(JSONObject mod) {
        JSONArray authors = mod.optJSONArray("authors");
        if (authors == null || authors.length() == 0) {
            String legacy = mod.optString("author", "Unknown").trim();
            return legacy.isEmpty() ? "Unknown" : legacy;
        }

        ArrayList<String> names = new ArrayList<>();
        for (int i = 0; i < authors.length(); i++) {
            Object value = authors.opt(i);
            String name = "";
            if (value instanceof String) {
                name = ((String) value).trim();
            } else if (value instanceof JSONObject) {
                name = ((JSONObject) value).optString("name", "").trim();
            }
            if (!name.isEmpty()) names.add(name);
        }
        return names.isEmpty() ? "Unknown" : android.text.TextUtils.join(", ", names);
    }

    private void loadThumbnail(String url, ImageView view, String modId) {
        thumbnailExecutor.execute(() -> {
            try {
                byte[] bytes = downloadBytes(url, MAX_THUMBNAIL_BYTES);
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bitmap == null) throw new IllegalArgumentException("Unsupported thumbnail image");
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) view.setImageBitmap(bitmap);
                });
            } catch (Exception e) {
                Log.w(TAG, "Could not load thumbnail for " + modId, e);
            }
        });
    }

    private static void writeBytesAtomically(File destination, byte[] bytes) throws Exception {
        File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IllegalStateException("Could not create catalog cache directory");
        }
        File temporary = new File(destination.getAbsolutePath() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(bytes);
            output.getFD().sync();
        }
        if (destination.exists() && !destination.delete()) {
            throw new IllegalStateException("Could not replace catalog cache");
        }
        if (!temporary.renameTo(destination)) {
            throw new IllegalStateException("Could not finalize catalog cache");
        }
    }

    private static byte[] readLimitedFile(File file, long maxBytes) throws Exception {
        if (!file.isFile()) {
            throw new IllegalStateException("No cached catalog");
        }
        if (file.length() > maxBytes) {
            throw new IllegalStateException("Cached catalog is too large");
        }
        try (InputStream input = new BufferedInputStream(new java.io.FileInputStream(file));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            copyLimited(input, output, maxBytes);
            return output.toByteArray();
        }
    }

    private static byte[] downloadBytes(String url, long maxBytes) throws Exception {
        HttpURLConnection connection = open(url);
        try (InputStream input = new BufferedInputStream(connection.getInputStream());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            copyLimited(input, output, maxBytes);
            return output.toByteArray();
        } finally {
            connection.disconnect();
        }
    }

    private static void downloadToFile(String url, File destination, long maxBytes) throws Exception {
        HttpURLConnection connection = open(url);
        File temporary = new File(destination.getParentFile(), destination.getName() + ".part");
        try (InputStream input = new BufferedInputStream(connection.getInputStream());
             FileOutputStream output = new FileOutputStream(temporary)) {
            copyLimited(input, output, maxBytes);
            output.getFD().sync();
        } finally {
            connection.disconnect();
        }
        if (destination.exists() && !destination.delete()) {
            throw new IllegalStateException("Could not replace cached download");
        }
        if (!temporary.renameTo(destination)) {
            throw new IllegalStateException("Could not finalize downloaded mod");
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        URL parsed = new URL(url);
        if (!"https".equalsIgnoreCase(parsed.getProtocol())) {
            throw new SecurityException("Only HTTPS mod-server URLs are allowed");
        }
        HttpURLConnection connection = (HttpURLConnection) parsed.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/json, application/zip, application/octet-stream");
        connection.setRequestProperty("User-Agent", "8CEE-BanjoRecomp-Android");
        int response = connection.getResponseCode();
        URL finalUrl = connection.getURL();
        if (finalUrl == null || !"https".equalsIgnoreCase(finalUrl.getProtocol())) {
            connection.disconnect();
            throw new SecurityException("Redirected to non-HTTPS URL");
        }
        if (response < 200 || response >= 300) {
            connection.disconnect();
            throw new IllegalStateException("Server returned HTTP " + response);
        }
        return connection;
    }

    private static void copyLimited(InputStream input, java.io.OutputStream output, long maxBytes) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > maxBytes) {
                throw new IllegalStateException("Download is larger than allowed");
            }
            output.write(buffer, 0, count);
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new BufferedInputStream(new java.io.FileInputStream(file))) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        StringBuilder result = new StringBuilder(64);
        for (byte b : digest.digest()) {
            result.append(String.format(Locale.US, "%02x", b & 0xff));
        }
        return result.toString();
    }

    private static String supportedPackageExtension(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        String lower = value.trim().toLowerCase(Locale.US);
        try {
            if (lower.startsWith("https://")) {
                lower = new URL(value).getPath().toLowerCase(Locale.US);
            }
        } catch (Exception ignored) {}
        if (lower.endsWith(".nrm")) return ".nrm";
        if (lower.endsWith(".rtz")) return ".rtz";
        if (lower.endsWith(".zip")) return ".zip";
        return null;
    }

    private static String packageTypeExtension(String packageType) {
        if (packageType == null) return null;
        switch (packageType.trim().toLowerCase(Locale.US)) {
            case "nrm":
            case ".nrm":
                return ".nrm";
            case "rtz":
            case ".rtz":
                return ".rtz";
            case "zip":
            case ".zip":
                return ".zip";
            default:
                return null;
        }
    }

    private static String resolvePackageExtension(String downloadUrl, String fileName, String packageType) {
        String urlExtension = supportedPackageExtension(downloadUrl);
        String fileExtension = supportedPackageExtension(fileName);
        String typeExtension = packageTypeExtension(packageType);

        String resolved = urlExtension != null ? urlExtension
                : fileExtension != null ? fileExtension
                : typeExtension;
        if (resolved == null) return null;
        if (urlExtension != null && !resolved.equals(urlExtension)) return null;
        if (fileExtension != null && !resolved.equals(fileExtension)) return null;
        if (typeExtension != null && !resolved.equals(typeExtension)) return null;
        if (packageType != null && !packageType.trim().isEmpty() && typeExtension == null) return null;
        return resolved;
    }

    private static String resolvePackageFileName(String id, String downloadUrl, String fileName, String packageType) {
        String extension = resolvePackageExtension(downloadUrl, fileName, packageType);
        if (extension == null) {
            throw new IllegalArgumentException("Unsupported or conflicting mod package type");
        }
        if (fileName != null && !fileName.trim().isEmpty()) {
            String trimmed = fileName.trim();
            if (trimmed.contains("/") || trimmed.contains("\\")) {
                throw new IllegalArgumentException("Unsafe mod package file_name");
            }
            String sanitized = sanitizeFileName(trimmed);
            if (!sanitized.toLowerCase(Locale.US).endsWith(extension)) {
                sanitized += extension;
            }
            return sanitized;
        }
        return sanitizeFileName(id) + extension;
    }

    private static String sanitizeFileName(String value) {
        String safe = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isEmpty() ? "mod" : safe;
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty() ? e.getClass().getSimpleName() : message;
    }

    private GradientDrawable rounded(int fillColor, int strokeColor, int strokeDp, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(strokeDp), strokeColor);
        return drawable;
    }

    private void styleButton(Button button, boolean primary) {
        button.setTextColor(primary ? Color.rgb(13, 17, 23) : Color.rgb(230, 237, 243));
        button.setTextSize(13f);
        button.setBackgroundTintList(ColorStateList.valueOf(primary
                ? Color.rgb(88, 166, 255)
                : Color.rgb(33, 38, 45)));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
