package com.ester.recipes.pdf;

import com.ester.recipes.model.Recipe;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Renders all recipes into a single PDF cookbook, grouped by category and
 * sorted alphabetically by title within each category.
 */
@Service
public class RecipePdfService {

    private static final Logger log = LoggerFactory.getLogger(RecipePdfService.class);

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");
    private static final Color ACCENT = new Color(140, 70, 20); // warm brown

    private static final Font TITLE_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 30, ACCENT);
    private static final Font SUBTITLE_FONT = FontFactory.getFont(FontFactory.HELVETICA, 13, Color.DARK_GRAY);
    private static final Font CATEGORY_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 20, ACCENT);
    private static final Font RECIPE_TITLE_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 15, Color.BLACK);
    private static final Font META_FONT = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 9, Color.GRAY);
    private static final Font SECTION_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, Color.BLACK);
    private static final Font BODY_FONT = FontFactory.getFont(FontFactory.HELVETICA, 11, Color.BLACK);

    /**
     * Writes the cookbook PDF.
     *
     * @param recipes  all recipes to include
     * @param target   destination file (parent directories are created)
     * @param bookTitle title shown on the cover page
     */
    public void write(List<Recipe> recipes, Path target, String bookTitle) {
        // Category -> recipes, with categories alphabetical and recipes alphabetical by title.
        Map<String, List<Recipe>> byCategory = groupByCategory(recipes);

        Document document = new Document(PageSize.A4, 54, 54, 54, 54);
        try {
            Files.createDirectories(target.getParent());
            try (OutputStream out = Files.newOutputStream(target)) {
                PdfWriter.getInstance(document, out);
                document.open();

                addCover(document, bookTitle, recipes.size(), byCategory.size());
                addContents(document, byCategory);

                for (Map.Entry<String, List<Recipe>> entry : byCategory.entrySet()) {
                    document.newPage();
                    addCategoryHeading(document, entry.getKey());
                    for (Recipe recipe : entry.getValue()) {
                        addRecipe(document, recipe);
                    }
                }

                // Must close the document while `out` is still open: this flushes the
                // whole PDF to the stream. Closing `out` first (e.g. in a finally) would
                // make the flush fail with ClosedChannelException.
                document.close();
            }
            log.info("Wrote PDF cookbook with {} recipe(s) across {} categor(y/ies): {}",
                    recipes.size(), byCategory.size(), target);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write PDF: " + target, e);
        }
    }

    private Map<String, List<Recipe>> groupByCategory(List<Recipe> recipes) {
        Map<String, List<Recipe>> byCategory = new TreeMap<>(Collator());
        for (Recipe recipe : recipes) {
            String category = (recipe.category() == null || recipe.category().isBlank())
                    ? "Outras" : recipe.category().trim();
            byCategory.computeIfAbsent(category, k -> new java.util.ArrayList<>()).add(recipe);
        }
        Comparator<Recipe> byTitle = Comparator.comparing(
                r -> r.title() == null ? "" : r.title(), Collator());
        byCategory.values().forEach(list -> list.sort(byTitle));
        return byCategory;
    }

    /** Accent-aware, case-insensitive ordering so "Açaí" and "Bolo" sort naturally in pt-BR. */
    private static java.text.Collator Collator() {
        java.text.Collator collator = java.text.Collator.getInstance(PT_BR);
        collator.setStrength(java.text.Collator.SECONDARY);
        return collator;
    }

    private void addCover(Document document, String bookTitle, int recipeCount, int categoryCount) {
        Paragraph spacer = new Paragraph(" ");
        spacer.setSpacingBefore(160);
        document.add(spacer);

        Paragraph title = new Paragraph(bookTitle, TITLE_FONT);
        title.setAlignment(Element.ALIGN_CENTER);
        document.add(title);

        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("dd 'de' MMMM 'de' yyyy", PT_BR));
        Paragraph subtitle = new Paragraph(
                recipeCount + " receitas em " + categoryCount + " categorias\n" + date,
                SUBTITLE_FONT);
        subtitle.setAlignment(Element.ALIGN_CENTER);
        subtitle.setSpacingBefore(16);
        document.add(subtitle);
    }

    private void addContents(Document document, Map<String, List<Recipe>> byCategory) {
        document.newPage();
        Paragraph heading = new Paragraph("Índice", CATEGORY_FONT);
        heading.setSpacingAfter(14);
        document.add(heading);

        for (Map.Entry<String, List<Recipe>> entry : byCategory.entrySet()) {
            Paragraph cat = new Paragraph(entry.getKey(), SECTION_FONT);
            cat.setSpacingBefore(8);
            document.add(cat);
            for (Recipe recipe : entry.getValue()) {
                Paragraph item = new Paragraph("   •  " + nz(recipe.title()), BODY_FONT);
                document.add(item);
            }
        }
    }

    private void addCategoryHeading(Document document, String category) {
        Paragraph heading = new Paragraph(category, CATEGORY_FONT);
        heading.setSpacingAfter(12);
        document.add(heading);
    }

    private void addRecipe(Document document, Recipe recipe) {
        Paragraph title = new Paragraph(nz(recipe.title()), RECIPE_TITLE_FONT);
        title.setSpacingBefore(14);
        title.setSpacingAfter(2);
        document.add(title);

        document.add(new Paragraph(metaLine(recipe), META_FONT));

        if (recipe.ingredients() != null && !recipe.ingredients().isEmpty()) {
            Paragraph section = new Paragraph("Ingredientes", SECTION_FONT);
            section.setSpacingBefore(8);
            section.setSpacingAfter(2);
            document.add(section);
            for (String ingredient : recipe.ingredients()) {
                Paragraph line = new Paragraph(new Phrase("•  " + nz(ingredient), BODY_FONT));
                line.setIndentationLeft(12);
                document.add(line);
            }
        }

        if (recipe.steps() != null && !recipe.steps().isEmpty()) {
            Paragraph section = new Paragraph("Modo de preparo", SECTION_FONT);
            section.setSpacingBefore(8);
            section.setSpacingAfter(2);
            document.add(section);
            int n = 1;
            for (String step : recipe.steps()) {
                Paragraph line = new Paragraph(new Phrase(n++ + ".  " + nz(step), BODY_FONT));
                line.setIndentationLeft(12);
                line.setSpacingAfter(2);
                document.add(line);
            }
        }
    }

    private String metaLine(Recipe recipe) {
        StringBuilder sb = new StringBuilder();
        if (recipe.yield() != null && !recipe.yield().isBlank()) {
            sb.append("Rendimento: ").append(recipe.yield().trim());
        }
        String origin = originLabel(recipe.originalLanguage());
        if (origin != null) {
            if (sb.length() > 0) {
                sb.append("   •   ");
            }
            sb.append("Origem: ").append(origin);
        }
        if (recipe.sourceImage() != null && !recipe.sourceImage().isBlank()) {
            if (sb.length() > 0) {
                sb.append("   •   ");
            }
            sb.append("Fonte: ").append(recipe.sourceImage());
        }
        return sb.toString();
    }

    private String originLabel(String lang) {
        if (lang == null) {
            return null;
        }
        return switch (lang.trim().toLowerCase(Locale.ROOT)) {
            case "it" -> "Italiana";
            case "pt" -> "Brasileira";
            case "" -> null;
            default -> lang;
        };
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
