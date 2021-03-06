package feign.opentracing;

import static java.util.concurrent.TimeUnit.SECONDS;

import feign.*;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.util.ThreadLocalScopeManager;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

import org.awaitility.Awaitility;
import org.hamcrest.core.IsEqual;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import feign.okhttp.OkHttpClient;
import io.opentracing.mock.MockSpan;
import io.opentracing.mock.MockTracer;
import io.opentracing.tag.Tags;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * @author Pavol Loffay
 */
public class FeignTracingTest {

    protected static final int NUMBER_OF_RETRIES = 2;
    protected static final String SPAN_NAME = "any_span_name";

    protected MockTracer mockTracer = new MockTracer(new ThreadLocalScopeManager(), MockTracer.Propagator.TEXT_MAP);
    protected MockWebServer mockWebServer = new MockWebServer();
    protected Feign feign = getClient();

    protected Client tracingClient(Client client) {
        return new TracingClient(client, mockTracer,
                Collections.<FeignSpanDecorator>singletonList(new feign.opentracing.FeignSpanDecorator.StandardTags())){

            @Override
            public String withSpanName(Request request) {
                return SPAN_NAME;
            }
        };
    }

    protected Feign getClient() {
        return Feign.builder()
                .client(tracingClient(new OkHttpClient()))
                .retryer(new Retryer.Default(100, SECONDS.toMillis(1), NUMBER_OF_RETRIES))
                .build();
    }

    @Before
    public void before() throws IOException {
        mockTracer.reset();
        mockWebServer.start();
    }

    @After
    public void after() throws IOException {
        mockWebServer.close();
    }

    protected interface StringEntityRequest {
        @RequestLine("GET")
        @Headers("Content-Type: application/json")
        String get();
    }

    @Test
    public void testStandardTags() {
        {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(202));

            StringEntityRequest
                    entity = feign.<StringEntityRequest>newInstance(new Target.HardCodedTarget(StringEntityRequest.class,
                    mockWebServer.url("/foo").toString()));
            entity.get();
        }

        List<MockSpan> mockSpans = mockTracer.finishedSpans();
        Assert.assertEquals(1, mockSpans.size());

        MockSpan mockSpan = mockSpans.get(0);
        Assert.assertEquals(SPAN_NAME, mockSpan.operationName());
        Assert.assertEquals(5, mockSpan.tags().size());
        Assert.assertNotNull(mockSpan.tags().get(Tags.COMPONENT.getKey()));
        Assert.assertEquals(Tags.SPAN_KIND_CLIENT, mockSpan.tags().get(Tags.SPAN_KIND.getKey()));
        Assert.assertEquals("GET", mockSpan.tags().get(Tags.HTTP_METHOD.getKey()));
        Assert.assertEquals("http://localhost:" + mockWebServer.getPort() + "/foo",
                mockSpan.tags().get(Tags.HTTP_URL.getKey()));
        Assert.assertEquals(202, mockSpan.tags().get(Tags.HTTP_STATUS.getKey()));
        Assert.assertEquals(0, mockSpan.logEntries().size());
    }

    @Test
    public void testInject() throws InterruptedException {
        {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200));

            StringEntityRequest
                    entity = feign.<StringEntityRequest>newInstance(new Target.HardCodedTarget(StringEntityRequest.class,
                    mockWebServer.url("/foo").toString()));
            entity.get();
        }

        List<MockSpan> mockSpans = mockTracer.finishedSpans();
        Assert.assertEquals(1, mockSpans.size());

        RecordedRequest recordedRequest = mockWebServer.takeRequest();
        Assert.assertEquals(mockSpans.get(0).context().spanId(),
                Long.parseLong(recordedRequest.getHeader("spanId")));
        Assert.assertEquals(mockSpans.get(0).context().traceId(),
                Long.parseLong(recordedRequest.getHeader("traceId")));
    }

    @Test
    public void testParentSpanFromSpanManager() throws InterruptedException {
        {
            Span span = mockTracer.buildSpan("parent")
                    .start();

            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200));

            try (Scope scope = mockTracer.activateSpan(span)) {
                StringEntityRequest
                    entity = feign.<StringEntityRequest>newInstance(
                    new Target.HardCodedTarget(StringEntityRequest.class,
                        mockWebServer.url("/foo").toString()));
                entity.get();
            } finally {
                span.finish();
            }
        }
        Awaitility.await().until(reportedSpansSize(), IsEqual.equalTo(2));

        List<MockSpan> mockSpans = mockTracer.finishedSpans();
        Assert.assertEquals(2, mockSpans.size());
        Assert.assertEquals(mockSpans.get(1).context().traceId(), mockSpans.get(0).context().traceId());
        Assert.assertEquals(mockSpans.get(1).context().spanId(), mockSpans.get(0).parentId());
    }

    @Test
    public void testUnknownHostException() {
        {
            StringEntityRequest entity =
                    feign.<StringEntityRequest>newInstance(new Target.HardCodedTarget(StringEntityRequest.class,
                    "http://www.abcfoobar.bar/baz"));
            try {
                entity.get();
            } catch (Exception ex) {
                //ok
            }
        }

        List<MockSpan> mockSpans = mockTracer.finishedSpans();
        // there are two spans due to retry mechanism
        Assert.assertEquals(2, mockSpans.size());
        assertErrorSpan(mockSpans.get(0), "http://www.abcfoobar.bar/baz");
        assertErrorSpan(mockSpans.get(1), "http://www.abcfoobar.bar/baz");
    }

    public static void assertErrorSpan(MockSpan mockSpan, String url) {
        Assert.assertEquals(5, mockSpan.tags().size());
        Assert.assertNotNull(mockSpan.tags().get(Tags.COMPONENT.getKey()));
        Assert.assertEquals(Tags.SPAN_KIND_CLIENT, mockSpan.tags().get(Tags.SPAN_KIND.getKey()));
        Assert.assertEquals("GET", mockSpan.tags().get(Tags.HTTP_METHOD.getKey()));
        Assert.assertEquals(url, mockSpan.tags().get(Tags.HTTP_URL.getKey()));
        Assert.assertEquals(Boolean.TRUE, mockSpan.tags().get(Tags.ERROR.getKey()));

        Assert.assertEquals(1, mockSpan.logEntries().size());
        Assert.assertEquals(Tags.ERROR.getKey(), mockSpan.logEntries().get(0).fields().get("event"));
        Assert.assertNotNull(mockSpan.logEntries().get(0).fields().get("error.object"));
    }

    private Callable<Integer> reportedSpansSize() {
        return new Callable<Integer>() {
            @Override
            public Integer call() throws Exception {
                return mockTracer.finishedSpans().size();
            }
        };
    }
}
