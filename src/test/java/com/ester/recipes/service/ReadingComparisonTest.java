package com.ester.recipes.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadingComparisonTest {

    private static final String BOLO = "Bolo de fubá\n2 ovos\n1 xícara de leite\nMisture tudo e asse por 40 minutos";

    @Test
    void identicalReadingsNeedNoReview() {
        ReadingComparison.Result result = ReadingComparison.compare(List.of(BOLO), BOLO);

        assertEquals(1.0, result.agreement());
        assertTrue(result.differences().isEmpty());
        assertFalse(result.needsReview());
    }

    @Test
    void caseAccentsPunctuationListNumbersAndSplittingAreIgnored() {
        String other = "BOLO DE FUBA\n2 ovos.\n1 xicara de leite\n1. Misture tudo\n2. e asse por 40 minutos.";

        assertEquals(1.0, ReadingComparison.compare(List.of(BOLO), other).agreement());
    }

    @Test
    void aDifferentQuantityNeedsReviewEvenIfAlmostEverythingAgrees() {
        String long1 = "Massa\n1 colher de fermento\n" + "misture bem a farinha com a água morna ".repeat(20);
        String long2 = long1.replace("1 colher", "2 colher");

        ReadingComparison.Result result = ReadingComparison.compare(List.of(long1), long2);

        assertTrue(result.agreement() > ReadingComparison.MIN_AGREEMENT);
        assertTrue(result.quantitiesDiffer());
        assertTrue(result.needsReview());
        assertEquals(List.of(new ReadingComparison.Difference("1", "2")), result.differences());
    }

    @Test
    void aMisreadWordIsShownSideBySide() {
        ReadingComparison.Result result = ReadingComparison.compare(List.of("8 leoa e farina"), "8 uova e farina");

        assertEquals(List.of(new ReadingComparison.Difference("leoa", "uova")), result.differences());
        assertFalse(result.quantitiesDiffer());
    }

    @Test
    void aLineOnlyOneModelReadWithItsQuantityNeedsReview() {
        ReadingComparison.Result result = ReadingComparison.compare(List.of("Massa\n1 kg de carne\nsal"), "Massa\nsal");

        assertEquals(List.of(new ReadingComparison.Difference("1 kg de carne", "")), result.differences());
        assertTrue(result.quantitiesDiffer());
    }

    @Test
    void anEndingCutOffNeedsReviewEvenIfAlmostEverythingAgrees() {
        String whole = "misture bem a farinha com a água morna ".repeat(20) + "leve ao forno e sirva";
        String cut = "misture bem a farinha com a água morna ".repeat(20) + "leve";

        ReadingComparison.Result result = ReadingComparison.compare(List.of(whole), cut);

        assertTrue(result.agreement() > ReadingComparison.MIN_AGREEMENT);
        assertTrue(result.passageMissing());
        assertTrue(result.needsReview());
    }

    @Test
    void lowAgreementNeedsReviewEvenWithoutNumbers() {
        ReadingComparison.Result result = ReadingComparison.compare(List.of("misture bem a massa"), "ave jeje quede aliar");

        assertFalse(result.quantitiesDiffer());
        assertTrue(result.needsReview());
    }

    @Test
    void recipesAreMatchedWhateverOrderTheyAreStoredIn() {
        String page = "Bolo de fubá\n2 ovos\nasse bem\nSopa de feijão\n1 litro de água\nferva bem";

        ReadingComparison.Result result = ReadingComparison.compare(
                List.of("Sopa de feijão\n1 litro de água\nferva bem", "Bolo de fubá\n2 ovos\nasse bem"), page);

        assertEquals(1.0, result.agreement());
    }

    @Test
    void emptyReadingsNeedReview() {
        assertTrue(ReadingComparison.compare(List.of(""), "").needsReview());
    }

    @Test
    void fractionsWrittenEitherWayAreTheSameNumber() {
        assertFalse(ReadingComparison.compare(List.of("½ xícara"), "1/2 xícara").quantitiesDiffer());
    }
}
