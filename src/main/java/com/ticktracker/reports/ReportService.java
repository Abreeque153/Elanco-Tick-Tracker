package com.ticktracker.reports;

import com.ticktracker.domain.Catalogue;
import com.ticktracker.domain.Sighting;
import com.ticktracker.domain.SightingSource;
import com.ticktracker.error.ConflictException;
import com.ticktracker.error.InvalidRequestException;
import com.ticktracker.ingest.SightingCleaner;
import com.ticktracker.store.SightingRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Accepts sightings reported by the public. The brief says citizens should
 * "contribute to a growing database of tick activity".
 *
 * <p>Reports are stricter than upstream data: the city and species must be
 * known, so every stored report can be placed on a map and attributed to a
 * region.
 */
public final class ReportService {

    private final Catalogue catalogue;
    private final SightingRepository sightings;
    private final Clock clock;

    public ReportService(Catalogue catalogue, SightingRepository sightings, Clock clock) {
        this.catalogue = catalogue;
        this.sightings = sightings;
        this.clock = clock;
    }

    public Sighting submit(LocalDateTime date, String city, String species) {
        Catalogue.City knownCity = catalogue.findCity(city).orElseThrow(() -> new InvalidRequestException(
                "Unknown city '" + city + "'. Supported cities: "
                        + catalogue.cities().stream().map(Catalogue.City::name).collect(Collectors.joining(", "))));
        Catalogue.Species knownSpecies = catalogue.findSpeciesByAnyName(species).orElseThrow(() ->
                new InvalidRequestException("Unknown species '" + species + "'. Supported species: "
                        + catalogue.species().stream().map(Catalogue.Species::name).collect(Collectors.joining(", "))));
        if (date.isAfter(LocalDateTime.now(clock.withZone(SightingCleaner.UK)))) {
            throw new InvalidRequestException("'date' cannot be in the future");
        }

        Sighting sighting = new Sighting("usr-" + UUID.randomUUID(), date, knownCity.name(), knownCity.region(),
                knownSpecies.name(), knownSpecies.latinName(), SightingSource.USER_REPORT);
        sightings.insertIfAbsent(sighting, clock.instant()).ifPresent(existingId -> {
            throw new ConflictException("This sighting is already recorded as '" + existingId + "'");
        });
        return sighting;
    }
}
