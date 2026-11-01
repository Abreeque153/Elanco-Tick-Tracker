#!/bin/sh
# Walks through the API with curl. Start the app first: ./mvnw spring-boot:run
# Usage: sh docs/demo.sh
BASE=${BASE:-http://localhost:8080/api/v1}
DOCS_DIR=$(cd "$(dirname "$0")" && pwd)

step() { printf '\n\033[1m== %s\033[0m\n' "$1"; }

step "1. Is the data fresh? (pipeline status)"
curl -s "$BASE/sync/status"

step "2. Dataset overview"
curl -s "$BASE/stats/summary"

step "3. Search: London, case-insensitive, 2020 onwards, 3 per page (note the X-Data-* headers)"
curl -s -D - "$BASE/sightings?city=london&from=2020-01-01&size=3" | sed -n '/^X-Data/p;/^{/,$p'

step "4. Location search: sightings within 50 km of Uxbridge"
curl -s "$BASE/sightings?lat=51.546&lon=-0.479&radiusKm=50&size=2"

step "5. Sightings per region"
curl -s "$BASE/stats/by-region"

step "6. Monthly trend for 2024"
curl -s "$BASE/stats/trends?interval=month&from=2024-01&to=2024-12"

step "7. Insights: seasonality, trend, risk areas, anomalies, forecast"
curl -s "$BASE/insights?forecastMonths=3"

step "8. Data cleaning demo: dry-run import of a deliberately messy batch"
curl -s -X POST "$BASE/sync/import?dryRun=true" -H "Content-Type: application/json" \
  --data-binary "@$DOCS_DIR/demo-dirty-batch.json"

step "9. Error handling: invalid input returns a problem-details 400"
curl -s "$BASE/sightings?from=2024-06-01&to=2024-01-01"

step "10. Report a new sighting (201 Created)"
curl -s -i -X POST "$BASE/sightings" -H "Content-Type: application/json" \
  -d '{"date":"2026-10-30T14:00:00","city":"London","species":"Sheep tick"}' | sed -n '/^HTTP/p;/^Location/p;/^{/,$p'
echo
