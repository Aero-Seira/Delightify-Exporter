package io.github.aeroseira.delightify_exporter.model;

public record RecipeRow(
    String recipeId,
    String typeId,
    String modid,
    String hash,
    String rawJson,
    int unparsed
) {
}
