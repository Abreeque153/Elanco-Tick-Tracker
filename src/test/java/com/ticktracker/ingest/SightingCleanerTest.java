package com.ticktracker.ingest;

import com.ticktracker.domain.Sighting;
import com.ticktracker.domain.SightingSource;
import com.ticktracker.support.TestSupport;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SightingCleanerTest {

    private final SightingCleaner cleaner = new SightingCleaner(TestSupport.CATALOGUE, TestSupport.clock());

    private CleanResult clean(String json) {
        return cleaner.clean(TestSupport.json(json), SightingSource.ELANCO_API);
    }

    private CleanResult.Accepted accepted(String json) {
        return assertInstanceOf(CleanResult.Accepted.class, clean(json));
    }

    private RejectionReason rejected(String json) {
        return assertInstanceOf(CleanResult.Rejected.class, clean(json)).reason();
    }

    @Test
    void acceptsARecordInTheApiFormatUnchanged() {
        CleanResult.Accepted result = accepted("""
                {"date":"2022-08-01T06:40:31","id":"02WNholuSg6ndCk4c1dA","latinName":"Ixodes apronophorus",
                 "location":"Manchester","species":"Marsh tick"}""");
        Sighting s = result.sighting();
        assertEquals("02WNholuSg6ndCk4c1dA", s.id());
        assertEquals(LocalDateTime.of(2022, 8, 1, 6, 40, 31), s.date());
        assertEquals("Manchester", s.city());
        assertEquals("North West", s.region());
        assertEquals("Marsh tick", s.species());
        assertEquals("Ixodes apronophorus", s.latinName());
        assertTrue(result.repairs().isEmpty(), "clean data needs no repairs");
    }

    @Test
    void normalisesCaseWhitespaceAndCityAliases() {
        CleanResult.Accepted result = accepted("""
                {"id":"a","date":"2024-08-14T09:30:00","location":"  newcastle   upon tyne ","species":"marsh TICK",
                 "latinName":"Ixodes apronophorus"}""");
        assertEquals("Newcastle", result.sighting().city());
        assertEquals("North East", result.sighting().region());
        assertEquals("Marsh tick", result.sighting().species());
        assertEquals(Set.of(Repair.CITY_NORMALISED, Repair.SPECIES_NORMALISED), result.repairs());
    }

    @Test
    void fillsTheLatinNameFromTheSpecies() {
        CleanResult.Accepted result = accepted("""
                {"id":"a","date":"2024-08-14T09:30:00","location":"Leeds","species":"Tree-hole tick"}""");
        assertEquals("Ixodes arboricola", result.sighting().latinName());
        assertTrue(result.repairs().contains(Repair.LATIN_NAME_INFERRED));
    }

    @Test
    void infersTheSpeciesFromTheLatinName() {
        CleanResult.Accepted result = accepted("""
                {"id":"a","date":"2024-08-14T09:30:00","location":"Leeds","latinName":"ixodes canisuga"}""");
        assertEquals("Fox/badger tick", result.sighting().species());
        assertEquals("Ixodes canisuga", result.sighting().latinName());
        assertTrue(result.repairs().containsAll(Set.of(Repair.SPECIES_INFERRED, Repair.LATIN_NAME_NORMALISED)));
    }

    @Test
    void keepsUnknownCitiesAndSpeciesButFlagsThem() {
        CleanResult.Accepted result = accepted("""
                {"id":"a","date":"2024-08-14T09:30:00","location":"exeter","species":"Mystery tick"}""");
        assertEquals("Exeter", result.sighting().city());
        assertEquals("Unknown", result.sighting().region());
        assertEquals("Mystery tick", result.sighting().species());
        assertEquals(Set.of(Repair.UNKNOWN_CITY, Repair.UNKNOWN_SPECIES), result.repairs());
    }

    @Test
    void acceptsCommonAlternativeDateFormats() {
        assertEquals(LocalDateTime.of(2024, 8, 14, 9, 30),
                accepted("{\"id\":\"a\",\"date\":\"14/08/2024 09:30\",\"location\":\"Leeds\",\"species\":\"Marsh tick\"}")
                        .sighting().date());
        assertEquals(LocalDateTime.of(2024, 8, 14, 9, 30, 15),
                accepted("{\"id\":\"a\",\"date\":\"2024-08-14 09:30:15\",\"location\":\"Leeds\",\"species\":\"Marsh tick\"}")
                        .sighting().date());

        CleanResult.Accepted dateOnly = accepted(
                "{\"id\":\"a\",\"date\":\"2024-08-14\",\"location\":\"Leeds\",\"species\":\"Marsh tick\"}");
        assertEquals(LocalDateTime.of(2024, 8, 14, 0, 0), dateOnly.sighting().date());
        assertTrue(dateOnly.repairs().contains(Repair.DATE_WITHOUT_TIME));
    }

    @Test
    void convertsUtcTimestampsToUkLocalTime() {
        // 09:00 UTC in August is 10:00 British Summer Time.
        CleanResult.Accepted result = accepted(
                "{\"id\":\"a\",\"date\":\"2024-08-16T09:00:00Z\",\"location\":\"Leeds\",\"species\":\"Marsh tick\"}");
        assertEquals(LocalDateTime.of(2024, 8, 16, 10, 0), result.sighting().date());
        assertTrue(result.repairs().contains(Repair.TIMEZONE_CONVERTED));
    }

    @Test
    void acceptsAlternativeFieldNames() {
        CleanResult.Accepted result = accepted("""
                {"id":"a","date":"2024-08-17T12:00:00","city":"Bristol","species":"Passerine tick",
                 "latin_name":"Dermacentor frontalis","unexpected":"ignored"}""");
        assertEquals("Bristol", result.sighting().city());
        assertTrue(result.repairs().isEmpty());
    }

    @Test
    void rejectsRecordsThatCannotBeTrusted() {
        assertEquals(RejectionReason.MALFORMED_RECORD, rejected("\"just a string\""));
        assertEquals(RejectionReason.MALFORMED_RECORD, rejected("[1, 2]"));
        assertEquals(RejectionReason.MISSING_ID,
                rejected("{\"date\":\"2024-01-01T00:00:00\",\"location\":\"Leeds\",\"species\":\"Marsh tick\"}"));
        assertEquals(RejectionReason.MISSING_ID,
                rejected("{\"id\":\"  \",\"date\":\"2024-01-01T00:00:00\",\"location\":\"Leeds\",\"species\":\"Marsh tick\"}"));
        assertEquals(RejectionReason.MISSING_DATE,
                rejected("{\"id\":\"a\",\"date\":null,\"location\":\"Leeds\",\"species\":\"Marsh tick\"}"));
        assertEquals(RejectionReason.MISSING_LOCATION,
                rejected("{\"id\":\"a\",\"date\":\"2024-01-01T00:00:00\",\"location\":\"\",\"species\":\"Marsh tick\"}"));
        assertEquals(RejectionReason.MISSING_SPECIES,
                rejected("{\"id\":\"a\",\"date\":\"2024-01-01T00:00:00\",\"location\":\"Leeds\"}"));
    }

    @Test
    void rejectsImpossibleImplausibleAndFutureDates() {
        assertEquals(RejectionReason.INVALID_DATE,
                rejected("{\"id\":\"a\",\"date\":\"2024-02-31T10:00:00\",\"location\":\"Leeds\",\"species\":\"Marsh tick\"}"));
        assertEquals(RejectionReason.INVALID_DATE,
                rejected("{\"id\":\"a\",\"date\":\"last Tuesday\",\"location\":\"Leeds\",\"species\":\"Marsh tick\"}"));
        assertEquals(RejectionReason.INVALID_DATE,
                rejected("{\"id\":\"a\",\"date\":\"1850-01-01T00:00:00\",\"location\":\"Leeds\",\"species\":\"Marsh tick\"}"));
        assertEquals(RejectionReason.FUTURE_DATE,
                rejected("{\"id\":\"a\",\"date\":\"2099-01-01T00:00:00\",\"location\":\"Leeds\",\"species\":\"Marsh tick\"}"));
    }

    @Test
    void rejectsASpeciesThatContradictsItsLatinName() {
        assertEquals(RejectionReason.SPECIES_CONFLICT, rejected("""
                {"id":"a","date":"2024-05-07T10:00:00","location":"Leeds","species":"Marsh tick",
                 "latinName":"Ixodes canisuga"}"""));
    }
}
