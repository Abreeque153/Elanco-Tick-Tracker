package com.ticktracker.ingest;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.Consumer;

/**
 * Reads a top-level JSON array one element at a time.
 *
 * <p>The Elanco API returns every sighting in one array with no pagination.
 * Streaming the elements keeps memory flat however large that array grows:
 * only the current element is in memory, not the whole payload.
 */
public final class JsonArrayReader {

    private final ObjectMapper mapper;

    public JsonArrayReader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Passes each array element to {@code sink} and returns the number read.
     *
     * @throws UpstreamException MALFORMED_RESPONSE if the body is not a JSON
     *                           array or is cut off part-way
     */
    public long forEach(InputStream body, Consumer<JsonNode> sink) throws IOException {
        long count = 0;
        try (JsonParser parser = mapper.getFactory().createParser(body)) {
            JsonToken first = parser.nextToken();
            if (first != JsonToken.START_ARRAY) {
                throw new UpstreamException(UpstreamException.Kind.MALFORMED_RESPONSE,
                        "Expected a JSON array but the response started with " + (first == null ? "nothing" : first));
            }
            while (true) {
                JsonToken token = parser.nextToken();
                if (token == JsonToken.END_ARRAY) {
                    return count;
                }
                if (token == null) {
                    throw new UpstreamException(UpstreamException.Kind.MALFORMED_RESPONSE,
                            "Response ended before the JSON array was closed (after " + count + " records)");
                }
                JsonNode element = mapper.readTree(parser);
                sink.accept(element);
                count++;
            }
        } catch (JsonProcessingException e) {
            throw new UpstreamException(UpstreamException.Kind.MALFORMED_RESPONSE,
                    "Invalid JSON after " + count + " records: " + e.getOriginalMessage(), e);
        }
    }
}
