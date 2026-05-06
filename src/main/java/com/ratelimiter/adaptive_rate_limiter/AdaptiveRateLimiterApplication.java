package com.ratelimiter.adaptive_rate_limiter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class AdaptiveRateLimiterApplication {

	public static void main(String[] args) {
		SpringApplication.run(AdaptiveRateLimiterApplication.class, args);
	}
}