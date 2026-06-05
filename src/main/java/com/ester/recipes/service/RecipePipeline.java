package com.ester.recipes.service;

import com.ester.recipes.config.EsterProperties;
import com.ester.recipes.model.Recipe;
import com.ester.recipes.pdf.RecipePdfService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * End-to-end pipeline, run once at startup:
 * <ol>
 *   <li>scan the input directory for images (skipping any already processed),</li>
 *   <li>extract recipes from each new image via the vision model,</li>
 *   <li>write one JSON file per recipe, then move the image into the processed folder,</li>
 *   <li>render the PDF cookbook from <em>all</em> stored JSON (this run plus previous runs).</li>
 * </ol>
 */
@Component
public class RecipePipeline implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RecipePipeline.class);

    private static final List<String> IMAGE_EXTENSIONS = List.of(".jpg", ".jpeg", ".png");

    private final EsterProperties properties;
    private final RecipeExtractionService extractionService;
    private final RecipeJsonStore jsonStore;
    private final RecipePdfService pdfService;

    public RecipePipeline(EsterProperties properties,
                          RecipeExtractionService extractionService,
                          RecipeJsonStore jsonStore,
                          RecipePdfService pdfService) {
        this.properties = properties;
        this.extractionService = extractionService;
        this.jsonStore = jsonStore;
        this.pdfService = pdfService;
    }

    @Override
    public void run(ApplicationArguments args) {
        Path inputDir = Path.of(properties.inputDir());
        Path processedDir = Path.of(properties.processedDir());
        Path jsonDir = Path.of(properties.jsonDir());
        Path pdfPath = Path.of(properties.outputDir()).resolve(properties.pdfName());

        long runStart = System.nanoTime();
        List<Path> images = findImages(inputDir);
        log.info("Found {} image(s) in {}", images.size(), inputDir.toAbsolutePath());

        int processed = 0;
        int skipped = 0;
        for (Path image : images) {
            String name = image.getFileName().toString();
            if (Files.exists(processedDir.resolve(name))) {
                log.info("Skipping already-processed image: {}", name);
                skipped++;
                continue;
            }
            long imageStart = System.nanoTime();
            try {
                List<Recipe> recipes = extractionService.extract(image);
                for (Recipe recipe : recipes) {
                    jsonStore.write(recipe, jsonDir);
                }
                moveToProcessed(image, processedDir);
                processed++;
                log.info("Processed {} ({} recipe(s)) in {}",
                        name, recipes.size(), humanDuration(System.nanoTime() - imageStart));
            } catch (Exception e) {
                // One bad image shouldn't abort the run, and we leave it in place to retry.
                log.error("Failed to process {} after {}: {}",
                        name, humanDuration(System.nanoTime() - imageStart), e.getMessage(), e);
            }
        }
        log.info("Processed {} new image(s), skipped {} already-processed.", processed, skipped);

        // The cookbook always reflects everything stored so far, not just this run.
        List<Recipe> allRecipes = jsonStore.readAll(jsonDir);
        if (allRecipes.isEmpty()) {
            log.warn("No recipes stored in {} — skipping PDF generation. (Whole run took {}.)",
                    jsonDir.toAbsolutePath(), humanDuration(System.nanoTime() - runStart));
            return;
        }

        pdfService.write(allRecipes, pdfPath, properties.pdfTitle());
        log.info("Done. {} recipe(s) total -> JSON in {} and PDF at {}. Whole run took {}.",
                allRecipes.size(), jsonDir.toAbsolutePath(), pdfPath.toAbsolutePath(),
                humanDuration(System.nanoTime() - runStart));
    }

    /** Formats a nanosecond duration as e.g. {@code "58.7s"} or {@code "1m 21s"}. */
    private static String humanDuration(long nanos) {
        double seconds = nanos / 1_000_000_000.0;
        if (seconds >= 60) {
            long minutes = (long) (seconds / 60);
            return String.format(Locale.ROOT, "%dm %02.0fs", minutes, seconds - minutes * 60);
        }
        return String.format(Locale.ROOT, "%.1fs", seconds);
    }

    private void moveToProcessed(Path image, Path processedDir) {
        try {
            Files.createDirectories(processedDir);
            Files.move(image, processedDir.resolve(image.getFileName()));
            log.info("Moved {} -> {}", image.getFileName(), processedDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to move processed image: " + image, e);
        }
    }

    private List<Path> findImages(Path inputDir) {
        if (!Files.isDirectory(inputDir)) {
            log.warn("Input directory does not exist: {}", inputDir.toAbsolutePath());
            return List.of();
        }
        // Files.list is non-recursive, so the processed/ and json/ sub-directories are ignored.
        try (Stream<Path> files = Files.list(inputDir)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(RecipePipeline::isImage)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list input directory: " + inputDir, e);
        }
    }

    private static boolean isImage(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return IMAGE_EXTENSIONS.stream().anyMatch(name::endsWith);
    }
}
