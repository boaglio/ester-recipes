package com.ester.recipes.service;

import com.ester.recipes.model.Recipe;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslationFixServiceTest {

    private static Recipe withTitle(String title, List<String> ingredients) {
        return new Recipe(title, "Massas", "it", "", "", ingredients, List.of("Misture."), "x.jpg", "", "");
    }

    @Test
    void flagsItalianTitles() {
        assertTrue(TranslationFixService.looksNonPortuguese(
                withTitle("Maccheroni alla Chitarra", List.of())));
        assertTrue(TranslationFixService.looksNonPortuguese(
                withTitle("Insalata di Granchi", List.of())));
        assertTrue(TranslationFixService.looksNonPortuguese(
                withTitle("Pollo à Diavola", List.of())));
    }

    @Test
    void flagsItalianInsideIngredientsOrSteps() {
        assertTrue(TranslationFixService.looksNonPortuguese(
                withTitle("Bolo", List.of("100 gr di zucchero", "burro"))));
    }

    @Test
    void leavesProperPortugueseAlone() {
        assertFalse(TranslationFixService.looksNonPortuguese(
                withTitle("Bolo de Cenoura", List.of("2 xícaras de farinha de trigo", "3 ovos"))));
        assertFalse(TranslationFixService.looksNonPortuguese(
                withTitle("Panetone Geladinho", List.of("1 panetone", "sorvete de creme"))));
    }
}
