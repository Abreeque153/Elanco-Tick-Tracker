package com.ticktracker.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

@Schema(description = "A tick sighting reported by a member of the public")
public record NewSightingRequest(
        @Schema(description = "When the tick was seen, UK local time", example = "2026-10-30T14:00:00")
        @NotNull
        LocalDateTime date,

        @Schema(description = "One of the supported UK cities (case-insensitive)", example = "London")
        @NotBlank
        @Size(max = 100)
        String city,

        @Schema(description = "Common or Latin name of a supported species", example = "Sheep tick")
        @NotBlank
        @Size(max = 100)
        String species) {
}
