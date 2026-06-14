package com.ester.recipes.service;

import com.ester.recipes.config.EsterProperties;
import com.ester.recipes.model.Recipe;
import com.ester.recipes.model.StoredRecipe;
import com.ester.recipes.pdf.RecipePdfService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * End-to-end pipeline, run once at startup:
 * <ol>
 *   <li>scan the input directory for images (skipping any already processed),</li>
 *   <li>extract recipes from each new image via the vision model — up to
 *       {@code ester.concurrency} images in parallel,</li>
 *   <li>write one JSON file per recipe, then move the image into the processed folder,</li>
 *   <li>render the PDF cookbook from <em>all</em> stored JSON (this run plus previous runs).</li>
 * </ol>
 *
 * <p>Work runs on the application's task executor, which uses virtual threads
 * ({@code spring.threads.virtual.enabled=true}) — a good fit for the blocking Ollama calls.
 * A {@link Semaphore} caps how many images hit the model at once so we don't overcommit the GPU.</p>
 */
@Component
public class RecipePipeline implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RecipePipeline.class);

    private static final List<String> IMAGE_EXTENSIONS = List.of(".jpg", ".jpeg", ".png");

    /** Max processing rounds before giving up on images the model can't handle. */
    private static final int MAX_ROUNDS = 5;

    private final EsterProperties properties;
    private final RecipeExtractionService extractionService;
    private final RecipeJsonStore jsonStore;
    private final TranslationFixService translationService;
    private final ImageGenerationService imageService;
    private final RecipePdfService pdfService;
    private final AsyncTaskExecutor taskExecutor;

    public RecipePipeline(EsterProperties properties,
                          RecipeExtractionService extractionService,
                          RecipeJsonStore jsonStore,
                          TranslationFixService translationService,
                          ImageGenerationService imageService,
                          RecipePdfService pdfService,
                          AsyncTaskExecutor taskExecutor) {
        this.properties = properties;
        this.extractionService = extractionService;
        this.jsonStore = jsonStore;
        this.translationService = translationService;
        this.imageService = imageService;
        this.pdfService = pdfService;
        this.taskExecutor = taskExecutor;
    }

    @Override
    public void run(ApplicationArguments args) {
        Path inputDir = Path.of(properties.inputDir());
        Path processedDir = Path.of(properties.processedDir());
        Path jsonDir = Path.of(properties.jsonDir());
        // agents.md: timestamped PDF name "receitas-da-ester-YYYY-MM-DD-HH_MI.pdf".
        String pdfName = "receitas-da-ester-"
                + java.time.LocalDateTime.now().format(
                        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HH_mm")) + ".pdf";
        Path pdfPath = Path.of(properties.outputDir()).resolve(pdfName);
        int concurrency = Math.max(1, properties.concurrency());

        long runStart = System.nanoTime();

        // Count images already done in previous runs so the progress % reflects the whole
        // batch (done + to-do), not just this run's slice.
        int alreadyDone = (int) countImages(processedDir);
        List<Path> pending = pendingImages(inputDir, processedDir);
        int batchTotal = alreadyDone + pending.size();
        AtomicInteger finished = new AtomicInteger(alreadyDone); // position within the whole batch
        log.info("Found {} new image(s) to process, {} already done -> batch of {} (concurrency {}).",
                pending.size(), alreadyDone, batchTotal, concurrency);

        // agents.md: "do not continue until all images were processed". Retry in rounds until
        // the input folder has no unprocessed images left, or a round makes no progress (an
        // image the model genuinely can't handle — we then stop rather than loop forever).
        int round = 0;
        int totalProcessed = 0;
        while (!pending.isEmpty() && round < MAX_ROUNDS) {
            round++;
            if (round > 1) {
                log.info("Retry round {} — {} image(s) still need processing.", round, pending.size());
            }
            Semaphore gate = new Semaphore(concurrency);
            AtomicInteger processed = new AtomicInteger();
            List<Future<?>> futures = new ArrayList<>();
            for (Path image : pending) {
                futures.add(taskExecutor.submit(
                        () -> processImage(image, jsonDir, processedDir, gate, processed,
                                finished, batchTotal)));
            }
            awaitAll(futures);
            totalProcessed += processed.get();
            if (processed.get() == 0) {
                log.warn("Round {} processed no new images — stopping retries.", round);
                break;
            }
            pending = pendingImages(inputDir, processedDir);
        }

        List<Path> remaining = pendingImages(inputDir, processedDir);
        if (remaining.isEmpty()) {
            log.info("All images processed ({} new this run).", totalProcessed);
        } else {
            log.warn("{} image(s) could NOT be processed after {} round(s) and remain in {}: {}",
                    remaining.size(), round, inputDir,
                    remaining.stream().map(p -> p.getFileName().toString()).toList());
        }

        // The cookbook always reflects everything stored so far, not just this run.
        List<StoredRecipe> allRecipes = jsonStore.readAll(jsonDir);
        if (allRecipes.isEmpty()) {
            log.warn("No recipes stored in {} — skipping PDF generation. (Whole run took {}.)",
                    jsonDir.toAbsolutePath(), humanDuration(System.nanoTime() - runStart));
            return;
        }

        // Optional second routine: fix any recipe still holding non-Portuguese text.
        if (properties.translation() != null && properties.translation().enabled()) {
            log.info("Translation-fix phase enabled — correcting non-Portuguese recipes.");
            translationService.fixAll(allRecipes, jsonDir);
            allRecipes = jsonStore.readAll(jsonDir); // re-read the corrected JSON for the PDF
        }

        // Optional, opt-in second phase: render an anime image per recipe via local ComfyUI.
        Path imageDir = Path.of(properties.jsonDir()).resolveSibling("images");
        if (properties.images() != null && properties.images().enabled()) {
            if (properties.images().dir() != null && !properties.images().dir().isBlank()) {
                imageDir = Path.of(properties.images().dir());
            }
            log.info("Image phase enabled — generating missing images via ComfyUI at {}",
                    properties.images().baseUrl());
            imageService.generateMissing(allRecipes, imageDir);
            imageService.generateCover(imageDir);
        }

        pdfService.write(allRecipes, pdfPath, properties.pdfTitle(), imageDir);
        log.info("Done. {} recipe(s) total -> JSON in {} and PDF at {}. Whole run took {}.",
                allRecipes.size(), jsonDir.toAbsolutePath(), pdfPath.toAbsolutePath(),
                humanDuration(System.nanoTime() - runStart));
    }

    /** Extract → write JSON → move one image. Self-contained: never lets one image abort the batch. */
    private void processImage(Path image, Path jsonDir, Path processedDir,
                              Semaphore gate, AtomicInteger processed,
                              AtomicInteger finished, int batchTotal) {
        String name = image.getFileName().toString();
        // Start the timer only after we hold a permit, so queued time isn't counted as
        // processing time (important when concurrency < number of images).
        long imageStart = 0;
        try {
            gate.acquire();
            imageStart = System.nanoTime();
            try {
                List<Recipe> recipes = extractionService.extract(image);
                for (Recipe recipe : recipes) {
                    jsonStore.write(recipe, jsonDir);
                }
                moveToProcessed(image, processedDir);
                processed.incrementAndGet();
                // Progress advances only on success, so retried failures don't double-count.
                log.info("Processed {} ({} recipe(s)) in {} — {}",
                        name, recipes.size(), humanDuration(System.nanoTime() - imageStart),
                        progress(finished, batchTotal));
            } finally {
                gate.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while processing {}", name);
        } catch (Exception e) {
            // Logged and left in place; a later round retries it.
            String took = imageStart == 0 ? "n/a" : humanDuration(System.nanoTime() - imageStart);
            log.error("Failed to process {} after {}: {}", name, took, e.getMessage());
        }
    }

    /** Images in the input dir that have not yet been moved to processed/. */
    private List<Path> pendingImages(Path inputDir, Path processedDir) {
        List<Path> pending = new ArrayList<>();
        for (Path image : findImages(inputDir)) {
            if (!Files.exists(processedDir.resolve(image.getFileName().toString()))) {
                pending.add(image);
            }
        }
        return pending;
    }

    /** Advances and renders batch progress, e.g. {@code "37/389 (10%)"}. */
    private static String progress(AtomicInteger finished, int batchTotal) {
        int n = finished.incrementAndGet();
        int pct = batchTotal > 0 ? (int) Math.round(n * 100.0 / batchTotal) : 100;
        return n + "/" + batchTotal + " (" + pct + "%)";
    }

    /** Quietly counts image files in a directory (0 if it doesn't exist). */
    private long countImages(Path dir) {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile).filter(RecipePipeline::isImage).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private void awaitAll(List<Future<?>> futures) {
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for image processing to finish.");
                return;
            } catch (ExecutionException e) {
                // processImage handles its own errors, so this is only for unexpected ones.
                log.error("Unexpected task failure: {}", e.getCause() == null ? e : e.getCause().getMessage());
            }
        }
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

    /** Formats a nanosecond duration as e.g. {@code "58.7s"} or {@code "1m 21s"}. */
    private static String humanDuration(long nanos) {
        double seconds = nanos / 1_000_000_000.0;
        if (seconds >= 60) {
            long minutes = (long) (seconds / 60);
            return String.format(Locale.ROOT, "%dm %02.0fs", minutes, seconds - minutes * 60);
        }
        return String.format(Locale.ROOT, "%.1fs", seconds);
    }
}
