package io.github.banjorecomp;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.List;

public final class DiagnosticsActivity extends Activity {
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        setContentView(scroll);

        TextView title = text("Logs & Diagnostics", 24, Typeface.BOLD);
        root.addView(title);
        root.addView(text("BanjoRecomp keeps the five newest diagnostic sessions. Tap a log to share it.", 14, Typeface.NORMAL));

        Button shareCurrent = new Button(this);
        shareCurrent.setText("Share current session log");
        shareCurrent.setOnClickListener(v -> {
            File file = DiagnosticsLogger.currentSessionFile();
            if (file == null) {
                List<File> files = DiagnosticsLogger.listLogFiles(this);
                if (!files.isEmpty()) file = files.get(0);
            }
            if (file != null) share(file);
        });
        root.addView(shareCurrent);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        if (list == null) return;
        list.removeAllViews();
        List<File> files = DiagnosticsLogger.listLogFiles(this);
        int count = Math.min(5, files.size());
        for (int i = 0; i < count; i++) {
            File file = files.get(i);
            Button button = new Button(this);
            button.setAllCaps(false);
            button.setText(file.getName() + " (" + humanSize(file.length()) + ")");
            button.setOnClickListener(v -> share(file));
            list.addView(button, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        if (count == 0) list.addView(text("No diagnostic logs yet.", 14, Typeface.ITALIC));
    }

    private void share(File file) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, file.getName());
        intent.putExtra(Intent.EXTRA_STREAM, DiagnosticsFilesProvider.shareUri(this, file.getName()));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, "Share diagnostic log"));
    }

    private TextView text(String value, float size, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Color.WHITE);
        view.setTypeface(Typeface.DEFAULT, style);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(java.util.Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0));
    }
}
