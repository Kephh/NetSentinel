package com.netsentinel.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

import java.nio.charset.StandardCharsets;

public final class LocalResponses {
    private LocalResponses() {
    }

    public static void sendJson(ChannelHandlerContext context, HttpResponseStatus status, String message) {
        FullHttpResponse response = jsonResponse(context, status, message);
        context.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    public static FullHttpResponse jsonResponse(ChannelHandlerContext context, HttpResponseStatus status, String message) {
        String escaped = escapeJson(message);
        ByteBuf left = context.alloc().buffer(16);
        left.writeCharSequence("{\"error\":\"", StandardCharsets.UTF_8);
        ByteBuf body = context.alloc().buffer(escaped.length());
        body.writeCharSequence(escaped, StandardCharsets.UTF_8);
        ByteBuf right = context.alloc().buffer(2);
        right.writeCharSequence("\"}", StandardCharsets.UTF_8);
        CompositeByteBuf composite = context.alloc().compositeBuffer(3);
        composite.addComponents(true, left, body, right);

        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, composite);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, composite.readableBytes());
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        return response;
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
