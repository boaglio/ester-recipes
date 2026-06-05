package com.ester.recipes.service;

import com.ester.recipes.model.Recipe;
import com.ester.recipes.model.RecipeExtraction;
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
            You are an expert at transcribing and translating scanned cooking-recipe clippings.

            This image is a photo of one or more printed/typed cooking recipes. Each recipe is
            written EITHER in Italian OR in Brazilian Portuguese.

            ===== ABSOLUTE TRANSLATION RULE =====
            EVERY piece of text you output (title, category, each ingredient, each step) MUST be
            written in BRAZILIAN PORTUGUESE. If the source is Italian, you MUST translate it.
            The output must contain NO Italian words at all — not in the title, not in the
            ingredients, not in the steps. The TITLE especially must be translated, e.g. the
            Italian title "Bibite all'ananas" must become "Bebida de Abacaxi". Translate
            culinary terms too, for example:
              ananas -> abacaxi; sciroppo -> calda; scatola -> lata; zucchero -> açúcar;
              vino bianco -> vinho branco; ghiaccio -> gelo; mescolare -> misturar;
              bibita -> bebida; unire -> juntar; diluire -> diluir; conservare -> conservar.
            Before finishing, re-read every field and make sure not a single Italian word remains.
            =====================================

            Other instructions:
            - The image may be ROTATED (90, 180 or 270 degrees) or slightly skewed. Mentally
              rotate it so the text is upright and read ALL of it carefully.
            - A single image MAY CONTAIN MORE THAN ONE recipe. Return every distinct recipe
              you find as a separate entry.
            - Transcribe faithfully, then translate. Do NOT invent ingredients, quantities or
              steps, and do NOT duplicate steps. Only include what is actually written; keep
              quantities and measurements accurate. If a value is missing, leave it empty.
            - Record each recipe's ORIGINAL language in `originalLanguage`: use 'it' if the
              printed source text was Italian (you saw Italian words such as 'sciroppo',
              'ananas', 'mescolare', 'scatola'), or 'pt' if the source was Portuguese. Decide
              this from the ORIGINAL text, not from your translated output.
            - In the title, drop decorative symbols such as leading/trailing asterisks.
            - Split ingredients into one entry each, and split the preparation into ordered
              steps, one entry each.
            - Choose the single most appropriate category (in Brazilian Portuguese), based on
              WHAT THE DISH IS, from this list: Sobremesas, Bebidas, Bolos e Tortas, Massas,
              Carnes, Aves, Peixes e Frutos do Mar, Saladas, Sopas, Pães, Molhos, Petiscos,
              Acompanhamentos. Judge by the finished dish: something frozen/sweet eaten with a
              spoon (e.g. a panettone layered with ice cream and chocolate) is "Sobremesas";
              only a liquid you actually drink is "Bebidas".

            Return ONLY the recipes found in this specific image.
            """;

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

        RecipeExtraction extraction = chatClient.prompt()
                // Pass the JSON schema as Ollama's `format` so the model is grammar-constrained
                // to emit exactly our {recipes:[...]} structure — no malformed or off-shape JSON.
                .options(OllamaChatOptions.builder().format(responseSchema).build())
                .user(user -> user
                        .text(PROMPT)
                        .media(mimeTypeFor(fileName), new FileSystemResource(image)))
                .call()
                .entity(converter);

        if (extraction == null || extraction.recipes() == null || extraction.recipes().isEmpty()) {
            log.warn("No recipes detected in {}", fileName);
            return List.of();
        }

        // Stamp the source image onto every recipe so the model can't get it wrong.
        List<Recipe> stamped = extraction.recipes().stream()
                .map(r -> new Recipe(
                        r.title(),
                        r.category(),
                        r.originalLanguage(),
                        r.yield(),
                        r.ingredients(),
                        r.steps(),
                        fileName))
                .toList();

        log.info("Found {} recipe(s) in {}: {}",
                stamped.size(), fileName, stamped.stream().map(Recipe::title).toList());
        return stamped;
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
