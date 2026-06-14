package com.ester.recipes.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class CategoryNormalizerTest {

    @Test
    void titleFixesGrossModelErrors() {
        assertEquals("Pães", CategoryNormalizer.normalize("Pizza", List.of(), "Bebidas"));
    }

    @Test
    void ingredientNameInTitleDoesNotOverrideCorrectModelCategory() {
        assertEquals("Sobremesas",
                CategoryNormalizer.normalize("Panetone Geladinho", List.of(), "Sobremesas"));
        assertEquals("Aves", CategoryNormalizer.normalize("Frango ao Vinho", List.of(), "Aves"));
    }

    @Test
    void ingredientsRescueSavoryDishesFromTheSweetDefault() {
        // No title/category signal, stored as "Outras" — ingredients must reveal the dish.
        assertEquals("Aves",
                CategoryNormalizer.normalize("Receita da Vovó",
                        List.of("1 kg de peito de frango", "2 colheres de manteiga"), "Outras"));
        assertEquals("Carnes",
                CategoryNormalizer.normalize("Prato Especial",
                        List.of("500g de carne moída", "1 cebola"), "Outras"));
        assertEquals("Peixes e Frutos do Mar",
                CategoryNormalizer.normalize("Delícia do Mar",
                        List.of("300g de camarão", "alho"), "Outras"));
    }

    @Test
    void neverReturnsOutras() {
        // Even with no usable signal, falls back to a real category (never "Outras").
        String c = CategoryNormalizer.normalize("Coisa Estranha", List.of(), "Outras");
        assertNotEquals("Outras", c);
        assertEquals(true, CategoryNormalizer.CANONICAL.contains(c));
    }

    @Test
    void titleDishWordsClassifyDirectly() {
        assertEquals("Sopas e Caldos", CategoryNormalizer.normalize("Sopa de Legumes", List.of(), ""));
        assertEquals("Bolos e Tortas", CategoryNormalizer.normalize("Bolo de Cenoura", List.of(), "Outras"));
        assertEquals("Massas", CategoryNormalizer.normalize("Lasanha à Bolonhesa", List.of(), "Carnes"));
        assertEquals("Bebidas", CategoryNormalizer.normalize("Ponche de Frutas", List.of(), "Outras"));
    }

    @Test
    void outrasIsNotInTheTaxonomy() {
        assertEquals(false, CategoryNormalizer.CANONICAL.contains("Outras"));
    }
}
