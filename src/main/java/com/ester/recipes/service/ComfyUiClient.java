package com.ester.recipes.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Minimal client for a local <a href="https://github.com/comfyanonymous/ComfyUI">ComfyUI</a>
 * server. Submits a text-to-image workflow, waits for it to finish, and returns the PNG bytes.
 *
 * <p>Uses the JDK HTTP client only — no extra dependency. The workflow graph is loaded from
 * {@code comfyui-workflow.json} and its checkpoint / prompts / size are filled in per request.</p>
 */
@Component
class ComfyUiClient {

    /** Node ids in comfyui-workflow.json. */
    private static final String NODE_CHECKPOINT = "4";
    private static final String NODE_POSITIVE = "6";
    private static final String NODE_NEGATIVE = "7";
    private static final String NODE_SAMPLER = "3";
    private static final String NODE_LATENT = "5";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final JsonNode workflowTemplate;

    ComfyUiClient() {
        try (InputStream in = new ClassPathResource("comfyui-workflow.json").getInputStream()) {
            this.workflowTemplate = mapper.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load comfyui-workflow.json", e);
        }
    }

    /** Parameters for one image. */
    record Request(String baseUrl, String checkpoint, String positive, String negative,
                   long seed, int steps, int width, int height, Duration timeout) {
    }

    /**
     * Generates a single image and returns its PNG bytes.
     *
     * @throws IOException          on transport/HTTP errors or a workflow rejection
     * @throws InterruptedException if interrupted while polling
     */
    byte[] generate(Request request) throws IOException, InterruptedException {
        ObjectNode workflow = workflowTemplate.deepCopy();
        inputs(workflow, NODE_CHECKPOINT).put("ckpt_name", request.checkpoint());
        inputs(workflow, NODE_POSITIVE).put("text", request.positive());
        inputs(workflow, NODE_NEGATIVE).put("text", request.negative());
        ObjectNode sampler = inputs(workflow, NODE_SAMPLER);
        sampler.put("seed", request.seed());
        sampler.put("steps", request.steps());
        ObjectNode latent = inputs(workflow, NODE_LATENT);
        latent.put("width", request.width());
        latent.put("height", request.height());

        ObjectNode body = mapper.createObjectNode();
        body.set("prompt", workflow);
        body.put("client_id", UUID.randomUUID().toString());

        String promptId = submit(request.baseUrl(), body);
        JsonNode imageRef = awaitImage(request.baseUrl(), promptId, request.timeout());
        return download(request.baseUrl(), imageRef);
    }

    /** POST /prompt -> prompt_id. */
    private String submit(String baseUrl, ObjectNode body) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/prompt"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IOException("ComfyUI /prompt returned " + res.statusCode() + ": " + res.body());
        }
        JsonNode json = mapper.readTree(res.body());
        JsonNode errors = json.get("node_errors");
        if (errors != null && errors.size() > 0) {
            throw new IOException("ComfyUI rejected the workflow: " + errors);
        }
        String id = json.path("prompt_id").asText(null);
        if (id == null) {
            throw new IOException("ComfyUI /prompt did not return a prompt_id: " + res.body());
        }
        return id;
    }

    /** Poll GET /history/{id} until the run finishes, then return the first output image node. */
    private JsonNode awaitImage(String baseUrl, String promptId, Duration timeout)
            throws IOException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/history/" + promptId))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200) {
                JsonNode entry = mapper.readTree(res.body()).get(promptId);
                if (entry != null) {
                    JsonNode image = firstImage(entry.path("outputs"));
                    if (image != null) {
                        return image;
                    }
                }
            }
            Thread.sleep(1500);
        }
        throw new IOException("Timed out waiting for ComfyUI to render prompt " + promptId);
    }

    /** Finds the first {@code images[0]} across all output nodes. */
    private JsonNode firstImage(JsonNode outputs) {
        for (Iterator<Map.Entry<String, JsonNode>> it = outputs.fields(); it.hasNext(); ) {
            JsonNode images = it.next().getValue().path("images");
            if (images.isArray() && !images.isEmpty()) {
                return images.get(0);
            }
        }
        return null;
    }

    /** GET /view?filename=...&subfolder=...&type=... -> PNG bytes. */
    private byte[] download(String baseUrl, JsonNode imageRef) throws IOException, InterruptedException {
        String url = baseUrl + "/view"
                + "?filename=" + enc(imageRef.path("filename").asText())
                + "&subfolder=" + enc(imageRef.path("subfolder").asText(""))
                + "&type=" + enc(imageRef.path("type").asText("output"));
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() != 200) {
            throw new IOException("ComfyUI /view returned " + res.statusCode());
        }
        return res.body();
    }

    private static ObjectNode inputs(ObjectNode workflow, String nodeId) {
        return (ObjectNode) workflow.path(nodeId).path("inputs");
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
