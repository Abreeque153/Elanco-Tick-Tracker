package com.ticktracker.domain;

import com.ticktracker.error.ConflictException;
import com.ticktracker.error.InvalidRequestException;
import com.ticktracker.reports.ReportService;
import com.ticktracker.store.SightingRepository;
import com.ticktracker.support.TestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportServiceTest {

    @TempDir
    Path tempDir;

    private SightingRepository repository;
    private ReportService reports;

    @BeforeEach
    void setUp() {
        repository = new SightingRepository(TestSupport.database(tempDir));
        reports = new ReportService(TestSupport.CATALOGUE, repository, TestSupport.clock());
    }

    @Test
    void storesAValidPublicReport() {
        Sighting s = reports.submit(LocalDateTime.of(2026, 10, 30, 14, 0), " london ", "ixodes ricinus");
        assertTrue(s.id().startsWith("usr-"));
        assertEquals("London", s.city());
        assertEquals("Sheep tick", s.species());
        assertEquals(SightingSource.USER_REPORT, repository.findById(s.id()).orElseThrow().source());
    }

    @Test
    void rejectsUnknownCitiesSpeciesAndFutureDates() {
        LocalDateTime when = LocalDateTime.of(2026, 10, 30, 14, 0);
        InvalidRequestException city = assertThrows(InvalidRequestException.class,
                () -> reports.submit(when, "Atlantis", "Sheep tick"));
        assertTrue(city.getMessage().contains("Supported cities"));
        assertThrows(InvalidRequestException.class, () -> reports.submit(when, "London", "Dragon tick"));
        assertThrows(InvalidRequestException.class,
                () -> reports.submit(LocalDateTime.of(2027, 1, 1, 0, 0), "London", "Sheep tick"));
    }

    @Test
    void refusesTheSameSightingTwice() {
        LocalDateTime when = LocalDateTime.of(2026, 10, 30, 14, 0);
        reports.submit(when, "Leeds", "Sheep tick");
        assertThrows(ConflictException.class, () -> reports.submit(when, "LEEDS", "Ixodes ricinus"));
    }
}
