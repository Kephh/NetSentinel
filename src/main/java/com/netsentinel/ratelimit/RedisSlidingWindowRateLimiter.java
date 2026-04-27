package com.netsentinel.ratelimit;

import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public final class RedisSlidingWindowRateLimiter implements RateLimiter {
    private static final String LUA = """
            local key = KEYS[1]
            local window_ms = tonumber(ARGV[1])
            local limit = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local member = ARGV[4]
            
            redis.call('ZREMRANGEBYSCORE', key, 0, now - window_ms)
            local count = redis.call('ZCARD', key)
            
            if count < limit then
                redis.call('ZADD', key, now, member)
                redis.call('PEXPIRE', key, window_ms)
                return 1
            else
                return 0
            end
            """;

    private final long limit;
    private final long windowMs;
    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisAsyncCommands<String, String> commands;

    public RedisSlidingWindowRateLimiter(String redisUri, long limit, long windowSeconds) {
        this.limit = Math.max(1, limit);
        this.windowMs = Math.max(1, windowSeconds * 1000);
        this.client = RedisClient.create(redisUri);
        this.connection = client.connect();
        this.commands = connection.async();
    }

    @Override
    public CompletionStage<Boolean> allow(String key) {
        String redisKey = "netsentinel:rate:sliding:" + (key == null || key.isBlank() ? "anonymous" : key);
        long now = Instant.now().toEpochMilli();
        String member = UUID.randomUUID().toString();
        
        return commands.eval(
                LUA,
                ScriptOutputType.INTEGER,
                new String[]{redisKey},
                Long.toString(windowMs),
                Long.toString(limit),
                Long.toString(now),
                member
        ).thenApply(result -> Long.valueOf(1L).equals(result));
    }

    @Override
    public void close() {
        connection.close();
        client.shutdown();
    }
}
