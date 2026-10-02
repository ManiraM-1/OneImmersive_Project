package com.maniram.textto3d;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Calls OpenAI's open-source Shap-E text-to-3D model, hosted for free on a
 * Hugging Face Space, through the Space's Gradio REST API.
 *
 * Gradio flow:
 *   1. POST {space}/gradio_api/call/{api}  {"data":[...inputs]}  -> {"event_id": "..."}
 *   2. GET  {space}/gradio_api/call/{api}/{event_id}  -> Server-Sent Events stream,
 *      ending with "event: complete" + "data: [<output file>]"
 */
@Component
public class ShapEClient {

    private final RestClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String spaceUrl;
    private final String apiName;

    public ShapEClient(@Value("${hf.space-url}") String spaceUrl,
                       @Value("${hf.api-name}") String apiName,
                       @Value("${hf.token:}") String token) {
        this.spaceUrl = spaceUrl.replaceAll("/$", "");
        this.apiName = apiName;
        RestClient.Builder builder = RestClient.builder();
        // A (free) Hugging Face token gives a bigger GPU quota than anonymous calls
        if (token != null && !token.isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + token);
        }
        this.http = builder.build();
    }

    /** Runs a generation and blocks until it finishes. Returns the URL of the generated .glb. */
    public String generate(String prompt) {
        int seed = ThreadLocalRandom.current().nextInt(0, 1_000_000);
        // inputs in the Space's order: prompt, seed, guidance_scale, num_inference_steps
        Map<String, Object> body = Map.of("data", List.of(prompt, seed, 15.0, 64));

        // Gradio 5 uses /gradio_api/call/..., Gradio 4 uses /call/... - try both
        String base = spaceUrl + "/gradio_api/call/" + apiName;
        JsonNode start;
        try {
            start = post(base, body);
        } catch (HttpClientErrorException.NotFound e) {
            base = spaceUrl + "/call/" + apiName;
            start = post(base, body);
        }

        String eventId = start.path("event_id").asText(null);
        if (eventId == null) {
            throw new GenerationException("Model service did not accept the request: " + start);
        }

        String stream;
        try {
            stream = http.get()
                    .uri(base + "/" + eventId)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw new GenerationException("Model service error " + e.getStatusCode().value());
        }
        return parseResult(stream);
    }

    /** Downloads the generated file. */
    public byte[] download(String url) {
        try {
            return http.get().uri(URI.create(url)).retrieve().body(byte[].class);
        } catch (RestClientResponseException e) {
            throw new GenerationException("Could not download the model (" + e.getStatusCode().value() + ")");
        }
    }

    private JsonNode post(String url, Map<String, Object> body) {
        try {
            return http.post().uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw e; // let caller retry with the other path
        } catch (RestClientResponseException e) {
            throw new GenerationException("Model service error " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString());
        }
    }

    /** Reads the SSE text and pulls the file URL out of the final "complete" event. */
    private String parseResult(String stream) {
        if (stream == null) throw new GenerationException("Empty response from model service");
        String event = null;
        String data = null;
        for (String line : stream.split("\\R")) {
            if (line.startsWith("event:")) event = line.substring(6).trim();
            else if (line.startsWith("data:")) data = line.substring(5).trim();
            if ("error".equals(event)) {
                throw new GenerationException("The free GPU is busy or its quota is used up. Please try again in a minute.");
            }
        }
        if (!"complete".equals(event) || data == null) {
            throw new GenerationException("Generation did not complete");
        }
        try {
            JsonNode output = mapper.readTree(data).path(0);
            if (output.isTextual()) return output.asText();
            if (output.path("url").isTextual()) return output.path("url").asText();
            if (output.path("path").isTextual()) {
                return spaceUrl + "/gradio_api/file=" + output.path("path").asText();
            }
        } catch (Exception ignored) {
            // fall through
        }
        throw new GenerationException("Could not read the model file from the response");
    }

    public static class GenerationException extends RuntimeException {
        public GenerationException(String message) { super(message); }
    }
}
