package dev.dsh.harness;

import android.os.Handler;
import android.os.Looper;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Best-effort discovery of dsh servers on the local network.
 *
 * <p>Enumerates the device's own IPv4 addresses, probes the /24 around each of
 * them on the requested port, and reports every address that accepted a TCP
 * connection. It is a convenience for the setup screen, not a discovery
 * protocol: a dsh server on another subnet needs to be entered by hand.</p>
 */
public final class LanScanner {

    /** Receives the scan outcome on the main thread. */
    public interface Listener {
        void onScanFinished(List<String> addresses);
    }

    private static final int THREADS = 48;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled;
    private volatile ExecutorService pool;

    /** Start a scan; a previous scan on this instance is cancelled. */
    public void start(final int port, final int timeoutMillis, final Listener listener) {
        cancel();
        cancelled = false;
        final ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        pool = executor;
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                scan(port, timeoutMillis, executor, listener);
            }
        }, "dsh-lan-scan");
        worker.setDaemon(true);
        worker.start();
    }

    /** Abort a running scan. */
    public void cancel() {
        cancelled = true;
        ExecutorService executor = pool;
        if (executor != null) {
            executor.shutdownNow();
        }
        pool = null;
    }

    private void scan(final int port, int timeoutMillis, ExecutorService executor,
                      final Listener listener) {
        final Set<String> found = new ConcurrentSkipListSet<>(new AddressOrder());
        List<String> candidates = candidates();
        final CountDownLatch remaining = new CountDownLatch(candidates.size());
        for (final String address : candidates) {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (!cancelled) {
                            Socket socket = new Socket();
                            try {
                                socket.connect(new InetSocketAddress(address, port), timeoutMillis);
                                found.add(address);
                            } finally {
                                try {
                                    socket.close();
                                } catch (Exception ignored) {
                                    // Nothing to recover from a failing close.
                                }
                            }
                        }
                    } catch (Exception unreachable) {
                        // Refused, timed out, or unreachable: not a server.
                    } finally {
                        remaining.countDown();
                    }
                }
            });
        }
        try {
            remaining.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        executor.shutdownNow();
        if (pool == executor) {
            pool = null;
        }
        final List<String> result = new ArrayList<>(found);
        main.post(new Runnable() {
            @Override
            public void run() {
                if (!cancelled) {
                    listener.onScanFinished(result);
                }
            }
        });
    }

    /** @return loopback plus one candidate address per host around each local IPv4 address. */
    private static List<String> candidates() {
        Set<String> addresses = new LinkedHashSet<>();
        // A harness hosted on this phone (Termux) listens on loopback only.
        addresses.add("127.0.0.1");
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress interfaceAddress : network.getInterfaceAddresses()) {
                    InetAddress local = interfaceAddress.getAddress();
                    if (!(local instanceof Inet4Address)) {
                        continue;
                    }
                    byte[] octets = local.getAddress();
                    String prefix = (octets[0] & 0xFF) + "." + (octets[1] & 0xFF) + "." + (octets[2] & 0xFF) + ".";
                    for (int host = 1; host < 255; host++) {
                        String candidate = prefix + host;
                        if (!candidate.equals(local.getHostAddress())) {
                            addresses.add(candidate);
                        }
                    }
                }
            }
        } catch (SocketException ignored) {
            // No interfaces enumerable: the scan finds nothing and reports that.
        }
        return new ArrayList<>(addresses);
    }

    /** Orders dotted-quad addresses numerically so results read naturally. */
    private static final class AddressOrder implements Comparator<String> {
        @Override
        public int compare(String left, String right) {
            String[] leftParts = left.split("\\.");
            String[] rightParts = right.split("\\.");
            for (int index = 0; index < 4; index++) {
                int a = index < leftParts.length ? parseInt(leftParts[index]) : 0;
                int b = index < rightParts.length ? parseInt(rightParts[index]) : 0;
                if (a != b) {
                    return a < b ? -1 : 1;
                }
            }
            return 0;
        }

        private int parseInt(String value) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException notNumeric) {
                return 0;
            }
        }
    }
}
