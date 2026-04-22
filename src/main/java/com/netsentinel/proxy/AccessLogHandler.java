package com.netsentinel.proxy;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

public final class AccessLogHandler extends ChannelDuplexHandler {
    private static final Logger logger = LoggerFactory.getLogger(AccessLogHandler.class);

    private String method;
    private String uri;
    private String clientIp;
    private int statusCode;
    private long startTimeNanos;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpRequest request) {
            this.startTimeNanos = System.nanoTime();
            this.method = request.method().name();
            this.uri = request.uri();
            this.clientIp = ((InetSocketAddress) ctx.channel().remoteAddress()).getAddress().getHostAddress();
        }
        super.channelRead(ctx, msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof HttpResponse response) {
            this.statusCode = response.status().code();
        }

        if (msg instanceof LastHttpContent) {
            long durationMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
            logger.info("[Access] {} - {} {} -> {} ({}ms)", clientIp, method, uri, statusCode, durationMs);
        }
        super.write(ctx, msg, promise);
    }
}
