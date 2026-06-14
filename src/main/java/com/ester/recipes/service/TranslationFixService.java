package com.ester.recipes.service;

import com.ester.recipes.config.EsterProperties;
import com.ester.recipes.model.Recipe;
import com.ester.recipes.model.StoredRecipe;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Second routine (agents.md, "# Processed JSON"): the stored recipes must be in Brazilian
 * Portuguese. Some came out of extraction with leftover Italian text (titles like
 * "Maccheroni alla Chitarra", ingredients, steps). This phase finds those and translates
 * them to PT-BR in place, leaving proper names untouched.
 *
 * <p>Opt-in via {@code ester.translation.enabled=true}. Only recipes that look non-Portuguese
 * are sent to the model, so it doesn't re-touch the hundreds already in good Portuguese.</p>
 */
@Service
public class TranslationFixService {

    private static final Logger log = LoggerFactory.getLogger(TranslationFixService.class);

    private static final String PROMPT = """
            The recipe JSON below is partly in Italian. Rewrite EVERY field — including the
            TITLE — entirely in BRAZILIAN PORTUGUESE. No Italian word may remain anywhere.

            Translate Italian cooking words even when they look like a name. Examples:
              bibita -> bebida; pollo -> frango; gallina -> galinha; insalata -> salada;
              zuppa -> sopa; minestra -> sopa; maccheroni -> macarrão; pasta -> massa;
              asciutta -> seca; frittata -> omelete; uova -> ovos; prosciutto -> presunto;
              formaggio -> queijo; burro -> manteiga; zucchero -> açúcar; farina -> farinha;
              latte -> leite; panna -> creme de leite; pomodoro -> tomate; cipolla -> cebola;
              aglio -> alho; avanzi -> sobras; albicocca/albicocco -> damasco;
              granchi -> caranguejo; pesce -> peixe; vitello -> vitela; maiale -> porco;
              "di" -> "de"; "con" -> "com"; "al/allo/alla" -> "ao/à".
            For "alla <Place>" keep the place and just translate the connector, e.g.
              "alla Siciliana" -> "à Siciliana"; "alla Parigina" -> "à Parigina";
              "alla Bolognese" -> "à Bolonhesa". Do NOT invent words like "do diabo" unless
              the original literally says "diavola".

            Keep ONLY true proper names: a person's surname, or a place adjective like
            "Siciliana", "Parigina", "Bolonhesa". Everything else must be Portuguese.

            Do NOT add, remove, merge or reorder ingredients or steps — translate them only,
            and keep every quantity and measurement exactly as given. Return the corrected recipe.

            Recipe JSON:
            """;

    /**
     * Strong Italian markers with little overlap with Portuguese. If any appears as a whole
     * word, the recipe is treated as not-yet-Portuguese and sent to the translator.
     */
    // Italian-only markers. Words identical in Portuguese (e.g. "carne", "sal", "molho")
    // are deliberately excluded to avoid flagging Portuguese recipes as false positives.
    private static final Set<String> ITALIAN_MARKERS = Set.of(
            "di", "alla", "allo", "alle", "della", "dello", "delle", "dei", "degli", "agli",
            "col", "coi", "con", "gli", "uova", "uovo", "pollo", "prosciutto", "formaggio",
            "zucchero", "burro", "maccheroni", "insalata", "frittata", "spuma", "bibita",
            "zuppa", "avanzi", "pirofila", "chitarra", "diavola", "granchi", "rondine",
            "triangolini", "brodo", "sugo", "pomodoro", "cipolla", "aglio", "ricotta",
            "scaloppine", "cannelloni", "tagliatelle", "parmigiano", "besciamella", "panna",
            "latte", "vino", "acqua", "olio", "farina", "pesce", "dolce", "verdure",
            "minestra", "contorno", "antipasto", "scampi", "baccala", "coppa", "spaghetti");

    private final ChatClient chatClient;
    private final EsterProperties properties;
    private final RecipeJsonStore jsonStore;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BeanOutputConverter<Recipe> converter = new BeanOutputConverter<>(Recipe.class);
    private final Map<String, Object> responseSchema = converter.getJsonSchemaMap();

    public TranslationFixService(ChatClient.Builder chatClientBuilder,
                                 EsterProperties properties,
                                 RecipeJsonStore jsonStore) {
        this.chatClient = chatClientBuilder.build();
        this.properties = properties;
        this.jsonStore = jsonStore;
    }

    /**
     * Ensures every recipe is in Brazilian Portuguese and has its {@code translated} block set.
     * Non-Portuguese recipes are translated with the configured model; already-Portuguese ones
     * just get {@code translated} copied from {@code original} (no model call). Idempotent:
     * recipes already carrying a translation are skipped.
     */
    public void fixAll(List<StoredRecipe> recipes, Path jsonDir) {
        String model = properties.translation() == null ? null : properties.translation().model();
        int translatedCount = 0;
        int copiedCount = 0;
        int failed = 0;
        for (StoredRecipe stored : recipes) {
            Recipe recipe = stored.recipe();
            Path file = jsonDir.resolve(stored.stem() + ".json");
            // Idempotent: a recipe that already has its translated block is left untouched, so
            // re-runs only handle newly-extracted recipes (no costly re-translation).
            if (recipe.translated() != null && !recipe.translated().isBlank()) {
                continue;
            }
            if (needsTranslation(recipe)) {
                try {
                    Recipe merged = merge(recipe, translate(recipe, model));
                    jsonStore.overwrite(merged, file);
                    translatedCount++;
                    log.info("Translated '{}' -> '{}'", recipe.title(), merged.title());
                } catch (Exception e) {
                    failed++;
                    log.error("Failed to translate '{}': {}", recipe.title(), e.getMessage());
                }
            } else if (recipe.translated() == null || recipe.translated().isBlank()) {
                // Already Portuguese — record the translated block without a model call.
                jsonStore.overwrite(withTranslated(recipe, textBlock(recipe)), file);
                copiedCount++;
            }
        }
        log.info("Translation phase done: {} translated, {} already-Portuguese, {} failed.",
                translatedCount, copiedCount, failed);
    }

    /** Returns a copy of the recipe with re-normalized category and the given translated block. */
    private static Recipe withTranslated(Recipe r, String translated) {
        return new Recipe(r.title(),
                CategoryNormalizer.normalize(r.title(), r.ingredients(), r.category()),
                r.originalLanguage(), r.yield(), r.date(), r.ingredients(), r.steps(),
                r.sourceImage(), r.original(), translated);
    }

    /** Joins title + ingredients + steps into one block. */
    private static String textBlock(Recipe r) {
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

    private Recipe translate(Recipe recipe, String model) throws Exception {
        String json = objectMapper.writeValueAsString(recipe);
        OllamaChatOptions.Builder options = OllamaChatOptions.builder().format(responseSchema);
        if (model != null && !model.isBlank()) {
            options.model(model);
        }
        return chatClient.prompt()
                .options(options.build())
                .user(PROMPT + json)
                .call()
                .entity(converter);
    }

    /** Builds the final PT recipe: the model's translation, with authoritative fields preserved,
     *  the verbatim {@code original} kept, and the {@code translated} block recorded. */
    private Recipe merge(Recipe original, Recipe translated) {
        String title = nonBlank(translated.title(), original.title());
        List<String> ingredients = emptyToOriginal(translated.ingredients(), original.ingredients());
        List<String> steps = emptyToOriginal(translated.steps(), original.steps());
        String yield = translated.yield() == null ? original.yield() : translated.yield();
        Recipe pt = new Recipe(title,
                CategoryNormalizer.normalize(title, ingredients, translated.category()),
                original.originalLanguage(), yield, original.date(), ingredients, steps,
                original.sourceImage(), original.original(), "");
        return withTranslated(pt, textBlock(pt));
    }

    /** Translate if the source was Italian (reliable after faithful extraction) or any field
     *  still reads as Italian (catches PT-tagged recipes with leftover Italian words). */
    static boolean needsTranslation(Recipe recipe) {
        return "it".equalsIgnoreCase(recipe.originalLanguage()) || looksNonPortuguese(recipe);
    }

    /** True if any field still contains an Italian marker word. */
    static boolean looksNonPortuguese(Recipe recipe) {
        StringBuilder sb = new StringBuilder();
        if (recipe.title() != null) {
            sb.append(' ').append(recipe.title());
        }
        if (recipe.ingredients() != null) {
            recipe.ingredients().forEach(i -> sb.append(' ').append(i));
        }
        if (recipe.steps() != null) {
            recipe.steps().forEach(s -> sb.append(' ').append(s));
        }
        String normalized = Normalizer.normalize(sb.toString(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
        for (String token : normalized.split("[^a-z]+")) {
            if (ITALIAN_MARKERS.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static String nonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private static List<String> emptyToOriginal(List<String> preferred, List<String> fallback) {
        return preferred == null || preferred.isEmpty() ? fallback : preferred;
    }
}
