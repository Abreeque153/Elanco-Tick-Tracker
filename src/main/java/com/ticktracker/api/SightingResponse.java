package com.ticktracker.api;

import com.ticktracker.domain.Catalogue;
import com.ticktracker.domain.Sighting;
import com.ticktracker.domain.SightingSource;

import java.time.LocalDateTime;
import java.util.Optional;

/** A sighting as returned by the API, with the city's coordinates so a client can map it. */
public record SightingResponse(
        String id,
        LocalDateTime date,
        String city,
        String region,
        Double latitude,
        Double longitude,
        String species,
        String latinName,
        SightingSource source) {

    public static SightingResponse from(Sighting s, Catalogue catalogue) {
        Optional<Catalogue.City> city = catalogue.findCity(s.city());
        return new SightingResponse(s.id(), s.date(), s.city(), s.region(),
                city.map(Catalogue.City::latitude).orElse(null),
                city.map(Catalogue.City::longitude).orElse(null),
                s.species(), s.latinName(), s.source());
    }
}
