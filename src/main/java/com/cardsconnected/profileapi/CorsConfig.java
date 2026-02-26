package com.cardsconnected.profileapi;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {
  @Override
  public void addCorsMappings(CorsRegistry registry) {
    String origin = System.getenv("CORS_ORIGIN");
    if (origin == null || origin.isBlank()) {
      origin = "*";
    }

    registry.addMapping("/**")
      .allowedOrigins(origin)
      .allowedMethods("GET", "POST", "PATCH", "OPTIONS")
      .allowedHeaders("Authorization", "Content-Type");
  }
}
