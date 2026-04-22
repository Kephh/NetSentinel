package com.netsentinel.proxy;

import com.netsentinel.events.EventPublisher;
import com.netsentinel.events.TrafficEvent;
import com.netsentinel.metrics.MetricsHandler;
import com.netsentinel.routing.BackendSelection;
import com.netsentinel.routing.BackendServer;
import com.netsentinel.routing.RoutingEngine;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.Queue;

public final class ProxyHandler extends ChannelInboundHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(ProxyHandler.class);
    private static final WriteBufferWaterMark WATER_MARK = new WriteBufferWaterMark(32 * 1024, 128 * 1024);

    private final RoutingEngine routingEngine;
    private final Class<? extends Channel> outboundChannelClass;
    private final Optional<SslContext> configuredBackendSslContext;
    private final EventPublisher eventPublisher;
    private SslContext defaultBackendSslContext;
    private ProxySession session;

    public ProxyHandler(
            RoutingEngine routingEngine,
            Class<? extends Channel> outboundChannelClass,
            Optional<SslContext> configuredBackendSslContext,
            EventPublisher eventPublisher
    ) {
        this.routingEngine = routingEngine;
        this.outboundChannelClass = outboundChannelClass;
        this.configuredBackendSslContext = configuredBackendSslContext;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) {
        if (message instanceof HttpRequest request) {
            handleRequest(context, request);
            return;
        }
        if (message instanceof HttpContent content) {
            handleContent(context, content);
            return;
        }
        context.fireChannelRead(message);
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) throws Exception {
        if (session != null && session.outbound != null) {
            session.outbound.close();
        }
        super.channelInactive(context);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        if (session != null) {
            failSession(context, session, cause, true);
            return;
        }
        context.close();
    }

    private void handleRequest(ChannelHandlerContext context, HttpRequest request) {
        if (session != null && !session.completed) {
            ReferenceCountUtil.release(request);
            LocalResponses.sendJson(context, HttpResponseStatus.TOO_MANY_REQUESTS, "HTTP pipelining is not enabled for this connection");
            return;
        }

        Optional<BackendSelection> selected = routingEngine.select(request);
        if (selected.isEmpty()) {
            logger.warn("No healthy backend available for request: {} {}", request.method(), request.uri());
            ReferenceCountUtil.release(request);
            LocalResponses.sendJson(context, HttpResponseStatus.SERVICE_UNAVAILABLE, "no healthy backend available");
            return;
        }

        BackendSelection selection = selected.get();
        BackendServer backend = selection.backend();
        backend.beginRequest();
        context.channel().attr(MetricsHandler.ROUTE_ID).set(selection.routeId());

        ProxySession next = new ProxySession(
                selection.routeId(),
                backend,
                request.method(),
                request.uri(),
                HttpUtil.isKeepAlive(request),
                System.nanoTime()
        );
        session = next;

        try {
            next.pendingWrites.add(copyRequestForBackend(context, request, backend));
        } catch (Exception exception) {
            ReferenceCountUtil.release(request);
            failSession(context, next, exception, true);
            return;
        }

        ReferenceCountUtil.release(request);
        context.channel().config().setAutoRead(false);
        logger.debug("Connecting to backend {} for request {} {}", backend.id(), next.method, next.uri);
        connectBackend(context, next);
    }

    private void handleContent(ChannelHandlerContext context, HttpContent content) {
        if (session == null || session.completed) {
            ReferenceCountUtil.release(content);
            return;
        }
        if (session.outbound == null || !session.outbound.isActive()) {
            session.pendingWrites.add(content);
            return;
        }
        writeToBackend(context, session, content);
    }

    private void connectBackend(ChannelHandlerContext context, ProxySession proxySession) {
        BackendServer backend = proxySession.backend;
        Bootstrap bootstrap = new Bootstrap()
                .group(context.channel().eventLoop())
                .channel(outboundChannelClass)
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000)
                .option(ChannelOption.WRITE_BUFFER_WATER_MARK, WATER_MARK)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) throws Exception {
                        channel.config().setAllocator(PooledByteBufAllocator.DEFAULT);
                        if ("https".equalsIgnoreCase(backend.uri().getScheme())) {
                            channel.pipeline().addLast(backendSslContext().newHandler(channel.alloc(), backend.uri().getHost(), backend.port()));
                        }
                        channel.pipeline().addLast(new HttpClientCodec());
                        channel.pipeline().addLast(new BackendResponseHandler(context, ProxyHandler.this, proxySession));
                    }
                });

        bootstrap.connect(backend.uri().getHost(), backend.port()).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                logger.error("Failed to connect to backend {}: {}", backend.id(), future.cause().getMessage());
                failSession(context, proxySession, future.cause(), true);
                return;
            }
            logger.debug("Connected to backend {}", backend.id());
            proxySession.outbound = future.channel();
            flushPending(context, proxySession);
            if (proxySession.outbound.isWritable()) {
                context.channel().config().setAutoRead(true);
            }
        });
    }

    private void flushPending(ChannelHandlerContext context, ProxySession proxySession) {
        while (!proxySession.pendingWrites.isEmpty() && proxySession.outbound != null && proxySession.outbound.isActive()) {
            writeToBackend(context, proxySession, proxySession.pendingWrites.remove());
        }
    }

    private void writeToBackend(ChannelHandlerContext context, ProxySession proxySession, Object message) {
        ChannelFuture write = proxySession.outbound.writeAndFlush(message);
        write.addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                failSession(context, proxySession, future.cause(), true);
                return;
            }
            context.channel().config().setAutoRead(proxySession.outbound.isWritable());
        });
    }

    private HttpObject copyRequestForBackend(ChannelHandlerContext context, HttpRequest request, BackendServer backend) throws URISyntaxException {
        String rewrittenUri = rewriteUri(request.uri(), backend.uri());
        HttpRequest outbound;
        if (request instanceof io.netty.handler.codec.http.FullHttpRequest fullRequest) {
            DefaultFullHttpRequest full = new DefaultFullHttpRequest(
                    fullRequest.protocolVersion(),
                    fullRequest.method(),
                    rewrittenUri,
                    fullRequest.content().retain()
            );
            full.trailingHeaders().set(fullRequest.trailingHeaders());
            outbound = full;
        } else {
            outbound = new DefaultHttpRequest(request.protocolVersion(), request.method(), rewrittenUri);
        }
        copyHeaders(request.headers(), outbound.headers(), backend, context);
        return (HttpObject) outbound;
    }

    private void copyHeaders(HttpHeaders source, HttpHeaders target, BackendServer backend, ChannelHandlerContext context) {
        target.set(source);
        target.remove(HttpHeaderNames.CONNECTION);
        target.remove(HttpHeaderNames.PROXY_AUTHENTICATE);
        target.remove(HttpHeaderNames.PROXY_AUTHORIZATION);
        target.remove(HttpHeaderNames.TRAILER);
        target.remove(HttpHeaderNames.UPGRADE);
        target.set(HttpHeaderNames.HOST, backend.hostHeader());
        target.set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        String originalHost = source.get(HttpHeaderNames.HOST);
        if (originalHost != null) {
            target.set("X-Forwarded-Host", originalHost);
        }
        target.set("X-Forwarded-Proto", context.pipeline().get(io.netty.handler.ssl.SslHandler.class) == null ? "http" : "https");
        target.set("X-NetSentinel-Backend", backend.id());
    }

    private String rewriteUri(String requestUri, URI backendUri) throws URISyntaxException {
        URI incoming = new URI(requestUri);
        String path = incoming.isAbsolute() ? incoming.getRawPath() : splitPath(requestUri);
        String query = incoming.isAbsolute() ? incoming.getRawQuery() : splitQuery(requestUri);
        if (path == null || path.isBlank()) {
            path = "/";
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        String basePath = backendUri.getRawPath();
        if (basePath != null && !basePath.isBlank() && !"/".equals(basePath)) {
            path = stripTrailingSlash(basePath) + path;
        }
        return query == null || query.isBlank() ? path : path + "?" + query;
    }

    private String splitPath(String value) {
        int query = value.indexOf('?');
        return query >= 0 ? value.substring(0, query) : value;
    }

    private String splitQuery(String value) {
        int query = value.indexOf('?');
        return query >= 0 && query + 1 < value.length() ? value.substring(query + 1) : null;
    }

    private String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private SslContext backendSslContext() throws SSLException {
        if (configuredBackendSslContext.isPresent()) {
            return configuredBackendSslContext.get();
        }
        if (defaultBackendSslContext == null) {
            defaultBackendSslContext = SslContextBuilder.forClient().build();
        }
        return defaultBackendSslContext;
    }

    private void failSession(ChannelHandlerContext context, ProxySession proxySession, Throwable cause, boolean writeResponse) {
        if (proxySession.completed) {
            return;
        }
        proxySession.completed = true;
        while (!proxySession.pendingWrites.isEmpty()) {
            ReferenceCountUtil.release(proxySession.pendingWrites.remove());
        }
        proxySession.backend.recordFailure(System.nanoTime() - proxySession.startedNanos);
        publish(proxySession, HttpResponseStatus.BAD_GATEWAY.code(), false);
        if (proxySession.outbound != null) {
            proxySession.outbound.close();
        }
        if (writeResponse && context.channel().isActive()) {
            LocalResponses.sendJson(context, HttpResponseStatus.BAD_GATEWAY, cause == null ? "backend failure" : cause.getMessage());
        } else {
            context.close();
        }
        if (session == proxySession) {
            session = null;
        }
    }

    private void completeSession(ProxySession proxySession, int statusCode) {
        if (proxySession.completed) {
            return;
        }
        proxySession.completed = true;
        boolean success = statusCode < 500;
        long latencyNanos = System.nanoTime() - proxySession.startedNanos;
        if (success) {
            proxySession.backend.recordSuccess(latencyNanos);
        } else {
            proxySession.backend.recordFailure(latencyNanos);
        }
        publish(proxySession, statusCode, success);
        if (session == proxySession) {
            session = null;
        }
    }

    private void publish(ProxySession proxySession, int statusCode, boolean success) {
        eventPublisher.publish(new TrafficEvent(
                Instant.now(),
                proxySession.routeId,
                proxySession.backend.id(),
                proxySession.method.name(),
                proxySession.uri,
                statusCode,
                System.nanoTime() - proxySession.startedNanos,
                success
        ));
    }

    private static final class ProxySession {
        private final String routeId;
        private final BackendServer backend;
        private final HttpMethod method;
        private final String uri;
        private final boolean keepAlive;
        private final long startedNanos;
        private final Queue<Object> pendingWrites = new ArrayDeque<>();
        private Channel outbound;
        private int statusCode = HttpResponseStatus.BAD_GATEWAY.code();
        private boolean completed;

        private ProxySession(String routeId, BackendServer backend, HttpMethod method, String uri, boolean keepAlive, long startedNanos) {
            this.routeId = routeId;
            this.backend = backend;
            this.method = method;
            this.uri = uri;
            this.keepAlive = keepAlive;
            this.startedNanos = startedNanos;
        }
    }

    private static final class BackendResponseHandler extends ChannelInboundHandlerAdapter {
        private final ChannelHandlerContext clientContext;
        private final ProxyHandler owner;
        private final ProxySession session;

        private BackendResponseHandler(ChannelHandlerContext clientContext, ProxyHandler owner, ProxySession session) {
            this.clientContext = clientContext;
            this.owner = owner;
            this.session = session;
        }

        @Override
        public void channelRead(ChannelHandlerContext backendContext, Object message) {
            if (!clientContext.channel().isActive()) {
                ReferenceCountUtil.release(message);
                backendContext.close();
                return;
            }
            if (message instanceof HttpResponse response) {
                session.statusCode = response.status().code();
                HttpUtil.setKeepAlive(response, session.keepAlive);
                response.headers().set("X-NetSentinel-Route", session.routeId);
            }

            boolean last = message instanceof LastHttpContent;
            ChannelFuture write = clientContext.writeAndFlush(message);
            if (last) {
                write.addListener((ChannelFutureListener) future -> {
                    owner.completeSession(session, session.statusCode);
                    backendContext.close();
                    if (!session.keepAlive) {
                        clientContext.close();
                    } else {
                        clientContext.channel().config().setAutoRead(true);
                    }
                });
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext context) {
            if (!session.completed) {
                owner.failSession(clientContext, session, null, false);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
            owner.failSession(clientContext, session, cause, true);
        }
    }
}
