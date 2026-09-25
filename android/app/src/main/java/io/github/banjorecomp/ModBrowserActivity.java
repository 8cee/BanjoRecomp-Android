package io.github.banjorecomp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
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

public final class ModBrowserActivity extends Activity {
    public static final String EXTRA_MOD_PATH = "banjo_mod_server_path";
    private static final String TAG = "BanjoModServer";
    private static final String CATALOG_URL =
            "https://raw.githubusercontent.com/8cee/BanjoRecomp-Android/android/mod-server/index.json";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final long MAX_CATALOG_BYTES = 2L * 1024L * 1024L;
    private static final long MAX_MOD_BYTES = 512L * 1024L * 1024L;
    private static final long MAX_THUMBNAIL_BYTES = 4L * 1024L * 1024L;
    private static final String CATALOG_CACHE_NAME = "mod-server-catalog.json";
    private static final String DOWNLOADED_PREFS = "mod-server-downloaded";
    private static final String SUPPORTED_GAME_ID = "bk";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
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
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(16));
        root.setBackgroundColor(Color.rgb(20, 24, 31));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("8CEE Mod Server");
        title.setTextColor(Color.WHITE);
        title.setTextSize(24f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button refresh = new Button(this);
        refresh.setText("Refresh");
        refresh.setOnClickListener(v -> refreshCatalog());
        header.addView(refresh);

        root.addView(header);

        search = new EditText(this);
        search.setHint("Search mods");
        search.setSingleLine(true);
        search.setTextColor(Color.WHITE);
        search.setHintTextColor(Color.GRAY);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (currentCatalog != null) showCatalog(currentCatalog, false);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        root.addView(search, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        typeFilter = new Spinner(this);
        typeAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, new ArrayList<>(Collections.singletonList("All")));
        typeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        typeFilter.setAdapter(typeAdapter);
        typeFilter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!updatingTypeFilter && currentCatalog != null) showCatalog(currentCatalog, false);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        root.addView(typeFilter, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        updatesOnly = new CheckBox(this);
        updatesOnly.setText("Updates only");
        updatesOnly.setTextColor(Color.WHITE);
        updatesOnly.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (currentCatalog != null) showCatalog(currentCatalog, false);
        });
        root.addView(updatesOnly, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        status = new TextView(this);
        status.setTextColor(Color.LTGRAY);
        status.setTextSize(14f);
        status.setPadding(0, dp(8), 0, dp(8));
        root.addView(status);

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        root.addView(progress, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        modList = new LinearLayout(this);
        modList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(modList, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
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

    private boolean hasCatalogUpdate(JSONObject mod) {
        String id = mod.optString("id", "").trim();
        String catalogVersion = mod.optString("version", "").trim();
        if (id.isEmpty() || catalogVersion.isEmpty()) return false;
        try {
            String installedVersion = BanjoSDLActivity.nativeGetInstalledModVersion(id);
            return installedVersion != null
                    && !installedVersion.isEmpty()
                    && !installedVersion.equals(catalogVersion);
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
        String downloadUrl = mod.optString("download_url", "").trim();
        String fileName = mod.optString("file_name", "").trim();
        String packageType = mod.optString("package_type", "").trim();
        String sha256 = mod.optString("sha256", "").trim().toLowerCase(Locale.US);

        if (id.isEmpty() || name.isEmpty() || downloadUrl.isEmpty()) {
            return;
        }
        boolean compatible = SUPPORTED_GAME_ID.equals(gameId)
                && (minAppVersionCode <= 0 || BuildConfig.VERSION_CODE >= minAppVersionCode);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackgroundColor(Color.rgb(35, 41, 52));

        if (!thumbnail.isEmpty()) {
            ImageView thumbnailView = new ImageView(this);
            thumbnailView.setAdjustViewBounds(true);
            thumbnailView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbnailView.setContentDescription(name + " thumbnail");
            card.addView(thumbnailView, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(160)));
            loadThumbnail(thumbnail, thumbnailView, id);
        }

        TextView nameView = new TextView(this);
        nameView.setText(name + (version.isEmpty() ? "" : "  v" + version));
        nameView.setTextColor(Color.WHITE);
        nameView.setTextSize(18f);
        nameView.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(nameView);

        TextView authorView = new TextView(this);
        authorView.setText("by " + author);
        authorView.setTextColor(Color.LTGRAY);
        card.addView(authorView);

        if (!description.isEmpty()) {
            TextView descView = new TextView(this);
            descView.setText(description);
            descView.setTextColor(Color.LTGRAY);
            descView.setPadding(0, dp(6), 0, dp(8));
            card.addView(descView);
        }

        TextView metaView = new TextView(this);
        StringBuilder meta = new StringBuilder("Type: ").append(type);
        if (!homepage.isEmpty()) meta.append("  •  Homepage available");
        if (!compatible) {
            if (!SUPPORTED_GAME_ID.equals(gameId)) {
                meta.append("  •  Incompatible game: ").append(gameId);
            } else {
                meta.append("  •  Requires app version code ").append(minAppVersionCode);
            }
        }
        metaView.setText(meta.toString());
        metaView.setTextColor(compatible ? Color.LTGRAY : Color.rgb(255, 180, 120));
        card.addView(metaView);

        if (!homepage.isEmpty()) {
            try {
                URL homepageUrl = new URL(homepage);
                if ("https".equalsIgnoreCase(homepageUrl.getProtocol())) {
                    Button homepageButton = new Button(this);
                    homepageButton.setText("Homepage");
                    homepageButton.setOnClickListener(v -> {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(homepage));
                        try {
                            startActivity(intent);
                        } catch (ActivityNotFoundException | SecurityException e) {
                            Log.w(TAG, "Could not open homepage for " + id, e);
                            status.setText("No app can open this mod homepage.");
                        }
                    });
                    card.addView(homepageButton);
                }
            } catch (Exception ignored) {
                Log.w(TAG, "Ignoring invalid homepage URL for " + id);
            }
        }

        String installedVersion = null;
        try {
            installedVersion = BanjoSDLActivity.nativeGetInstalledModVersion(id);
        } catch (UnsatisfiedLinkError error) {
            Log.w(TAG, "Installed mod lookup unavailable", error);
        }
        String downloadedVersion = getSharedPreferences(DOWNLOADED_PREFS, MODE_PRIVATE)
                .getString(id, null);

        if (installedVersion != null && !installedVersion.isEmpty()) {
            TextView statusView = new TextView(this);
            boolean installedCurrent = installedVersion.equals(version);
            statusView.setText("Installed: " + installedVersion
                    + (installedCurrent ? " • Up to date" : " • Update available"));
            statusView.setTextColor(installedCurrent ? Color.LTGRAY : Color.rgb(255, 220, 120));
            card.addView(statusView);
        }

        Button install = new Button(this);
        if (installedVersion != null && !installedVersion.isEmpty()) {
            install.setText(installedVersion.equals(version)
                    ? "Reinstall"
                    : "Update " + installedVersion + " → " + version);
        } else if (downloadedVersion != null && !downloadedVersion.isEmpty()) {
            install.setText(downloadedVersion.equals(version)
                    ? "Download Again"
                    : "Download Update " + downloadedVersion + " → " + version);
        } else {
            install.setText("Download & Install");
        }
        install.setEnabled(compatible);
        if (!compatible) {
            install.setText("Not Compatible");
        }
        install.setOnClickListener(v -> {
            install.setEnabled(false);
            downloadMod(id, name, version, downloadUrl, fileName, packageType, sha256, install);
        });
        card.addView(install);

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

            Button toggle = new Button(this);
            toggle.setText(autoEnabled ? "Required by Dependency" : enabled ? "Disable" : "Enable");
            toggle.setEnabled(!autoEnabled);
            final boolean targetEnabled = !enabled;
            toggle.setOnClickListener(v -> {
                try {
                    BanjoSDLActivity.nativeSetModEnabled(id, targetEnabled);
                    refreshCatalog();
                } catch (UnsatisfiedLinkError error) {
                    status.setText("Could not change mod state: native bridge unavailable");
                }
            });
            actions.addView(toggle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            Button uninstall = new Button(this);
            String uninstallBlockReason = null;
            try {
                uninstallBlockReason = BanjoSDLActivity.nativeGetModUninstallBlockReason(id);
            } catch (UnsatisfiedLinkError error) {
                uninstallBlockReason = "Native bridge unavailable";
            }
            if (uninstallBlockReason != null && !uninstallBlockReason.isEmpty()) {
                uninstall.setText("Uninstall Blocked");
                uninstall.setEnabled(false);
                TextView reasonView = new TextView(this);
                reasonView.setText(uninstallBlockReason);
                reasonView.setTextColor(Color.rgb(255, 180, 120));
                card.addView(reasonView);
            } else {
                uninstall.setText("Uninstall");
                uninstall.setOnClickListener(v -> new AlertDialog.Builder(this)
                        .setTitle("Uninstall " + name + "?")
                        .setMessage("This removes the installed mod package from BanjoRecomp. You can download it again later.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Uninstall", (dialog, which) -> {
                            try {
                                boolean removed = BanjoSDLActivity.nativeUninstallMod(id);
                                if (removed) {
                                    getSharedPreferences(DOWNLOADED_PREFS, MODE_PRIVATE).edit().remove(id).apply();
                                    status.setText("Uninstalled " + name);
                                    refreshCatalog();
                                } else {
                                    status.setText("Could not uninstall " + name + ". It may now be in use or required.");
                                }
                            } catch (UnsatisfiedLinkError error) {
                                status.setText("Could not uninstall: native bridge unavailable");
                            }
                        })
                        .show());
            }
            actions.addView(uninstall, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            card.addView(actions);
        }

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(12));
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

    private static JSONObject parseCatalog(byte[] bytes) throws Exception {
        JSONObject root = new JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        if (root.optInt("schema", 0) != 1) {
            throw new IllegalArgumentException("Unsupported catalog schema");
        }
        JSONArray mods = root.optJSONArray("mods");
        if (mods == null) {
            throw new IllegalArgumentException("Catalog has no mods array");
        }
        for (int i = 0; i < mods.length(); i++) {
            JSONObject mod = mods.optJSONObject(i);
            if (mod == null) {
                throw new IllegalArgumentException("Catalog entry " + i + " is not an object");
            }
            String id = mod.optString("id", "").trim();
            String name = mod.optString("name", "").trim();
            String gameId = mod.optString("game_id", SUPPORTED_GAME_ID).trim();
            String download = mod.optString("download_url", "").trim();
            String fileName = mod.optString("file_name", "").trim();
            String packageType = mod.optString("package_type", "").trim();
            if (id.isEmpty() || name.isEmpty() || download.isEmpty()) {
                throw new IllegalArgumentException("Catalog entry " + i + " is missing id, name, or download_url");
            }
            if (gameId.isEmpty()) {
                throw new IllegalArgumentException("Catalog entry " + id + " has an empty game_id");
            }
            int minAppVersionCode = mod.optInt("min_app_version_code", 0);
            if (minAppVersionCode < 0) {
                throw new IllegalArgumentException("Catalog entry " + id + " has a negative min_app_version_code");
            }
            URL parsed = new URL(download);
            if (resolvePackageExtension(download, fileName, packageType) == null) {
                throw new IllegalArgumentException("Catalog entry " + id
                        + " must identify a .zip, .nrm, or .rtz package via URL, file_name, or package_type");
            }
            if (!fileName.isEmpty() && (fileName.contains("/") || fileName.contains("\\"))) {
                throw new IllegalArgumentException("Catalog entry " + id + " has an unsafe file_name");
            }
            if (!"https".equalsIgnoreCase(parsed.getProtocol())) {
                throw new SecurityException("Catalog entry " + id + " does not use HTTPS");
            }
            String thumbnail = mod.optString("thumbnail", "").trim();
            if (!thumbnail.isEmpty()) {
                URL thumbnailUrl = new URL(thumbnail);
                if (!"https".equalsIgnoreCase(thumbnailUrl.getProtocol())) {
                    throw new SecurityException("Catalog entry " + id + " thumbnail does not use HTTPS");
                }
            }
            JSONArray authors = mod.optJSONArray("authors");
            if (authors != null) {
                for (int authorIndex = 0; authorIndex < authors.length(); authorIndex++) {
                    Object author = authors.opt(authorIndex);
                    if (author instanceof String) {
                        if (((String) author).trim().isEmpty()) {
                            throw new IllegalArgumentException("Catalog entry " + id + " has an empty author");
                        }
                    } else if (author instanceof JSONObject) {
                        if (((JSONObject) author).optString("name", "").trim().isEmpty()) {
                            throw new IllegalArgumentException("Catalog entry " + id + " has an author without a name");
                        }
                    } else {
                        throw new IllegalArgumentException("Catalog entry " + id + " has an invalid authors entry");
                    }
                }
            }
            String checksum = mod.optString("sha256", "").trim();
            if (!checksum.isEmpty() && !checksum.matches("(?i)[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Catalog entry " + id + " has an invalid SHA-256");
            }
        }
        return root;
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
        executor.execute(() -> {
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
