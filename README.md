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
2. New images are processed on virtual threads, up to `ester.concurrency` at a time.
   `RecipeExtractionService` sends each to the Ollama vision model with a prompt that
   de-rotates, transcribes, translates to PT-BR, and classifies each recipe.
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
- `output/process-YYYY-MM-DD_HH_MM_SS.log` — full log of each run (also printed to console)

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
| `spring.threads.virtual.enabled` | `true` | run pipeline tasks on virtual threads |
| `ester.concurrency` | `1` | images processed in parallel (see Performance) |
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

## Performance & scaling

The cost is the model's translation, not the image I/O. Two regimes:

- **Cold** (model not yet in VRAM): the first image of a session pays a one-time
  ~60–90s to load the 3.2 GB model.
- **Warm** (model resident): **~8–12s per single-recipe image** on an RTX 4060 with
  `qwen2.5vl:3b`. Multi-recipe images scale with the number of output tokens.

In a batch the model stays warm throughout, so plan around the warm rate, e.g. **~400
images ≈ 1–1.5 hours** (plus the one-time cold load). Images that carry 2–3 recipes each
push that higher.

**Parallelism (`ester.concurrency`)** processes several images at once on virtual threads.
It only speeds things up if the **Ollama server can run requests concurrently** — set
`OLLAMA_NUM_PARALLEL >= concurrency` on the server and ensure spare VRAM. Even then, a
single GPU running one model is largely compute-bound, so expect *sublinear* gains (often
little to none on an 8 GB card). Measured on this RTX 4060, `concurrency: 2` gave no
speedup, so the default is `1`. Raise it only after measuring on your hardware:

```bash
# on the Ollama server (systemd): sudo systemctl edit ollama  ->  Environment=OLLAMA_NUM_PARALLEL=2
mvn spring-boot:run -Dspring-boot.run.arguments="--ester.concurrency=2"
```

For a long batch, run it detached and watch the per-image / whole-run timings in the log:

```bash
nohup mvn -q spring-boot:run > run.log 2>&1 &
tail -f run.log
```

It's resumable — processed images move to `recipes/processed/` and are skipped on restart,
so you can stop and re-run anytime.

## Anime images (optional)

A second, opt-in phase can render an **anime-style image per recipe** with a local, free
[ComfyUI](https://github.com/comfyanonymous/ComfyUI) server and embed it in the PDF. It's a
separate pass so it never competes with the extraction run for the GPU.

**One-time setup**
1. Install the NVIDIA Container Toolkit (lets Docker use the GPU):
   ```bash
   sudo apt-get install -y nvidia-container-toolkit
   sudo nvidia-ctk runtime configure --runtime=docker && sudo systemctl restart docker
   ```
2. Put a free anime checkpoint in `comfyui/models/checkpoints/` (e.g. Animagine XL 4.0 or
   Illustrious XL from Hugging Face / Civitai), and set its file name as
   `ester.images.checkpoint` in `application.yml`.
3. Start ComfyUI: `docker compose up -d` (API on `http://localhost:8188`).

**Run the image phase** (after the extraction batch, so they don't share the 8 GB GPU):
```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--ester.images.enabled=true"
```
It does no new extraction (all images already processed), generates `recipes/images/<stem>.png`
for every recipe that doesn't have one yet (resumable — skips existing), then rebuilds the PDF
with each image above its recipe. The prompt is built from the recipe's title + main
ingredients (`ImageGenerationService.buildPositivePrompt`).

Config lives under `ester.images.*` (model, steps, size, negative prompt, timeout). The
ComfyUI workflow is `src/main/resources/comfyui-workflow.json` — edit it to suit your model.

## Known limitations

- **`originalLanguage` can be wrong.** The model occasionally tags an Italian recipe as
  `pt` because it reports the language *after* translating. The translation itself is
  correct; only this metadata tag (and the PDF "Origem" label) may be off.
- OCR quality depends on the model and the scan. The clippings are rotated/aged, which is
  the main accuracy risk; spot-check the JSON against the images for important recipes.
- Categories are model-chosen from a suggested PT-BR list; anything unrecognized or blank
  is grouped under **"Outras"** in the PDF.
