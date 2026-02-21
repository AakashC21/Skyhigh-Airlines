package com.skyhigh.core.config;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Distributed Rate Limiting using Bucket4j + Redis.
 * Enforces limits across multiple application replicas.
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class RateLimitingConfig implements WebMvcConfigurer {

    private final ProxyManager<byte[]> proxyManager;
    private final BucketConfiguration bucketConfiguration;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                String key = "rate_limit:" + getClientIp(request);

                // Fetch the bucket state from Redis (atomic)
                Bucket bucket = proxyManager.builder().build(key.getBytes(), bucketConfiguration);

                if (bucket.tryConsume(1)) {
                    return true;
                } else {
                    response.setStatus(429);
                    response.setHeader("Retry-After", "1");
                    log.warn("Rate limit exceeded for client: {}", key);
                    return false;
                }
            }
        }).addPathPatterns("/api/v1/seats/hold", "/api/v1/bookings/confirm", "/api/v1/waitlist/join");
    }

    private String getClientIp(HttpServletRequest request) {
        String xf = request.getHeader("X-Forwarded-For");
        if (xf != null && !xf.isBlank()) {
            return xf.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
