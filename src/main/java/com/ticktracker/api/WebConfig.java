package com.ticktracker.api;

import com.ticktracker.ingest.SyncService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.time.Instant;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    public static final String LAST_SYNCED_HEADER = "X-Data-Last-Synced";
    public static final String STALE_HEADER = "X-Data-Stale";

    /** A browser frontend (e.g. a map) on another origin may call the read API. */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("*")
                .allowedMethods("GET", "POST")
                .exposedHeaders(LAST_SYNCED_HEADER, STALE_HEADER);
    }

    /**
     * Stamps every API response with the freshness of the data, so a client
     * can show "last updated" or warn when the upstream has been down for a
     * while.
     */
    @Bean
    public OncePerRequestFilter dataFreshnessFilter(SyncService syncService) {
        return new OncePerRequestFilter() {
            @Override
            protected boolean shouldNotFilter(HttpServletRequest request) {
                return !request.getRequestURI().startsWith("/api/");
            }

            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                response.setHeader(LAST_SYNCED_HEADER,
                        syncService.lastSuccessfulSync().map(Instant::toString).orElse("never"));
                response.setHeader(STALE_HEADER, String.valueOf(syncService.isDataStale()));
                chain.doFilter(request, response);
            }
        };
    }
}
