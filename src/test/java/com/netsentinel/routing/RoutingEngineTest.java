package com.netsentinel.routing;

import com.netsentinel.config.NetSentinelConfig;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutingEngineTest {
    @Test
    void selectsRouteByHostAndHeader() {
        RoutingEngine engine = new RoutingEngine();
        engine.load(new NetSentinelConfig(
                NetSentinelConfig.ServerConfig.defaults(),
                NetSentinelConfig.SecurityConfig.defaults(),
                NetSentinelConfig.WafConfig.defaults(),
                NetSentinelConfig.HealthConfig.defaults(),
                List.of(new NetSentinelConfig.RouteConfig(
                        "premium",
                        "api.example.com",
                        Map.of("X-User-Tier", "gold"),
                        RoutingPolicy.WEIGHTED_ROUND_ROBIN,
                        List.of(new NetSentinelConfig.BackendConfig("api-a", URI.create("http://127.0.0.1:9001"), 100, "/health"))
                ))
        ));
        DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
        request.headers().set(HttpHeaderNames.HOST, "api.example.com");
        request.headers().set("X-User-Tier", "gold");

        BackendSelection selection = engine.select(request).orElseThrow();

        assertEquals("premium", selection.routeId());
        assertEquals("api-a", selection.backend().id());
    }

    @Test
    void skipsDownBackends() {
        RoutingEngine engine = new RoutingEngine();
        engine.load(new NetSentinelConfig(
                NetSentinelConfig.ServerConfig.defaults(),
                NetSentinelConfig.SecurityConfig.defaults(),
                NetSentinelConfig.WafConfig.defaults(),
                NetSentinelConfig.HealthConfig.defaults(),
                List.of(new NetSentinelConfig.RouteConfig(
                        "default",
                        "*",
                        Map.of(),
                        RoutingPolicy.WEIGHTED_ROUND_ROBIN,
                        List.of(
                                new NetSentinelConfig.BackendConfig("api-a", URI.create("http://127.0.0.1:9001"), 100, "/health"),
                                new NetSentinelConfig.BackendConfig("api-b", URI.create("http://127.0.0.1:9002"), 100, "/health")
                        )
                ))
        ));
        engine.markBackendDown("api-a");
        DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
        request.headers().set(HttpHeaderNames.HOST, "anything.example");

        assertTrue(engine.select(request).stream().allMatch(selection -> "api-b".equals(selection.backend().id())));
    }
}
