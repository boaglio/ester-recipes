package com.ester.recipes.service;

import com.ester.recipes.config.EsterProperties;
import com.ester.recipes.model.Recipe;
import com.ester.recipes.model.RecipeExtraction;
import tools.jackson.core.JacksonException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Sends a recipe image to the local Ollama vision model and gets back the
 * structured recipes it contains, already translated to Brazilian Portuguese.
 */
@Service
public class RecipeExtractionService {

    private static final Logger log = LoggerFactory.getLogger(RecipeExtractionService.class);

    private static final String PROMPT = """
            You are an expert at transcribing scanned cooking-recipe clippings.

            This image is a photo of one or more printed, typed or handwritten recipes, written EITHER
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
              you find as a separate entry, even one that only lists ingredients.
            - TITLE: use the recipe's OWN heading, the nearest one above its ingredients. When
              several recipes sit under one headline of a page or article, the headline is NOT
              their title: each recipe takes the smaller heading of its own section. Use the
              headline only for a recipe with no heading of its own. Never use a line about time,
              servings, a magazine or a page number as the title.
            - SEPARATE RECIPES: every ingredient and step must come from that recipe's own section.
              Never copy lines from a neighboring recipe. Text may run in columns, and a recipe
              may continue at the top of the next column.
            - Ingredients may be written in a running paragraph ("Ingredientes: a, b, c"); split
              them all the same.
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

    /**
     * Tokens the repeat penalty looks back on when retrying. Ollama's default of 64 is shorter than
     * a repeated ingredient with its JSON punctuation, so such a loop would never be penalized.
     */
    private static final int RETRY_REPEAT_WINDOW = 256;

    private final ChatClient chatClient;

    /** Parses the model's JSON into {@link RecipeExtraction} and provides its JSON schema. */
    private final BeanOutputConverter<RecipeExtraction> converter =
            new BeanOutputConverter<>(RecipeExtraction.class);

    /** The schema, handed to Ollama so decoding is constrained to exactly this structure. */
    private final Map<String, Object> responseSchema = converter.getJsonSchemaMap();

    /** Longest side of the image sent to the model, in pixels; 0 or less sends the original. */
    private final int maxImageSide;

    /** How many answers were cut off so far, by image, so that each retry can differ. */
    private final Map<String, Integer> cutOffAnswers = new ConcurrentHashMap<>();

    public RecipeExtractionService(ChatClient.Builder chatClientBuilder, EsterProperties properties) {
        this.chatClient = chatClientBuilder.build();
        this.maxImageSide = properties.maxImageSide();
    }

    /** The model answered properly and found no recipe, as on a notebook cover or a blank page. */
    public static class NoRecipeFoundException extends IllegalStateException {
        public NoRecipeFoundException(String fileName) {
            super("No recipes detected in " + fileName);
        }
    }

    /**
     * Extracts every recipe from a single image file.
     *
     * @param image path to a {@code .jpg}/{@code .jpeg}/{@code .png} recipe image
     * @return the recipes found, each tagged with the source image file name; never empty
     * @throws NoRecipeFoundException if the model answers that the image has no recipe
     * @throws IllegalStateException  if the answer can't be used, so the image stays pending
     */
    public List<Recipe> extract(Path image) {
        String fileName = image.getFileName().toString();
        log.info("Reading recipes from image: {}", fileName);

        RecipeExtraction extraction = readImage(image, fileName);

        if (extraction == null || extraction.recipes() == null) {
            throw new NoRecipeFoundException(fileName);
        }

        // Normalize the category onto the canonical taxonomy and stamp the source image
        // onto every recipe so the model can't get it wrong.
        List<Recipe> stamped = extraction.recipes().stream()
                .filter(RecipeExtractionService::hasContent)
                .map(r -> RecipeText.clean(new Recipe(
                        r.title(),
                        CategoryNormalizer.normalize(r.title(), r.ingredients(), r.category()),
                        r.originalLanguage(),
                        r.yield(),
                        r.date(),
                        r.ingredients(),
                        r.steps(),
                        fileName,
                        textBlock(r),  // verbatim original-language text
                        "")))          // translated is filled by the gemma4 translation phase
                .toList();
        if (stamped.isEmpty()) {
            throw new NoRecipeFoundException(fileName);
        }
        requireTitles(stamped, fileName);

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

    /**
     * Reads an image with another model, for comparing with the main reading. Nothing is stored
     * or cleaned here: the text is returned as the model wrote it.
     *
     * @throws IllegalStateException if the model finds no text
     */
    public String readText(Path image, String model) {
        String fileName = image.getFileName().toString();
        RecipeExtraction extraction = call(image, fileName, null, model);
        String text = extraction == null || extraction.recipes() == null ? ""
                : extraction.recipes().stream()
                        .filter(RecipeExtractionService::hasContent)
                        .map(RecipeExtractionService::textBlock)
                        .collect(Collectors.joining("\n"));
        if (text.isBlank()) {
            throw new IllegalStateException("No recipes detected in " + fileName + " by " + model);
        }
        return text;
    }

    /**
     * The book leaves out a recipe with no title, so accepting one would lose it silently. Failing
     * keeps the image pending, where the retry rounds and the end-of-run error will report it.
     */
    static void requireTitles(List<Recipe> recipes, String fileName) {
        long untitled = recipes.stream().filter(r -> r.title() == null || r.title().isBlank()).count();
        if (untitled > 0) {
            throw new IllegalStateException(untitled + " recipe(s) with no title in " + fileName);
        }
    }

    /**
     * Calls the model. An answer cut off mid-JSON means the model got stuck repeating itself until
     * the context was full, and at temperature 0 the same request often gets stuck the same way. So
     * each retry of such an image discourages repetition more strongly. That can also change words,
     * which the check report shows by comparing with the second reading.
     */
    private RecipeExtraction readImage(Path image, String fileName) {
        Double penalty = repeatPenalty(cutOffAnswers.getOrDefault(fileName, 0));
        if (penalty != null) {
            log.warn("Earlier answer for {} was cut off; retrying with repeat penalty {}.", fileName, penalty);
        }
        try {
            return call(image, fileName, penalty, null);
        } catch (RuntimeException e) {
            if (isCutOff(e)) {
                cutOffAnswers.merge(fileName, 1, Integer::sum);
            }
            throw e;
        }
    }

    /** The penalty after this many cut-off answers: null first (Ollama's default of 1.1), then rising. */
    static Double repeatPenalty(int cutOffAnswers) {
        return cutOffAnswers <= 0 ? null : Math.round((1.1 + 0.1 * cutOffAnswers) * 10) / 10.0;
    }

    /** One model call. Non-null {@code repeatPenalty} and {@code model} override the configured ones. */
    private RecipeExtraction call(Path image, String fileName, Double repeatPenalty, String model) {
        OllamaChatOptions.Builder options = OllamaChatOptions.builder()
                // JSON schema as Ollama's `format` -> grammar-constrained {recipes:[...]} output.
                .format(responseSchema)
                // Thinking models otherwise reason until the context is full and answer with no
                // text. Not all obey: qwen3-vl:4b still only "thinks" (Ollama 0.40), so it can't be used.
                .disableThinking();
        if (repeatPenalty != null) {
            options.repeatPenalty(repeatPenalty).repeatLastN(RETRY_REPEAT_WINDOW);
        }
        if (model != null) {
            options.model(model);
        }
        ModelImage modelImage = imageForModel(image, maxImageSide);
        return chatClient.prompt()
                .options(options)
                .user(user -> user
                        .text(PROMPT)
                        .media(modelImage.mimeType(), modelImage.content()))
                .call()
                .entity(converter);
    }

    /**
     * The image as the model should receive it. Full-size photos take most of the context and
     * the vision encoder can run out of GPU memory on them, so bigger ones are scaled down
     * (the file itself is untouched). An image that can't be decoded here is sent as it is.
     */
    static ModelImage imageForModel(Path image, int maxSide) {
        ModelImage original = new ModelImage(mimeTypeFor(image.getFileName().toString()),
                new FileSystemResource(image));
        if (maxSide <= 0) {
            return original;
        }
        try {
            BufferedImage source = ImageIO.read(image.toFile());
            if (source == null || Math.max(source.getWidth(), source.getHeight()) <= maxSide) {
                return original;
            }
            return new ModelImage(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(scaledPng(source, maxSide)));
        } catch (IOException e) {
            log.warn("Could not scale {} ({}); sending the original.", image.getFileName(), e.getMessage());
            return original;
        }
    }

    /** PNG because lossless: compression artifacts around small print hurt the reading. */
    private static byte[] scaledPng(BufferedImage source, int maxSide) throws IOException {
        double scale = (double) maxSide / Math.max(source.getWidth(), source.getHeight());
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.drawImage(source, 0, 0, width, height, Color.WHITE, null);
        graphics.dispose();
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(scaled, "png", png);
        return png.toByteArray();
    }

    record ModelImage(MimeType mimeType, Resource content) {
    }

    private static boolean hasContent(Recipe r) {
        return (r.title() != null && !r.title().isBlank())
                || (r.ingredients() != null && !r.ingredients().isEmpty())
                || (r.steps() != null && !r.steps().isEmpty());
    }

    private static boolean isCutOff(Throwable t) {
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (cause instanceof JacksonException) {
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
