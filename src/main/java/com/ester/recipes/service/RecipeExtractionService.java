package com.ester.recipes.service;

import com.ester.recipes.model.Recipe;
import com.ester.recipes.model.RecipeExtraction;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sends a recipe image to the local Ollama vision model and gets back the
 * structured recipes it contains, already translated to Brazilian Portuguese.
 */
@Service
public class RecipeExtractionService {

    private static final Logger log = LoggerFactory.getLogger(RecipeExtractionService.class);

    private static final String PROMPT = """
            You are an expert at transcribing scanned cooking-recipe clippings.

            This image is a photo of one or more printed/typed cooking recipes, written EITHER
            in Italian OR in Brazilian Portuguese.

            ===== FAITHFUL TRANSCRIPTION RULE =====
            Transcribe each recipe VERBATIM in its ORIGINAL language. Do NOT translate anything:
            keep Italian text in Italian and Portuguese text in Portuguese. The title,
            ingredients and steps must be exactly the words printed on the image. A separate
            step (not you) will translate later.
            =======================================

            Other instructions:
            - The image may be ROTATED (90, 180 or 270 degrees) or slightly skewed. Mentally
              rotate it so the text is upright and read ALL of it carefully.
            - A single image MAY CONTAIN MORE THAN ONE recipe. Return every distinct recipe
              you find as a separate entry.
            - Do NOT invent ingredients, quantities or steps, and do NOT duplicate steps. Only
              include what is actually written; keep quantities and measurements accurate. If a
              value is missing, leave it empty.
            - Record each recipe's original language in `originalLanguage`: 'it' for Italian,
              'pt' for Portuguese.
            - In the title, drop decorative symbols such as leading/trailing asterisks.
            - Split ingredients into one entry each, and split the preparation into ordered
              steps, one entry each.
            - DATE: if the clipping shows any date or year (a printed publication date, a
              handwritten date, or just a year), copy it into `date` EXACTLY as written
              (e.g. '1987', '12/03/1985', 'março de 1990'). This is the recipe/clipping's own
              date — never invent it and never use today's date. Leave `date` empty if none.
            - CATEGORY: choose EXACTLY ONE category, judging by WHAT THE FINISHED DISH IS, and
              copy it verbatim from this fixed list (do not invent new ones):
                Entradas e Petiscos, Saladas, Sopas e Caldos, Massas, Carnes, Aves,
                Peixes e Frutos do Mar, Acompanhamentos, Molhos e Temperos, Pães,
                Bolos e Tortas, Sobremesas, Bebidas, Doces e Conservas, Outras.
            - Leave `original` and `translated` empty — the application fills them.

            Return ONLY the recipes found in this specific image.
            """;

    /** Larger contexts tried, in order, when a dense page truncates at the default size.
     *  Kept small/empty on purpose: escalating to huge contexts on a runaway-looping page
     *  wastes many minutes per attempt without ever fixing it (the output cap bounds it). */
    private static final int[] RETRY_CONTEXTS = {};

    private final ChatClient chatClient;

    /** Parses the model's JSON into {@link RecipeExtraction} and provides its JSON schema. */
    private final BeanOutputConverter<RecipeExtraction> converter =
            new BeanOutputConverter<>(RecipeExtraction.class);

    /** The schema, handed to Ollama so decoding is constrained to exactly this structure. */
    private final Map<String, Object> responseSchema = converter.getJsonSchemaMap();

    public RecipeExtractionService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * Extracts every recipe from a single image file.
     *
     * @param image path to a {@code .jpg}/{@code .jpeg}/{@code .png} recipe image
     * @return the recipes found, each tagged with the source image file name; never null
     */
    public List<Recipe> extract(Path image) {
        String fileName = image.getFileName().toString();
        log.info("Reading recipes from image: {}", fileName);

        RecipeExtraction extraction = callWithRetry(image, fileName);

        if (extraction == null || extraction.recipes() == null || extraction.recipes().isEmpty()) {
            log.warn("No recipes detected in {}", fileName);
            return List.of();
        }

        // Normalize the category onto the canonical taxonomy and stamp the source image
        // onto every recipe so the model can't get it wrong.
        List<Recipe> stamped = extraction.recipes().stream()
                .map(r -> new Recipe(
                        r.title(),
                        CategoryNormalizer.normalize(r.title(), r.ingredients(), r.category()),
                        r.originalLanguage(),
                        r.yield(),
                        r.date(),
                        r.ingredients(),
                        r.steps(),
                        fileName,
                        textBlock(r),  // verbatim original-language text
                        ""))           // translated is filled by the gemma4 translation phase
                .toList();

        // A runaway model sometimes repeats the same recipe many times in one image's output;
        // collapse those so we don't write dozens of identical JSON files.
        List<Recipe> unique = dedupeWithinImage(stamped);
        if (unique.size() < stamped.size()) {
            log.info("Collapsed {} repeated recipe(s) from {} (model repetition).",
                    stamped.size() - unique.size(), fileName);
        }

        log.info("Found {} recipe(s) in {}: {}",
                unique.size(), fileName, unique.stream().map(Recipe::title).toList());
        return unique;
    }

    /** Calls the model; on a truncated-JSON failure, retries with progressively larger context. */
    private RecipeExtraction callWithRetry(Path image, String fileName) {
        try {
            return call(image, fileName, null);
        } catch (RuntimeException first) {
            if (!isTruncatedJson(first)) {
                throw first;
            }
            for (int ctx : RETRY_CONTEXTS) {
                log.warn("Output for {} was truncated — retrying with num_ctx={}.", fileName, ctx);
                try {
                    return call(image, fileName, ctx);
                } catch (RuntimeException retry) {
                    if (!isTruncatedJson(retry)) {
                        throw retry;
                    }
                }
            }
            throw first; // exhausted retries — let the pipeline log it and try again next round
        }
    }

    /** One model call. {@code numCtx} overrides the configured context when non-null. */
    private RecipeExtraction call(Path image, String fileName, Integer numCtx) {
        OllamaChatOptions.Builder options = OllamaChatOptions.builder()
                // JSON schema as Ollama's `format` -> grammar-constrained {recipes:[...]} output.
                .format(responseSchema);
        if (numCtx != null) {
            options.numCtx(numCtx);
        }
        return chatClient.prompt()
                .options(options.build())
                .user(user -> user
                        .text(PROMPT)
                        .media(mimeTypeFor(fileName), new FileSystemResource(image)))
                .call()
                .entity(converter);
    }

    private static boolean isTruncatedJson(Throwable t) {
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (cause instanceof JsonProcessingException) {
                return true;
            }
        }
        return false;
    }

    /** Keeps one recipe per unique title+ingredients signature, preserving order. */
    private static List<Recipe> dedupeWithinImage(List<Recipe> recipes) {
        Map<String, Recipe> unique = new LinkedHashMap<>();
        for (Recipe recipe : recipes) {
            unique.putIfAbsent(signature(recipe), recipe);
        }
        return new ArrayList<>(unique.values());
    }

    /** Joins title + ingredients + steps into one text block (used for the verbatim original). */
    static String textBlock(Recipe r) {
        StringBuilder sb = new StringBuilder();
        if (r.title() != null && !r.title().isBlank()) {
            sb.append(r.title()).append('\n');
        }
        if (r.ingredients() != null) {
            r.ingredients().forEach(i -> sb.append(i).append('\n'));
        }
        if (r.steps() != null) {
            r.steps().forEach(s -> sb.append(s).append('\n'));
        }
        return sb.toString().trim();
    }

    private static String signature(Recipe recipe) {
        String title = recipe.title() == null ? "" : recipe.title().trim().toLowerCase(Locale.ROOT);
        String ingredients = recipe.ingredients() == null ? ""
                : String.join("|", recipe.ingredients()).toLowerCase(Locale.ROOT);
        return title + "##" + ingredients;
    }

    private static MimeType mimeTypeFor(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return MimeTypeUtils.IMAGE_PNG;
        }
        // jpg / jpeg and anything else we treat as JPEG.
        return MimeTypeUtils.IMAGE_JPEG;
    }
}
