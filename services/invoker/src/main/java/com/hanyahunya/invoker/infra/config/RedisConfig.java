package com.hanyahunya.invoker.infra.config;

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

        // 동시 요청 2000개까지는 버티도록 설정 (가상 스레드라 충분함)
        config.setMaxTotal(2000);

        // 평소에도 어느 정도 열어둘지 (너무 적으면 갑자기 몰릴 때 커넥션 맺느라 느려짐)
        config.setMaxIdle(500);
        config.setMinIdle(0);

        config.setMaxWait(Duration.ofMillis(5000));
        return config;
    }
}