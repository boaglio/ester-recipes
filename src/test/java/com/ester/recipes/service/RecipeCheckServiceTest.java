package com.ester.recipes.service;

import com.ester.recipes.config.EsterProperties;
import com.ester.recipes.model.Recipe;
import com.ester.recipes.model.StoredRecipe;
import com.ester.recipes.pdf.BookSelection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RecipeCheckServiceTest {

    private static final String TEXT = "Bolo\n2 ovos\nMisture e asse";

    @TempDir
    Path root;

    private final RecipeExtractionService extraction = mock(RecipeExtractionService.class);
    private Path processed;
    private Path output;

    @BeforeEach
    void setUp() throws IOException {
        processed = Files.createDirectory(root.resolve("processed"));
        output = root.resolve("output");
        Files.writeString(processed.resolve("a.jpg"), "image");
    }

    private RecipeCheckService service(String checkModel) {
        EsterProperties properties = new EsterProperties("", "", root.resolve("no-recipe").toString(), "", "", "", "", 1, 0,
                new EsterProperties.Check(true, checkModel, root.resolve("check").toString()), null, null, null);
        return new RecipeCheckService(extraction, properties, "main");
    }

    private static List<StoredRecipe> recipe(String image) {
        return List.of(new StoredRecipe(new Recipe("Bolo", "Bolos e Tortas", "pt", "", "",
                List.of("2 ovos"), List.of("Misture e asse"), image, TEXT, ""), "bolo"));
    }

    private Path check(RecipeCheckService service, List<StoredRecipe> recipes) {
        return service.checkAll(recipes, BookSelection.select(recipes), processed, output);
    }

    private static StoredRecipe recipeFile(String stem, String step) {
        return new StoredRecipe(new Recipe("Bolo", "Bolos e Tortas", "pt", "", "", List.of("2 ovos"), List.of(step),
                "a.jpg", "Bolo\n2 ovos\n" + step, ""), stem);
    }

    @Test
    void scansSetAsideAndRepeatedRecipesAreListed() throws IOException {
        Files.createDirectory(root.resolve("no-recipe"));
        Files.writeString(root.resolve("no-recipe/cover.jpg"), "cover");
        when(extraction.readText(any(), any())).thenReturn(TEXT);
        List<StoredRecipe> recipes = List.of(
                recipeFile("bolo", "Misture a farinha"),
                recipeFile("bolo-2", "Misture a farinha e asse"),
                recipeFile("bolo-3", "Ferva o caldo devagar"));

        String report = Files.readString(check(service("second"), recipes));

        assertTrue(report.contains("## Scans with no recipe found"), report);
        assertTrue(report.contains("- cover.jpg"), report);
        assertTrue(report.contains("| bolo-2 | bolo |"), report);
        assertTrue(report.contains("| Bolo (2) | bolo-3 | a.jpg |"), report);
    }

    @Test
    void aPageWithDifferentNumbersIsReportedWithBothReadings() throws IOException {
        when(extraction.readText(any(), eq("second"))).thenReturn("Bolo\n3 ovos\nMisture e asse");

        String report = Files.readString(check(service("second"), recipe("a.jpg")));

        assertTrue(report.contains("**1 need review**"), report);
        assertTrue(report.contains("**Numbers differ**"), report);
        assertTrue(report.contains("| 2 | 3 |"), report);
    }

    @Test
    void aPageWhereTheReadingsAgreeLooksFine() throws IOException {
        when(extraction.readText(any(), any())).thenReturn(TEXT);

        String report = Files.readString(check(service("second"), recipe("a.jpg")));

        assertTrue(report.contains("**0 need review**"), report);
        assertTrue(report.contains("## Look fine"), report);
    }

    @Test
    void aSmallDifferenceOnAFinePageIsStillListed() throws IOException {
        String longText = "Bolo\n2 ovos\n" + "misture bem a farinha com a água ".repeat(10);
        when(extraction.readText(any(), any())).thenReturn(longText.replace("Bolo", "Bola"));
        List<StoredRecipe> stored = List.of(new StoredRecipe(new Recipe("Bolo", "Bolos e Tortas", "pt", "", "",
                List.of("2 ovos"), List.of("Misture."), "a.jpg", longText, ""), "bolo"));

        String report = Files.readString(check(service("second"), stored));

        assertTrue(report.contains("**0 need review**"), report);
        assertTrue(report.contains("Bolo / Bola"), report);
    }

    @Test
    void aScanIsNotReadTwice() {
        when(extraction.readText(any(), any())).thenReturn(TEXT);

        check(service("second"), recipe("a.jpg"));
        check(service("second"), recipe("a.jpg"));

        verify(extraction, times(1)).readText(any(), any());
    }

    @Test
    void aFailedReadingIsRetriedAndThenReportedAsNotChecked() throws IOException {
        when(extraction.readText(any(), any())).thenThrow(new IllegalStateException("bad answer"));

        String report = Files.readString(check(service("second"), recipe("a.jpg")));

        verify(extraction, times(3)).readText(any(), any());
        assertTrue(report.contains("**Not checked:**"), report);
        assertFalse(Files.exists(root.resolve("check/second/a.jpg.txt")));
    }

    @Test
    void aMissingScanIsReportedAsNotChecked() throws IOException {
        String report = Files.readString(check(service("second"), recipe("gone.jpg")));

        verifyNoInteractions(extraction);
        assertTrue(report.contains("**Not checked:**"), report);
    }

    @Test
    void theMainModelCannotCheckItself() {
        assertThrows(IllegalStateException.class,
                () -> check(service("main"), recipe("a.jpg")));
    }
}
