package com.redhat.developers.msa.aloha;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import com.uber.jaeger.metrics.Metrics;
import com.uber.jaeger.metrics.NullStatsReporter;
import com.uber.jaeger.metrics.StatsFactoryImpl;
import com.uber.jaeger.reporters.RemoteReporter;
import com.uber.jaeger.samplers.ProbabilisticSampler;
import com.uber.jaeger.senders.Sender;
import com.uber.jaeger.senders.UDPSender;

import brave.opentracing.BraveTracer;
import io.opentracing.NoopTracerFactory;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.propagation.Format;
import io.opentracing.propagation.TextMap;
import io.opentracing.tag.Tags;
import io.vertx.ext.web.RoutingContext;
import zipkin.reporter.AsyncReporter;
import zipkin.reporter.Reporter;
import zipkin.reporter.urlconnection.URLConnectionSender;

/**
 * @author Pavol Loffay
 */
public class TracingConfiguration {
    private static final String SERVICE_NAME = "aloha";

    public static final String ACTIVE_SPAN = AlohaVerticle.class + ".activeSpan";
    public static final Tracer tracer = tracer();

    private TracingConfiguration() {}

    private static Tracer tracer() {
        String tracingSystem = System.getenv("TRACING_SYSTEM");
        if ("zipkin".equals(tracingSystem)) {
            System.out.println("Using Zipkin tracer");
            return zipkinTracer(System.getenv("ZIPKIN_SERVER_URL"));
        } else if ("jaeger".equals(tracingSystem)) {
            System.out.println("Using Jaeger tracer");
            return jaegerTracer(System.getenv("JAEGER_SERVER_URL"));
        }


        System.out.println("Using Noop tracer");
        return NoopTracerFactory.create();
    }

    private static Tracer zipkinTracer(String url) {
        Reporter<zipkin.Span> reporter = AsyncReporter.builder(URLConnectionSender.create(url + "/api/v1/spans"))
                .build();
        brave.Tracer braveTracer = brave.Tracer.newBuilder().localServiceName(SERVICE_NAME).reporter(reporter).build();
        return BraveTracer.wrap(braveTracer);
    }

    private static Tracer jaegerTracer(String url) {
        Sender sender = new UDPSender(url, 0, 0);
        return new com.uber.jaeger.Tracer.Builder(SERVICE_NAME,
                new RemoteReporter(sender, 100, 50,
                        new Metrics(new StatsFactoryImpl(new NullStatsReporter()))),
                new ProbabilisticSampler(1.0))
                .build();
    }

    public static void tracingHandler(RoutingContext routingContext) {
        SpanContext parent = tracer.extract(Format.Builtin.HTTP_HEADERS, new TextMap() {
            @Override
            public Iterator<Map.Entry<String, String>> iterator() {
                return routingContext.request().headers().iterator();
            }
            @Override
            public void put(String key, String value) {}
        });

        Span span = tracer.buildSpan(routingContext.request().method().toString())
                .asChildOf(parent)
                .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_SERVER)
                .withTag(Tags.HTTP_METHOD.getKey(), routingContext.request().method().toString())
                .withTag(Tags.HTTP_URL.getKey(), routingContext.request().absoluteURI())
                .start();

        routingContext.put(ACTIVE_SPAN, span);

        routingContext.addBodyEndHandler(event -> {
            Tags.HTTP_STATUS.set(span, routingContext.response().getStatusCode());
            span.finish();
        });

        routingContext.next();
    }

    public static void tracingFailureHandler(RoutingContext routingContext) {
        if (routingContext.failed() == true) {
            Span span = routingContext.get(ACTIVE_SPAN);
            Tags.ERROR.set(span, Boolean.TRUE);

            if (routingContext.failure() != null) {
                Map<String, Object> errorLogs = new HashMap(2);
                errorLogs.put("event", Tags.ERROR.getKey());
                errorLogs.put("error.object", routingContext.failure());
                span.log(errorLogs);
            }
        }
    }
}
