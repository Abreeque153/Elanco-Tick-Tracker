package com.ticktracker.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Reference data: the UK cities in the dataset (mapped to their ONS region and
 * centre point) and the known tick species with their Latin names.
 *
 * <p>The cleaner uses it to normalise messy input such as "london" or
 * "Newcastle upon Tyne", the query parser uses it for case-insensitive filters
 * and radius search, and the stats endpoints use it to group cities into
 * regions.
 */
public final class Catalogue {

    public static final String UNKNOWN_REGION = "Unknown";

    public record City(String name, String region, double latitude, double longitude, List<String> aliases) {
    }

    public record Species(String name, String latinName) {
    }

    private final List<City> cities;
    private final List<Species> species;
    private final Map<String, City> cityByKey = new LinkedHashMap<>();
    private final Map<String, Species> speciesByName = new LinkedHashMap<>();
    private final Map<String, Species> speciesByLatinName = new LinkedHashMap<>();
    private final Map<String, String> regionByKey = new LinkedHashMap<>();

    public Catalogue(List<City> cities, List<Species> species) {
        this.cities = List.copyOf(cities);
        this.species = List.copyOf(species);
        for (City city : this.cities) {
            cityByKey.put(Text.key(city.name()), city);
            city.aliases().forEach(alias -> cityByKey.put(Text.key(alias), city));
            regionByKey.put(Text.key(city.region()), city.region());
        }
        regionByKey.put(Text.key(UNKNOWN_REGION), UNKNOWN_REGION);
        for (Species s : this.species) {
            speciesByName.put(Text.key(s.name()), s);
            speciesByLatinName.put(Text.key(s.latinName()), s);
        }
    }

    /** The 14 cities and 5 species in the Elanco dataset, plus two common UK species for public reports. */
    public static Catalogue ukDefault() {
        List<City> cities = List.of(
                new City("London", "London", 51.5072, -0.1276, List.of("Greater London", "City of London")),
                new City("Manchester", "North West", 53.4808, -2.2426, List.of()),
                new City("Liverpool", "North West", 53.4084, -2.9916, List.of()),
                new City("Birmingham", "West Midlands", 52.4862, -1.8904, List.of()),
                new City("Nottingham", "East Midlands", 52.9548, -1.1581, List.of()),
                new City("Leicester", "East Midlands", 52.6369, -1.1398, List.of()),
                new City("Sheffield", "Yorkshire and the Humber", 53.3811, -1.4701, List.of()),
                new City("Leeds", "Yorkshire and the Humber", 53.8008, -1.5491, List.of()),
                new City("Newcastle", "North East", 54.9783, -1.6178,
                        List.of("Newcastle upon Tyne", "Newcastle-upon-Tyne")),
                new City("Bristol", "South West", 51.4545, -2.5879, List.of()),
                new City("Southampton", "South East", 50.9097, -1.4044, List.of()),
                new City("Cardiff", "Wales", 51.4816, -3.1791, List.of("Caerdydd")),
                new City("Glasgow", "Scotland", 55.8642, -4.2518, List.of()),
                new City("Edinburgh", "Scotland", 55.9533, -3.1883, List.of()));
        List<Species> species = List.of(
                new Species("Marsh tick", "Ixodes apronophorus"),
                new Species("Southern rodent tick", "Ixodes acuminatus"),
                new Species("Passerine tick", "Dermacentor frontalis"),
                new Species("Tree-hole tick", "Ixodes arboricola"),
                new Species("Fox/badger tick", "Ixodes canisuga"),
                new Species("Sheep tick", "Ixodes ricinus"),
                new Species("Hedgehog tick", "Ixodes hexagonus"));
        return new Catalogue(cities, species);
    }

    public Optional<City> findCity(String raw) {
        return Optional.ofNullable(raw).map(Text::key).map(cityByKey::get);
    }

    public Optional<Species> findSpecies(String raw) {
        return Optional.ofNullable(raw).map(Text::key).map(speciesByName::get);
    }

    public Optional<Species> findSpeciesByLatinName(String raw) {
        return Optional.ofNullable(raw).map(Text::key).map(speciesByLatinName::get);
    }

    /** Looks a species up by common name first, then by Latin name. */
    public Optional<Species> findSpeciesByAnyName(String raw) {
        return findSpecies(raw).or(() -> findSpeciesByLatinName(raw));
    }

    public Optional<String> findRegion(String raw) {
        return Optional.ofNullable(raw).map(Text::key).map(regionByKey::get);
    }

    public List<City> cities() {
        return cities;
    }

    public List<Species> species() {
        return species;
    }

    /** Known regions in alphabetical order (excluding "Unknown"). */
    public List<String> regions() {
        TreeSet<String> names = new TreeSet<>();
        cities.forEach(c -> names.add(c.region()));
        return List.copyOf(names);
    }

    /** Known cities whose centre is within {@code radiusKm} of the point, nearest first. */
    public List<City> citiesWithin(double latitude, double longitude, double radiusKm) {
        List<City> result = new ArrayList<>();
        for (City city : cities) {
            if (distanceKm(latitude, longitude, city.latitude(), city.longitude()) <= radiusKm) {
                result.add(city);
            }
        }
        result.sort(Comparator.comparingDouble(c -> distanceKm(latitude, longitude, c.latitude(), c.longitude())));
        return result;
    }

    /** Great-circle distance using the haversine formula. */
    public static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double earthRadiusKm = 6371.0088;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * earthRadiusKm * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}
