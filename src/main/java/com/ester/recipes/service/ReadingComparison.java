package com.ester.recipes.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compares two readings of the same scan word by word. It ignores case, accents, punctuation,
 * line breaks and list numbers, so only different words count, and how each model split the page
 * into recipes and steps doesn't matter.
 *
 * <p>Agreement doesn't prove a word right: two models can misread it the same way.</p>
 */
final class ReadingComparison {

    /** Below this share of words in common a page needs review even if its numbers agree. */
    static final double MIN_AGREEMENT = 0.90;

    /** One reading having this many more words at one place means text was skipped or cut off. */
    private static final int MISSING_PASSAGE_WORDS = 3;

    private static final Pattern WORD = Pattern.compile("\\p{L}+|\\d+");

    /** Words of one reading with no counterpart in the other, at the same place in the text. */
    record Difference(String main, String check) {
    }

    /**
     * @param agreement        share of the words the two readings have in common, 0 to 1
     * @param differences      the places where they differ, in reading order
     * @param quantitiesDiffer whether any of those places holds different numbers
     * @param passageMissing   whether one reading has a passage the other lacks
     */
    record Result(double agreement, List<Difference> differences, boolean quantitiesDiffer,
                  boolean passageMissing) {

        boolean needsReview() {
            return quantitiesDiffer || passageMissing || agreement < MIN_AGREEMENT;
        }
    }

    private record Word(String text, String key) {

        boolean isNumber() {
            return Character.isDigit(text.charAt(0));
        }
    }

    private ReadingComparison() {
    }

    /**
     * @param mainRecipes the main reading's text, one entry per recipe, in any order: stored
     *                    recipes don't remember their order on the page
     * @param check       the second reading of the whole page
     */
    static Result compare(List<String> mainRecipes, String check) {
        List<Word> checkWords = words(check);
        List<Word> mainWords = inPageOrder(mainRecipes, checkWords);
        int[][] table = commonWords(mainWords, checkWords);

        List<Difference> differences = new ArrayList<>();
        boolean quantitiesDiffer = false;
        boolean passageMissing = false;
        int common = 0;
        int m = 0;
        int c = 0;
        while (m < mainWords.size() || c < checkWords.size()) {
            if (m < mainWords.size() && c < checkWords.size()
                    && mainWords.get(m).key().equals(checkWords.get(c).key())) {
                common++;
                m++;
                c++;
                continue;
            }
            int mainStart = m;
            int checkStart = c;
            while ((m < mainWords.size() || c < checkWords.size())
                    && !(m < mainWords.size() && c < checkWords.size()
                            && mainWords.get(m).key().equals(checkWords.get(c).key()))) {
                if (c == checkWords.size() || (m < mainWords.size() && table[m + 1][c] >= table[m][c + 1])) {
                    m++;
                } else {
                    c++;
                }
            }
            List<Word> mainGap = mainWords.subList(mainStart, m);
            List<Word> checkGap = checkWords.subList(checkStart, c);
            differences.add(new Difference(text(mainGap), text(checkGap)));
            quantitiesDiffer |= !numbers(mainGap).equals(numbers(checkGap));
            passageMissing |= Math.abs(mainGap.size() - checkGap.size()) >= MISSING_PASSAGE_WORDS;
        }
        int total = mainWords.size() + checkWords.size();
        return new Result(total == 0 ? 0 : 2.0 * common / total, differences, quantitiesDiffer, passageMissing);
    }

    /** Puts each recipe where most of its words fall in the second reading of the page. */
    private static List<Word> inPageOrder(List<String> recipes, List<Word> checkWords) {
        record Placed(List<Word> words, int position) {
        }
        List<Placed> placed = new ArrayList<>();
        for (String recipe : recipes) {
            List<Word> words = words(recipe);
            placed.add(new Placed(words, medianMatch(words, checkWords)));
        }
        placed.sort(Comparator.comparingInt(Placed::position));
        List<Word> all = new ArrayList<>();
        placed.forEach(p -> all.addAll(p.words()));
        return all;
    }

    private static int medianMatch(List<Word> words, List<Word> checkWords) {
        int[][] table = commonWords(words, checkWords);
        List<Integer> matches = new ArrayList<>();
        int w = 0;
        int c = 0;
        while (w < words.size() && c < checkWords.size()) {
            if (words.get(w).key().equals(checkWords.get(c).key())) {
                matches.add(c);
                w++;
                c++;
            } else if (table[w + 1][c] >= table[w][c + 1]) {
                w++;
            } else {
                c++;
            }
        }
        return matches.isEmpty() ? Integer.MAX_VALUE : matches.get(matches.size() / 2);
    }

    /** {@code table[a][b]}: most words in common, in order, between the lists from a and from b. */
    private static int[][] commonWords(List<Word> first, List<Word> second) {
        int[][] table = new int[first.size() + 1][second.size() + 1];
        for (int a = first.size() - 1; a >= 0; a--) {
            for (int b = second.size() - 1; b >= 0; b--) {
                table[a][b] = first.get(a).key().equals(second.get(b).key())
                        ? table[a + 1][b + 1] + 1
                        : Math.max(table[a + 1][b], table[a][b + 1]);
            }
        }
        return table;
    }

    private static List<Word> words(String text) {
        List<Word> words = new ArrayList<>();
        if (text == null) {
            return words;
        }
        // NFKC turns "½" into "1⁄2", so it matches a "1/2" in the other reading.
        for (String line : Normalizer.normalize(text, Normalizer.Form.NFKC).split("\\R")) {
            Matcher matcher = WORD.matcher(RecipeText.withoutListMarkers(line));
            while (matcher.find()) {
                words.add(new Word(matcher.group(), key(matcher.group())));
            }
        }
        return words;
    }

    private static String key(String word) {
        return Normalizer.normalize(word, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    private static List<String> numbers(List<Word> words) {
        return words.stream().filter(Word::isNumber).map(Word::key).toList();
    }

    private static String text(List<Word> words) {
        return String.join(" ", words.stream().map(Word::text).toList());
    }
}
