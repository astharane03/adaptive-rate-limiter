package com.ratelimiter.adaptive_rate_limiter.config;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import io.lettuce.core.api.StatefulConnection;

import java.time.Duration;
import java.util.List;

/**
 * Wires up everything Redis-related into the Spring context.
 *
 * Three things this class creates:
 *
 * 1. RedisConnectionFactory — the actual TCP connection to Redis,
 *    with a connection pool so we don't open/close connections per request.
 *
 * 2. RedisTemplate — the main class we use to talk to Redis.
 *    We configure it with StringSerializers so keys and values
 *    are stored as plain text — readable in RedisInsight.
 *
 * 3. Two DefaultRedisScript beans — the Lua scripts loaded from
 *    src/main/resources/scripts/. Spring caches the script SHA
 *    after first upload and uses EVALSHA on subsequent calls —
 *    faster than sending the full script every time.
 */
@Configuration
public class RedisConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        // Which Redis server to connect to
        RedisStandaloneConfiguration serverConfig = new RedisStandaloneConfiguration(redisHost, redisPort);

        // Connection pool — keeps N connections warm and ready
        // Without pooling, every Redis call opens + closes a TCP connection
        GenericObjectPoolConfig<StatefulConnection<?, ?>> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(16);
        poolConfig.setMaxIdle(8);
        poolConfig.setMinIdle(2);
        poolConfig.setMaxWait(Duration.ofMillis(1000));

        LettucePoolingClientConfiguration clientConfig =
                LettucePoolingClientConfiguration.builder()
                        .commandTimeout(Duration.ofMillis(2000))
                        .poolConfig(poolConfig)
                        .build();

        return new LettuceConnectionFactory(serverConfig, clientConfig);
    }

    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory connectionFactory) {

        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // StringRedisSerializer stores keys/values as plain UTF-8 strings.
        // Without this, Spring uses Java serialization — keys look like
        // garbage bytes in RedisInsight. With this, you see "rl:tb:client:abc123"
        StringRedisSerializer serializer = new StringRedisSerializer();
        template.setKeySerializer(serializer);
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(serializer);
        template.setHashValueSerializer(serializer);

        template.afterPropertiesSet();
        return template;
    }

    /**
     * Loads token_bucket.lua from src/main/resources/scripts/
     * Result type List.class matches what the Lua script returns:
     * {allowed, remaining_tokens, retry_after_seconds}
     */
    @Bean
    public DefaultRedisScript<List> tokenBucketScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/token_bucket.lua"));
        script.setResultType(List.class);
        return script;
    }

    /**
     * Loads sliding_window.lua from src/main/resources/scripts/
     * Result type List.class matches what the Lua script returns:
     * {allowed, current_count, retry_after_seconds}
     */
    @Bean
    public DefaultRedisScript<List> slidingWindowScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/sliding_window.lua"));
        script.setResultType(List.class);
        return script;
    }
}