package com.ester.recipes.service;

import com.ester.recipes.model.Recipe;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageGenerationServiceTest {

    @Test
    void promptIncludesStyleTitleAndIngredients() {
        Recipe r = new Recipe("Bolo de Cenoura", "Bolos e Tortas", "pt", "8 porções", "1990",
                List.of("2 xícaras de farinha de trigo", "300g de cenoura", "3 ovos"),
                List.of("Misture tudo."), "img.jpg", "", "");

        String prompt = ImageGenerationService.buildPositivePrompt(r);

        assertTrue(prompt.contains("anime style food still life"), prompt);
        assertTrue(prompt.contains("Bolo de Cenoura"), prompt);
        assertTrue(prompt.contains("cenoura"), prompt);
        // Quantities/measures are stripped from the ingredient hints.
        assertFalse(prompt.matches(".*\\b300g\\b.*"), prompt);
    }

    @Test
    void handlesEmptyRecipeGracefully() {
        Recipe r = new Recipe("", "Outras", "", "", "", List.of(), List.of(), "", "", "");
        String prompt = ImageGenerationService.buildPositivePrompt(r);
        assertTrue(prompt.contains("anime style food still life"), prompt);
    }
}
