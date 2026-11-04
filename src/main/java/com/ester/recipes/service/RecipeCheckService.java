package com.ester.recipes.service;

import com.ester.recipes.config.EsterProperties;
import com.ester.recipes.model.Recipe;
import com.ester.recipes.model.StoredRecipe;
import com.ester.recipes.pdf.BookSelection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Finds the pages worth reviewing: reads every processed scan with a second model and reports
 * where the two readings disagree. No recipe is changed, and the book is built either way.
 *
 * <p>Opt-in via {@code ester.check.enabled=true}. Each second reading is kept on disk, so a later
 * run only reads new scans and retries the failed ones.</p>
 */
@Service
public class RecipeCheckService {

    private static final Logger log = LoggerFactory.getLogger(RecipeCheckService.class);

    private static final int MAX_ATTEMPTS = 3;

    /** Enough to judge a page in the report; a page with more needs a full review anyway. */
    private static final int MAX_DIFFERENCES_SHOWN = 25;
    private static final int MAX_DIFFERENCE_LENGTH = 90;

    private final RecipeExtractionService extractionService;
    private final EsterProperties properties;
    private final String mainModel;

    public RecipeCheckService(RecipeExtractionService extractionService,
                              EsterProperties properties,
                              @Value("${spring.ai.ollama.chat.model:}") String mainModel) {
        this.extractionService = extractionService;
        this.properties = properties;
        this.mainModel = mainModel;
    }

    private record Page(String image, List<String> titles, List<String> problems,
                        ReadingComparison.Result comparison) {

        boolean checked() {
            return comparison != null;
        }

        boolean needsReview() {
            return !checked() || !problems.isEmpty() || comparison.needsReview();
        }

        double agreement() {
            return checked() ? comparison.agreement() : -1;
        }
    }

    /**
     * @return the report written to {@code outputDir}
     * @throws IllegalStateException if the second model is the main one: a model at temperature 0
     *                               agrees with itself, so every page would look clean
     */
    public Path checkAll(List<StoredRecipe> recipes, BookSelection book, Path processedDir, Path outputDir) {
        String checkModel = properties.check().model();
        if (checkModel == null || checkModel.isBlank() || checkModel.equals(mainModel)) {
            throw new IllegalStateException("ester.check.model must name a different model than the main one ("
                    + mainModel + "), or every page would look clean.");
        }
        Path readingsDir = Path.of(properties.check().dir()).resolve(RecipeJsonStore.slug(checkModel));

        List<Page> pages = new ArrayList<>();
        for (Map.Entry<String, List<Recipe>> entry : byImage(recipes).entrySet()) {
            String image = entry.getKey();
            String reading = secondReading(processedDir.resolve(image), readingsDir, checkModel);
            pages.add(page(image, entry.getValue(), reading));
        }
        pages.sort(Comparator.comparingDouble(Page::agreement));

        Path report = writeReport(pages, book, checkModel, outputDir);
        long review = pages.stream().filter(Page::needsReview).count();
        log.info("Check phase done: {} of {} page(s) need review -> {}", review, pages.size(), report);
        return report;
    }

    private static Map<String, List<Recipe>> byImage(List<StoredRecipe> recipes) {
        Map<String, List<Recipe>> byImage = new LinkedHashMap<>();
        for (StoredRecipe stored : recipes) {
            String image = stored.recipe().sourceImage();
            byImage.computeIfAbsent(image == null ? "" : image, k -> new ArrayList<>()).add(stored.recipe());
        }
        return byImage;
    }

    /** The stored second reading, or a new one; null if the scan could not be read. */
    private String secondReading(Path image, Path readingsDir, String model) {
        Path stored = readingsDir.resolve(image.getFileName() + ".txt");
        try {
            if (Files.exists(stored)) {
                return Files.readString(stored);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + stored, e);
        }
        if (!Files.isRegularFile(image)) {
            log.error("Cannot check {}: the scan is not in the processed folder.", image);
            return null;
        }
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                log.info("Second reading of {} with {}.", image.getFileName(), model);
                String reading = extractionService.readText(image, model);
                store(reading, stored);
                return reading;
            } catch (RuntimeException e) {
                log.warn("Second reading of {} failed (attempt {}/{}): {}",
                        image.getFileName(), attempt, MAX_ATTEMPTS, e.getMessage());
            }
        }
        return null;
    }

    private static void store(String reading, Path file) {
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, reading);
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store " + file, e);
        }
    }

    private static Page page(String image, List<Recipe> recipes, String reading) {
        List<String> titles = recipes.stream()
                .map(r -> r.title() == null || r.title().isBlank() ? "(no title)" : r.title())
                .toList();
        List<String> problems = new ArrayList<>();
        for (Recipe recipe : recipes) {
            if (recipe.ingredients() == null || recipe.ingredients().isEmpty()) {
                problems.add("no ingredients in \"" + recipe.title() + "\"");
            }
            if (recipe.steps() == null || recipe.steps().isEmpty()) {
                problems.add("no steps in \"" + recipe.title() + "\"");
            }
        }
        if (reading == null) {
            return new Page(image, titles, problems, null);
        }
        // `original` is the text before any translation, so it can be compared with the scan's.
        List<String> texts = recipes.stream()
                .map(r -> r.original() == null || r.original().isBlank()
                        ? RecipeExtractionService.textBlock(r) : r.original())
                .toList();
        return new Page(image, titles, problems, ReadingComparison.compare(texts, reading));
    }

    private Path writeReport(List<Page> pages, BookSelection book, String checkModel, Path outputDir) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH_mm"));
        Path report = outputDir.resolve("check-report-" + timestamp + ".md");
        try {
            Files.createDirectories(outputDir);
            Files.writeString(report, report(pages, mainModel, checkModel)
                    + setAsideSection(properties.noRecipeDir()) + bookSection(book));
            return report;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + report, e);
        }
    }

    private static String report(List<Page> pages, String mainModel, String checkModel) {
        List<Page> review = pages.stream().filter(Page::needsReview).toList();
        List<Page> clean = pages.stream().filter(p -> !p.needsReview()).toList();

        StringBuilder out = new StringBuilder("# Recipe check report\n\n");
        out.append(pages.size()).append(" page(s) read by `").append(mainModel).append("` and `")
                .append(checkModel).append("`: **").append(review.size()).append(" need review**, ")
                .append(clean.size()).append(" look fine.\n\n")
                .append("The readings agreeing doesn't prove them right: both models can misread a word ")
                .append("the same way. The book is built from the `").append(mainModel).append("` reading; ")
                .append("this report changes nothing.\n");

        for (Page page : review) {
            out.append("\n## ").append(page.image().isBlank() ? "(unknown scan)" : page.image());
            if (page.checked()) {
                out.append(String.format(Locale.ROOT, " — %.0f%% of the words agree", page.agreement() * 100));
            }
            out.append("\n\nRecipes: ").append(String.join("; ", page.titles())).append("\n\n");
            if (!page.checked()) {
                out.append("- **Not checked:** the second model could not read this scan. Run again to retry.\n");
            } else {
                if (page.comparison().quantitiesDiffer()) {
                    out.append("- **Numbers differ** between the readings.\n");
                }
                if (page.comparison().passageMissing()) {
                    out.append("- **One reading has a passage the other lacks.**\n");
                }
                if (page.agreement() < ReadingComparison.MIN_AGREEMENT) {
                    out.append("- **The readings differ a lot.**\n");
                }
            }
            page.problems().forEach(problem -> out.append("- The main reading has ").append(problem).append(".\n"));
            if (page.checked() && !page.comparison().differences().isEmpty()) {
                appendDifferences(out, page.comparison().differences(), mainModel, checkModel);
            }
        }

        if (!clean.isEmpty()) {
            out.append("\n## Look fine\n\n");
            for (Page page : clean) {
                out.append(String.format(Locale.ROOT, "- %s — %.0f%% (%s)%s%n",
                        page.image(), page.agreement() * 100, String.join("; ", page.titles()),
                        smallDifferences(page.comparison().differences())));
            }
        }
        return out.toString();
    }

    /** Every scan set aside so far, from any run: a real recipe page may be among them. */
    private static String setAsideSection(String noRecipeDir) throws IOException {
        if (noRecipeDir == null || !Files.isDirectory(Path.of(noRecipeDir))) {
            return "";
        }
        List<String> names;
        try (Stream<Path> files = Files.list(Path.of(noRecipeDir))) {
            names = files.map(f -> f.getFileName().toString()).sorted().toList();
        }
        if (names.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("\n## Scans with no recipe found\n\n")
                .append("Set aside in `").append(noRecipeDir).append("` and not in the book. ")
                .append("If one has a recipe, move it back to the pending folder.\n\n");
        names.forEach(name -> out.append("- ").append(name).append('\n'));
        return out.toString();
    }

    private static String bookSection(BookSelection book) {
        StringBuilder out = new StringBuilder();
        if (!book.merged().isEmpty()) {
            out.append("\n## Repeated recipes\n\nSame name and contents, so the most complete reading is in the book.\n\n")
                    .append("| In the book | Left out |\n|---|---|\n");
            book.merged().forEach(m -> out.append("| ").append(m.kept().stem())
                    .append(" | ").append(m.leftOut().stem()).append(" |\n"));
        }
        if (!book.renamed().isEmpty()) {
            out.append("\n## Same name, different recipes\n\nAll in the book, numbered. The name may be a misreading, ")
                    .append("or these may be one recipe read very differently.\n\n")
                    .append("| Name in the book | Recipe file | Scan |\n|---|---|---|\n");
            book.renamed().forEach(r -> out.append("| ").append(r.newTitle()).append(" | ")
                    .append(r.recipe().stem()).append(" | ").append(r.recipe().recipe().sourceImage()).append(" |\n"));
        }
        if (!book.untitled().isEmpty()) {
            out.append("\n## Left out of the book: no name\n\n");
            book.untitled().forEach(u -> out.append("- ").append(u.stem()).append('\n'));
        }
        return out.toString();
    }

    /** Even a page that looks fine may hide a misread word, so its few differences are listed. */
    private static String smallDifferences(List<ReadingComparison.Difference> differences) {
        return differences.stream()
                .limit(MAX_DIFFERENCES_SHOWN)
                .map(d -> cell(d.main()) + " / " + cell(d.check()))
                .collect(Collectors.joining("; ", differences.isEmpty() ? "" : ": ", ""));
    }

    private static void appendDifferences(StringBuilder out, List<ReadingComparison.Difference> differences,
                                          String mainModel, String checkModel) {
        out.append("\n| `").append(mainModel).append("` | `").append(checkModel).append("` |\n|---|---|\n");
        differences.stream().limit(MAX_DIFFERENCES_SHOWN).forEach(d ->
                out.append("| ").append(cell(d.main())).append(" | ").append(cell(d.check())).append(" |\n"));
        if (differences.size() > MAX_DIFFERENCES_SHOWN) {
            out.append("\n…and ").append(differences.size() - MAX_DIFFERENCES_SHOWN).append(" more.\n");
        }
    }

    private static String cell(String text) {
        if (text.isEmpty()) {
            return "(nothing)";
        }
        return text.length() <= MAX_DIFFERENCE_LENGTH ? text : text.substring(0, MAX_DIFFERENCE_LENGTH) + "…";
    }
}
