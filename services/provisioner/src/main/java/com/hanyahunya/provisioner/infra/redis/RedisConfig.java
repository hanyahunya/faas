package com.hanyahunya.provisioner.infra.redis;

import org.springframework.boot.autoconfigure.data.redis.JedisClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import redis.clients.jedis.JedisPoolConfig;

import java.time.Duration;

@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        // Key 문자열
        template.setKeySerializer(new StringRedisSerializer());
        // Value JSON
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());

        return template;
    }

    @Bean
    public JedisClientConfigurationBuilderCustomizer jedisClientConfigurationBuilderCustomizer() {
        return builder -> builder.usePooling().poolConfig(jedisPoolConfig());
    }

    private JedisPoolConfig jedisPoolConfig() {
        JedisPoolConfig config = new JedisPoolConfig();

        config.setMaxTotal(350);
        config.setMaxIdle(100);
        config.setMinIdle(0);
        config.setMaxWait(Duration.ofMillis(4000));
        config.setTimeBetweenEvictionRuns(Duration.ofMillis(30000));
        config.setMinEvictableIdleDuration(Duration.ofMillis(60000));

        return config;
    }
}
