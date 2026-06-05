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
 */
@ConfigurationProperties(prefix = "ester")
public record EsterProperties(
        String inputDir,
        String processedDir,
        String jsonDir,
        String outputDir,
        String pdfName,
        String pdfTitle
) {
}
