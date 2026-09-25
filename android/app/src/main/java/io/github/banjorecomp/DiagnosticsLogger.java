package io.github.banjorecomp;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class DiagnosticsLogger {
    private static final String TAG = "BanjoRecomp";
    private static final String PREFS = "banjo_diagnostics";
    private static final String PREF_ENABLED = "enabled";
    private static final boolean DEFAULT_ENABLED = true;
    private static final int KEEP_FILES = 5;
    private static final long MAX_BYTES = 8L * 1024L * 1024L;
    private static final int FLUSH_EVERY_N = 25;
    private static final Object LOCK = new Object();
    private static Session session;

    private static final class Session {
        final File file;
        final FileOutputStream stream;
        final BufferedWriter writer;
        Process logcat;
        Thread.UncaughtExceptionHandler previousHandler;
        long bytes;
        boolean capped;
        int pendingSinceFlush;
        long total;
        long fatalCount;
        long errorCount;
        long warnCount;
        long infoCount;
        long debugCount;
        long verboseCount;
        final Map<String, Integer> errors = new LinkedHashMap<>();
        final Map<String, Integer> warnings = new LinkedHashMap<>();

        Session(File file, FileOutputStream stream, BufferedWriter writer) {
            this.file = file;
            this.stream = stream;
            this.writer = writer;
        }
    }

    private DiagnosticsLogger() {}

    public static void start(Context context) {
        Context app = context.getApplicationContext();
        if (!isEnabled(app)) return;
        synchronized (LOCK) {
            if (session != null) return;
            try {
                File dir = getDiagnosticsDir(app);
                File file = new File(dir, sessionFileName());
                FileOutputStream stream = new FileOutputStream(file, false);
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(stream, StandardCharsets.UTF_8), 16 * 1024);
                Session s = new Session(file, stream, writer);
                session = s;

                writeHeader(app, s);
                installCrashHandler(s);
                startLogcatCapture(s);
                prune(dir, file);
                Log.i(TAG, "Diagnostics session started: " + file.getName());
            } catch (Throwable t) {
                Log.w(TAG, "Diagnostics capture unavailable", t);
                closeLocked(false, null);
            }
        }
    }

    public static void stop(String reason) {
        synchronized (LOCK) {
            closeLocked(true, reason);
        }
    }

    public static void mark(String message) {
        synchronized (LOCK) {
            Session s = session;
            if (s == null) return;
            try {
                writeLine(s, "---- [" + now() + "] " + message + " ----\n", true);
            } catch (Throwable ignored) {}
        }
    }

    public static boolean isEnabled(Context context) {
        try {
            return prefs(context).getBoolean(PREF_ENABLED, DEFAULT_ENABLED);
        } catch (Throwable ignored) {
            return DEFAULT_ENABLED;
        }
    }

    public static void setEnabled(Context context, boolean enabled) {
        Context app = context.getApplicationContext();
        try {
            prefs(app).edit().putBoolean(PREF_ENABLED, enabled).apply();
        } catch (Throwable t) {
            Log.w(TAG, "Could not save diagnostics preference", t);
        }

        synchronized (LOCK) {
            if (enabled) {
                if (session == null) start(app);
            } else {
                closeLocked(true, "disabled by user");
            }
        }
    }

    public static File currentSessionFile() {
        synchronized (LOCK) {
            return session == null ? null : session.file;
        }
    }

    public static File getDiagnosticsDir(Context context) {
        File base = context.getExternalFilesDir(null);
        File dir = new File(base != null ? base : context.getFilesDir(), "diagnostics");
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    public static List<File> listLogFiles(Context context) {
        ArrayList<File> out = new ArrayList<>();
        File[] files = getDiagnosticsDir(context).listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.getName().endsWith(".log")) out.add(f);
            }
        }
        out.sort(Comparator.comparingLong(File::lastModified).reversed());
        return out;
    }

    public static int deleteArchivedLogs(Context context) {
        synchronized (LOCK) {
            File current = session == null ? null : session.file;
            int deleted = 0;
            File[] files = getDiagnosticsDir(context).listFiles();
            if (files == null) return 0;
            for (File file : files) {
                if (!file.isFile() || !file.getName().endsWith(".log") || file.equals(current)) continue;
                try {
                    if (file.delete()) deleted++;
                } catch (Throwable ignored) {}
            }
            return deleted;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void installCrashHandler(Session s) {
        s.previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            synchronized (LOCK) {
                try {
                    if (session == s) {
                        writeCrash(s, thread, throwable);
                        writeSummary(s, "CRASH — summary generated before system handler");
                        flushAndSync(s);
                    }
                } catch (Throwable ignored) {}
            }

            Thread.UncaughtExceptionHandler previous = s.previousHandler;
            if (previous != null) previous.uncaughtException(thread, throwable);
        });
    }

    private static void startLogcatCapture(Session s) {
        final int pid = android.os.Process.myPid();
        try {
            Process process = new ProcessBuilder(
                    "logcat", "-v", "time", "--pid=" + pid, "-T", "1")
                    .redirectErrorStream(true)
                    .start();
            s.logcat = process;
            Thread reader = new Thread(() -> drain(s, process), "banjo-diagnostics-logcat");
            reader.setDaemon(true);
            reader.start();
            writeLine(s, "---- [" + now() + "] logcat capture started (pid=" + pid + ") ----\n", true);
        } catch (Throwable t) {
            Log.w(TAG, "Process logcat capture unavailable", t);
            try {
                writeLine(s, "WARNING: process logcat capture unavailable: " + safe(t.getMessage()) + "\n", true);
            } catch (Throwable ignored) {}
        }
    }

    private static void writeHeader(Context context, Session s) throws Exception {
        StringBuilder b = new StringBuilder();
        b.append("================================================================\n");
        b.append("BanjoRecomp Android — DIAGNOSTIC LOG\n");
        b.append("Session started : ").append(now()).append("\n");
        b.append("App             : ").append(appVersion(context)).append("\n");
        b.append("Package         : ").append(context.getPackageName()).append("\n");
        b.append("Device          : ").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append(" — Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        b.append("ABI             : ").append(Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown").append("\n");
        b.append("Capture         : process logcat + lifecycle + Java crash handler\n");
        b.append("Coverage        : Vulkan/RT64, audio, SDL surface/lifecycle, mods, saves,\n");
        b.append("                  Thunderstore, virtual touch controls, crashes.\n");
        b.append("Retention       : newest 5 sessions, 8 MB maximum per session\n");
        b.append("================================================================\n");
        writeLine(s, b.toString(), true);
    }

    private static void writeCrash(Session s, Thread thread, Throwable throwable) throws Exception {
        StringBuilder b = new StringBuilder();
        b.append("\n================================================================\n");
        b.append("!!! JAVA CRASH CAPTURED !!! [").append(now()).append("]\n");
        b.append("Thread: ").append(thread.getName()).append("\n");
        b.append("Exception: ").append(throwable.getClass().getName())
                .append(": ").append(safe(throwable.getMessage())).append("\n");
        appendStack(b, throwable, 0);
        b.append("================================================================\n");
        writeLine(s, b.toString(), true);
    }

    private static void appendStack(StringBuilder b, Throwable throwable, int depth) {
        if (throwable == null || depth > 5) return;
        for (StackTraceElement frame : throwable.getStackTrace()) {
            b.append("  at ").append(frame).append('\n');
        }
        Throwable cause = throwable.getCause();
        if (cause != null) {
            b.append("Caused by: ").append(cause.getClass().getName())
                    .append(": ").append(safe(cause.getMessage())).append('\n');
            appendStack(b, cause, depth + 1);
        }
    }

    private static void drain(Session s, Process process) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8), 16 * 1024)) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (LOCK) {
                    if (session != s || s.capped) return;
                    char level = levelOf(line);
                    count(s, level, line);
                    boolean important = level == 'F' || level == 'E' || level == 'W';
                    boolean flush = important || ++s.pendingSinceFlush >= FLUSH_EVERY_N;
                    if (flush) s.pendingSinceFlush = 0;
                    writeLine(s, line + "\n", flush);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static char levelOf(String line) {
        for (int i = 19; i < line.length() - 1 && i < 40; i++) {
            if (line.charAt(i) == '/' && i > 0) {
                char level = line.charAt(i - 1);
                return "VDIWEF".indexOf(level) >= 0 ? level : '?';
            }
        }
        return '?';
    }

    private static void count(Session s, char level, String line) {
        s.total++;
        switch (level) {
            case 'F':
                s.fatalCount++;
                collect(s.errors, line);
                break;
            case 'E':
                s.errorCount++;
                collect(s.errors, line);
                break;
            case 'W':
                s.warnCount++;
                collect(s.warnings, line);
                break;
            case 'I': s.infoCount++; break;
            case 'D': s.debugCount++; break;
            case 'V': s.verboseCount++; break;
            default: break;
        }
    }

    private static void collect(Map<String, Integer> map, String line) {
        String key = line;
        int marker = line.indexOf("): ");
        if (marker >= 0 && marker + 3 < line.length()) {
            int slash = line.indexOf('/');
            String tag = "logcat";
            if (slash >= 0 && slash + 1 < marker) {
                int tagEnd = line.indexOf(' ', slash + 1);
                if (tagEnd < 0 || tagEnd > marker) tagEnd = marker;
                tag = line.substring(slash + 1, tagEnd).trim();
            }
            key = tag + ": " + line.substring(marker + 3).trim();
        }
        if (key.length() > 240) key = key.substring(0, 240) + "…";

        Integer count = map.get(key);
        if (count != null) {
            map.put(key, count + 1);
        } else if (map.size() < 40) {
            map.put(key, 1);
        }
    }

    private static void writeSummary(Session s, String note) {
        try {
            StringBuilder b = new StringBuilder();
            b.append("\n================================================================\n");
            b.append("AUTOMATIC SESSION SUMMARY");
            if (note != null && !note.isEmpty()) b.append(" — ").append(note);
            b.append("\n");
            b.append(String.format(Locale.US,
                    "Captured lines: %d (F:%d E:%d W:%d I:%d D:%d V:%d)\n",
                    s.total, s.fatalCount, s.errorCount, s.warnCount,
                    s.infoCount, s.debugCount, s.verboseCount));
            if (s.capped) b.append("WARNING: capture reached the 8 MB session limit.\n");

            if (s.errors.isEmpty() && s.warnings.isEmpty()) {
                b.append("No fatal/error/warning lines were recorded in this session.\n");
            } else {
                if (!s.errors.isEmpty()) {
                    b.append("\nMost frequent errors/fatal lines:\n");
                    dumpTop(b, s.errors);
                }
                if (!s.warnings.isEmpty()) {
                    b.append("\nMost frequent warnings:\n");
                    dumpTop(b, s.warnings);
                }
            }
            b.append("================================================================\n");
            writeLine(s, b.toString(), true);
        } catch (Throwable ignored) {}
    }

    private static void dumpTop(StringBuilder b, Map<String, Integer> map) {
        int shown = 0;
        for (Map.Entry<String, Integer> entry : map.entrySet()) {
            b.append(String.format(Locale.US, "  %3dx  %s\n", entry.getValue(), entry.getKey()));
            if (++shown >= 25) break;
        }
    }

    private static void writeLine(Session s, String text, boolean flush) throws Exception {
        if (s.capped) return;
        s.writer.write(text);
        s.bytes += text.getBytes(StandardCharsets.UTF_8).length;
        if (flush) s.writer.flush();

        if (s.bytes >= MAX_BYTES) {
            s.writer.write("\n[CAPTURE PAUSED: session reached the 8 MB limit]\n");
            s.writer.flush();
            s.capped = true;
        }
    }

    private static void closeLocked(boolean withSummary, String reason) {
        Session s = session;
        session = null;
        if (s == null) return;

        try {
            if (reason != null) {
                writeLine(s, "---- [" + now() + "] end of session: " + reason + " ----\n", true);
            }
            if (withSummary) writeSummary(s, null);
        } catch (Throwable ignored) {}

        try { if (s.logcat != null) s.logcat.destroy(); } catch (Throwable ignored) {}
        try {
            if (s.previousHandler != null) {
                Thread.setDefaultUncaughtExceptionHandler(s.previousHandler);
            }
        } catch (Throwable ignored) {}
        flushAndSync(s);
        try { s.writer.close(); } catch (Throwable ignored) {}
        Log.i(TAG, "Diagnostics session ended: " + s.file.getName());
    }

    private static void flushAndSync(Session s) {
        try { s.writer.flush(); } catch (Throwable ignored) {}
        try { s.stream.getFD().sync(); } catch (Throwable ignored) {}
    }

    private static void prune(File dir, File current) {
        try {
            File[] files = dir.listFiles();
            if (files == null) return;
            ArrayList<File> logs = new ArrayList<>();
            for (File f : files) {
                if (f.isFile() && f.getName().endsWith(".log") && !f.equals(current)) logs.add(f);
            }
            logs.sort(Comparator.comparingLong(File::lastModified).reversed());
            for (int i = KEEP_FILES - 1; i < logs.size(); i++) {
                try { logs.get(i).delete(); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static String appVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName + " (" + info.getLongVersionCode() + ")";
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static String sessionFileName() {
        return "banjo-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".log";
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
