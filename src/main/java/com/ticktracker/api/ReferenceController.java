package com.ticktracker.api;

import com.ticktracker.domain.Catalogue;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/reference")
@Tag(name = "5. Reference data", description = "Supported cities, regions and species (for dropdowns and maps)")
public class ReferenceController {

    public record Region(String name, List<String> cities) {
    }

    private final Catalogue catalogue;

    public ReferenceController(Catalogue catalogue) {
        this.catalogue = catalogue;
    }

    @GetMapping("/cities")
    @Operation(summary = "Cities with region and coordinates")
    public List<Catalogue.City> cities() {
        return catalogue.cities();
    }

    @GetMapping("/regions")
    @Operation(summary = "Regions and the cities in each")
    public List<Region> regions() {
        return catalogue.regions().stream()
                .map(region -> new Region(region, catalogue.cities().stream()
                        .filter(c -> c.region().equals(region)).map(Catalogue.City::name).toList()))
                .toList();
    }

    @GetMapping("/species")
    @Operation(summary = "Tick species with Latin names")
    public List<Catalogue.Species> species() {
        return catalogue.species();
    }
}
