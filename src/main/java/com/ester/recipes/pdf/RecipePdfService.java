package com.ester.recipes.pdf;

import com.ester.recipes.model.Recipe;
import com.ester.recipes.model.StoredRecipe;
import com.ester.recipes.service.CategoryNormalizer;
import com.ester.recipes.service.ImageGenerationService;
import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
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

    // agents.md: "use a small font". Cover title stays large; everything else is compact.
    private static final Font TITLE_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 30, ACCENT);
    private static final Font SUBTITLE_FONT = FontFactory.getFont(FontFactory.HELVETICA, 12, Color.DARK_GRAY);
    private static final Font CATEGORY_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 15, ACCENT);
    private static final Font RECIPE_TITLE_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, ACCENT);
    private static final Font KICKER_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 6.5f, Color.GRAY);
    private static final Font META_FONT = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 6.5f, Color.GRAY);
    private static final Font SECTION_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, Color.BLACK);
    private static final Font BODY_FONT = FontFactory.getFont(FontFactory.HELVETICA, 7.5f, Color.BLACK);
    private static final Font INDEX_FONT = FontFactory.getFont(FontFactory.HELVETICA, 8, new Color(40, 40, 120));

    /**
     * Writes the cookbook PDF.
     *
     * @param recipes   all recipes to include (each with its file stem, used to find its image)
     * @param target    destination file (parent directories are created)
     * @param bookTitle title shown on the cover page
     * @param imageDir  directory holding {@code <stem>.png} images; embedded when present
     */
    public void write(List<StoredRecipe> recipes, Path target, String bookTitle, Path imageDir) {
        // Drop repeated recipe names, then group by a freshly-resolved category (so old
        // "Outras"/inconsistent categories in the JSON are corrected at book-build time).
        List<StoredRecipe> deduped = dedupeByTitle(recipes);
        Map<String, List<StoredRecipe>> byCategory = groupByCategory(deduped);

        Document document = new Document(PageSize.A4, 54, 54, 54, 54);
        try {
            Files.createDirectories(target.getParent());
            try (OutputStream out = Files.newOutputStream(target)) {
                PdfWriter.getInstance(document, out);
                document.open();

                // Flatten into one ordered list with sequential ids — shared by the clickable
                // index and the two-per-page rendering so links point at the right page.
                List<Entry> ordered = new java.util.ArrayList<>();
                int id = 0;
                for (Map.Entry<String, List<StoredRecipe>> entry : byCategory.entrySet()) {
                    for (StoredRecipe stored : entry.getValue()) {
                        ordered.add(new Entry(id++, entry.getKey(), stored));
                    }
                }

                addCover(document, bookTitle, deduped.size(), byCategory.size(), imageDir);
                addSummary(document, byCategory, deduped.size());
                addContents(document, byCategory, ordered);
                addRecipesTwoPerPage(document, ordered, imageDir);

                // Must close the document while `out` is still open: this flushes the
                // whole PDF to the stream. Closing `out` first (e.g. in a finally) would
                // make the flush fail with ClosedChannelException.
                document.close();
            }
            log.info("Wrote PDF cookbook with {} recipe(s) across {} categor(y/ies): {}",
                    deduped.size(), byCategory.size(), target);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write PDF: " + target, e);
        }
    }

    /** Keeps the first recipe for each (accent-insensitive) title — no repeated names in the book. */
    private List<StoredRecipe> dedupeByTitle(List<StoredRecipe> recipes) {
        Map<String, StoredRecipe> unique = new java.util.LinkedHashMap<>();
        for (StoredRecipe stored : recipes) {
            String key = strip(stored.recipe().title());
            if (!key.isBlank()) {
                unique.putIfAbsent(key, stored);
            }
        }
        return new java.util.ArrayList<>(unique.values());
    }

    private Map<String, List<StoredRecipe>> groupByCategory(List<StoredRecipe> recipes) {
        Map<String, List<StoredRecipe>> byCategory = new TreeMap<>(Collator());
        for (StoredRecipe stored : recipes) {
            String category = CategoryNormalizer.normalize(
                    stored.recipe().title(), stored.recipe().ingredients(), stored.recipe().category());
            byCategory.computeIfAbsent(category, k -> new java.util.ArrayList<>()).add(stored);
        }
        Comparator<StoredRecipe> byTitle = Comparator.comparing(
                s -> s.recipe().title() == null ? "" : s.recipe().title(), Collator());
        byCategory.values().forEach(list -> list.sort(byTitle));
        return byCategory;
    }

    /** A "summary" page before the index: number of recipes in each category, plus the total. */
    private void addSummary(Document document, Map<String, List<StoredRecipe>> byCategory, int total) {
        document.newPage();
        Paragraph heading = new Paragraph("Resumo", CATEGORY_FONT);
        heading.setSpacingAfter(14);
        document.add(heading);

        for (Map.Entry<String, List<StoredRecipe>> entry : byCategory.entrySet()) {
            Paragraph line = new Paragraph(
                    entry.getKey() + ": " + entry.getValue().size() + " receita(s)", BODY_FONT);
            line.setSpacingAfter(2);
            document.add(line);
        }
        Paragraph totalLine = new Paragraph("Total: " + total + " receitas", SECTION_FONT);
        totalLine.setSpacingBefore(10);
        document.add(totalLine);
    }

    /** Accent-stripped lowercase key for case/accent-insensitive title de-duplication. */
    private static String strip(String text) {
        if (text == null) {
            return "";
        }
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(PT_BR)
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** Accent-aware, case-insensitive ordering so "Açaí" and "Bolo" sort naturally in pt-BR. */
    private static java.text.Collator Collator() {
        java.text.Collator collator = java.text.Collator.getInstance(PT_BR);
        collator.setStrength(java.text.Collator.SECONDARY);
        return collator;
    }

    private void addCover(Document document, String bookTitle, int recipeCount, int categoryCount,
                          Path imageDir) {
        Paragraph spacer = new Paragraph(" ");
        spacer.setSpacingBefore(70);
        document.add(spacer);

        Paragraph title = new Paragraph(bookTitle, TITLE_FONT);
        title.setAlignment(Element.ALIGN_CENTER);
        document.add(title);

        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("dd 'de' MMMM 'de' yyyy", PT_BR));
        Paragraph subtitle = new Paragraph(
                recipeCount + " receitas em " + categoryCount + " categorias\n" + date,
                SUBTITLE_FONT);
        subtitle.setAlignment(Element.ALIGN_CENTER);
        subtitle.setSpacingBefore(12);
        subtitle.setSpacingAfter(16);
        document.add(subtitle);

        // The big anime cover image of all food categories (agents.md), if generated.
        if (imageDir != null) {
            Path cover = imageDir.resolve(ImageGenerationService.COVER_NAME);
            if (Files.isRegularFile(cover)) {
                try {
                    Image image = compressedJpeg(cover, 640, 0.75f);
                    image.scaleToFit(360, 470);
                    image.setAlignment(Element.ALIGN_CENTER);
                    document.add(image);
                } catch (Exception e) {
                    log.warn("Skipping cover image: {}", e.getMessage());
                }
            }
        }
    }

    /** One recipe with its sequential id (clickable-index destination) and resolved category. */
    private record Entry(int id, String category, StoredRecipe stored) {
    }

    /** Clickable index: each entry jumps to its recipe's page (agents.md). */
    private void addContents(Document document, Map<String, List<StoredRecipe>> byCategory,
                             List<Entry> ordered) {
        Map<String, Integer> idByStem = new java.util.HashMap<>();
        for (Entry e : ordered) {
            idByStem.put(e.stored().stem(), e.id());
        }

        document.newPage();
        Paragraph heading = new Paragraph("Índice", CATEGORY_FONT);
        heading.setSpacingAfter(10);
        document.add(heading);

        for (Map.Entry<String, List<StoredRecipe>> entry : byCategory.entrySet()) {
            Paragraph cat = new Paragraph(entry.getKey(), SECTION_FONT);
            cat.setSpacingBefore(6);
            document.add(cat);
            for (StoredRecipe stored : entry.getValue()) {
                int id = idByStem.getOrDefault(stored.stem(), -1);
                Chunk link = new Chunk("•  " + displayTitle(stored.recipe().title()), INDEX_FONT);
                if (id >= 0) {
                    link.setLocalGoto("r" + id);
                }
                Paragraph item = new Paragraph(link);
                item.setIndentationLeft(12);
                document.add(item);
            }
        }
    }

    /** Two recipes per page: [recipe1][image1] top row, [image2][recipe2] bottom row (agents.md). */
    private void addRecipesTwoPerPage(Document document, List<Entry> ordered, Path imageDir) {
        for (int i = 0; i < ordered.size(); i += 2) {
            document.newPage();
            Entry first = ordered.get(i);
            Entry second = (i + 1 < ordered.size()) ? ordered.get(i + 1) : null;

            PdfPTable table = new PdfPTable(2); // equal columns so the mirrored layout balances
            table.setWidthPercentage(100);

            table.addCell(recipeCell(first, imageDir));
            table.addCell(imageCell(first, imageDir));

            if (second != null) {
                table.addCell(imageCell(second, imageDir));
                table.addCell(recipeCell(second, imageDir));
            } else {
                table.addCell(emptyCell());
                table.addCell(emptyCell());
            }
            document.add(table);
        }
    }

    private PdfPCell recipeCell(Entry entry, Path imageDir) {
        Recipe recipe = entry.stored().recipe();
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(7);
        cell.setVerticalAlignment(Element.ALIGN_TOP);

        Paragraph kicker = new Paragraph(displayTitle(entry.category()).toUpperCase(PT_BR), KICKER_FONT);
        kicker.setSpacingAfter(1);
        cell.addElement(kicker);

        Chunk titleChunk = new Chunk(displayTitle(recipe.title()), RECIPE_TITLE_FONT);
        titleChunk.setLocalDestination("r" + entry.id()); // index link target
        Paragraph title = new Paragraph(titleChunk);
        title.setSpacingAfter(1);
        cell.addElement(title);

        String meta = metaLine(recipe);
        if (!meta.isBlank()) {
            cell.addElement(new Paragraph(meta, META_FONT));
        }

        List<String> ingredients = dedupeIngredients(recipe.ingredients());
        if (!ingredients.isEmpty()) {
            Paragraph section = new Paragraph("Ingredientes", SECTION_FONT);
            section.setSpacingBefore(4);
            section.setSpacingAfter(1);
            cell.addElement(section);
            boolean single = ingredients.size() == 1;
            for (String ingredient : ingredients) {
                cell.addElement(new Paragraph((single ? "" : "•  ") + displayText(ingredient), BODY_FONT));
            }
        }

        List<String> steps = recipe.steps() == null ? List.of() : recipe.steps();
        if (!steps.isEmpty()) {
            Paragraph section = new Paragraph("Modo de preparo", SECTION_FONT);
            section.setSpacingBefore(4);
            section.setSpacingAfter(1);
            cell.addElement(section);
            boolean single = steps.size() == 1;
            int n = 1;
            for (String step : steps) {
                cell.addElement(new Paragraph((single ? "" : (n++ + ".  ")) + stepText(step), BODY_FONT));
            }
        }
        return cell;
    }

    private PdfPCell imageCell(Entry entry, Path imageDir) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setPadding(7);
        if (imageDir != null) {
            Path imageFile = entry.stored().imageFile(imageDir);
            if (Files.isRegularFile(imageFile)) {
                try {
                    // Embed a small compressed JPEG (not the full PNG) to keep the PDF small.
                    Image image = compressedJpeg(imageFile, 300, 0.7f);
                    image.scaleToFit(190, 190); // small image (agents.md)
                    image.setAlignment(Element.ALIGN_CENTER);
                    cell.addElement(image);
                } catch (Exception e) {
                    log.warn("Skipping image for '{}': {}", entry.stored().recipe().title(), e.getMessage());
                }
            }
        }
        return cell;
    }

    private PdfPCell emptyCell() {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        return cell;
    }

    /** Cleans a TITLE for index/heading: removes stray symbols/words, fixes ALL-CAPS (agents.md). */
    static String displayTitle(String title) {
        if (title == null) {
            return "";
        }
        String t = title.replaceAll("[{}\",]", " ")           // remove } " { ,
                .replaceAll("(?i)\\b(yield|kids)\\b", " ")      // remove these words
                .replaceAll("[*]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return fixAllCaps(t);
    }

    /** Cleans body text (ingredients/steps): fixes ALL-CAPS only. */
    static String displayText(String text) {
        return fixAllCaps(text == null ? "" : text.trim());
    }

    /** Title-cases any word written in full uppercase (agents.md: no full-caps words). */
    static String fixAllCaps(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String word : text.split(" ")) {
            if (word.length() > 1 && word.equals(word.toUpperCase(PT_BR))
                    && word.chars().anyMatch(Character::isLetter)) {
                word = word.substring(0, 1).toUpperCase(PT_BR) + word.substring(1).toLowerCase(PT_BR);
            }
            sb.append(word).append(' ');
        }
        return sb.toString().trim();
    }

    /** Removes duplicate ingredient lines (agents.md), case/space-insensitive, keeping order. */
    private static List<String> dedupeIngredients(List<String> ingredients) {
        if (ingredients == null) {
            return List.of();
        }
        Map<String, String> unique = new java.util.LinkedHashMap<>();
        for (String ingredient : ingredients) {
            if (ingredient != null && !ingredient.isBlank()) {
                unique.putIfAbsent(ingredient.trim().toLowerCase(PT_BR).replaceAll("\\s+", " "),
                        ingredient.trim());
            }
        }
        return new java.util.ArrayList<>(unique.values());
    }

    /** Strips a leading "N." / "N)" so my own numbering doesn't double (agents.md: no "1. 1."). */
    private static String stepText(String step) {
        String t = step == null ? "" : step.trim();
        // Strip a leading "N." / "N)" AND any leading dash/bullet, so we don't render "1. - ...".
        t = t.replaceFirst("^\\d+\\s*[.)\\-:]\\s*", "").replaceFirst("^[-–•*]\\s*", "").trim();
        return displayText(t);
    }

    private String metaLine(Recipe recipe) {
        StringBuilder sb = new StringBuilder();
        if (recipe.date() != null && !recipe.date().isBlank()) {
            sb.append("Data: ").append(recipe.date().trim());
        }
        if (recipe.yield() != null && !recipe.yield().isBlank()) {
            if (!sb.isEmpty()) {
                sb.append("   •   ");
            }
            sb.append("Rendimento: ").append(recipe.yield().trim());
        }
        String origin = originLabel(recipe.originalLanguage());
        if (origin != null) {
            if (!sb.isEmpty()) {
                sb.append("   •   ");
            }
            sb.append("Origem: ").append(origin);
        }
        if (recipe.sourceImage() != null && !recipe.sourceImage().isBlank()) {
            if (!sb.isEmpty()) {
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

    /**
     * Loads an image, downscales it so the longest side is at most {@code maxPx}, and re-encodes
     * it as a JPEG at {@code quality}. Embedding these compact JPEGs (instead of the original
     * 512px PNGs) keeps the PDF small — a few MB instead of 200+.
     */
    private static Image compressedJpeg(Path file, int maxPx, float quality) throws Exception {
        BufferedImage src = ImageIO.read(file.toFile());
        if (src == null) {
            throw new IOException("Unreadable image: " + file);
        }
        int w = src.getWidth();
        int h = src.getHeight();
        double scale = Math.min(1.0, (double) maxPx / Math.max(w, h));
        int nw = Math.max(1, (int) Math.round(w * scale));
        int nh = Math.max(1, (int) Math.round(h * scale));

        BufferedImage dst = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, nw, nh, Color.WHITE, null);
        g.dispose();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.setOutput(ios);
            writer.write(null, new IIOImage(dst, null, null), param);
        } finally {
            writer.dispose();
        }
        return Image.getInstance(out.toByteArray());
    }
}
