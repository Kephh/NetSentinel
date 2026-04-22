package com.netsentinel.security;

import com.netsentinel.proxy.LocalResponses;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.ReferenceCountUtil;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class WafHandler extends ChannelInboundHandlerAdapter {
    private final boolean enabled;
    private final AhoCorasickMatcher matcher;
    private boolean blocked;

    public WafHandler(boolean enabled, List<String> patterns) {
        this.enabled = enabled;
        this.matcher = new AhoCorasickMatcher(patterns);
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) {
        if (!enabled || blocked) {
            context.fireChannelRead(message);
            return;
        }

        Optional<String> match = Optional.empty();
        if (message instanceof HttpRequest request) {
            match = inspectRequest(request);
        }
        if (match.isEmpty() && message instanceof HttpContent content) {
            match = matcher.findIn(content.content());
        }

        if (match.isPresent()) {
            blocked = true;
            ReferenceCountUtil.release(message);
            LocalResponses.sendJson(context, HttpResponseStatus.FORBIDDEN, "WAF blocked request pattern: " + match.get());
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
}
