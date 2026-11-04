package com.ester.recipes.service;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps a recipe onto a fixed, canonical Brazilian-Portuguese taxonomy. Per agents.md there is
 * NO "Outras" bucket — every recipe must land in a real food category.
 *
 * <p>Signals, in priority order:</p>
 * <ol>
 *   <li><b>Title starting with a dish-word</b> (pizza, sopa, bolo, pudim…) — Portuguese names
 *       the dish first, so "Pudim de Pão" is a pudim and "Frango ao Molho" is not a sauce.</li>
 *   <li><b>Model category</b> — exact canonical match, then keyword/synonym.</li>
 *   <li><b>Earliest food word anywhere in the title</b> — dish, side dish or main ingredient.</li>
 *   <li><b>Ingredients</b> — a protein/pasta/sweet keyword reveals what the dish is.</li>
 *   <li><b>Default</b> — this collection is dessert-heavy, so the last resort is "Sobremesas".</li>
 * </ol>
 *
 * <p>Keywords match as whole words (optional trailing "s"), so "bolo" ≠ "bolonhesa".</p>
 */
public final class CategoryNormalizer {

    /** Closed set of categories the PDF groups by — no "Outras". */
    public static final List<String> CANONICAL = List.of(
            "Entradas e Petiscos",
            "Saladas",
            "Sopas e Caldos",
            "Massas",
            "Carnes",
            "Aves",
            "Peixes e Frutos do Mar",
            "Acompanhamentos",
            "Molhos e Temperos",
            "Pães",
            "Bolos e Tortas",
            "Sobremesas",
            "Bebidas",
            "Doces e Conservas");

    /** Last resort when no signal matches (collection skews sweet). */
    private static final String DEFAULT_CATEGORY = "Sobremesas";

    private static final Map<String, String> BY_NORMALIZED = new LinkedHashMap<>();
    private static final Map<Pattern, String> TITLE_KEYWORDS = new LinkedHashMap<>();
    private static final Map<Pattern, String> CATEGORY_KEYWORDS = new LinkedHashMap<>();
    private static final Map<Pattern, String> INGREDIENT_KEYWORDS = new LinkedHashMap<>();
    private static final Map<Pattern, String> SIDE_DISH_KEYWORDS = new LinkedHashMap<>();

    static {
        for (String canonical : CANONICAL) {
            BY_NORMALIZED.put(strip(canonical), canonical);
        }

        // Title dish-words (name the dish, not an ingredient).
        // Longer phrases win over their first word: a fruit salad is a dessert, not a salad.
        title("Sobremesas", "salada de frutas", "salada de fruta", "arroz doce", "arroz-doce");
        title("Bebidas", "caldo de cana");
        title("Pães", "pizza", "pao", "paes", "focaccia", "rosca", "broa");
        title("Sopas e Caldos", "sopa", "sopao", "caldo", "canja", "zuppa", "minestra");
        title("Saladas", "salada", "insalata");
        title("Molhos e Temperos", "molho", "vinagrete", "maionese", "pesto");
        title("Doces e Conservas", "geleia", "compota", "marmelada", "conserva", "picles");
        title("Sobremesas", "pudim", "mousse", "sorvete", "geladinho", "pave", "brigadeiro",
                "beijinho", "gelatina", "flan", "quindim", "manjar", "ambrosia", "rabanada", "rabanadas");
        title("Bolos e Tortas", "bolo", "torta", "cuca", "petit-four", "petit-fours");
        title("Massas", "lasanha", "lasagne", "nhoque", "gnocchi", "macarrao", "macarronada",
                "risoto", "risotto", "talharim", "ravioli", "espaguete", "canelone", "rondelli",
                "maccheroni", "capeletti", "capelete", "penne", "rondele");
        title("Bebidas", "ponche", "bebida", "bibita", "drink", "coquetel", "batida", "vitamina",
                "milk-shake", "milkshake", "refresco", "suco");

        // Model-category synonyms (model usually already returns a canonical value).
        category("Entradas e Petiscos", "petisco", "petiscos", "entrada", "entradas", "aperitivo",
                "antipasto", "tira gosto", "salgados", "salgadinhos");
        category("Saladas", "salada", "saladas");
        category("Sopas e Caldos", "sopa", "sopas", "caldo", "caldos", "zuppa", "minestra");
        category("Massas", "massa", "massas", "pasta");
        category("Carnes", "carne", "carnes");
        category("Aves", "ave", "aves", "frango", "pollo");
        category("Peixes e Frutos do Mar", "peixe", "peixes", "frutos do mar", "pesce", "marisco", "mariscos");
        category("Acompanhamentos", "acompanhamento", "acompanhamentos", "guarnicao", "guarnicoes");
        category("Molhos e Temperos", "molho", "molhos", "tempero", "temperos", "condimento", "condimentos");
        category("Pães", "pao", "paes", "pane");
        category("Bolos e Tortas", "bolo", "bolos", "torta", "tortas", "torte");
        category("Sobremesas", "sobremesa", "sobremesas", "doce de colher", "dolce", "dessert");
        category("Bebidas", "bebida", "bebidas", "drink", "bibita");
        category("Doces e Conservas", "doce", "doces", "geleia", "geleias", "compota", "conserva",
                "conservas", "confeitaria");

        // Ingredient signals — mainly to route SAVORY dishes away from the sweet default.
        // Order matters: poultry/fish/pasta before generic "carne".
        ingredient("Aves", "frango", "galinha", "peru", "pato", "chester", "codorna");
        ingredient("Peixes e Frutos do Mar", "peixe", "bacalhau", "camarao", "lula", "polvo",
                "sardinha", "atum", "salmao", "siri", "ostra", "mexilhao", "merluza", "pescada");
        ingredient("Massas", "macarrao", "espaguete", "lasanha", "nhoque", "talharim", "ravioli",
                "penne", "capeletti");
        ingredient("Carnes", "carne", "bife", "alcatra", "patinho", "costela", "lombo", "porco",
                "vitela", "cordeiro", "linguica", "bacon", "picanha", "maminha", "pernil", "carneiro",
                "coelho", "fil mignon", "file mignon", "cupim");
        ingredient("Sobremesas", "leite condensado", "chocolate", "cacau", "chantilly", "sorvete");

        // Only a weak signal: "Arroz de Forno com Frango" may well be a main dish.
        addAll(SIDE_DISH_KEYWORDS, "Acompanhamentos", "arroz", "feijao", "farofa", "pure", "polenta",
                "batata", "legume");
    }

    private CategoryNormalizer() {
    }

    /**
     * @param title       the (translated) recipe title — primary signal
     * @param ingredients ingredient lines — used to tell savory dishes apart
     * @param rawCategory the category the model returned — secondary signal
     * @return one of {@link #CANONICAL}, never "Outras"
     */
    public static String normalize(String title, List<String> ingredients, String rawCategory) {
        Match dish = earliestMatch(title, TITLE_KEYWORDS);
        if (dish != null && dish.start() == 0) {
            return dish.category();
        }
        if (rawCategory != null && !rawCategory.isBlank() && !isOutras(rawCategory)) {
            String exact = BY_NORMALIZED.get(strip(rawCategory));
            if (exact != null) {
                return exact;
            }
            String byCategory = keywordMatch(rawCategory, CATEGORY_KEYWORDS);
            if (byCategory != null) {
                return byCategory;
            }
        }
        Match food = earliestOf(dish, earliestOf(earliestMatch(title, INGREDIENT_KEYWORDS),
                earliestMatch(title, SIDE_DISH_KEYWORDS)));
        if (food != null) {
            return food.category();
        }
        if (ingredients != null && !ingredients.isEmpty()) {
            String joined = String.join(" ", ingredients);
            String byIngredient = keywordMatch(joined, INGREDIENT_KEYWORDS);
            if (byIngredient != null) {
                return byIngredient;
            }
        }
        return DEFAULT_CATEGORY;
    }

    /** Where a keyword was found in a title and how long it is. */
    private record Match(int start, int length, String category) {
    }

    /** The keyword found first in the text; between keywords at the same spot, the longest. */
    private static Match earliestMatch(String text, Map<Pattern, String> keywords) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String norm = strip(text);
        Match best = null;
        for (Map.Entry<Pattern, String> entry : keywords.entrySet()) {
            Matcher matcher = entry.getKey().matcher(norm);
            if (matcher.find()) {
                best = earliestOf(best, new Match(matcher.start(), matcher.end() - matcher.start(), entry.getValue()));
            }
        }
        return best;
    }

    private static Match earliestOf(Match a, Match b) {
        if (a == null || b == null) {
            return a == null ? b : a;
        }
        if (a.start() != b.start()) {
            return a.start() < b.start() ? a : b;
        }
        return a.length() >= b.length() ? a : b;
    }

    private static boolean isOutras(String raw) {
        String s = strip(raw);
        return s.equals("outras") || s.equals("outros") || s.equals("diversos") || s.equals("outro");
    }

    private static String keywordMatch(String text, Map<Pattern, String> keywords) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String norm = strip(text);
        for (Map.Entry<Pattern, String> entry : keywords.entrySet()) {
            if (entry.getKey().matcher(norm).find()) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static void title(String canonical, String... words) {
        addAll(TITLE_KEYWORDS, canonical, words);
    }

    private static void category(String canonical, String... words) {
        addAll(CATEGORY_KEYWORDS, canonical, words);
    }

    private static void ingredient(String canonical, String... words) {
        addAll(INGREDIENT_KEYWORDS, canonical, words);
    }

    private static void addAll(Map<Pattern, String> target, String canonical, String... words) {
        for (String word : words) {
            target.put(Pattern.compile("\\b" + Pattern.quote(strip(word)) + "s?\\b"), canonical);
        }
    }

    private static String strip(String text) {
        String noAccents = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return noAccents.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
