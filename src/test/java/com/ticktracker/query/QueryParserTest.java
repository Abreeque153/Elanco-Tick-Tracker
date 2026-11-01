package com.ticktracker.query;

import com.ticktracker.error.InvalidRequestException;
import com.ticktracker.store.SightingQuery;
import com.ticktracker.support.TestSupport;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryParserTest {

    private final QueryParser parser = new QueryParser(TestSupport.CATALOGUE);

    @Test
    void dateOnlyBoundsIncludeTheWholeEndDay() {
        SightingQuery q = parser.parse("2024-01-01", "2024-01-31", null, null, null, null, null, null);
        assertEquals(LocalDateTime.of(2024, 1, 1, 0, 0), q.from());
        assertEquals(LocalDateTime.of(2024, 2, 1, 0, 0), q.toExclusive());
    }

    @Test
    void acceptsMonthsAndDateTimes() {
        SightingQuery month = parser.parse("2024-05", "2024-06", null, null, null, null, null, null);
        assertEquals(LocalDateTime.of(2024, 5, 1, 0, 0), month.from());
        assertEquals(LocalDateTime.of(2024, 7, 1, 0, 0), month.toExclusive());

        SightingQuery precise = parser.parse("2024-05-01T09:30", "2024-05-01T10:00:00", null, null, null, null, null, null);
        assertEquals(LocalDateTime.of(2024, 5, 1, 9, 30), precise.from());
        assertEquals(LocalDateTime.of(2024, 5, 1, 10, 0, 1), precise.toExclusive());
    }

    @Test
    void rejectsBadOrReversedDates() {
        assertThrows(InvalidRequestException.class, () -> parser.parse("yesterday", null, null, null, null, null, null, null));
        assertThrows(InvalidRequestException.class, () -> parser.parse("2024-02-30", null, null, null, null, null, null, null));
        InvalidRequestException e = assertThrows(InvalidRequestException.class,
                () -> parser.parse("2024-06-01", "2024-01-01", null, null, null, null, null, null));
        assertTrue(e.getMessage().contains("'from' must be earlier than 'to'"));
    }

    @Test
    void filtersAreCaseInsensitiveAndAcceptSeveralValues() {
        SightingQuery q = parser.parse(null, null, List.of(" london ", "leeds,NEWCASTLE UPON TYNE"),
                List.of("north west"), List.of("ixodes canisuga", "marsh TICK"), null, null, null);
        assertEquals(Set.of("London", "Leeds", "Newcastle"), q.cities());
        assertEquals(Set.of("North West"), q.regions());
        assertEquals(Set.of("Fox/badger tick", "Marsh tick"), q.species());
    }

    @Test
    void emptyFiltersMeanNoFilter() {
        SightingQuery q = parser.parse(" ", null, List.of(""), null, null, null, null, null);
        assertNull(q.from());
        assertNull(q.cities());
        assertNull(q.regions());
    }

    @Test
    void unknownRegionsAreRejectedWithTheValidList() {
        InvalidRequestException e = assertThrows(InvalidRequestException.class,
                () -> parser.parse(null, null, null, List.of("Narnia"), null, null, null, null));
        assertTrue(e.getMessage().contains("Scotland"), e.getMessage());
    }

    @Test
    void radiusSearchFindsNearbyCities() {
        // Uxbridge, west London: London is ~25 km away, Southampton ~100 km.
        SightingQuery near = parser.parse(null, null, null, null, null, 51.546, -0.479, 40.0);
        assertEquals(Set.of("London"), near.cities());

        SightingQuery wider = parser.parse(null, null, null, null, null, 51.546, -0.479, 120.0);
        assertTrue(wider.cities().containsAll(Set.of("London", "Southampton")));

        SightingQuery nothing = parser.parse(null, null, null, null, null, 60.0, -30.0, 10.0);
        assertTrue(nothing.cities().isEmpty(), "an empty set means 'match nothing', not 'no filter'");
    }

    @Test
    void radiusIntersectsWithAnExplicitCityFilter() {
        SightingQuery q = parser.parse(null, null, List.of("Leeds", "London"), null, null, 51.546, -0.479, 40.0);
        assertEquals(Set.of("London"), q.cities());
    }

    @Test
    void radiusNeedsBothCoordinatesAndSaneValues() {
        assertThrows(InvalidRequestException.class, () -> parser.parse(null, null, null, null, null, 51.5, null, null));
        assertThrows(InvalidRequestException.class, () -> parser.parse(null, null, null, null, null, 95.0, 0.0, null));
        assertThrows(InvalidRequestException.class, () -> parser.parse(null, null, null, null, null, 51.5, 0.0, -1.0));
    }

    @Test
    void validatesPagingAndSorting() {
        assertThrows(InvalidRequestException.class, () -> QueryParser.validatePage(-1, 10));
        assertThrows(InvalidRequestException.class, () -> QueryParser.validatePage(0, 0));
        assertThrows(InvalidRequestException.class, () -> QueryParser.validatePage(0, 501));
        assertFalse(QueryParser.parseSortAscending(null));
        assertFalse(QueryParser.parseSortAscending("date,desc"));
        assertTrue(QueryParser.parseSortAscending("DATE,ASC"));
        assertThrows(InvalidRequestException.class, () -> QueryParser.parseSortAscending("species"));
    }
}
