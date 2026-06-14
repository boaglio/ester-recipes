package com.ester.recipes.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Application-specific settings, bound from the {@code ester.*} keys in application.yml.
 *
 * @param inputDir     directory scanned for recipe images
 * @param processedDir directory images are moved into once successfully processed
 * @param jsonDir      directory for the per-recipe JSON files
 * @param outputDir    directory where the PDF cookbook is written
 * @param pdfName      file name of the generated cookbook PDF (under outputDir)
 * @param pdfTitle     title printed on the PDF cover page
 * @param concurrency  how many images to process in parallel (each is one in-flight
 *                     Ollama request). Keep it in line with the GPU's capacity and the
 *                     server's {@code OLLAMA_NUM_PARALLEL}. Values &lt; 1 are treated as 1.
 * @param images       optional anime-image generation phase (via a local ComfyUI server)
 * @param translation  optional second routine that fixes non-Portuguese text in the JSON
 */
@ConfigurationProperties(prefix = "ester")
public record EsterProperties(
        String inputDir,
        String processedDir,
        String jsonDir,
        String outputDir,
        String pdfName,
        String pdfTitle,
        int concurrency,
        ImageGen images,
        Translation translation
) {

    /**
     * Settings for the optional translation-fix phase ({@code ester.translation.*}).
     *
     * @param enabled when false (default) the phase is skipped entirely
     * @param model   Ollama model to translate with; blank = the default chat model. A good
     *                text model can be set here if the vision model translates poorly.
     */
    public record Translation(
            boolean enabled,
            String model
    ) {
    }

    /**
     * Settings for the optional image phase ({@code ester.images.*}).
     *
     * @param enabled        when false (default) the phase is skipped entirely
     * @param baseUrl        ComfyUI server, e.g. {@code http://localhost:8188}
     * @param checkpoint     checkpoint file name as it sits in ComfyUI's models/checkpoints
     * @param steps          sampler steps
     * @param width          image width in pixels
     * @param height         image height in pixels
     * @param dir            directory the PNGs are written to (one {@code <stem>.png} per recipe)
     * @param negativePrompt shared negative prompt
     * @param timeoutSeconds max wait for one image before giving up
     */
    public record ImageGen(
            boolean enabled,
            String baseUrl,
            String checkpoint,
            int steps,
            int width,
            int height,
            String dir,
            String negativePrompt,
            int timeoutSeconds
    ) {
    }
}
