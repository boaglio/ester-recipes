package com.ester.recipes.service;

import com.ester.recipes.config.EsterProperties;
import com.ester.recipes.model.Recipe;
import com.ester.recipes.pdf.RecipePdfService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecipePipelineTest {

    @TempDir
    Path root;

    private final RecipeExtractionService extraction = mock(RecipeExtractionService.class);
    private final RecipePdfService pdf = mock(RecipePdfService.class);
    private RecipePipeline pipeline;
    private Path pending;
    private Path processed;

    private static final List<Recipe> ONE_RECIPE = List.of(new Recipe("Bolo", "Bolos e Tortas", "pt", "", "",
            List.of("2 ovos"), List.of("Misture."), "a.jpg", "", ""));

    @BeforeEach
    void setUp() throws IOException {
        pending = Files.createDirectory(root.resolve("pending"));
        processed = root.resolve("processed");
        EsterProperties properties = new EsterProperties(pending.toString(), processed.toString(),
                root.resolve("no-recipe").toString(), root.resolve("json").toString(), root.resolve("output").toString(),
                "x.pdf", "Receitas da Ester", 1, 0, null, null, null, null);
        pipeline = pipeline(properties);
    }

    private RecipePipeline pipeline(EsterProperties properties) {
        RecipePipeline p = new RecipePipeline(properties, extraction, new RecipeJsonStore(),
                mock(RecipeCheckService.class), mock(TranslationFixService.class), mock(ImageGenerationService.class), pdf,
                new SimpleAsyncTaskExecutor());
        p.retryPause = Duration.ZERO;
        return p;
    }

    @Test
    void theReviewedBookReadsNoScanAndLeavesTheReviewedFilesAsTheyAre() throws IOException {
        Files.writeString(pending.resolve("a.jpg"), "image");
        Path reviewed = Files.createDirectory(root.resolve("reviewed"));
        // Full caps would be lowered by the normal run's clean-up; the reviewed run must not touch it.
        String json = """
                {"title":"BOLO","category":"Bolos e Tortas","originalLanguage":"pt","yield":"","date":"",
                 "ingredients":["2 ovos"],"steps":["Misture."],"sourceImage":"a.jpg","original":"","translated":""}
                """;
        Path file = Files.writeString(reviewed.resolve("bolos-e-tortas-bolo.json"), json);
        EsterProperties properties = new EsterProperties(pending.toString(), processed.toString(),
                root.resolve("no-recipe").toString(), root.resolve("json").toString(), root.resolve("output").toString(),
                "x.pdf", "Receitas da Ester", 1, 0, null, null, null,
                new EsterProperties.Reviewed(true, reviewed.toString()));

        pipeline(properties).run(null);

        verify(extraction, never()).extract(any());
        assertTrue(Files.exists(pending.resolve("a.jpg")));
        assertEquals(json, Files.readString(file));
        verify(pdf).write(any(), any(), any(), any());
    }

    @Test
    void anEmptyReviewedFolderStopsWithAClearMessage() {
        EsterProperties properties = new EsterProperties(pending.toString(), processed.toString(),
                root.resolve("no-recipe").toString(), root.resolve("json").toString(), root.resolve("output").toString(),
                "x.pdf", "Receitas da Ester", 1, 0, null, null, null,
                new EsterProperties.Reviewed(true, root.resolve("reviewed").toString()));

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> pipeline(properties).run(null));

        assertTrue(failure.getMessage().contains("No reviewed recipes"), failure.getMessage());
        verify(pdf, never()).write(any(), any(), any(), any());
    }

    @Test
    void aLoneImageIsRetriedAfterAFailure() throws IOException {
        Path image = Files.writeString(pending.resolve("a.jpg"), "image");
        when(extraction.extract(any()))
                .thenThrow(new IllegalStateException("bad answer"))
                .thenReturn(ONE_RECIPE);

        pipeline.run(null);

        verify(extraction, times(2)).extract(any());
        assertFalse(Files.exists(image));
        assertTrue(Files.exists(processed.resolve("a.jpg")));
        verify(pdf).write(any(), any(), any(), any());
    }

    @Test
    void aPageThatKeepsHavingNoRecipeIsSetAsideAndTheBookIsBuilt() throws IOException {
        Path cover = Files.writeString(pending.resolve("cover.jpg"), "cover");
        Files.writeString(pending.resolve("page.jpg"), "page");
        when(extraction.extract(any())).thenAnswer(call -> {
            Path image = call.getArgument(0);
            if (image.getFileName().toString().equals("cover.jpg")) {
                throw new RecipeExtractionService.NoRecipeFoundException("cover.jpg");
            }
            return ONE_RECIPE;
        });

        pipeline.run(null);

        verify(extraction, times(5)).extract(cover);
        assertFalse(Files.exists(cover));
        assertTrue(Files.exists(root.resolve("no-recipe/cover.jpg")));
        assertTrue(Files.exists(processed.resolve("page.jpg")));
        verify(pdf).write(any(), any(), any(), any());
    }

    @Test
    void aPageFoundToHaveARecipeOnARetryIsNotSetAside() throws IOException {
        Files.writeString(pending.resolve("a.jpg"), "image");
        when(extraction.extract(any()))
                .thenThrow(new RecipeExtractionService.NoRecipeFoundException("a.jpg"))
                .thenReturn(ONE_RECIPE);

        pipeline.run(null);

        assertTrue(Files.exists(processed.resolve("a.jpg")));
        assertFalse(Files.exists(root.resolve("no-recipe/a.jpg")));
    }

    @Test
    void aPageThatEndsOnARealErrorStaysPendingAndStopsTheBook() throws IOException {
        Path image = Files.writeString(pending.resolve("a.jpg"), "image");
        when(extraction.extract(any()))
                .thenThrow(new RecipeExtractionService.NoRecipeFoundException("a.jpg"))
                .thenThrow(new IllegalStateException("cut off"));

        assertThrows(IllegalStateException.class, () -> pipeline.run(null));

        assertTrue(Files.exists(image));
        verify(pdf, never()).write(any(), any(), any(), any());
    }

    @Test
    void theBookIsNotBuiltWhileAnImageRemainsUnprocessed() throws IOException {
        Path image = Files.writeString(pending.resolve("a.jpg"), "image");
        when(extraction.extract(any())).thenThrow(new IllegalStateException("No recipes detected"));

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> pipeline.run(null));

        assertTrue(failure.getMessage().contains("a.jpg"));
        verify(extraction, times(5)).extract(any());
        assertTrue(Files.exists(image));
        verify(pdf, never()).write(any(), any(), any(), any());
    }

    @Test
    void aFailedMoveLeavesNoRecipesToDuplicateOnTheRetry() throws IOException {
        Files.writeString(pending.resolve("a.jpg"), "image");
        Files.writeString(processed.getParent().resolve("processed"), "a file where the folder should be");
        when(extraction.extract(any())).thenReturn(ONE_RECIPE);

        assertThrows(IllegalStateException.class, () -> pipeline.run(null));

        assertEquals(List.of(), list(root.resolve("json")));
    }

    @Test
    void aDifferentImageReusingAProcessedNameIsStillRead() throws IOException {
        Files.createDirectory(processed);
        Files.writeString(processed.resolve("a.jpg"), "old image");
        Files.writeString(pending.resolve("a.jpg"), "new image");
        when(extraction.extract(any())).thenReturn(ONE_RECIPE);

        pipeline.run(null);

        verify(extraction).extract(any());
        assertEquals("old image", Files.readString(processed.resolve("a.jpg")));
        assertEquals("new image", Files.readString(processed.resolve("a-2.jpg")));
    }

    @Test
    void theSameImageIsNotReadTwice() throws IOException {
        Files.createDirectory(processed);
        Files.writeString(processed.resolve("a.jpg"), "image");
        Files.writeString(pending.resolve("a.jpg"), "image");

        pipeline.run(null);

        verify(extraction, never()).extract(any());
    }

    private static List<String> list(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).toList();
        }
    }
}
