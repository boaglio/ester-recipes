package com.ester.recipes.pdf;

import com.ester.recipes.model.Recipe;
import com.ester.recipes.model.StoredRecipe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipePdfServiceTest {

    private static final int FIRST_RECIPE_PAGE = 4; // cover, summary, index

    @TempDir
    Path dir;

    private final RecipePdfService service = new RecipePdfService();

    private static StoredRecipe stored(String stem, String title, List<String> ingredients, List<String> steps) {
        return new StoredRecipe(new Recipe(title, "Bolos e Tortas", "pt", "", "", ingredients, steps, "", "", ""), stem);
    }

    private PdfReader write(List<StoredRecipe> recipes) throws IOException {
        Path pdf = dir.resolve("book.pdf");
        service.write(recipes, pdf, "Receitas da Ester", null);
        return new PdfReader(pdf.toString());
    }

    @Test
    void symbolsTheFontLacksAreRewrittenInsteadOfVanishing() {
        assertEquals("1/3 xícara", RecipePdfService.printable("⅓ xícara"));
        assertEquals("2 1/3 xícaras", RecipePdfService.printable("2⅓ xícaras"));
        assertEquals("½ xícara", RecipePdfService.printable("½ xícara"));
        assertEquals("180°C", RecipePdfService.printable("180℃"));
        assertEquals("Misture -> bata", RecipePdfService.printable("Misture → bata"));
        assertEquals("1º ovo", RecipePdfService.printable("1º ovo"));
        assertEquals("açúcar", RecipePdfService.printable("açúcar"));
        assertEquals("? bolo", RecipePdfService.printable("🎂 bolo"));
    }

    @Test
    void titlesLoseTheWordsAndSymbolsBannedFromTheIndex() {
        assertEquals("", RecipePdfService.displayTitle("Yield"));
        assertEquals("Bolo", RecipePdfService.displayTitle("Bolo Kids {"));
        assertEquals("Torta da Vovó", RecipePdfService.displayTitle("\"Torta\", da Vovó}"));
        assertEquals("Bolo", RecipePdfService.displayTitle("Bolo Yield: 4"));
        assertEquals("Bolo", RecipePdfService.displayTitle("Bolo [kids]"));
    }

    @Test
    void namesThatPrintTheSameAppearOnceAndEmptyNamesNotAtAll() throws IOException {
        PdfReader reader = write(List.of(
                stored("a", "Sopa Duplicada,", List.of("1 ovo"), List.of("Ferva.")),
                stored("b", "Sopa Duplicada", List.of("2 ovos"), List.of("Ferva.")),
                stored("c", "Yield", List.of("alface"), List.of("Sirva."))));

        String summary = new PdfTextExtractor(reader).getTextFromPage(2);

        assertEquals(4, reader.getNumberOfPages());
        assertTrue(summary.contains("Total"));
        assertTrue(summary.contains("Categoria"));
        assertFalse(summary.contains("receita(s)"));
    }

    @Test
    void categoryLabelsAreNotInFullCapitals() throws IOException {
        PdfReader reader = write(List.of(stored("a", "Bolo", List.of("1 ovo"), List.of("Asse."))));

        String page = new PdfTextExtractor(reader).getTextFromPage(FIRST_RECIPE_PAGE);

        assertTrue(page.contains("Bolos e Tortas"));
        assertFalse(page.contains("BOLOS"));
    }

    @Test
    void aLongRecipeAndItsPartnerShareOnePage() throws IOException {
        PdfReader reader = write(List.of(
                stored("a", "Bolo Curto", List.of("1 ovo"), List.of("Asse.", "Sirva.")),
                stored("b", "Bolo Longo", ingredients(25), steps(30))));

        String page = new PdfTextExtractor(reader).getTextFromPage(FIRST_RECIPE_PAGE);

        assertEquals(FIRST_RECIPE_PAGE, reader.getNumberOfPages());
        assertTrue(page.contains("Bolo Curto"));
        assertTrue(page.contains("Bolo Longo"));
    }

    @Test
    void aRecipeTooLongForOnePageKeepsAllItsText() throws IOException {
        PdfReader reader = write(List.of(stored("a", "Bolo Enorme", ingredients(25), steps(200))));

        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        StringBuilder text = new StringBuilder();
        for (int page = FIRST_RECIPE_PAGE; page <= reader.getNumberOfPages(); page++) {
            text.append(extractor.getTextFromPage(page));
        }

        assertTrue(reader.getNumberOfPages() > FIRST_RECIPE_PAGE);
        IntStream.range(0, 200).forEach(i -> assertTrue(text.toString().contains("passo" + i + "fim"), "step " + i));
    }

    private static List<String> ingredients(int count) {
        return IntStream.range(0, count).mapToObj(i -> "ingrediente " + i).toList();
    }

    private static List<String> steps(int count) {
        List<String> steps = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            steps.add("passo" + i + "fim mexa em fogo baixo até obter um creme homogêneo e brilhante, sem deixar grudar.");
        }
        return steps;
    }
}
