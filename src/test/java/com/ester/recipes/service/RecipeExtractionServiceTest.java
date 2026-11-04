package com.ester.recipes.service;

import com.ester.recipes.model.Recipe;
import com.ester.recipes.service.RecipeExtractionService.ModelImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.MimeTypeUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RecipeExtractionServiceTest {

    @TempDir
    Path dir;

    private Path jpeg(int width, int height) throws IOException {
        Path file = dir.resolve("scan.jpg");
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "jpg", file.toFile());
        return file;
    }

    @Test
    void eachRetryOfACutOffAnswerDiscouragesRepetitionMore() {
        assertNull(RecipeExtractionService.repeatPenalty(0));
        assertEquals(1.2, RecipeExtractionService.repeatPenalty(1));
        assertEquals(1.3, RecipeExtractionService.repeatPenalty(2));
        assertEquals(1.5, RecipeExtractionService.repeatPenalty(4));
    }

    @Test
    void aLargeScanIsScaledDownKeepingItsProportions() throws IOException {
        Path scan = jpeg(3000, 1500);
        byte[] onDisk = Files.readAllBytes(scan);

        ModelImage image = RecipeExtractionService.imageForModel(scan, 1600);
        BufferedImage sent = ImageIO.read(new ByteArrayInputStream(image.content().getContentAsByteArray()));

        assertEquals(MimeTypeUtils.IMAGE_PNG, image.mimeType());
        assertEquals(1600, sent.getWidth());
        assertEquals(800, sent.getHeight());
        assertArrayEquals(onDisk, Files.readAllBytes(scan));
    }

    @Test
    void aScanWithinTheLimitIsSentAsItIs() throws IOException {
        Path scan = jpeg(1600, 900);

        ModelImage image = RecipeExtractionService.imageForModel(scan, 1600);

        assertEquals(MimeTypeUtils.IMAGE_JPEG, image.mimeType());
        assertArrayEquals(Files.readAllBytes(scan), image.content().getContentAsByteArray());
    }

    @Test
    void noLimitSendsTheOriginal() throws IOException {
        Path scan = jpeg(3000, 1500);

        assertArrayEquals(Files.readAllBytes(scan),
                RecipeExtractionService.imageForModel(scan, 0).content().getContentAsByteArray());
    }

    @Test
    void anImageThatCannotBeDecodedIsLeftForTheModelToReject() throws IOException {
        Path broken = Files.writeString(dir.resolve("broken.jpg"), "not an image");

        assertArrayEquals(Files.readAllBytes(broken),
                RecipeExtractionService.imageForModel(broken, 1600).content().getContentAsByteArray());
    }

    @Test
    void aRecipeWithNoTitleKeepsTheImagePending() {
        Recipe titled = new Recipe("Bolo", "Bolos e Tortas", "pt", "", "", List.of(), List.of(), "a.jpg", "", "");
        Recipe untitled = new Recipe(" ", "Bolos e Tortas", "pt", "", "", List.of("2 ovos"), List.of(), "a.jpg", "", "");

        assertDoesNotThrow(() -> RecipeExtractionService.requireTitles(List.of(titled), "a.jpg"));
        assertThrows(IllegalStateException.class,
                () -> RecipeExtractionService.requireTitles(List.of(titled, untitled), "a.jpg"));
    }
}
