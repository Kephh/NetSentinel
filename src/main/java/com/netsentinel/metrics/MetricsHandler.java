package com.netsentinel.metrics;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.util.AttributeKey;

public final class MetricsHandler extends ChannelDuplexHandler {
    public static final AttributeKey<String> ROUTE_ID = AttributeKey.valueOf("netsentinel.routeId");
    private static final AttributeKey<Long> REQUEST_STARTED_NANOS = AttributeKey.valueOf("netsentinel.requestStartedNanos");
    private static final AttributeKey<Boolean> REQUEST_IN_FLIGHT = AttributeKey.valueOf("netsentinel.requestInFlight");
    private static final AttributeKey<Boolean> RESPONSE_RECORDED = AttributeKey.valueOf("netsentinel.responseRecorded");

    private final NetSentinelMetrics metrics;

    public MetricsHandler(NetSentinelMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
        if (message instanceof HttpRequest) {
            context.channel().attr(REQUEST_STARTED_NANOS).set(System.nanoTime());
            context.channel().attr(RESPONSE_RECORDED).set(Boolean.FALSE);
            if (!Boolean.TRUE.equals(context.channel().attr(REQUEST_IN_FLIGHT).get())) {
                context.channel().attr(REQUEST_IN_FLIGHT).set(Boolean.TRUE);
                metrics.requestStarted();
            }
        }
        super.channelRead(context, message);
    }

    @Override
    public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
        if (message instanceof HttpResponse response) {
            if (!Boolean.TRUE.equals(context.channel().attr(RESPONSE_RECORDED).get())) {
                String route = context.channel().attr(ROUTE_ID).get();
                Long started = context.channel().attr(REQUEST_STARTED_NANOS).get();
                metrics.recordRequest(route);
                metrics.recordError(route, response.status().code());
                if (started != null) {
                    metrics.recordLatency(route, System.nanoTime() - started);
                }
                context.channel().attr(RESPONSE_RECORDED).set(Boolean.TRUE);
                promise.addListener(ignored -> finishRequest(context));
            }
        }
        super.write(context, message, promise);
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) throws Exception {
        finishRequest(context);
        super.channelInactive(context);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) throws Exception {
        finishRequest(context);
        super.exceptionCaught(context, cause);
    }

    private void finishRequest(ChannelHandlerContext context) {
        Boolean inFlight = context.channel().attr(REQUEST_IN_FLIGHT).getAndSet(Boolean.FALSE);
        if (Boolean.TRUE.equals(inFlight)) {
            metrics.requestFinished();
        }
        context.channel().attr(REQUEST_STARTED_NANOS).set(null);
        context.channel().attr(ROUTE_ID).set(null);
        context.channel().attr(RESPONSE_RECORDED).set(Boolean.FALSE);
    }
}
