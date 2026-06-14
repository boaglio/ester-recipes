package com.ester.recipes.model;

import java.nio.file.Path;

/**
 * A recipe as read back from disk, paired with the base name ("stem") of its JSON file.
 *
 * <p>The stem links the three artifacts for one recipe: {@code <stem>.json} (data) and
 * {@code <stem>.png} (its generated anime image, if any). Keeping the stem lets the image
 * phase and the PDF resolve the right image even when two recipes share a category+title
 * (duplicates get distinct stems like {@code ...-2}).</p>
 */
public record StoredRecipe(Recipe recipe, String stem) {

    /** Resolves this recipe's image file inside {@code imageDir} (may not exist yet). */
    public Path imageFile(Path imageDir) {
        return imageDir.resolve(stem + ".png");
    }
}
