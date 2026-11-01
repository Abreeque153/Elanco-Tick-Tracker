package com.ticktracker.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Title and description shown at the top of /swagger-ui.html. */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI tickTrackerOpenApi() {
        return new OpenAPI().info(new Info()
                .title("UK Tick Sightings Tracker API")
                .version("1.0.0")
                .description("""
                        Backend for a UK tick-sighting tracker, built for the Elanco placement task.

                        Data comes from the Elanco tick sightings API. It is streamed in, validated, de-duplicated \
                        and stored in SQLite, then served from there, so these endpoints stay fast and keep working \
                        when the upstream API is slow or down.

                        **Start here:** `GET /api/v1/stats/summary`, `GET /api/v1/sightings?city=london`, \
                        `GET /api/v1/stats/trends?interval=month`, `GET /api/v1/insights`.

                        Every `/api` response carries `X-Data-Last-Synced` and `X-Data-Stale` headers. \
                        Errors use RFC 9457 problem details (`application/problem+json`).""")
                .contact(new Contact().name("Abreeque Rauf Sahi").email("sahi.abreeque@gmail.com")));
    }
}
