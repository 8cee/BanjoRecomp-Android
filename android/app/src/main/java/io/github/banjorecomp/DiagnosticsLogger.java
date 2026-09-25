package io.github.banjorecomp;

import android.content.Context;
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
import java.util.List;
import java.util.Locale;

public final class DiagnosticsLogger {
    private static final String TAG = "BanjoRecomp";
    private static final int KEEP_FILES = 5;
    private static final long MAX_BYTES = 8L * 1024L * 1024L;
    private static final Object LOCK = new Object();
    private static Session session;

    private static final class Session {
        final File file;
        final FileOutputStream stream;
        final BufferedWriter writer;
        Process logcat;
        long bytes;

        Session(File file, FileOutputStream stream, BufferedWriter writer) {
            this.file = file;
            this.stream = stream;
            this.writer = writer;
        }
    }

    private DiagnosticsLogger() {}

    public static void start(Context context) {
        synchronized (LOCK) {
            if (session != null) return;
            try {
                File dir = getDiagnosticsDir(context);
                String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
                File file = new File(dir, "banjo-" + stamp + ".log");
                FileOutputStream stream = new FileOutputStream(file, false);
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8), 16384);
                Session s = new Session(file, stream, writer);
                session = s;
                write(s, "BanjoRecomp Android diagnostic log\n");
                write(s, "Package: " + context.getPackageName() + "\n");
                write(s, "Device: " + Build.MANUFACTURER + " " + Build.MODEL
                        + " Android " + Build.VERSION.RELEASE + " API " + Build.VERSION.SDK_INT + "\n");
                write(s, "Started: " + new Date() + "\n\n");
                Process p = new ProcessBuilder("logcat", "-v", "time",
                        "--pid=" + android.os.Process.myPid(), "-T", "1")
                        .redirectErrorStream(true).start();
                s.logcat = p;
                Thread t = new Thread(() -> drain(s, p), "banjo-diagnostics");
                t.setDaemon(true);
                t.start();
                prune(dir, file);
            } catch (Throwable t) {
                Log.w(TAG, "Diagnostics capture unavailable", t);
                closeLocked();
            }
        }
    }

    public static void stop(String reason) {
        synchronized (LOCK) {
            if (session == null) return;
            try {
                write(session, "\nSession ended: " + reason + "\n");
            } catch (Throwable ignored) {}
            closeLocked();
        }
    }

    public static void mark(String message) {
        synchronized (LOCK) {
            if (session == null) return;
            try {
                write(session, "---- " + new Date() + " " + message + " ----\n");
            } catch (Throwable ignored) {}
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
            for (File f : files) if (f.isFile() && f.getName().endsWith(".log")) out.add(f);
        }
        out.sort(Comparator.comparingLong(File::lastModified).reversed());
        return out;
    }

    private static void drain(Session s, Process process) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            int pending = 0;
            while ((line = reader.readLine()) != null) {
                synchronized (LOCK) {
                    if (session != s) return;
                    if (s.bytes >= MAX_BYTES) return;
                    s.writer.write(line);
                    s.writer.newLine();
                    s.bytes += line.length() + 1L;
                    if (++pending >= 20 || line.contains(" E/") || line.contains(" F/")) {
                        s.writer.flush();
                        pending = 0;
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void write(Session s, String text) throws Exception {
        if (s.bytes >= MAX_BYTES) return;
        s.writer.write(text);
        s.writer.flush();
        s.bytes += text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static void closeLocked() {
        Session s = session;
        session = null;
        if (s == null) return;
        try { if (s.logcat != null) s.logcat.destroy(); } catch (Throwable ignored) {}
        try { s.writer.flush(); } catch (Throwable ignored) {}
        try { s.stream.getFD().sync(); } catch (Throwable ignored) {}
        try { s.writer.close(); } catch (Throwable ignored) {}
    }

    private static void prune(File dir, File current) {
        File[] files = dir.listFiles();
        if (files == null) return;
        ArrayList<File> logs = new ArrayList<>();
        for (File f : files) if (f.isFile() && f.getName().endsWith(".log") && !f.equals(current)) logs.add(f);
        logs.sort(Comparator.comparingLong(File::lastModified).reversed());
        for (int i = KEEP_FILES - 1; i < logs.size(); i++) {
            try { logs.get(i).delete(); } catch (Throwable ignored) {}
        }
    }
}
