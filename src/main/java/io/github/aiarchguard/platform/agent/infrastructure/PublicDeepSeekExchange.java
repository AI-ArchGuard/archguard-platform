package io.github.aiarchguard.platform.agent.infrastructure;

import java.net.InetAddress;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Fixed-host production preflight; deliberately separate from loopback-only transport tests. */
final class PublicDeepSeekExchange implements DeepSeekHttpTransport.Exchange, AutoCloseable {
    private final DeepSeekHttpTransport.Exchange delegate;
    private final Resolver resolver;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<Future<InetAddress[]>> resolving = new AtomicReference<>();
    private final ThreadPoolExecutor dns = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new SynchronousQueue<>(), task -> {
            var thread = new Thread(task, "agent-fixed-host-dns");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());

    PublicDeepSeekExchange() { this(new BoundedJdkHttpExchange(), InetAddress::getAllByName); }
    PublicDeepSeekExchange(DeepSeekHttpTransport.Exchange delegate, Resolver resolver) {
        this.delegate = delegate; this.resolver = resolver;
    }
    @Override public DeepSeekHttpTransport.Response exchange(HttpRequest request, Duration remaining) {
        requireOpen();
        if (request == null || remaining == null || remaining.isZero() || remaining.isNegative()
                || remaining.compareTo(Duration.ofSeconds(30)) > 0) throw unavailable();
        var uri = request.uri();
        if (!"https".equals(uri.getScheme()) || !"api.deepseek.com".equals(uri.getHost())
                || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !"/responses".equals(uri.getRawPath()) || !"POST".equals(request.method())) throw unavailable();
        long started = System.nanoTime();
        Future<InetAddress[]> lookup = null;
        try {
            // A blocked OS resolver cannot accumulate an unbounded queue or retain a request/Key.
            lookup = dns.submit(() -> resolver.resolve("api.deepseek.com"));
            if (!resolving.compareAndSet(null, lookup)) throw unavailable();
            InetAddress[] addresses = lookup.get(Math.min(remaining.toNanos(), TimeUnit.SECONDS.toNanos(2)), TimeUnit.NANOSECONDS);
            if (addresses == null || addresses.length == 0 || addresses.length > 16) throw unavailable();
            for (var address : addresses) if (!publicAddress(address)) throw unavailable();
            requireOpen();
            long left = remaining.toNanos() - (System.nanoTime() - started);
            if (Thread.currentThread().isInterrupted() || left <= 0) throw new DeepSeekTransportFailure("MODEL_TIMEOUT");
            return delegate.exchange(request, Duration.ofNanos(left));
        } catch (TimeoutException timeout) {
            throw new DeepSeekTransportFailure("MODEL_TIMEOUT");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new DeepSeekTransportFailure("MODEL_TIMEOUT");
        } catch (DeepSeekTransportFailure safe) { throw safe; }
        catch (Exception unavailable) { throw unavailable(); }
        finally {
            if (lookup != null) { resolving.compareAndSet(lookup, null); lookup.cancel(true); }
        }
    }

    private static boolean publicAddress(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        int a = Byte.toUnsignedInt(bytes[0]), b = Byte.toUnsignedInt(bytes[1]);
        if (bytes.length == 4) {
            int c = Byte.toUnsignedInt(bytes[2]);
            return a != 0 && a != 10 && a != 127 && a < 224
                && !(a == 100 && b >= 64 && b <= 127) && !(a == 169 && b == 254)
                && !(a == 172 && b >= 16 && b <= 31) && !(a == 192 && b == 168)
                && !(a == 192 && b == 0 && (c == 0 || c == 2)) && !(a == 192 && b == 88 && c == 99)
                && !(a == 198 && (b == 18 || b == 19 || b == 51 && c == 100))
                && !(a == 203 && b == 0 && c == 113);
        }
        if (bytes.length != 16 || (a & 0xe0) != 0x20) return false;
        // Conservative global-unicast subset: exclude special-purpose, transition and documentation blocks.
        return !(a == 0x20 && b == 0x01 && (Byte.toUnsignedInt(bytes[2]) < 2
                || Byte.toUnsignedInt(bytes[2]) == 0x0d && Byte.toUnsignedInt(bytes[3]) == 0xb8))
            && !(a == 0x20 && b == 0x02) && !(a == 0x3f && b == 0xff && (bytes[2] & 0xf0) == 0);
    }

    private void requireOpen() { if (closed.get()) throw unavailable(); }
    private static DeepSeekTransportFailure unavailable() { return new DeepSeekTransportFailure("MODEL_UNAVAILABLE"); }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        var lookup = resolving.getAndSet(null);
        if (lookup != null) lookup.cancel(true);
        dns.shutdownNow();
        if (delegate instanceof AutoCloseable resource) {
            try { resource.close(); } catch (Exception unavailable) { throw unavailable(); }
        }
    }
    boolean awaitTermination(Duration timeout) throws InterruptedException {
        long started = System.nanoTime();
        if (!dns.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS)) return false;
        long left = timeout.toNanos() - (System.nanoTime() - started);
        return !(delegate instanceof BoundedJdkHttpExchange http)
            || left > 0 && http.awaitTermination(Duration.ofNanos(left));
    }
    @FunctionalInterface interface Resolver { InetAddress[] resolve(String host) throws Exception; }
}
