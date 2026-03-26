package io.github.aeroseira.delightify_exporter.source;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import io.github.aeroseira.delightify_exporter.model.RecipeRow;
import io.github.aeroseira.delightify_exporter.util.HashUtil;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

public class RecipeSource {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

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
                
                // 序列化配方为 JSON
                JsonObject recipeJson = serializeRecipe(recipe, recipeId, typeIdStr);
                String rawJson = GSON.toJson(recipeJson);
                int unparsed = 0;
                
                // 计算 hash
                String hashInput = recipeId.toString() + "|" + typeIdStr + "|" + rawJson;
                String hash = HashUtil.sha1(hashInput);
                
                recipes.add(new RecipeRow(
                    recipeId.toString(),
                    typeIdStr,
                    modid,
                    hash,
                    rawJson,
                    unparsed
                ));
            } catch (Exception e) {
                LOGGER.debug("Failed to process recipe {}: {}", recipeId, e.getMessage());
            }
        }
        
        return recipes;
    }
    
    /**
     * 将 Recipe 对象序列化为 JSON 格式
     */
    private JsonObject serializeRecipe(Recipe<?> recipe, ResourceLocation recipeId, String typeId) {
        JsonObject json = new JsonObject();
        json.addProperty("id", recipeId.toString());
        json.addProperty("type", typeId);
        
        // 序列化输出
        ItemStack result = recipe.getResultItem(null); // null registry access for basic info
        json.add("result", serializeItemStack(result));
        
        // 序列化输入（根据配方类型）
        JsonArray ingredients = new JsonArray();
        NonNullList<Ingredient> inputs = recipe.getIngredients();
        for (Ingredient ingredient : inputs) {
            ingredients.add(serializeIngredient(ingredient));
        }
        json.add("ingredients", ingredients);
        
        // 添加配方特定信息
        if (recipe instanceof ShapedRecipe shaped) {
            json.addProperty("pattern_width", shaped.getWidth());
            json.addProperty("pattern_height", shaped.getHeight());
        }
        
        return json;
    }
    
    /**
     * 序列化 ItemStack 为 JSON
     */
    private JsonObject serializeItemStack(ItemStack stack) {
        JsonObject json = new JsonObject();
        if (!stack.isEmpty()) {
            ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
            json.addProperty("item", itemId != null ? itemId.toString() : "unknown");
            json.addProperty("count", stack.getCount());
            if (stack.hasTag()) {
                json.addProperty("nbt", stack.getTag().toString());
            }
        }
        return json;
    }
    
    /**
     * 序列化 Ingredient 为 JSON
     */
    private JsonObject serializeIngredient(Ingredient ingredient) {
        JsonObject json = new JsonObject();
        JsonArray items = new JsonArray();
        
        ItemStack[] stacks = ingredient.getItems();
        for (ItemStack stack : stacks) {
            ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (itemId != null) {
                items.add(itemId.toString());
            }
        }
        
        json.add("items", items);
        json.addProperty("is_empty", ingredient.isEmpty());
        
        return json;
    }
}
