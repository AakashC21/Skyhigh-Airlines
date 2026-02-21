package com.skyhigh.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.hibernate6.Hibernate6Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                // Handle Hibernate lazy proxies
                .registerModule(new Hibernate6Module())
                // Handle Java 8 date/time types
                .registerModule(new JavaTimeModule())
                // Don't serialize dates as timestamps
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                // Don't fail when serializing lazy-loaded objects
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }
}
