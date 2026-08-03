package dev.notune.transcribe;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Plain-JVM coverage for {@link PostProcessor#process(String, PostProcessCallback)}
 * against a controlled HTTP server (P1.3): exactly one non-streaming request
 * with {@code stream:false}, the transcript injected at most once, correct
 * handling of success/empty/HTTP-error responses and of the toggle-off
 * during-flight fallback to the raw transcript.
 *
 * <p>Uses the package-private {@link PostProcessor.PostProcessorSettings}
 * seam with a fake settings object, so no Android Context or framework
 * classes are involved — matching the Guantelette JVM-test harness.</p>
 *
 * <p><strong>Timeout coverage:</strong> the real OkHttp timeout path
 * (socket read timeout → {@code onFailure} → onError) is exercised with a
 * test-scaled client (100 ms read timeout against a stalled MockWebServer
 * body), and the production values (30 s connect, 60 s read/write) are
 * asserted via {@link PostProcessor#buildProductionClient()}. The wall-clock
 * durations themselves are <em>not</em> waited out here — a 60 s read
 * timeout would stall CI for a minute per run — so that part of the
 * guarantee stays with device/network smoke tests.</p>
 */
public class PostProcessorTest {

    private MockWebServer server;

    /** Fake settings source for the processor under test. */
    static final class FakeSettings implements PostProcessor.PostProcessorSettings {
        boolean enabled = true;
        String url;
        String key = "test-key";
        String model = "test-model";
        String prompt = "";

        @Override
        public boolean isPostProcessEnabled() {
            return enabled;
        }

        @Override
        public String getEffectiveApiUrl() {
            return url;
        }

        @Override
        public String getApiKey() {
            return key;
        }

        @Override
        public String getModelName() {
            return model;
        }

        @Override
        public String getActivePromptBody() {
            return prompt;
        }
    }

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @After
    public void tearDown() throws Exception {
        // Never leak a test-scaled client into the next test: restore the
        // lazy production client regardless of which test ran.
        PostProcessor.resetSharedClientForTests();
        server.shutdown();
    }

    private static String completionJson(String content) {
        return "{\"choices\":[{\"message\":{\"content\":\"" + content + "\"}}]}";
    }

    private PostProcessor newProcessor(FakeSettings settings, Object owner) {
        return new PostProcessor(settings, null, null, owner);
    }

    @Test
    public void singleNonStreamingRequestWithInjectedOutputOnce() throws Exception {
        FakeSettings settings = new FakeSettings();
        settings.url = server.url("/v1").toString();
        settings.prompt = "Edit this: ${output}\nFix grammar.";

        // A delayed response keeps the request observable in-flight; the
        // server-side recording lets us assert the exact payload.
        server.enqueue(new MockResponse().setBodyDelay(1, TimeUnit.SECONDS)
                .setBody(completionJson("texto refinado")));

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> outcome = new AtomicReference<>("pending");
        newProcessor(settings, new Object()).process("hola mundo",
                new PostProcessor.PostProcessCallback() {
                    @Override
                    public void onSuccess(String refinedText) {
                        outcome.set("ok:" + refinedText);
                        done.countDown();
                    }

                    @Override
                    public void onError(String error) {
                        outcome.set("err:" + error);
                        done.countDown();
                    }
                });

        assertTrue("callback must fire", done.await(5, TimeUnit.SECONDS));
        assertEquals("ok:texto refinado", outcome.get());

        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("/v1/chat/completions", request.getPath());
        assertEquals("Bearer test-key", request.getHeader("Authorization"));

        JSONObject body = new JSONObject(request.getBody().readUtf8());
        assertFalse("stream must be explicitly false", body.getBoolean("stream"));
        assertEquals("test-model", body.getString("model"));

        JSONArray messages = body.getJSONArray("messages");
        assertEquals(2, messages.length());
        String system = messages.getJSONObject(0).getString("content");
        assertTrue("system prompt must embed the transcript at ${output}",
                system.contains("Edit this: hola mundo"));
        assertEquals("Apply the instructions to the transcript above.",
                messages.getJSONObject(1).getString("content"));

        // The raw transcript must appear exactly once in the whole payload
        // (in the system message), never duplicated as a user message.
        String full = body.toString();
        assertEquals(1, countOccurrences(full, "hola mundo"));
    }

    @Test
    public void withoutOutputMarkerTranscriptGoesAsUserMessage() throws Exception {
        FakeSettings settings = new FakeSettings();
        settings.url = server.url("/").toString();
        settings.prompt = "Just fix grammar.";
        server.enqueue(new MockResponse().setBody(completionJson("fixed")));

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> outcome = new AtomicReference<>("pending");
        newProcessor(settings, new Object()).process("raw text",
                callback("ok:", "err:", outcome, done));

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("ok:fixed", outcome.get());

        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        JSONObject body = new JSONObject(request.getBody().readUtf8());
        JSONArray messages = body.getJSONArray("messages");
        assertEquals("raw text", messages.getJSONObject(messages.length() - 1).getString("content"));
    }

    @Test
    public void emptyResultTextReportsError() throws Exception {
        FakeSettings settings = new FakeSettings();
        settings.url = server.url("/").toString();
        server.enqueue(new MockResponse().setBody(completionJson("   ")));

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> outcome = new AtomicReference<>("pending");
        newProcessor(settings, new Object()).process("raw", callback("ok:", "err:", outcome, done));

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("err:Empty response from AI", outcome.get());
    }

    @Test
    public void productionClientAppliesTimeoutGuarantees() {
        // The exact guarantee users get on device: 30 s to connect, 60 s
        // read/write for the complete non-streaming response. Asserting the
        // applied values is cheap in the harness; waiting out 60 s is not.
        OkHttpClient client = PostProcessor.buildProductionClient();
        assertEquals(30_000, client.connectTimeoutMillis());
        assertEquals(60_000, client.readTimeoutMillis());
        assertEquals(60_000, client.writeTimeoutMillis());
    }

    @Test
    public void stalledResponseHitsReadTimeoutAndReportsError() throws Exception {
        // Real OkHttp timeout behavior, scaled down for CI: a 100 ms read
        // timeout against a 5 s-delayed body must abort the call and surface
        // onError (the caller then delivers the raw transcript as fallback).
        // The production 60 s read timeout would need a minute to verify the
        // same path — that wall-clock duration stays out of the harness.
        PostProcessor.setSharedClientForTests(new OkHttpClient.Builder()
                .connectTimeout(100, TimeUnit.MILLISECONDS)
                .readTimeout(100, TimeUnit.MILLISECONDS)
                .writeTimeout(100, TimeUnit.MILLISECONDS)
                .build());
        try {
            FakeSettings settings = new FakeSettings();
            settings.url = server.url("/").toString();
            server.enqueue(new MockResponse().setBodyDelay(5, TimeUnit.SECONDS)
                    .setBody(completionJson("late")));

            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<String> outcome = new AtomicReference<>("pending");
            newProcessor(settings, new Object()).process("raw",
                    callback("ok:", "err:", outcome, done));

            // 6 s > the 5 s body delay: if the timeout mechanism were broken,
            // the delayed body would arrive and the assertion below would fail
            // with the more diagnostic "expected an error, got ok:late".
            assertTrue("timeout callback must fire", done.await(6, TimeUnit.SECONDS));
            assertTrue("expected an error, got " + outcome.get(),
                    outcome.get().startsWith("err:"));
            assertTrue("expected socket timeout message, got " + outcome.get(),
                    outcome.get().contains("timeout"));
        } finally {
            PostProcessor.resetSharedClientForTests();
        }
    }

    @Test
    public void httpErrorReportsApiError() throws Exception {
        FakeSettings settings = new FakeSettings();
        settings.url = server.url("/").toString();
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> outcome = new AtomicReference<>("pending");
        newProcessor(settings, new Object()).process("raw", callback("ok:", "err:", outcome, done));

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("err:API Error 500", outcome.get());
    }

    @Test
    public void malformedJsonReportsParseError() throws Exception {
        FakeSettings settings = new FakeSettings();
        settings.url = server.url("/").toString();
        server.enqueue(new MockResponse().setBody("this is not json"));

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> outcome = new AtomicReference<>("pending");
        newProcessor(settings, new Object()).process("raw", callback("ok:", "err:", outcome, done));

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue("expected parse error, got " + outcome.get(), outcome.get().startsWith("err:"));
    }

    @Test
    public void toggleOffDuringFlightDeliversRawTranscript() throws Exception {
        FakeSettings settings = new FakeSettings();
        settings.url = server.url("/").toString();
        // Slow response so we can flip the toggle while the request flies.
        server.enqueue(new MockResponse().setBodyDelay(1, TimeUnit.SECONDS)
                .setBody(completionJson("refined")));

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> outcome = new AtomicReference<>("pending");
        newProcessor(settings, new Object()).process("raw", callback("ok:", "err:", outcome, done));

        // The user disabled post-processing while the request was in flight.
        settings.enabled = false;

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("ok:raw", outcome.get());
    }

    @Test
    public void exactlyOneFinalDeliveryPerRequest() throws Exception {
        FakeSettings settings = new FakeSettings();
        settings.url = server.url("/").toString();
        server.enqueue(new MockResponse().setBody(completionJson("once")));

        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger deliveries = new AtomicInteger(0);
        newProcessor(settings, new Object()).process("raw",
                new PostProcessor.PostProcessCallback() {
                    @Override
                    public void onSuccess(String refinedText) {
                        deliveries.incrementAndGet();
                        done.countDown();
                    }

                    @Override
                    public void onError(String error) {
                        deliveries.incrementAndGet();
                        done.countDown();
                    }
                });

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("exactly one final delivery", 1, deliveries.get());
    }

    private static PostProcessor.PostProcessCallback callback(
            final String okPrefix, final String errPrefix,
            final AtomicReference<String> outcome, final CountDownLatch done) {
        return new PostProcessor.PostProcessCallback() {
            @Override
            public void onSuccess(String refinedText) {
                outcome.set(okPrefix + refinedText);
                done.countDown();
            }

            @Override
            public void onError(String error) {
                outcome.set(errPrefix + error);
                done.countDown();
            }
        };
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while ((from = haystack.indexOf(needle, from)) != -1) {
            count++;
            from += needle.length();
        }
        return count;
    }
}
