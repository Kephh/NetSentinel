package com.netsentinel.security;

import com.netsentinel.proxy.LocalResponses;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class WafHandler extends ChannelInboundHandlerAdapter {
    private final boolean enabled;
    private final AhoCorasickMatcher matcher;
    private final com.netsentinel.metrics.NetSentinelMetrics metrics;
    private AhoCorasickMatcher.State bodyState;
    private boolean blocked;

    public WafHandler(boolean enabled, List<String> patterns, com.netsentinel.metrics.NetSentinelMetrics metrics) {
        this.enabled = enabled;
        this.matcher = new AhoCorasickMatcher(patterns);
        this.metrics = metrics;
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) {
        if (!enabled || blocked) {
            context.fireChannelRead(message);
            return;
        }

        Optional<String> match = Optional.empty();
        if (message instanceof HttpRequest request) {
            bodyState = matcher.newState();
            match = inspectRequest(request);
        }
        if (match.isEmpty() && message instanceof HttpContent content) {
            if (bodyState == null) {
                bodyState = matcher.newState();
            }
            match = inspectContent(content);
            if (message instanceof LastHttpContent) {
                bodyState = null;
            }
        }

        if (match.isPresent()) {
            blocked = true;
            String pattern = match.get();
            String route = context.channel().attr(com.netsentinel.metrics.MetricsHandler.ROUTE_ID).get();
            metrics.recordWafBlock(route, pattern);
            
            ReferenceCountUtil.release(message);
            LocalResponses.sendJson(context, HttpResponseStatus.FORBIDDEN, "WAF blocked request pattern: " + pattern);
            return;
        }

        context.fireChannelRead(message);
    }

    private Optional<String> inspectRequest(HttpRequest request) {
        Optional<String> uriMatch = matcher.findIn(request.uri());
        if (uriMatch.isPresent()) {
            return uriMatch;
        }
        HttpHeaders headers = request.headers();
        for (Map.Entry<String, String> header : headers) {
            Optional<String> nameMatch = matcher.findIn(header.getKey());
            if (nameMatch.isPresent()) {
                return nameMatch;
            }
            Optional<String> valueMatch = matcher.findIn(header.getValue());
            if (valueMatch.isPresent()) {
                return valueMatch;
            }
        }
        return matcher.findIn(headers.get(HttpHeaderNames.COOKIE));
    }

    private Optional<String> inspectContent(HttpContent content) {
        for (int i = content.content().readerIndex(); i < content.content().writerIndex(); i++) {
            Optional<String> match = bodyState.accept(content.content().getByte(i));
            if (match.isPresent()) {
                return match;
            }
        }
        return Optional.empty();
    }
}
