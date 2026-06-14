package com.ester.recipes;

import com.ester.recipes.config.EsterProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * ester-recipes — reads scanned cooking-recipe images with a local Ollama vision
 * model, writes one JSON file per recipe, and renders a sorted PDF cookbook.
 */
@SpringBootApplication
@EnableConfigurationProperties(EsterProperties.class)
public class EsterRecipesApplication {

    public static void main(String[] args) {
        SpringApplication.run(EsterRecipesApplication.class, args);
    }
}
