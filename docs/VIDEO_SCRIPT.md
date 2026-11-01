# Video walkthrough script (≈ 9½ minutes)

**Set-up before recording**
1. `rm -f data/ticks.db*` for a fresh start, then `./mvnw spring-boot:run`.
2. Open http://localhost:8080 (Swagger UI) in a browser and have the project open in your IDE.
3. Have a terminal ready in the project folder (for `docs/demo.sh` / curl).
4. Use a large font in the terminal and IDE (most viewers watch at 1080p or lower).

---

### 0:00–0:45 · Introduction
> "Hi, I'm Abreeque Rauf Sahi, a first-year Computer Science student at Brunel. This is my backend
> for a UK tick-sightings tracker, built in Java with Spring Boot and SQLite. It pulls data
> from Elanco's API, cleans and de-duplicates it, stores it, and serves search, statistics and
> insights through a REST API that keeps working even when the upstream API doesn't."

### 0:45–2:15 · What I found and the architecture
Show the **architecture diagram** in the README.
> "Before writing code I profiled the API. It returns all 1,000 records in a single array with no
> pagination; its city filter is case-sensitive, so 'london' returns nothing; it documents 429
> rate limits and 500 errors; and under load the response time went up to about five seconds."
>
> "So the key decision was: don't proxy the API, ingest it. A background sync streams the data in,
> cleans it, and stores it in SQLite. Every user request is answered from the local database, so
> it's fast, and an upstream outage only delays the next refresh."

Point at the packages: `ingest`, `store`, `analytics`, `api`.
> "The core logic is plain Java. Spring is only at the edges, which is why I can unit-test the
> pipeline without starting a server."

### 2:15–3:30 · Data handling (show code briefly)
Open `SightingCleaner` and scroll through the rules.
> "Each record is validated. Anything that can be safely fixed is repaired: casing, extra spaces,
> 'Newcastle upon Tyne' to 'Newcastle', UK date formats, a missing Latin name filled from the
> species. Anything that can't be trusted is rejected with a reason: no date, an impossible date like
> 31st February, a species that contradicts its Latin name."

Open `SyncService` and highlight the de-duplication block.
> "Duplicates are caught two ways: the same id twice, or the same time, city and species under a
> different id, even against records already stored. Records are streamed and written 500 at a
> time, so memory stays flat for big feeds and a broken connection keeps the work already done."

### 3:30–4:45 · Live demo: the cleaning pipeline
In Swagger UI: **POST /api/v1/sync/import**, `dryRun=true`, paste `docs/demo-dirty-batch.json`.
> "The real data happened to be clean, so here's a deliberately messy batch: 14 records."
Point at `inserted: 4`, `rejectedByReason` (each reason once), `repairs`, and one
`rejectionSamples` entry, especially `DUPLICATE_CONTENT` matching a real stored record.
> "Dry run means nothing is saved. It's a safe way to check a new data source."

### 4:45–6:30 · Live demo: search, filtering and reporting
1. `GET /sightings?city=london&from=2020-01-01&size=3`: "case-insensitive, time range, paged. Each
   result includes coordinates for a map. Note the `X-Data-Last-Synced` header."
2. `GET /sightings?lat=51.546&lon=-0.479&radiusKm=50`: "location search: ticks within 50 km of
   Uxbridge."
3. `GET /stats/by-region`: "sightings per region, with percentages."
4. `GET /stats/trends?interval=month&from=2024-01&to=2024-12`, then `interval=week`: "zero-filled
   so it can be charted straight away; weekly uses proper ISO weeks."
5. `GET /stats/trends?interval=year&splitBy=species`: "one series per species."

### 6:30–7:45 · Insights extension
`GET /api/v1/insights`
> "For the AI/ML extension I chose explainable statistics, because with about one sighting per city
> per month a trained model would mostly learn noise, and a public-health tool shouldn't cry wolf."
Walk through `seasonality.interpretation`, `trend.interpretation`, `anomalies.interpretation`,
`riskAreas[0]`, `forecast`.
> "On this data it correctly reports no real seasonality, trend or spikes. The data looks
> uniformly random. My tests prove the same code does detect genuine patterns in synthetic data."

### 7:45–8:45 · Error handling
1. `GET /sightings?from=2024-06-01&to=2024-01-01`: show the problem-details 400.
2. `POST /sightings` with a missing city: field-level validation errors. Then a valid report → 201.
3. Open `ElancoApiClientTest` and run it in the IDE (or show the list of test names).
> "Upstream failures are handled with timeouts, retries with exponential backoff, honouring
> Retry-After on rate limits, and a circuit breaker. These tests use a fake server to simulate
> 500s, 429s, timeouts and broken streams, which you can't reliably trigger on the real API."
Optionally show `GET /sync/status` and `/actuator/health`.

### 8:45–9:30 · Wrap-up
> "With more time I'd add incremental sync, PostgreSQL with PostGIS for proper geospatial queries,
> authentication on the write endpoints, and a map frontend. Everything's in the README, including
> how to run it with a single command. Thanks for watching."

---
**Recording tips:** record with QuickTime (File → New Screen Recording) or OBS; upload to YouTube as
**Unlisted**; put the link in the README and your email.
