package com.netsentinel;

import com.netsentinel.config.ConfigLoader;
import com.netsentinel.config.NetSentinelConfig;
import com.netsentinel.events.AsyncJsonFileEventPublisher;
import com.netsentinel.events.AsyncJsonLogEventPublisher;
import com.netsentinel.events.EventPublisher;
import com.netsentinel.health.HealthCheckService;
import com.netsentinel.metrics.MetricsHandler;
import com.netsentinel.metrics.NetSentinelMetrics;
import com.netsentinel.proxy.AccessLogHandler;
import com.netsentinel.proxy.BackpressureHandler;
import com.netsentinel.proxy.ProxyHandler;
import com.netsentinel.ratelimit.LocalTokenBucketRateLimiter;
import com.netsentinel.ratelimit.NoopRateLimiter;
import com.netsentinel.ratelimit.RateLimitHandler;
import com.netsentinel.ratelimit.RateLimiter;
import com.netsentinel.ratelimit.RedisSlidingWindowRateLimiter;
import com.netsentinel.ratelimit.RedisTokenBucketRateLimiter;
import com.netsentinel.ratelimit.ResilientRateLimiter;
import com.netsentinel.routing.RoutingEngine;
import com.netsentinel.security.TlsContextFactory;
import com.netsentinel.security.WafHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

public final class NetSentinelServer {
    private static final Logger logger = LoggerFactory.getLogger(NetSentinelServer.class);
    private static final WriteBufferWaterMark WATER_MARK = new WriteBufferWaterMark(32 * 1024, 128 * 1024);

    private NetSentinelServer() {
    }

    public static void main(String[] args) throws Exception {
        Path configPath = args.length > 0 ? Path.of(args[0]) : Path.of("config", "netsentinel.json");
        NetSentinelConfig config = ConfigLoader.load(configPath);

        NetSentinelMetrics metrics = new NetSentinelMetrics();
        metrics.start(config.server().managementPort());

        RoutingEngine routingEngine = new RoutingEngine();
        routingEngine.load(config, metrics);

        RateLimiter rateLimiter = createRateLimiter(config.server().rateLimit(), config.server().redisUri());
        EventPublisher eventPublisher = createEventPublisher(config.audit());
        HealthCheckService healthCheckService = new HealthCheckService(routingEngine, config.health(), metrics);
        healthCheckService.start();
        AutoCloseable reload = routingEngine.startHotReload(configPath);

        Optional<SslContext> inboundSslContext = TlsContextFactory.inbound(config.security().inboundTls());
        Optional<SslContext> backendSslContext = TlsContextFactory.backend(config.security().backendTls());

        boolean epoll = Epoll.isAvailable();
        EventLoopGroup bossGroup = epoll ? new EpollEventLoopGroup(1) : new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = epoll ? new EpollEventLoopGroup() : new NioEventLoopGroup();
        Class<? extends ServerChannel> serverChannel = epoll ? EpollServerSocketChannel.class : NioServerSocketChannel.class;
        Class<? extends Channel> outboundChannel = epoll ? EpollSocketChannel.class : NioSocketChannel.class;

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            tryClose(reload);
            tryClose(healthCheckService);
            tryClose(eventPublisher);
            tryClose(rateLimiter);
            tryClose(metrics);
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }, "netsentinel-shutdown"));

        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(serverChannel)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, WATER_MARK)
                .childOption(ChannelOption.AUTO_READ, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        channel.pipeline().addLast(new LoggingHandler(LogLevel.INFO));
                        channel.config().setAllocator(PooledByteBufAllocator.DEFAULT);
                        inboundSslContext.ifPresent(ssl -> channel.pipeline().addLast(ssl.newHandler(channel.alloc())));
                        channel.pipeline().addLast(new HttpRequestDecoder(4096, 8192, 8192, false));
                        channel.pipeline().addLast(new HttpResponseEncoder());
                        channel.pipeline().addLast(new AccessLogHandler());
                        channel.pipeline().addLast(new BackpressureHandler());
                        channel.pipeline().addLast(new RateLimitHandler(rateLimiter));
                        channel.pipeline().addLast(new WafHandler(config.waf().enabled(), config.waf().patterns(), metrics));
                        channel.pipeline().addLast(new MetricsHandler(metrics));
                        channel.pipeline().addLast(new ProxyHandler(routingEngine, outboundChannel, backendSslContext, eventPublisher, metrics));
                    }
                });

        ChannelFuture bind = bootstrap.bind(config.server().host(), config.server().port()).sync();
        logger.info(
                "NetSentinel listening on {}:{} with {} transport, metrics on :{}",
                config.server().host(),
                config.server().port(),
                epoll ? "epoll" : "nio",
                config.server().managementPort()
        );
        bind.channel().closeFuture().sync();
    }

    private static RateLimiter createRateLimiter(NetSentinelConfig.RateLimitConfig config, String redisUri) {
        if (!config.enabled()) {
            return new NoopRateLimiter();
        }
        if (redisUri != null && !redisUri.isBlank()) {
            RateLimiter redis;
            if (config.algorithm() == NetSentinelConfig.RateLimitAlgorithm.SLIDING_WINDOW) {
                redis = new RedisSlidingWindowRateLimiter(redisUri, config.capacity(), 1); // 1 second window for refillPerSecond semantics
            } else {
                redis = new RedisTokenBucketRateLimiter(redisUri, config.capacity(), config.refillPerSecond());
            }
            RateLimiter local = new LocalTokenBucketRateLimiter(config.capacity(), config.refillPerSecond());
            return new ResilientRateLimiter(redis, local, Duration.ofSeconds(30));
        }
        return new LocalTokenBucketRateLimiter(config.capacity(), config.refillPerSecond());
    }

    private static EventPublisher createEventPublisher(NetSentinelConfig.AuditConfig config) throws Exception {
        if (config == null || !config.enabled()) {
            return event -> {
            };
        }
        if (config.fileSink()) {
            return new AsyncJsonFileEventPublisher(Path.of(config.filePath()));
        }
        return new AsyncJsonLogEventPublisher(System.out);
    }

    private static void tryClose(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }
}
