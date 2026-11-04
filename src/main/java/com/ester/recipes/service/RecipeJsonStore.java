package com.ester.recipes.service;

import com.ester.recipes.model.Recipe;
import com.ester.recipes.model.StoredRecipe;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

    /** Keeps file names well under the usual 255-byte limit when a title runs long. */
    private static final int MAX_BASE_NAME = 150;

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    /**
     * Writes all the recipes of one image, each as {@code <category>-<title>.json} inside
     * {@code jsonDir}, choosing a non-colliding name if that file already exists. If any write
     * fails, the ones already done are removed, so retrying the image can't duplicate recipes.
     *
     * <p>Synchronized so that parallel image processing can't have two threads resolve the
     * same free file name before either has written it.</p>
     *
     * @return the paths of the files written
     */
    public synchronized List<Path> writeAll(List<Recipe> recipes, Path jsonDir) {
        List<Path> written = new ArrayList<>();
        try {
            for (Recipe recipe : recipes) {
                written.add(write(recipe, jsonDir));
            }
            return written;
        } catch (RuntimeException e) {
            deleteAll(written);
            throw e;
        }
    }

    /** Removes recipe files whose image could not be completed. */
    public void deleteAll(List<Path> files) {
        for (Path file : files) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                log.error("Could not remove {}: {}", file, e.getMessage());
            }
        }
    }

    private Path write(Recipe recipe, Path jsonDir) {
        try {
            Files.createDirectories(jsonDir);
            String baseName = baseName(recipe);
            Path target = uniquePath(jsonDir, baseName.isBlank() ? "recipe" : baseName);
            writeAtomically(recipe, target);
            log.info("Wrote {}", target);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write JSON for recipe: " + recipe.title(), e);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to write JSON for recipe: " + recipe.title(), e);
        }
    }

    /** Overwrites an existing recipe JSON file in place (used by the translation-fix phase). */
    public void overwrite(Recipe recipe, Path jsonFile) {
        try {
            writeAtomically(recipe, jsonFile);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to overwrite " + jsonFile, e);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to overwrite " + jsonFile, e);
        }
    }

    /** Applies the agents.md text rules to recipes stored before those rules ran on the JSON. */
    public void normalizeAll(Path jsonDir) {
        int changed = 0;
        for (StoredRecipe stored : readAll(jsonDir)) {
            Recipe cleaned = RecipeText.clean(stored.recipe());
            if (!cleaned.equals(stored.recipe())) {
                overwrite(cleaned, jsonDir.resolve(stored.stem() + ".json"));
                changed++;
            }
        }
        if (changed > 0) {
            log.info("Normalized the text of {} stored recipe(s).", changed);
        }
    }

    /** Writes to a temporary file first, so a crash can't leave a half-written recipe behind. */
    private void writeAtomically(Recipe recipe, Path target) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            objectMapper.writeValue(temp.toFile(), recipe);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * Reads every {@code *.json} file in {@code jsonDir} back into recipes, each paired with
     * its file-name stem (used to link the matching {@code <stem>.png} image). A file that can't
     * be parsed is logged and skipped, so it can't cost the whole book.
     *
     * @return all stored recipes (this run and previous runs); empty if the dir is missing
     */
    public List<StoredRecipe> readAll(Path jsonDir) {
        if (!Files.isDirectory(jsonDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(jsonDir)) {
            List<StoredRecipe> recipes = new ArrayList<>();
            for (Path file : files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList()) {
                try {
                    Recipe recipe = objectMapper.readValue(file.toFile(), Recipe.class);
                    String stem = file.getFileName().toString().replaceFirst("(?i)\\.json$", "");
                    recipes.add(new StoredRecipe(recipe, stem));
                } catch (JacksonException e) {
                    log.error("Skipping unreadable recipe file {}: {}", file, e.getOriginalMessage());
                }
            }
            return recipes;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read JSON directory: " + jsonDir, e);
        }
    }

    private static String baseName(Recipe recipe) {
        String baseName = slug(recipe.category()) + "-" + slug(recipe.title());
        if (baseName.length() > MAX_BASE_NAME) {
            baseName = baseName.substring(0, MAX_BASE_NAME);
        }
        return baseName.replaceAll("-+$", "");
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
