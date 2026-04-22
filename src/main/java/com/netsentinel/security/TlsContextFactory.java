package com.netsentinel.security;

import com.netsentinel.config.NetSentinelConfig;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;

import java.io.File;
import java.util.Optional;

public final class TlsContextFactory {
    private TlsContextFactory() {
    }

    public static Optional<SslContext> inbound(NetSentinelConfig.TlsConfig tlsConfig) throws Exception {
        if (tlsConfig == null || !tlsConfig.enabled()) {
            return Optional.empty();
        }
        SslContextBuilder builder = SslContextBuilder.forServer(
                file(tlsConfig.certificateChainPath(), "certificateChainPath"),
                file(tlsConfig.privateKeyPath(), "privateKeyPath")
        );
        if (!tlsConfig.trustCertificatePath().isBlank()) {
            builder.trustManager(file(tlsConfig.trustCertificatePath(), "trustCertificatePath"));
        }
        if (tlsConfig.requireClientAuth()) {
            builder.clientAuth(ClientAuth.REQUIRE);
        }
        return Optional.of(builder.build());
    }

    public static Optional<SslContext> backend(NetSentinelConfig.TlsConfig tlsConfig) throws Exception {
        if (tlsConfig == null || !tlsConfig.enabled()) {
            return Optional.empty();
        }
        SslContextBuilder builder = SslContextBuilder.forClient();
        if (!tlsConfig.trustCertificatePath().isBlank()) {
            builder.trustManager(file(tlsConfig.trustCertificatePath(), "trustCertificatePath"));
        }
        if (!tlsConfig.certificateChainPath().isBlank() && !tlsConfig.privateKeyPath().isBlank()) {
            builder.keyManager(file(tlsConfig.certificateChainPath(), "certificateChainPath"), file(tlsConfig.privateKeyPath(), "privateKeyPath"));
        }
        return Optional.of(builder.build());
    }

    private static File file(String path, String name) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("TLS " + name + " is required when TLS is enabled");
        }
        return new File(path);
    }
}
