package io.github.aeroseira.delightify_exporter.source;

import io.github.aeroseira.delightify_exporter.model.RecipeRow;
import io.github.aeroseira.delightify_exporter.util.HashUtil;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

public class RecipeSource {

    public List<RecipeRow> collect(MinecraftServer server) {
        List<RecipeRow> recipes = new ArrayList<>();
        RecipeManager recipeManager = server.getRecipeManager();
        
        // 遍历所有已知的配方 ID
        for (ResourceLocation recipeId : recipeManager.getRecipeIds().toList()) {
            try {
                Recipe<?> recipe = recipeManager.byKey(recipeId).orElse(null);
                if (recipe == null) continue;
                
                // 获取 serializer id 作为 type_id
                ResourceLocation serializerId = ForgeRegistries.RECIPE_SERIALIZERS.getKey(recipe.getSerializer());
                String typeIdStr = serializerId != null ? serializerId.toString() : "unknown";
                
                String modid = recipeId.getNamespace();
                
                // 计算 hash
                String hashInput = recipeId.toString() + "|" + typeIdStr;
                String hash = HashUtil.sha1(hashInput);
                
                recipes.add(new RecipeRow(
                    recipeId.toString(),
                    typeIdStr,
                    modid,
                    hash,
                    null,
                    1
                ));
            } catch (Exception e) {
                // 忽略解析错误，继续处理其他配方
            }
        }
        
        return recipes;
    }
}
