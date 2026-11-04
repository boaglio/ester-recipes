package com.ester.recipes.pdf;

import com.ester.recipes.model.Recipe;
import com.ester.recipes.model.StoredRecipe;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookSelectionTest {

    private static final String HEADLINE = "Novas receitas com o delicioso atum";

    private static StoredRecipe recipe(String stem, String title, List<String> ingredients, String step) {
        return new StoredRecipe(new Recipe(title, "Saladas", "pt", "", "", ingredients, List.of(step),
                "a.jpg", "", ""), stem);
    }

    private static List<String> titles(BookSelection selection) {
        return selection.recipes().stream().map(r -> r.recipe().title()).toList();
    }

    @Test
    void differentRecipesUnderOneNameAreAllKeptWithUniqueNames() {
        BookSelection selection = BookSelection.select(List.of(
                recipe("a", HEADLINE, List.of("2 latas de atum", "1 xícara de salsão", "amendoim salgado"),
                        "Escorra o azeite e aqueça mexendo"),
                recipe("b", HEADLINE, List.of("1 lata de atum", "1 lata de ervilhas", "cenoura ralada"),
                        "Cozinhe a batata e tempere com vinagre"),
                recipe("c", HEADLINE, List.of("2 latas de atum", "abobrinha em cubinhos", "pães sírios"),
                        "Frite a cebola e polvilhe queijo")));

        assertEquals(List.of(HEADLINE, HEADLINE + " (2)", HEADLINE + " (3)"), titles(selection));
        assertTrue(selection.merged().isEmpty());
        assertEquals(2, selection.renamed().size());
    }

    @Test
    void twoReadingsOfOneRecipeBecomeTheMostCompleteOne() {
        StoredRecipe partial = recipe("a", "Lasagnoni", List.of("Lasagnoni alle vongole"),
                "Cuocete la pasta con aglio e vongole");
        StoredRecipe complete = recipe("b", "Lasagnoni",
                List.of("Lasagnoni alle vongole", "uno spicchio d'aglio", "vongole", "prezzemolo"),
                "Cuocete la pasta con aglio e vongole, poi aggiungete il prezzemolo");

        BookSelection selection = BookSelection.select(List.of(partial, complete));

        assertEquals(List.of(complete), selection.recipes());
        assertEquals(List.of(new BookSelection.Merged(complete, partial)), selection.merged());
    }

    @Test
    void differentQuantitiesOfOneRecipeStillCountAsOneRecipe() {
        BookSelection selection = BookSelection.select(List.of(
                recipe("a", "Queijo com nozes", List.of("100g de queijo fresco", "nozes picadas"), "Misture bem tudo"),
                recipe("b", "Queijo com nozes", List.of("300g de queijo fresco", "nozes picadas"), "Misture bem tudo")));

        assertEquals(1, selection.recipes().size());
        assertEquals(1, selection.merged().size());
    }

    @Test
    void namesThatOnlyPrintAlikeAreTheSameName() {
        BookSelection selection = BookSelection.select(List.of(
                recipe("a", "Sopa, de Feijão", List.of("feijão preto"), "Ferva devagar"),
                recipe("b", "SOPA DE FEIJAO", List.of("alface crespa"), "Lave as folhas")));

        assertEquals("SOPA DE FEIJAO (2)", titles(selection).get(1));
    }

    @Test
    void aNumberedNameDoesNotCollideWithARealOne() {
        BookSelection selection = BookSelection.select(List.of(
                recipe("a", "Bolo", List.of("farinha branca"), "Asse devagar"),
                recipe("b", "Bolo", List.of("alface crespa"), "Lave as folhas"),
                recipe("c", "Bolo (2)", List.of("feijão preto"), "Ferva bastante")));

        assertEquals(3, titles(selection).stream().distinct().count());
    }

    private static StoredRecipe read(String stem, String title, String original) {
        return new StoredRecipe(new Recipe(title, "Massas", "it", "", "", List.of(), List.of("Tradução " + stem),
                "a.jpg", original, ""), stem);
    }

    @Test
    void copiesTranslatedUnderDifferentNamesAreStillOneRecipe() {
        StoredRecipe partial = read("a", "Lasanha Especial", "LASAGNE SPECIALI\nCuocere le lasagne");
        StoredRecipe complete = read("b", "Lasanhas Especiais",
                "LASAGNE SPECIALI\nCuocere le lasagne\nmozzarella tagliata\nacciughe spinate");

        BookSelection selection = BookSelection.select(List.of(partial, complete));

        assertEquals(List.of(complete), selection.recipes());
        assertEquals(List.of(new BookSelection.Merged(complete, partial)), selection.merged());
    }

    @Test
    void differentRecipesUnderOneHeadingKeepDifferentTitlesUnnumbered() {
        BookSelection selection = BookSelection.select(List.of(
                read("a", "Sopa de Feijão", "RICETTE\nfagioli neri bollire lentamente"),
                read("b", "Salada Verde", "RICETTE\nlattuga lavare foglie croccanti")));

        assertEquals(List.of("Sopa de Feijão", "Salada Verde"), titles(selection));
        assertTrue(selection.merged().isEmpty());
        assertTrue(selection.renamed().isEmpty());
    }

    @Test
    void differentHeadingsWithOneTranslatedTitleStayDifferentRecipes() {
        BookSelection selection = BookSelection.select(List.of(
                read("a", "Bolo", "TORTA\nfarina zucchero burro cuocere"),
                read("b", "Bolo", "DOLCE\nlattuga pomodori aceto lavare")));

        assertEquals(List.of("Bolo", "Bolo (2)"), titles(selection));
    }

    @Test
    void aRecipeWithNoNameLeftIsReported() {
        BookSelection selection = BookSelection.select(List.of(recipe("a", "Yield", List.of("ovo"), "Ferva")));

        assertTrue(selection.recipes().isEmpty());
        assertEquals(1, selection.untitled().size());
    }

    @Test
    void selectingAgainChangesNothing() {
        BookSelection first = BookSelection.select(List.of(
                recipe("a", "Bolo", List.of("farinha branca"), "Asse devagar"),
                recipe("b", "Bolo", List.of("alface crespa"), "Lave as folhas")));

        BookSelection second = BookSelection.select(first.recipes());

        assertEquals(titles(first), titles(second));
        assertTrue(second.merged().isEmpty());
        assertTrue(second.renamed().isEmpty());
    }
}
