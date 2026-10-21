package dev.dsh.harness;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs the DeepSeek Harness inside the app.
 *
 * <p>The APK carries three pieces: a Node 24 runtime built for Android, the
 * harness installation as a zip of {@code node_modules}, and the shared
 * libraries Node links against. {@link #install} unpacks the assets into app
 * storage, then {@link #start} spawns {@code dsh web} on loopback and hands the
 * activity the tokenized URL it prints.</p>
 *
 * <p>Native assets are executable bytes in app storage, which Android only
 * permits for apps targeting SDK 28 or lower (the same constraint Termux
 * lives with), so the manifest pins that target. The ELF runpaths are rewritten
 * to {@code $ORIGIN} at packaging time, which keeps the runtime relocatable
 * inside the app sandbox.</p>
 *
 * <p>Everything the harness needs at runtime lives in the app's private
 * directory, so nothing depends on a terminal, a package manager, or another
 * app being installed.</p>
 */
public final class EmbeddedHarness {

    /** Receives runtime lifecycle events on the main thread. */
    public interface Listener {
        /** Human-readable progress while the payload is unpacked or started. */
        void onProgress(String stage);

        /** The server is listening; {@code url} carries the launch token. */
        void onReady(String url);

        /** Startup failed; {@code log} is the process output collected so far. */
        void onFailure(String log);
    }

    private static final String TAG = "DshEmbedded";
    private static final String PAYLOAD_REVISION = "1";
    private static final Pattern LAUNCH_URL =
            Pattern.compile("(http://127\\.0\\.0\\.1:\\d+/\\?token=[A-Za-z0-9_-]+)");
    private static final int MAX_LOG_LINES = 400;
    private static final int START_TIMEOUT_MS = 180_000;

    private final Context context;
    private final List<String> logLines = new ArrayList<>();
    private Process process;
    private Thread reader;
    private volatile boolean stopping;

    public EmbeddedHarness(Context context) {
        this.context = context.getApplicationContext();
    }

    /** @return {@code true} while the harness process is alive. */
    public boolean isRunning() {
        return process != null && process.isAlive();
    }

    /** @return the collected process output, oldest line first. */
    public List<String> log() {
        synchronized (logLines) {
            return new ArrayList<>(logLines);
        }
    }

    /** @return the directory holding the unpacked payload. */
    public File runtimeDir() {
        return new File(context.getFilesDir(), "runtime");
    }

    /**
     * Unpack the bundled payload when the installed copy is missing or stale.
     *
     * @param progress receives coarse stage descriptions
     * @throws IOException when an asset is missing or the disk is full
     */
    public void install(Progress progress) throws IOException {
        File runtime = runtimeDir();
        String expected = versionName() + ":" + PAYLOAD_REVISION;
        File stamp = new File(runtime, "payload.stamp");
        File entry = new File(runtime, "node_modules/@deepseek-ai/dsh/lib/bin.js");
        if (stamp.isFile() && entry.isFile() && expected.equals(readText(stamp))) {
            progress.stage("Runtime ready");
            return;
        }
        progress.stage("Unpacking the harness runtime");
        deleteTree(runtime);
        File lib = new File(runtime, "lib");
        if (!lib.mkdirs() && !lib.isDirectory()) throw new IOException("cannot create " + lib);

        // Android 10+ refuses to exec files in app storage for targetSdk >= 29;
        // this app targets 28 (the model Termux proves works) and runs the
        // runtime from its private directory.
        File node = new File(runtime, "node");
        copyAsset("runtime/node", node);
        // setExecutable() may report success on FUSE-backed storage without the
        // bit sticking, so ask for the effective permission afterwards.
        node.setExecutable(true, false);
        if (!node.canExecute()) throw new IOException("cannot make " + node + " executable");

        copyAsset("runtime/cacert.pem", new File(runtime, "cacert.pem"));
        String[] libraries = {
                "libc++_shared.so", "libcares.so", "libcrypto.so.3", "libicudata.so.78",
                "libicui18n.so.78", "libicuuc.so.78", "libsqlite3.so", "libssl.so.3", "libz.so.1",
        };
        for (String name : libraries) {
            copyAsset("runtime/lib/" + name, new File(lib, name));
        }

        progress.stage("Installing the harness files");
        unzipAsset("runtime/payload.zip", runtime, progress);

        File bin = new File(runtime, "bin");
        if (!bin.mkdirs() && !bin.isDirectory()) throw new IOException("cannot create " + bin);
        writeBashShim(new File(bin, "bash"));

        for (String dir : new String[]{"home", "home/.dsh", "workspace", "tmp"}) {
            File created = new File(runtime, dir);
            if (!created.mkdirs() && !created.isDirectory()) {
                throw new IOException("cannot create " + created);
            }
        }
        writeText(stamp, expected);
        progress.stage("Runtime installed");
    }

    /**
     * Start {@code dsh web} on loopback and report the URL it prints.
     *
     * <p>Runs the install first when needed; all callbacks arrive on the main
     * thread of the caller's process.</p>
     */
    public void start(final Listener listener) {
        stop();
        stopping = false;
        synchronized (logLines) {
            logLines.clear();
        }
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    install(new Progress() {
                        @Override
                        public void stage(final String stage) {
                            post(new Runnable() {
                                @Override
                                public void run() {
                                    listener.onProgress(stage);
                                }
                            });
                        }
                    });
                    if (stopping) return;
                    post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onProgress("Starting the harness");
                        }
                    });
                    final String url = spawnAndWait();
                    if (stopping) return;
                    if (url == null) {
                        post(new Runnable() {
                            @Override
                            public void run() {
                                listener.onFailure(joinedLog());
                            }
                        });
                        return;
                    }
                    post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onReady(url);
                        }
                    });
                } catch (final Exception failure) {
                    Log.w(TAG, "embedded harness failed", failure);
                    post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onFailure(joinedLog() + "\n" + failure);
                        }
                    });
                }
            }
        }, "dsh-embedded-start");
        worker.setDaemon(true);
        worker.start();
    }

    /** Terminate the harness process; safe to call repeatedly. */
    public void stop() {
        stopping = true;
        Process current = process;
        process = null;
        if (current != null) {
            current.destroy();
            try {
                if (!current.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) current.destroyForcibly();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                current.destroyForcibly();
            }
        }
        Thread current0 = reader;
        reader = null;
        if (current0 != null) current0.interrupt();
    }

    private String spawnAndWait() throws IOException, InterruptedException {
        File runtime = runtimeDir();
        File workspace = new File(runtime, "workspace");
        int port = reservePort();
        List<String> command = new ArrayList<>();
        command.add(new File(runtimeDir(), "node").getAbsolutePath());
        // The harness reads Node's internal module loader; --expose-internals is
        // what the JS replacement for the native require-builtin addon uses.
        command.add("--expose-internals");
        command.add(new File(runtime, "node_modules/@deepseek-ai/dsh/lib/bin.js").getAbsolutePath());
        command.add("web");
        command.add("--host");
        command.add("127.0.0.1");
        command.add("--port");
        command.add(Integer.toString(port));
        command.add("--no-open");

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workspace);
        builder.redirectErrorStream(true);
        builder.environment().put("HOME", new File(runtime, "home").getAbsolutePath());
        builder.environment().put("DSH_HOME", new File(runtime, "home/.dsh").getAbsolutePath());
        builder.environment().put("TMPDIR", new File(runtime, "tmp").getAbsolutePath());
        builder.environment().put("PATH", new File(runtime, "bin").getAbsolutePath()
                + ":/system/bin:/system/xbin:/vendor/bin");
        builder.environment().put("LD_LIBRARY_PATH", new File(runtime, "lib").getAbsolutePath());
        builder.environment().put("SSL_CERT_FILE", new File(runtime, "cacert.pem").getAbsolutePath());
        // No Landlock on Android: the kernel-side sandbox addon has no bionic
        // build, so sessions run with the app's own UID boundary instead.
        builder.environment().put("DSH_PERMISSION_MODE", "danger-full-access");
        builder.environment().put("TERM", "dumb");
        builder.environment().remove("NODE_OPTIONS");

        appendLog("$ " + command.get(0) + " … web --port " + port);
        Process started = builder.start();
        process = started;
        startPump(started);

        long deadline = System.currentTimeMillis() + START_TIMEOUT_MS;
        String url = null;
        while (System.currentTimeMillis() < deadline) {
            if (!started.isAlive()) break;
            url = findUrl();
            if (url != null) break;
            Thread.sleep(200);
        }
        if (url == null && started.isAlive()) {
            appendLog("timed out waiting for the launch URL");
        }
        if (url == null) {
            stopQuietly(started);
            return null;
        }
        if (!waitForPort(port, 30_000)) {
            appendLog("the server printed a URL but did not accept connections");
            stopQuietly(started);
            return null;
        }
        return url;
    }

    private void startPump(final Process started) {
        Thread pump = new Thread(new Runnable() {
            @Override
            public void run() {
                try (BufferedReader input = new BufferedReader(new InputStreamReader(
                        started.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = input.readLine()) != null) {
                        appendLog(line);
                    }
                } catch (IOException closed) {
                    // The process ended; the failure path reports the log.
                }
            }
        }, "dsh-embedded-log");
        pump.setDaemon(true);
        reader = pump;
        pump.start();
    }

    private String findUrl() {
        synchronized (logLines) {
            for (String line : logLines) {
                Matcher matcher = LAUNCH_URL.matcher(line);
                if (matcher.find()) return matcher.group(1);
            }
        }
        return null;
    }

    private boolean waitForPort(int port, int timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (!isRunning()) return false;
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
                return true;
            } catch (IOException notYet) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            } finally {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // Nothing to recover from a failing close.
                }
            }
        }
        return false;
    }

    private void stopQuietly(Process target) {
        target.destroy();
        try {
            if (!target.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) target.destroyForcibly();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            target.destroyForcibly();
        }
    }

    private int reservePort() throws IOException {
        for (int candidate : new int[]{3080, 3081, 3082, 3083, 0}) {
            ServerSocket socket = new ServerSocket();
            try {
                socket.setReuseAddress(false);
                socket.bind(new InetSocketAddress("127.0.0.1", candidate));
                return socket.getLocalPort();
            } catch (IOException busy) {
                // Try the next candidate.
            } finally {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // Closing a probe socket cannot strand a port.
                }
            }
        }
        throw new IOException("no loopback port available");
    }

    private String versionName() {
        try {
            String name = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
            return name == null ? "dev" : name;
        } catch (PackageManager.NameNotFoundException unknown) {
            return "dev";
        }
    }

    // ------------------------------------------------------------- assets

    private void copyAsset(String asset, File target) throws IOException {
        try (InputStream input = context.getAssets().open(asset);
             OutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
    }

    /**
     * Unpack {@code payload.zip} straight from the APK into {@code root}.
     *
     * <p>Streaming avoids a second 53 MB copy in app storage; the archive is
     * written by {@code scripts/prepare-runtime.sh} with forward-slash entry
     * names only.</p>
     */
    private void unzipAsset(String asset, File root, Progress progress) throws IOException {
        String rootPath = root.getCanonicalPath();
        try (InputStream raw = context.getAssets().open(asset);
             java.util.zip.ZipInputStream archive =
                     new java.util.zip.ZipInputStream(new java.io.BufferedInputStream(raw, 128 * 1024))) {
            int count = 0;
            byte[] buffer = new byte[128 * 1024];
            java.util.zip.ZipEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                File target = new File(root, entry.getName());
                if (!target.getCanonicalPath().startsWith(rootPath)) {
                    throw new IOException("payload entry escapes the runtime directory: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    if (!target.mkdirs() && !target.isDirectory()) {
                        throw new IOException("cannot create " + target);
                    }
                    archive.closeEntry();
                    continue;
                }
                File parent = target.getParentFile();
                if (parent != null && !parent.mkdirs() && !parent.isDirectory()) {
                    throw new IOException("cannot create " + parent);
                }
                try (OutputStream output = new FileOutputStream(target)) {
                    int read;
                    while ((read = archive.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                    }
                }
                archive.closeEntry();
                count++;
                if (count % 2000 == 0) {
                    progress.stage("Installing the harness files (" + count + " files)");
                }
            }
        }
    }

    private void writeBashShim(File target) throws IOException {
        // The harness runs every tool command as `bash -c <command>` and the
        // terminal backend asks for `bash --noprofile --norc -i`. Android only
        // ships mksh at /system/bin/sh, so this shim drops the bash-only startup
        // flags and hands the command to mksh, which covers the POSIX syntax
        // the tools use.
        String script = "#!/system/bin/sh\n"
                + "# Installed by EmbeddedHarness: run bash invocations on Android's mksh.\n"
                + "cmd=\n"
                + "while [ $# -gt 0 ]; do\n"
                + "  case \"$1\" in\n"
                + "    -c|-lc) cmd=\"$2\"; shift 2; break ;;\n"
                + "    --noprofile|--norc|-i|-l|--login) shift ;;\n"
                + "    --version) echo 'GNU bash, version 5.2.0(1)-release (mksh shim)'; exit 0 ;;\n"
                + "    *) break ;;\n"
                + "  esac\n"
                + "done\n"
                + "if [ -n \"$cmd\" ]; then exec /system/bin/sh -c \"$cmd\"; fi\n"
                + "exec /system/bin/sh \"$@\"\n";
        writeText(target, script);
        target.setExecutable(true, false);
        if (!target.canExecute()) {
            // The harness still runs; only tools that shell out lose their shell.
            Log.w(TAG, "could not make " + target + " executable");
        }
    }

    private static void writeText(File target, String text) throws IOException {
        try (OutputStream output = new FileOutputStream(target)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String readText(File source) throws IOException {
        try (InputStream input = new java.io.FileInputStream(source)) {
            java.io.ByteArrayOutputStream collected = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[256];
            int read;
            while ((read = input.read(buffer)) != -1) {
                collected.write(buffer, 0, read);
            }
            return new String(collected.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void deleteTree(File target) {
        if (!target.exists()) return;
        File[] children = target.listFiles();
        if (children != null) {
            for (File child : children) deleteTree(child);
        }
        if (!target.delete()) Log.w(TAG, "could not delete " + target);
    }

    // -------------------------------------------------------------- log

    private void appendLog(String line) {
        synchronized (logLines) {
            logLines.add(line);
            while (logLines.size() > MAX_LOG_LINES) logLines.remove(0);
        }
    }

    private String joinedLog() {
        StringBuilder joined = new StringBuilder();
        synchronized (logLines) {
            for (String line : logLines) joined.append(line).append('\n');
        }
        return joined.toString();
    }

    private void post(Runnable action) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(action);
    }

    /** Receives the coarse install stages. */
    public interface Progress {
        /** @param stage human-readable description of the current step */
        void stage(String stage);
    }
}
