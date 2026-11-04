package com.ester.recipes.service;

import com.ester.recipes.model.Recipe;
import com.ester.recipes.model.StoredRecipe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RecipeJsonStoreTest {

    @TempDir
    Path dir;

    private final RecipeJsonStore store = new RecipeJsonStore();

    private static Recipe recipe(String title) {
        return new Recipe(title, "Bolos e Tortas", "pt", "", "", List.of("2 ovos"), List.of("Misture."), "a.jpg", "", "");
    }

    @Test
    void veryLongTitlesStillGetAFile() {
        List<Path> written = store.writeAll(List.of(recipe("Bolo muito comprido ".repeat(40))), dir);

        assertEquals(1, written.size());
        assertEquals(1, store.readAll(dir).size());
    }

    @Test
    void aFailedWriteLeavesNoRecipeOfThatImageBehind() throws IOException {
        // Occupying the temporary name with a directory makes the second write fail.
        Files.createDirectory(dir.resolve("bolos-e-tortas-segundo.json.tmp"));

        assertThrows(RuntimeException.class,
                () -> store.writeAll(List.of(recipe("Primeiro"), recipe("Segundo")), dir));

        assertEquals(List.of(), jsonFiles());
    }

    @Test
    void anUnreadableFileIsSkippedInsteadOfFailingTheWholeBook() throws IOException {
        store.writeAll(List.of(recipe("Bolo")), dir);
        Files.createFile(dir.resolve("vazio.json"));
        Files.writeString(dir.resolve("quebrado.json"), "{\"title\":\"Tr");

        List<StoredRecipe> recipes = store.readAll(dir);

        assertEquals(1, recipes.size());
        assertEquals("Bolo", recipes.get(0).recipe().title());
    }

    @Test
    void writingLeavesNoTemporaryFiles() throws IOException {
        List<Path> written = store.writeAll(List.of(recipe("Bolo")), dir);
        store.overwrite(recipe("Bolo Novo"), written.get(0));

        assertEquals(written, jsonFiles());
        try (Stream<Path> all = Files.list(dir)) {
            assertEquals(1, all.count());
        }
        assertEquals("Bolo Novo", store.readAll(dir).get(0).recipe().title());
    }

    @Test
    void storedRecipesAreBroughtUpToTheTextRules() {
        Path file = store.writeAll(List.of(new Recipe("BOLO DE MILHO", "Bolos e Tortas", "pt", "", "",
                List.of("2 OVOS", "2 ovos"), List.of("Misture."), "a.jpg", "BOLO DE MILHO", "")), dir).get(0);

        store.normalizeAll(dir);

        Recipe stored = store.readAll(dir).get(0).recipe();
        assertEquals(file, dir.resolve(store.readAll(dir).get(0).stem() + ".json"));
        assertEquals("Bolo de Milho", stored.title());
        assertEquals(List.of("2 Ovos"), stored.ingredients());
        assertEquals("BOLO DE MILHO", stored.original());
    }

    private List<Path> jsonFiles() throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.toString().endsWith(".json")).toList();
        }
    }
}
