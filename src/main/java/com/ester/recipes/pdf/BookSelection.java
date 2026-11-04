package com.ester.recipes.pdf;

import com.ester.recipes.model.Recipe;
import com.ester.recipes.model.StoredRecipe;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chooses which stored recipes go in the book, so that no name repeats (AGENTS.md) and no recipe is
 * lost. Recipes with the same name and contents are readings of one recipe, such as a clipping
 * scanned twice, and the most complete one is kept. A name matches on the title or on the original
 * heading, as translation can name two copies differently ("Lasanha Especial", "Lasanhas Especiais").
 * Same name with other contents means different recipes, and each stays, under a numbered name if
 * its title is taken. The JSON files are not changed.
 *
 * @param recipes  the recipes for the book, with unique names
 * @param merged   the readings left out, each with the one kept instead
 * @param renamed  the recipes that had their name numbered
 * @param untitled the recipes left out because no name remains once the title is cleaned
 */
public record BookSelection(List<StoredRecipe> recipes, List<Merged> merged, List<Renamed> renamed,
                            List<StoredRecipe> untitled) {

    /**
     * Share of the smaller recipe's words the other must also have to be the same recipe. Two
     * readings of one clipping shared 98%; different recipes of one page shared at most 38%. The
     * smaller recipe counts, as one reading may have lost part of the text.
     */
    private static final double SAME_RECIPE = 0.6;

    /** Shorter words and numbers are left out: "de", "com" and "1" say nothing about a recipe. */
    private static final Pattern MEANINGFUL_WORD = Pattern.compile("\\p{L}{4,}");

    public record Merged(StoredRecipe kept, StoredRecipe leftOut) {
    }

    public record Renamed(StoredRecipe recipe, String newTitle) {
    }

    public static BookSelection select(List<StoredRecipe> stored) {
        List<StoredRecipe> untitled = new ArrayList<>();
        List<StoredRecipe> titled = new ArrayList<>();
        Set<String> titlesTaken = new HashSet<>();
        for (StoredRecipe recipe : stored) {
            String title = nameKey(recipe.recipe().title());
            if (title.isBlank()) {
                untitled.add(recipe);
            } else {
                titled.add(recipe);
                titlesTaken.add(title);
            }
        }

        List<StoredRecipe> recipes = new ArrayList<>();
        List<Merged> merged = new ArrayList<>();
        List<Renamed> renamed = new ArrayList<>();
        Set<String> titlesUsed = new HashSet<>();
        for (List<StoredRecipe> sameName : groupByName(titled)) {
            for (List<StoredRecipe> readings : groupBySameRecipe(sameName)) {
                StoredRecipe best = readings.stream()
                        .max(Comparator.comparingInt(BookSelection::wordCount)).orElseThrow();
                readings.stream().filter(r -> r != best).forEach(r -> merged.add(new Merged(best, r)));
                if (titlesUsed.add(nameKey(best.recipe().title()))) {
                    recipes.add(best);
                    continue;
                }
                String newTitle = numberedTitle(best.recipe().title(), titlesTaken);
                titlesUsed.add(nameKey(newTitle));
                renamed.add(new Renamed(best, newTitle));
                recipes.add(new StoredRecipe(withTitle(best.recipe(), newTitle), best.stem()));
            }
        }
        return new BookSelection(recipes, merged, renamed, untitled);
    }

    private static String numberedTitle(String title, Set<String> titlesTaken) {
        for (int number = 2; ; number++) {
            String numbered = title + " (" + number + ")";
            if (titlesTaken.add(nameKey(numbered))) {
                return numbered;
            }
        }
    }

    private static String nameKey(String title) {
        return RecipePdfService.strip(RecipePdfService.displayTitle(title));
    }

    /** The recipe's names: its title and, once it has been read, the heading it was printed under. */
    private static Set<String> names(StoredRecipe stored) {
        Set<String> names = new HashSet<>();
        names.add(nameKey(stored.recipe().title()));
        String original = stored.recipe().original();
        if (original != null && !original.isBlank()) {
            String heading = nameKey(original.strip().lines().findFirst().orElse(""));
            if (!heading.isBlank()) {
                names.add(heading);
            }
        }
        return names;
    }

    /** Groups recipes linked by any name, so that a title can link two copies whose headings differ. */
    private static List<List<StoredRecipe>> groupByName(List<StoredRecipe> recipes) {
        List<List<StoredRecipe>> groups = new ArrayList<>();
        List<Set<String>> groupNames = new ArrayList<>();
        for (StoredRecipe recipe : recipes) {
            List<StoredRecipe> group = new ArrayList<>(List.of(recipe));
            Set<String> names = names(recipe);
            for (int i = groups.size() - 1; i >= 0; i--) {
                if (!Collections.disjoint(groupNames.get(i), names)) {
                    group.addAll(0, groups.remove(i));
                    names.addAll(groupNames.remove(i));
                }
            }
            groups.add(group);
            groupNames.add(names);
        }
        return groups;
    }

    private static List<List<StoredRecipe>> groupBySameRecipe(List<StoredRecipe> sameName) {
        List<List<StoredRecipe>> groups = new ArrayList<>();
        for (StoredRecipe recipe : sameName) {
            List<StoredRecipe> group = groups.stream()
                    .filter(g -> g.stream().anyMatch(other -> sameRecipe(recipe, other)))
                    .findFirst().orElse(null);
            if (group == null) {
                group = new ArrayList<>();
                groups.add(group);
            }
            group.add(recipe);
        }
        return groups;
    }

    private static boolean sameRecipe(StoredRecipe first, StoredRecipe second) {
        // The original text when both have it: translation words the same text differently.
        boolean originals = hasOriginal(first.recipe()) && hasOriginal(second.recipe());
        Set<String> a = words(first.recipe(), originals);
        Set<String> b = words(second.recipe(), originals);
        if (a.isEmpty() || b.isEmpty()) {
            return true; // one has nothing of its own to lose
        }
        Set<String> common = new HashSet<>(a);
        common.retainAll(b);
        return (double) common.size() / Math.min(a.size(), b.size()) >= SAME_RECIPE;
    }

    private static boolean hasOriginal(Recipe recipe) {
        return recipe.original() != null && !recipe.original().isBlank();
    }

    private static Set<String> words(Recipe recipe, boolean original) {
        Set<String> words = new HashSet<>();
        Matcher matcher = MEANINGFUL_WORD.matcher(original ? normalized(recipe.original()) : plainText(recipe));
        while (matcher.find()) {
            words.add(matcher.group());
        }
        return words;
    }

    private static int wordCount(StoredRecipe stored) {
        Recipe recipe = stored.recipe();
        return (hasOriginal(recipe) ? recipe.original() : plainText(recipe)).split("\\s+").length;
    }

    private static String plainText(Recipe recipe) {
        List<String> parts = new ArrayList<>();
        if (recipe.ingredients() != null) {
            parts.addAll(recipe.ingredients());
        }
        if (recipe.steps() != null) {
            parts.addAll(recipe.steps());
        }
        return normalized(String.join(" ", parts));
    }

    private static String normalized(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }

    private static Recipe withTitle(Recipe r, String title) {
        return new Recipe(title, r.category(), r.originalLanguage(), r.yield(), r.date(), r.ingredients(),
                r.steps(), r.sourceImage(), r.original(), r.translated());
    }
}
