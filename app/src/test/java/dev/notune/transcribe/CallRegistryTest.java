package dev.notune.transcribe;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Plain-JVM coverage for {@link PostProcessor.CallRegistry} cancellation
 * semantics (P0.1): destroying/cancelling one surface must never cancel
 * another surface's in-flight post-processing call, while the global
 * {@code cancelAll()} still aborts everything.
 *
 * <p>Runs against a real {@link MockWebServer} with delayed responses so the
 * calls are deterministically in flight when cancellation fires. No Android
 * framework classes are touched, matching the existing Guantelete JVM-test
 * harness (AGENTS.md §3).</p>
 */
public class CallRegistryTest {

    private MockWebServer server;
    private OkHttpClient client;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    private Call newCall(String path) {
        return client.newCall(new Request.Builder().url(server.url(path)).build());
    }

    /** Captures the outcome of one enqueued call: "ok:<body>" or "fail:<exc>". */
    private static Callback capture(String marker, CountDownLatch done,
                                    AtomicReference<String> result) {
        return new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                result.set(marker + ":fail");
                done.countDown();
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (Response r = response) {
                    result.set(marker + ":ok:" + r.body().string());
                }
                done.countDown();
            }
        };
    }

    @Test
    public void cancellingOneOwnerLeavesTheOtherUntouched() throws Exception {
        Object ownerA = new Object();
        Object ownerB = new Object();
        // Delay both responses: cancellation below must find both calls still
        // in flight, or the test would be a race. Same body on both so the
        // assertion does not depend on MockWebServer's request dispatch order.
        server.enqueue(new MockResponse().setBodyDelay(3, TimeUnit.SECONDS).setBody("ok"));
        server.enqueue(new MockResponse().setBodyDelay(3, TimeUnit.SECONDS).setBody("ok"));

        Call callA = newCall("/a");
        Call callB = newCall("/b");
        PostProcessor.CallRegistry.register(callA, ownerA);
        PostProcessor.CallRegistry.register(callB, ownerB);

        CountDownLatch doneA = new CountDownLatch(1);
        CountDownLatch doneB = new CountDownLatch(1);
        AtomicReference<String> resultA = new AtomicReference<>("pending");
        AtomicReference<String> resultB = new AtomicReference<>("pending");
        callA.enqueue(capture("a", doneA, resultA));
        callB.enqueue(capture("b", doneB, resultB));

        // Cancel only owner A's in-flight call.
        PostProcessor.CallRegistry.cancelAllForOwner(ownerA);

        assertTrue("call A should fail after owner-scoped cancellation",
                doneA.await(5, TimeUnit.SECONDS));
        assertEquals("a:fail", resultA.get());
        assertTrue("call B (different owner) must still complete",
                doneB.await(6, TimeUnit.SECONDS));
        assertEquals("b:ok:ok", resultB.get());
    }

    @Test
    public void globalCancelAllAbortsEveryOwner() throws Exception {
        Object ownerA = new Object();
        Object ownerB = new Object();
        server.enqueue(new MockResponse().setBodyDelay(3, TimeUnit.SECONDS).setBody("ok-a"));
        server.enqueue(new MockResponse().setBodyDelay(3, TimeUnit.SECONDS).setBody("ok-b"));

        Call callA = newCall("/a");
        Call callB = newCall("/b");
        PostProcessor.CallRegistry.register(callA, ownerA);
        PostProcessor.CallRegistry.register(callB, ownerB);

        CountDownLatch doneA = new CountDownLatch(1);
        CountDownLatch doneB = new CountDownLatch(1);
        AtomicReference<String> resultA = new AtomicReference<>("pending");
        AtomicReference<String> resultB = new AtomicReference<>("pending");
        callA.enqueue(capture("a", doneA, resultA));
        callB.enqueue(capture("b", doneB, resultB));

        PostProcessor.CallRegistry.cancelAll();

        assertTrue(doneA.await(5, TimeUnit.SECONDS));
        assertTrue(doneB.await(5, TimeUnit.SECONDS));
        assertEquals("a:fail", resultA.get());
        assertEquals("b:fail", resultB.get());
    }

    @Test
    public void unregisterRemovesCallFromCancellationScope() throws Exception {
        Object owner = new Object();
        server.enqueue(new MockResponse().setBodyDelay(1, TimeUnit.SECONDS).setBody("ok"));

        Call call = newCall("/x");
        PostProcessor.CallRegistry.register(call, owner);
        PostProcessor.CallRegistry.unregister(call);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>("pending");
        call.enqueue(capture("x", done, result));

        // Nothing is registered for this owner anymore, so this must no-op.
        PostProcessor.CallRegistry.cancelAllForOwner(owner);

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("x:ok:ok", result.get());
    }

    @Test
    public void ownerScopedCancelIgnoresNullOwner() throws Exception {
        Object owner = new Object();
        server.enqueue(new MockResponse().setBodyDelay(1, TimeUnit.SECONDS).setBody("ok"));

        Call call = newCall("/y");
        // An ownerless call (legacy/edge path) is registered with null.
        PostProcessor.CallRegistry.register(call, null);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>("pending");
        call.enqueue(capture("y", done, result));

        PostProcessor.CallRegistry.cancelAllForOwner(owner);
        PostProcessor.CallRegistry.cancelAllForOwner(null);

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("y:ok:ok", result.get());
    }
}
