package com.netsentinel.ratelimit;

import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;

import java.time.Instant;
import java.util.concurrent.CompletionStage;

public final class RedisTokenBucketRateLimiter implements RateLimiter {
    private static final String LUA = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill_per_second = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local requested = tonumber(ARGV[4])
            local fill_time = capacity / refill_per_second
            local ttl = math.floor(fill_time * 2)
            local values = redis.call('HMGET', key, 'tokens', 'timestamp')
            local tokens = tonumber(values[1])
            if tokens == nil then tokens = capacity end
            local timestamp = tonumber(values[2])
            if timestamp == nil then timestamp = now end
            local delta = math.max(0, now - timestamp)
            local filled = math.min(capacity, tokens + (delta * refill_per_second))
            local allowed = filled >= requested
            local new_tokens = filled
            if allowed then new_tokens = filled - requested end
            redis.call('HMSET', key, 'tokens', new_tokens, 'timestamp', now)
            redis.call('EXPIRE', key, ttl)
            if allowed then return 1 else return 0 end
            """;

    private final long capacity;
    private final long refillPerSecond;
    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisAsyncCommands<String, String> commands;

    public RedisTokenBucketRateLimiter(String redisUri, long capacity, long refillPerSecond) {
        this.capacity = Math.max(1, capacity);
        this.refillPerSecond = Math.max(1, refillPerSecond);
        this.client = RedisClient.create(redisUri);
        this.connection = client.connect();
        this.commands = connection.async();
    }

    @Override
    public CompletionStage<Boolean> allow(String key) {
        String redisKey = "netsentinel:rate:" + (key == null || key.isBlank() ? "anonymous" : key);
        String now = Double.toString(Instant.now().toEpochMilli() / 1000.0d);
        return commands.eval(
                LUA,
                ScriptOutputType.INTEGER,
                new String[]{redisKey},
                Long.toString(capacity),
                Long.toString(refillPerSecond),
                now,
                "1"
        ).thenApply(result -> Long.valueOf(1L).equals(result));
    }

    @Override
    public void close() {
        connection.close();
        client.shutdown();
    }
}
