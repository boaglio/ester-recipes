package com.ester.recipes.model;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * A single cooking recipe extracted from an image.
 *
 * <p>All human-readable text is normalized to Brazilian Portuguese. The original
 * source language is preserved in {@link #originalLanguage()} for reference, and
 * {@link #sourceImage()} records which image file the recipe came from. {@link #original()}
 * keeps the verbatim source-language transcription and {@link #translated()} its Brazilian
 * Portuguese translation, for auditing the translation.</p>
 *
 * <p>The {@link JsonPropertyDescription} annotations double as the JSON-schema
 * field descriptions Spring AI sends to the model, guiding structured output.</p>
 */
public record Recipe(

        @JsonPropertyDescription("Recipe title in Brazilian Portuguese, e.g. 'Panetone Geladinho'.")
        String title,

        @JsonPropertyDescription("Choose EXACTLY ONE category, copied verbatim from this list: "
                + "'Entradas e Petiscos', 'Saladas', 'Sopas e Caldos', 'Massas', 'Carnes', 'Aves', "
                + "'Peixes e Frutos do Mar', 'Acompanhamentos', 'Molhos e Temperos', 'Pães', "
                + "'Bolos e Tortas', 'Sobremesas', 'Bebidas', 'Doces e Conservas', 'Outras'.")
        String category,

        @JsonPropertyDescription("ISO 639-1 code of the recipe's ORIGINAL language before translation: "
                + "'it' for Italian, 'pt' for Portuguese.")
        String originalLanguage,

        @JsonPropertyDescription("Approximate yield / servings in Brazilian Portuguese if stated, "
                + "e.g. '4 porções'. Empty string if not stated.")
        String yield,

        @JsonPropertyDescription("Any date or year printed or handwritten on the clipping, copied "
                + "exactly as it appears, e.g. '1987', '12/03/1985', 'março de 1990'. This is the "
                + "date of the recipe/clipping, NOT today's date. Empty string if no date is visible.")
        String date,

        @JsonPropertyDescription("Ingredient lines in Brazilian Portuguese, each including quantity and item, "
                + "e.g. '300g de chocolate', '1 panetone de 1kg'. One entry per ingredient.")
        List<String> ingredients,

        @JsonPropertyDescription("Ordered preparation steps in Brazilian Portuguese. One entry per step, "
                + "in the order they should be performed.")
        List<String> steps,

        @JsonPropertyDescription("File name of the source image this recipe was read from. "
                + "Leave empty; it is filled in by the application.")
        String sourceImage,

        @JsonPropertyDescription("Leave empty; the application fills this with the verbatim "
                + "original-language text.")
        String original,

        @JsonPropertyDescription("Leave empty; the application fills this with the Brazilian "
                + "Portuguese translation.")
        String translated
) {
}
