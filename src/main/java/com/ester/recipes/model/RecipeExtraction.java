package com.ester.recipes.model;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * Root object the vision model returns for a single image.
 *
 * <p>A single image may contain more than one recipe, so the model returns a list.
 * Wrapping the list in an object (rather than asking for a bare JSON array) makes
 * structured output far more reliable with local models.</p>
 */
public record RecipeExtraction(

        @JsonPropertyDescription("All distinct recipes found in the image. Empty list if the image "
                + "contains no recipe.")
        List<Recipe> recipes
) {
}
