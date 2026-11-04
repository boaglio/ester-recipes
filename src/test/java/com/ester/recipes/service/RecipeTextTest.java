package com.ester.recipes.service;

import com.ester.recipes.model.Recipe;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecipeTextTest {

    @Test
    void numbersThatAreQuantitiesSurvive() {
        assertEquals("1.5 litros de água fervente", RecipeText.withoutListMarkers("1.5 litros de água fervente"));
        assertEquals("2-3 minutos no forno", RecipeText.withoutListMarkers("2-3 minutos no forno"));
        assertEquals("10 - 15 minutos", RecipeText.withoutListMarkers("10 - 15 minutos"));
        assertEquals("350: forno quente", RecipeText.withoutListMarkers("350: forno quente"));
        assertEquals("5 litros de água", RecipeText.withoutListMarkers("1. 5 litros de água"));
    }

    @Test
    void listMarkersAreRemovedEvenWhenRepeated() {
        assertEquals("Misture", RecipeText.withoutListMarkers("1. Misture"));
        assertEquals("Misture", RecipeText.withoutListMarkers("1. 1. Misture"));
        assertEquals("Asse", RecipeText.withoutListMarkers("2) 2) Asse"));
        assertEquals("Sirva", RecipeText.withoutListMarkers("10 - Sirva"));
        assertEquals("Misture", RecipeText.withoutListMarkers("1.Misture"));
        assertEquals("Misture", RecipeText.withoutListMarkers("1. - Misture"));
        assertEquals("", RecipeText.withoutListMarkers("1."));
    }

    @Test
    void sectionHeadingsAreNotSteps() {
        assertEquals(List.of("Modo de fazer o recheio com calma", "Misture."),
                RecipeText.steps(List.of("Modo de Fazer:", "MODO DE PREPARO", "Preparação:",
                        "Modo de fazer o recheio com calma", "Misture.")));
    }

    @Test
    void blankStepsAreDropped() {
        assertEquals(List.of("Misture"), RecipeText.steps(Arrays.asList("", "Misture", "   ", null, "2.")));
    }

    @Test
    void ingredientsRepeatedWithOtherCaseAccentsOrSpacesAreDropped() {
        assertEquals(List.of("açúcar", "300g de farinha"),
                RecipeText.ingredients(List.of("açúcar", "acucar", "AÇÚCAR", "300g de farinha", "300 g de farinha")));
    }

    @Test
    void ingredientNumbersAreRemovedOnlyWhenTheyAreListPositions() {
        assertEquals(List.of("farinha"), RecipeText.ingredients(List.of("1. farinha")));
        assertEquals(List.of("2. ovos"), RecipeText.ingredients(List.of("2. ovos")));
        assertEquals(List.of("1 ovo"), RecipeText.ingredients(List.of("- 1 ovo")));
    }

    @Test
    void wordsInFullCapitalsAreConverted() {
        assertEquals("Bolo de Fubá", RecipeText.fixAllCaps("BOLO DE FUBÁ"));
        assertEquals("(Bolo) de Milho", RecipeText.fixAllCaps("(BOLO) DE MILHO"));
        assertEquals("Pão e Vinho", RecipeText.fixAllCaps("PÃO E VINHO"));
        assertEquals("500ml de leite", RecipeText.fixAllCaps("500ML de leite"));
    }

    @Test
    void unitsAndSingleLettersKeepTheirCase() {
        assertEquals("Asse a 180°C", RecipeText.fixAllCaps("Asse a 180°C"));
        assertEquals("Rica em vitamina A", RecipeText.fixAllCaps("Rica em vitamina A"));
    }

    @Test
    void cleaningKeepsTheOriginalTextAndCanBeRepeated() {
        Recipe raw = new Recipe("BOLO DE MILHO", "Bolos e Tortas", "pt", "4 PORÇÕES", "1987",
                List.of("2 OVOS", "2 ovos"), List.of("1. 1. MISTURE TUDO"), "a.jpg", "BOLO DE MILHO", "");

        Recipe cleaned = RecipeText.clean(raw);

        assertEquals("Bolo de Milho", cleaned.title());
        assertEquals("4 Porções", cleaned.yield());
        assertEquals(List.of("2 Ovos"), cleaned.ingredients());
        assertEquals(List.of("Misture Tudo"), cleaned.steps());
        assertEquals("BOLO DE MILHO", cleaned.original());
        assertEquals(cleaned, RecipeText.clean(cleaned));
    }
}
