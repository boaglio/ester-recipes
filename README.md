# ester-recipes

A Spring Boot + Spring AI batch app that reads scanned **cooking-recipe images**
(Italian and Brazilian Portuguese, possibly several recipes per image, often rotated)
using a **local Ollama vision model**, and produces:

- one **JSON file per recipe** (title, category, ingredients, steps, source language, source image), and
- a single **PDF cookbook** with all recipes grouped by category and sorted alphabetically.

All recipe text is **translated to Brazilian Portuguese**; the detected original
language (`it`/`pt`) is kept as a tag.

Built on **Spring Boot 3.5.14** and **Spring AI 1.1.3**.

## How it works

```
recipes/*.jpg ──► RecipeExtractionService ──► one JSON per recipe ──► move image ──► RecipePdfService
                  (qwen2.5vl:3b via Ollama)     (recipes/json/*.json)   (recipes/processed/)  (output/ester-recipes.pdf)
```

1. `RecipePipeline` (an `ApplicationRunner`) scans `recipes/` (top level only) for
   `.jpg/.jpeg/.png`, **skipping any image already in `recipes/processed/`**.
2. For each new image, `RecipeExtractionService` sends it to the Ollama vision model with a
   prompt that de-rotates, transcribes, translates to PT-BR, and classifies each recipe.
   A **JSON schema is passed as Ollama's `format`**, so decoding is grammar-constrained to
   the exact `{ "recipes": [ ... ] }` structure (no malformed or off-shape JSON).
3. `RecipeJsonStore` writes one pretty UTF-8 JSON per recipe into `recipes/json/` (never
   overwriting an existing file), then the image is **moved to `recipes/processed/`**.
4. `RecipePdfService` (OpenPDF) renders the cookbook from **all** JSON in `recipes/json/` —
   this run plus every previous run — so the PDF is always the complete, sorted collection.

One failing image is logged and left in place to retry; it never aborts the run.
Re-running only processes **new** images, then refreshes the PDF.

## Requirements

- **Java 21+** (built/tested on JDK 25; compiles to Java 21 bytecode).
- **Maven 3.9+**
- **Ollama** running locally at `http://localhost:11434`.
- The vision model pulled:

  ```bash
  ollama pull qwen2.5vl:3b
  ```

  `qwen2.5vl:3b` is the default: it fits entirely in an 8 GB GPU (e.g. RTX 4060) and is
  faster than the 7b while still reading the scans and categorizing correctly. For higher
  OCR fidelity on hard scans, pull `qwen2.5vl:7b` and set
  `--spring.ai.ollama.chat.options.model=qwen2.5vl:7b`.

## Run

Put recipe images in `./recipes/` (two samples are included), then:

```bash
mvn spring-boot:run
```

Outputs:

- `recipes/json/<categoria>-<titulo>.json` — one file per recipe
- `recipes/processed/` — images that have been read (so re-runs skip them)
- `output/ester-recipes.pdf` — the full cookbook

Point at a different image folder without editing config:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--ester.input-dir=/path/to/images"
```

Or build a runnable jar:

```bash
mvn clean package
java -jar target/ester-recipes-1.0.0.jar
```

## Configuration (`src/main/resources/application.yml`)

| Key | Default | Meaning |
|-----|---------|---------|
| `spring.ai.ollama.base-url` | `http://localhost:11434` | Ollama server |
| `spring.ai.ollama.chat.options.model` | `qwen2.5vl:3b` | vision model |
| `spring.ai.ollama.chat.options.temperature` | `0.0` | deterministic transcription |
| `ester.input-dir` | `./recipes` | folder scanned for images (top level only) |
| `ester.processed-dir` | `./recipes/processed` | images are moved here once read |
| `ester.json-dir` | `./recipes/json` | per-recipe JSON folder |
| `ester.output-dir` | `./output` | where the PDF is written |
| `ester.pdf-name` | `ester-recipes.pdf` | cookbook file name |
| `ester.pdf-title` | `Receitas da Ester` | PDF cover title |

Any key can be overridden on the command line, e.g. `--ester.pdf-title="Meu Livro"`.

## Project layout

```
model/Recipe.java              record: one recipe (JSON shape, with @JsonPropertyDescription hints)
model/RecipeExtraction.java    record: { recipes: [...] } wrapper (one image → many recipes)
config/EsterProperties.java    @ConfigurationProperties("ester")
service/RecipeExtractionService.java  vision call + schema-constrained structured output
service/RecipeJsonStore.java   read/write per-recipe JSON (accent-free slug names, never overwrites)
service/RecipePipeline.java    end-to-end runner: skip processed, extract, move, rebuild PDF
pdf/RecipePdfService.java      OpenPDF cookbook: cover, índice, category sections
```

## Known limitations

- **`originalLanguage` can be wrong.** The model occasionally tags an Italian recipe as
  `pt` because it reports the language *after* translating. The translation itself is
  correct; only this metadata tag (and the PDF "Origem" label) may be off.
- OCR quality depends on the model and the scan. The clippings are rotated/aged, which is
  the main accuracy risk; spot-check the JSON against the images for important recipes.
- Categories are model-chosen from a suggested PT-BR list; anything unrecognized or blank
  is grouped under **"Outras"** in the PDF.
