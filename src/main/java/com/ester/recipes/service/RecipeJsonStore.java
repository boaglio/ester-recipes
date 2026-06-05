package com.ester.recipes.service;

import com.ester.recipes.model.Recipe;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Reads and writes the per-recipe JSON files.
 *
 * <p>Each {@link Recipe} is one pretty-printed UTF-8 JSON file named from its category and
 * title. Writes never overwrite an existing file (from this run or a previous one), so the
 * JSON directory accumulates every recipe ever processed — which is exactly what the PDF is
 * then built from via {@link #readAll(Path)}.</p>
 */
@Service
public class RecipeJsonStore {

    private static final Logger log = LoggerFactory.getLogger(RecipeJsonStore.class);

    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * Writes a single recipe as {@code <category>-<title>.json} inside {@code jsonDir},
     * choosing a non-colliding name if that file already exists.
     *
     * @return the path of the file written
     */
    public Path write(Recipe recipe, Path jsonDir) {
        try {
            Files.createDirectories(jsonDir);
            String baseName = slug(recipe.category()) + "-" + slug(recipe.title());
            Path target = uniquePath(jsonDir, baseName.isBlank() ? "recipe" : baseName);
            objectMapper.writeValue(target.toFile(), recipe);
            log.info("Wrote {}", target);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write JSON for recipe: " + recipe.title(), e);
        }
    }

    /**
     * Reads every {@code *.json} file in {@code jsonDir} back into recipes.
     *
     * @return all stored recipes (this run and previous runs); empty if the dir is missing
     */
    public List<Recipe> readAll(Path jsonDir) {
        if (!Files.isDirectory(jsonDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(jsonDir)) {
            List<Recipe> recipes = new ArrayList<>();
            for (Path file : files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList()) {
                recipes.add(objectMapper.readValue(file.toFile(), Recipe.class));
            }
            return recipes;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read JSON directory: " + jsonDir, e);
        }
    }

    /** Resolves {@code <base>.json}, appending {@code -2}, {@code -3}… until the name is free on disk. */
    private Path uniquePath(Path jsonDir, String base) {
        Path candidate = jsonDir.resolve(base + ".json");
        int counter = 2;
        while (Files.exists(candidate)) {
            candidate = jsonDir.resolve(base + "-" + counter++ + ".json");
        }
        return candidate;
    }

    /** Turns arbitrary text into a lowercase, accent-free, hyphenated file-name fragment. */
    static String slug(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String noAccents = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return noAccents.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
    }
}
