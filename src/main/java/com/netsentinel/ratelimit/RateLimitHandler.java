package com.netsentinel.ratelimit;

import com.netsentinel.proxy.LocalResponses;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.ReferenceCountUtil;

import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.Queue;

public final class RateLimitHandler extends ChannelInboundHandlerAdapter {
    private final RateLimiter rateLimiter;
    private final Queue<Object> pending = new ArrayDeque<>();
    private boolean waiting;

    public RateLimitHandler(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) {
        if (waiting) {
            pending.add(message);
            return;
        }
        if (!(message instanceof HttpRequest request)) {
            context.fireChannelRead(message);
            return;
        }

        waiting = true;
        pending.add(message);
        context.channel().config().setAutoRead(false);
        rateLimiter.allow(clientKey(context, request)).whenComplete((allowed, failure) ->
                context.executor().execute(() -> completeDecision(context, failure == null && Boolean.TRUE.equals(allowed)))
        );
    }

    private void completeDecision(ChannelHandlerContext context, boolean allowed) {
        waiting = false;
        context.channel().config().setAutoRead(true);
        if (allowed) {
            while (!pending.isEmpty()) {
                context.fireChannelRead(pending.remove());
            }
            return;
        }
        while (!pending.isEmpty()) {
            ReferenceCountUtil.release(pending.remove());
        }
        LocalResponses.sendJson(context, HttpResponseStatus.TOO_MANY_REQUESTS, "rate limit exceeded");
    }

    private static String clientKey(ChannelHandlerContext context, HttpRequest request) {
        String apiKey = request.headers().get("X-API-Key");
        if (apiKey != null && !apiKey.isBlank()) {
            return "api-key:" + apiKey.trim();
        }
        
        String auth = request.headers().get("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            String token = auth.substring(7).trim();
            int firstDot = token.indexOf('.');
            int secondDot = token.lastIndexOf('.');
            if (firstDot > 0 && secondDot > firstDot) {
                // Use JWT signature as unique tenant identifier for rate limiting
                return "jwt:" + token.substring(secondDot + 1);
            }
            return "token:" + token;
        }

        String forwardedFor = request.headers().get("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            int comma = forwardedFor.indexOf(',');
            return comma > -1 ? "ip:" + forwardedFor.substring(0, comma).trim() : "ip:" + forwardedFor.trim();
        }
        
        if (context.channel().remoteAddress() instanceof InetSocketAddress socketAddress) {
            return "ip:" + socketAddress.getAddress().getHostAddress();
        }
        return "anonymous";
    }
}
