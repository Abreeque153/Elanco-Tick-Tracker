package com.ticktracker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * UK Tick Sightings Tracker backend.
 *
 * <p>Start with {@code ./mvnw spring-boot:run}, then open
 * http://localhost:8080 for the interactive API docs.
 */
@SpringBootApplication
public class TickTrackerApplication {

    public static void main(String[] args) {
        SpringApplication.run(TickTrackerApplication.class, args);
    }
}
