package com.ticktracker.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogueTest {

    private final Catalogue catalogue = Catalogue.ukDefault();

    @Test
    void coversEveryCityAndSpeciesInTheElancoDataset() {
        // The 14 cities and 5 species observed in the live API on 1 Nov 2026
        List.of("Manchester", "London", "Glasgow", "Birmingham", "Southampton", "Nottingham", "Sheffield",
                "Liverpool", "Edinburgh", "Newcastle", "Leicester", "Bristol", "Cardiff", "Leeds")
                .forEach(city -> assertTrue(catalogue.findCity(city).isPresent(), city));
        List.of("Marsh tick", "Southern rodent tick", "Passerine tick", "Tree-hole tick", "Fox/badger tick")
                .forEach(species -> assertTrue(catalogue.findSpecies(species).isPresent(), species));
    }

    @Test
    void lookupsIgnoreCaseWhitespaceAndAliases() {
        assertEquals("Newcastle", catalogue.findCity("  NEWCASTLE   upon tyne").orElseThrow().name());
        assertEquals("Fox/badger tick", catalogue.findSpeciesByAnyName("IXODES CANISUGA").orElseThrow().name());
        assertEquals("Yorkshire and the Humber", catalogue.findRegion("yorkshire AND the humber").orElseThrow());
        assertTrue(catalogue.findCity(null).isEmpty());
    }

    @Test
    void haversineDistanceIsAccurate() {
        // London to Edinburgh is about 534 km as the crow flies
        Catalogue.City london = catalogue.findCity("London").orElseThrow();
        Catalogue.City edinburgh = catalogue.findCity("Edinburgh").orElseThrow();
        double km = Catalogue.distanceKm(london.latitude(), london.longitude(), edinburgh.latitude(), edinburgh.longitude());
        assertEquals(534, km, 5);
    }

    @Test
    void radiusSearchReturnsNearestFirst() {
        List<Catalogue.City> nearSheffield = catalogue.citiesWithin(53.3811, -1.4701, 60);
        assertEquals("Sheffield", nearSheffield.get(0).name());
        assertTrue(nearSheffield.stream().anyMatch(c -> c.name().equals("Leeds")));
        assertTrue(nearSheffield.stream().anyMatch(c -> c.name().equals("Nottingham")));
    }

    @Test
    void titleCasesUnknownNames() {
        assertEquals("Milton Keynes", Text.titleCase("  milton   KEYNES "));
        assertEquals("Stoke-On-Trent", Text.titleCase("stoke-on-trent"));
    }
}
