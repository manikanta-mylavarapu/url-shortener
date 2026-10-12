package com.example.shortener.config;

import com.example.shortener.service.CodeGenerator;
import com.example.shortener.service.TokenBucketLimiter;
import com.example.shortener.service.UrlValidator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.SecureRandom;
import java.time.Clock;

/** Wires the framework-free core classes into Spring. */
@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class AppConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    CodeGenerator codeGenerator(AppProperties props) {
        return new CodeGenerator(props.codeLength(), new SecureRandom());
    }

    @Bean
    UrlValidator urlValidator(AppProperties props) {
        return new UrlValidator(props.blockPrivateNetworks());
    }

    @Bean
    TokenBucketLimiter tokenBucketLimiter(AppProperties props, Clock clock) {
        AppProperties.RateLimit rl = props.rateLimit();
        return new TokenBucketLimiter(rl.capacity(), rl.refillPerSecond(), rl.maxTrackedClients(), clock);
    }
}
