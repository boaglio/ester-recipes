package com.ester.recipes.service;

import com.ester.recipes.config.EsterProperties;
import com.ester.recipes.model.Recipe;
import com.ester.recipes.model.StoredRecipe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Optional second phase: turns each stored recipe into an anime-style image via a local
 * ComfyUI server, saved as {@code <stem>.png} next to the recipes. Skips any recipe that
 * already has an image, so it is fully resumable and never re-renders.
 *
 * <p>Runs only when {@code ester.images.enabled=true}; it is decoupled from extraction so it
 * can be a separate pass that doesn't compete with the vision model for the GPU.</p>
 */
@Service
public class ImageGenerationService {

    private static final Logger log = LoggerFactory.getLogger(ImageGenerationService.class);

    private final ComfyUiClient comfyUiClient;
    private final EsterProperties properties;

    public ImageGenerationService(ComfyUiClient comfyUiClient, EsterProperties properties) {
        this.comfyUiClient = comfyUiClient;
        this.properties = properties;
    }

    /** Generates images for every recipe that doesn't have one yet, into {@code imageDir}. */
    public void generateMissing(List<StoredRecipe> recipes, Path imageDir) {
        EsterProperties.ImageGen cfg = properties.images();
        try {
            Files.createDirectories(imageDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create image directory: " + imageDir, e);
        }

        int total = recipes.size();
        int done = 0;
        int generated = 0;
        int failed = 0;
        for (StoredRecipe stored : recipes) {
            done++;
            Path target = stored.imageFile(imageDir);
            if (Files.exists(target)) {
                continue;
            }
            long start = System.nanoTime();
            try {
                byte[] png = comfyUiClient.generate(buildRequest(stored, cfg));
                Files.write(target, png);
                generated++;
                log.info("Generated image {} in {} — {}/{}",
                        target.getFileName(), humanDuration(System.nanoTime() - start), done, total);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted during image generation.");
                return;
            } catch (Exception e) {
                failed++;
                log.error("Failed to generate image for '{}': {}", stored.recipe().title(), e.getMessage());
            }
        }
        log.info("Image phase done: {} generated, {} failed, {} already existed.",
                generated, failed, total - generated - failed);
    }

    /** File name of the book's cover image (a bigger anime spread of all food categories). */
    public static final String COVER_NAME = "_cover.png";

    /** Generates the cover: one bigger anime image showing all the book's food categories. */
    public void generateCover(Path imageDir) {
        EsterProperties.ImageGen cfg = properties.images();
        Path target = imageDir.resolve(COVER_NAME);
        if (Files.exists(target)) {
            return;
        }
        try {
            Files.createDirectories(imageDir);
            long start = System.nanoTime();
            // Taller than a recipe image (it's a cover), and a fixed seed for reproducibility.
            ComfyUiClient.Request request = new ComfyUiClient.Request(
                    cfg.baseUrl(),
                    cfg.checkpoint(),
                    COVER_PROMPT,
                    COVER_NEGATIVE, // the cover deliberately includes a person (the nonna)
                    42L,
                    cfg.steps(),
                    768,
                    1024,
                    Duration.ofSeconds(Math.max(60, cfg.timeoutSeconds() * 2L)));
            byte[] png = comfyUiClient.generate(request);
            Files.write(target, png);
            log.info("Generated cover image {} in {}",
                    target.getFileName(), humanDuration(System.nanoTime() - start));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Failed to generate cover image: {}", e.getMessage());
        }
    }

    private static final String COVER_PROMPT =
            "masterpiece, best quality, anime style cookbook cover illustration, a kind smiling "
            + "elderly Italian grandmother (nonna) with grey hair and an apron, standing behind "
            + "a big rustic table full of assorted food covering every category: cakes and pies, "
            + "desserts, fruit drinks, pasta, roasted meats, fish, fresh salads, breads, jams and "
            + "preserves, soups, appetizers, all together, vibrant colors, warm cozy lighting, "
            + "highly detailed, no text";

    private static final String COVER_NEGATIVE =
            "lowres, blurry, text, watermark, signature, deformed, extra fingers, ugly, "
            + "multiple people, crowd";

    private ComfyUiClient.Request buildRequest(StoredRecipe stored, EsterProperties.ImageGen cfg) {
        Recipe recipe = stored.recipe();
        // Stable per-recipe seed so re-runs reproduce the same image.
        long seed = Integer.toUnsignedLong(stored.stem().hashCode());
        return new ComfyUiClient.Request(
                cfg.baseUrl(),
                cfg.checkpoint(),
                buildPositivePrompt(recipe),
                cfg.negativePrompt() == null ? "" : cfg.negativePrompt(),
                seed,
                cfg.steps(),
                cfg.width(),
                cfg.height(),
                Duration.ofSeconds(Math.max(30, cfg.timeoutSeconds())));
    }

    /** Builds an anime food-illustration prompt from the recipe's title and a few ingredients. */
    static String buildPositivePrompt(Recipe recipe) {
        String title = recipe.title() == null ? "" : recipe.title().trim();
        String mainIngredients = "";
        if (recipe.ingredients() != null && !recipe.ingredients().isEmpty()) {
            mainIngredients = recipe.ingredients().stream()
                    .limit(4)
                    .map(ImageGenerationService::simplifyIngredient)
                    .filter(s -> !s.isBlank())
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
        }
        StringBuilder prompt = new StringBuilder(
                "masterpiece, best quality, anime style food still life, only food, no characters");
        if (!title.isBlank()) {
            prompt.append(", \"").append(title).append('"');
        }
        if (!mainIngredients.isBlank()) {
            prompt.append(", made with ").append(mainIngredients);
        }
        prompt.append(", plated dish on a table, food photography, appetizing, vibrant colors, "
                + "soft studio lighting, highly detailed, no people, empty scene");
        return prompt.toString();
    }

    /** Drops quantities/measures so the prompt sees the ingredient, not "300g de". */
    private static String simplifyIngredient(String ingredient) {
        if (ingredient == null) {
            return "";
        }
        return ingredient
                .replaceAll("(?i)\\b\\d+([.,/]\\d+)?\\s*(g|kg|ml|l|colher(es)?|x[ií]cara(s)?|copo(s)?|"
                        + "lata(s)?|pitada(s)?|tablete(s)?|porç(ão|ões)|gema(s)?|ovo(s)?)?\\b", " ")
                .replaceAll("(?i)\\b(de|da|do|das|dos|para|com|e)\\b", " ")
                .replaceAll("[^\\p{L} ]+", " ")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static String humanDuration(long nanos) {
        double seconds = nanos / 1_000_000_000.0;
        if (seconds >= 60) {
            long minutes = (long) (seconds / 60);
            return String.format(Locale.ROOT, "%dm %02.0fs", minutes, seconds - minutes * 60);
        }
        return String.format(Locale.ROOT, "%.1fs", seconds);
    }
}
