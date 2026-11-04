package com.ester.recipes.service;

import com.ester.recipes.model.Recipe;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text rules from agents.md shared by the stored JSON and the PDF: no words in full capitals,
 * no repeated ingredients and no doubled list numbers. Every method is idempotent.
 */
public final class RecipeText {

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    /** Words kept lowercase when a phrase in full capitals is converted ("Bolo de Fubá"). */
    private static final Set<String> CONNECTORS = Set.of("de", "da", "do", "das", "dos", "e", "a", "o",
            "as", "os", "ao", "aos", "à", "às", "com", "em", "no", "na", "nos", "nas", "para", "por", "ou");

    /**
     * A list number in front of a step. Needs a space or a following letter, so quantities such
     * as "1.5 litros" and "2-3 minutos" are left alone; a dash also must not start a range.
     */
    private static final Pattern LIST_NUMBER = Pattern.compile(
            "^\\d{1,2}(?:\\s*[.):]\\s+|\\s*-\\s+(?!\\d)|\\s*[.)\\-:](?=\\p{L}))");
    private static final Pattern BARE_NUMBER = Pattern.compile("^\\d{1,2}\\s*[.):\\-]$");
    private static final Pattern BULLET = Pattern.compile("^[-–•*]\\s*");
    private static final Pattern INGREDIENT_NUMBER = Pattern.compile("^(\\d{1,2})[.)]\\s*(?=\\p{L})");

    /** A section heading the model returned as if it were a step ("Modo de Fazer:"). */
    private static final Pattern STEP_HEADING = Pattern.compile(
            "^(modo de (fazer|preparo|preparar)|preparo|preparacao|procedimento|preparazione)\\s*:?$");

    private RecipeText() {
    }

    /** Applies the rules to the fields of a recipe; {@code original} stays verbatim. */
    public static Recipe clean(Recipe r) {
        return new Recipe(
                r.title() == null ? null : fixAllCaps(r.title()),
                r.category(),
                r.originalLanguage(),
                r.yield() == null ? null : fixAllCaps(r.yield()),
                r.date(),
                r.ingredients() == null ? null : ingredients(r.ingredients()),
                r.steps() == null ? null : steps(r.steps()),
                r.sourceImage(),
                r.original(),
                r.translated());
    }

    /** Converts each word written in full capitals; units such as "180°C" keep their case. */
    public static String fixAllCaps(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean previousWasCaps = false;
        for (String word : text.trim().split(" +")) {
            long letters = word.chars().filter(Character::isLetter).count();
            boolean caps = letters > 0 && word.equals(word.toUpperCase(PT_BR));
            String lower = word.toLowerCase(PT_BR);
            String fixed = word;
            if (caps && letters == 1) {
                // A lone "E" joins words only inside a capitalized phrase; "Vitamina A" stays.
                if (previousWasCaps && CONNECTORS.contains(lower)) {
                    fixed = lower;
                }
            } else if (caps) {
                boolean connector = !sb.isEmpty() && CONNECTORS.contains(lower);
                fixed = connector || Character.isDigit(word.charAt(0)) ? lower : capitalizeFirstLetter(lower);
                previousWasCaps = true;
            } else if (letters > 0) {
                previousWasCaps = false;
            }
            sb.append(fixed).append(' ');
        }
        return sb.toString().trim();
    }

    /** Removes blank and repeated ingredients (ignoring case, accents and spaces), keeping order. */
    public static List<String> ingredients(List<String> ingredients) {
        if (ingredients == null) {
            return List.of();
        }
        Map<String, String> unique = new LinkedHashMap<>();
        int position = 0;
        for (String ingredient : ingredients) {
            if (ingredient == null || ingredient.isBlank()) {
                continue;
            }
            position++;
            String text = fixAllCaps(BULLET.matcher(withoutListPosition(ingredient.trim(), position)).replaceFirst(""));
            if (!text.isBlank()) {
                unique.putIfAbsent(ingredientKey(text), text);
            }
        }
        return new ArrayList<>(unique.values());
    }

    /** Removes list numbers, bullets and blank entries from the preparation steps. */
    public static List<String> steps(List<String> steps) {
        if (steps == null) {
            return List.of();
        }
        List<String> cleaned = new ArrayList<>();
        for (String step : steps) {
            String text = fixAllCaps(withoutListMarkers(step));
            if (!text.isBlank() && !isStepHeading(text)) {
                cleaned.add(text);
            }
        }
        return cleaned;
    }

    private static boolean isStepHeading(String step) {
        String plain = Normalizer.normalize(step, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return STEP_HEADING.matcher(plain.toLowerCase(PT_BR).trim()).matches();
    }

    /** Strips every leading list number and bullet, so "1. 1. Misture" becomes "Misture". */
    static String withoutListMarkers(String step) {
        String text = step == null ? "" : step.trim();
        String previous;
        do {
            previous = text;
            text = LIST_NUMBER.matcher(text).replaceFirst("");
            text = BULLET.matcher(text).replaceFirst("").trim();
        } while (!text.equals(previous));
        return BARE_NUMBER.matcher(text).matches() ? "" : text;
    }

    /** Strips "2." only from the second line: elsewhere it is a quantity, as in "2. ovos". */
    private static String withoutListPosition(String ingredient, int position) {
        Matcher matcher = INGREDIENT_NUMBER.matcher(ingredient);
        if (matcher.find() && Integer.parseInt(matcher.group(1)) == position) {
            return ingredient.substring(matcher.end());
        }
        return ingredient;
    }

    private static String capitalizeFirstLetter(String word) {
        for (int i = 0; i < word.length(); i++) {
            if (Character.isLetter(word.charAt(i))) {
                return word.substring(0, i) + word.substring(i, i + 1).toUpperCase(PT_BR) + word.substring(i + 1);
            }
        }
        return word;
    }

    private static String ingredientKey(String ingredient) {
        return Normalizer.normalize(ingredient, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(PT_BR)
                .replaceAll("\\s+", "");
    }
}
