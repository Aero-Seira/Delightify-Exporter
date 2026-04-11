package io.github.aeroseira.delightify_exporter.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import io.github.aeroseira.delightify_exporter.client.RecipeViewRenderHelper.RenderTask;
import io.github.aeroseira.delightify_exporter.client.RecipeViewRenderHelper.RenderResult;
import io.github.aeroseira.delightify_exporter.model.RecipeViewRow;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 客户端专用：配方视图采集器
 */
@OnlyIn(Dist.CLIENT)
public class RecipeViewSource {
    
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    
    private final boolean jeiAvailable;
    
    public RecipeViewSource() {
        boolean isClient = FMLEnvironment.dist == Dist.CLIENT;
        boolean jeiLoaded = ModList.get().isLoaded("jei");
        this.jeiAvailable = isClient && jeiLoaded;
        LOGGER.info("RecipeViewSource: isClient={}, jeiLoaded={}, jeiAvailable={}", 
            isClient, jeiLoaded, this.jeiAvailable);
    }
    
    public List<RecipeViewRow> collect(Set<String> typeIds) {
        LOGGER.info("Collecting recipe views for {} types", typeIds.size());
        List<RecipeViewRow> results = new ArrayList<>();
        
        if (typeIds.isEmpty()) {
            return results;
        }
        
        Map<String, RenderTask> renderTasks = new HashMap<>();
        
        for (String typeId : typeIds) {
            if (typeId == null || typeId.isEmpty()) continue;
            
            JsonObject layout = createLayout(typeId);
            if (layout != null) {
                renderTasks.put(typeId, new RenderTask(typeId, layout));
            } else {
                results.add(RecipeViewRow.unavailable(typeId, "no_layout_template"));
            }
        }
        
        LOGGER.info("Created {} render tasks", renderTasks.size());
        
        if (!renderTasks.isEmpty()) {
            renderAsync(results, renderTasks);
        }
        
        LOGGER.info("Returning {} rows", results.size());
        return results;
    }
    
    private void renderAsync(List<RecipeViewRow> results, Map<String, RenderTask> tasks) {
        LOGGER.info("Starting async render for {} recipe views...", tasks.size());
        long startTime = System.currentTimeMillis();
        
        CompletableFuture<Map<String, RenderResult>> future = RecipeViewRenderHelper.startAsyncRender(
            tasks,
            progress -> {
                int percent = (int) progress.getProgress();
                if (percent % 10 == 0 && progress.completed > 0) {
                    LOGGER.info("Progress: {}% ({}/{})", percent, progress.completed, progress.total);
                }
            }
        );
        
        while (!future.isDone()) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        try {
            Map<String, RenderResult> renderResults = future.get(5, TimeUnit.MINUTES);
            long elapsed = System.currentTimeMillis() - startTime;
            
            for (RenderResult renderResult : renderResults.values()) {
                if (renderResult.base64Png != null) {
                    results.add(new RecipeViewRow(
                        renderResult.typeId,
                        GSON.toJson(renderResult.layout),
                        renderResult.base64Png,
                        RecipeViewRow.CURRENT_SCHEMA_VERSION
                    ));
                } else {
                    results.add(RecipeViewRow.unavailable(renderResult.typeId, "render_failed"));
                }
            }
            
            LOGGER.info("Async render finished in {}ms: {} views", elapsed, renderResults.size());
        } catch (Exception e) {
            LOGGER.error("Render failed: {}", e.getMessage(), e);
        }
    }
    
    @Nullable
    private JsonObject createLayout(String typeId) {
        JsonObject layout = new JsonObject();
        layout.addProperty("schema", RecipeViewRow.CURRENT_SCHEMA_VERSION);
        layout.addProperty("type_id", typeId);
        layout.addProperty("unavailable", false);
        layout.addProperty("source", "rendered");
        
        if (typeId.equals("minecraft:crafting_shaped") || typeId.equals("minecraft:crafting_shapeless")) {
            return createCraftingLayout(layout);
        } else if (typeId.equals("minecraft:smelting") || 
                   typeId.equals("minecraft:blasting") || 
                   typeId.equals("minecraft:smoking")) {
            return createSmeltingLayout(layout);
        } else if (typeId.equals("minecraft:stonecutting")) {
            return createStonecuttingLayout(layout);
        } else if (typeId.equals("minecraft:smithing_transform") || 
                   typeId.equals("minecraft:smithing_trim")) {
            return createSmithingLayout(layout);
        } else if (typeId.equals("minecraft:campfire_cooking")) {
            return createCampfireLayout(layout);
        } else {
            return createDefaultLayout(layout);
        }
    }
    
    private JsonObject createCraftingLayout(JsonObject layout) {
        JsonObject size = new JsonObject();
        size.addProperty("w", 176);
        size.addProperty("h", 85);
        layout.add("size", size);
        
        JsonArray slots = new JsonArray();
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                JsonObject slot = new JsonObject();
                slot.addProperty("id", "in_" + (row * 3 + col));
                slot.addProperty("role", "input");
                slot.addProperty("x", 30 + col * 18);
                slot.addProperty("y", 17 + row * 18);
                slot.addProperty("w", 18);
                slot.addProperty("h", 18);
                slots.add(slot);
            }
        }
        JsonObject outSlot = new JsonObject();
        outSlot.addProperty("id", "out_0");
        outSlot.addProperty("role", "output");
        outSlot.addProperty("x", 124);
        outSlot.addProperty("y", 35);
        outSlot.addProperty("w", 18);
        outSlot.addProperty("h", 18);
        slots.add(outSlot);
        layout.add("slots", slots);
        
        JsonArray widgets = new JsonArray();
        JsonObject arrow = new JsonObject();
        arrow.addProperty("kind", "arrow");
        arrow.addProperty("x", 90);
        arrow.addProperty("y", 35);
        arrow.addProperty("style", "crafting");
        widgets.add(arrow);
        layout.add("widgets", widgets);
        
        return layout;
    }
    
    private JsonObject createSmeltingLayout(JsonObject layout) {
        JsonObject size = new JsonObject();
        size.addProperty("w", 176);
        size.addProperty("h", 85);
        layout.add("size", size);
        
        JsonArray slots = new JsonArray();
        JsonObject inSlot = new JsonObject();
        inSlot.addProperty("id", "in_0");
        inSlot.addProperty("role", "input");
        inSlot.addProperty("x", 56);
        inSlot.addProperty("y", 17);
        inSlot.addProperty("w", 18);
        inSlot.addProperty("h", 18);
        slots.add(inSlot);
        
        JsonObject fuelSlot = new JsonObject();
        fuelSlot.addProperty("id", "catalyst_0");
        fuelSlot.addProperty("role", "catalyst");
        fuelSlot.addProperty("x", 56);
        fuelSlot.addProperty("y", 53);
        fuelSlot.addProperty("w", 18);
        fuelSlot.addProperty("h", 18);
        slots.add(fuelSlot);
        
        JsonObject outSlot = new JsonObject();
        outSlot.addProperty("id", "out_0");
        outSlot.addProperty("role", "output");
        outSlot.addProperty("x", 116);
        outSlot.addProperty("y", 35);
        outSlot.addProperty("w", 18);
        outSlot.addProperty("h", 18);
        slots.add(outSlot);
        layout.add("slots", slots);
        
        JsonArray widgets = new JsonArray();
        JsonObject flame = new JsonObject();
        flame.addProperty("kind", "flame");
        flame.addProperty("x", 56);
        flame.addProperty("y", 36);
        widgets.add(flame);
        
        JsonObject arrow = new JsonObject();
        arrow.addProperty("kind", "arrow");
        arrow.addProperty("x", 79);
        arrow.addProperty("y", 34);
        widgets.add(arrow);
        layout.add("widgets", widgets);
        
        return layout;
    }
    
    private JsonObject createStonecuttingLayout(JsonObject layout) {
        JsonObject size = new JsonObject();
        size.addProperty("w", 176);
        size.addProperty("h", 85);
        layout.add("size", size);
        
        JsonArray slots = new JsonArray();
        JsonObject inSlot = new JsonObject();
        inSlot.addProperty("id", "in_0");
        inSlot.addProperty("role", "input");
        inSlot.addProperty("x", 20);
        inSlot.addProperty("y", 33);
        inSlot.addProperty("w", 18);
        inSlot.addProperty("h", 18);
        slots.add(inSlot);
        
        JsonObject outSlot = new JsonObject();
        outSlot.addProperty("id", "out_0");
        outSlot.addProperty("role", "output");
        outSlot.addProperty("x", 143);
        outSlot.addProperty("y", 33);
        outSlot.addProperty("w", 18);
        outSlot.addProperty("h", 18);
        slots.add(outSlot);
        layout.add("slots", slots);
        
        return layout;
    }
    
    private JsonObject createSmithingLayout(JsonObject layout) {
        JsonObject size = new JsonObject();
        size.addProperty("w", 176);
        size.addProperty("h", 85);
        layout.add("size", size);
        
        JsonArray slots = new JsonArray();
        JsonObject templateSlot = new JsonObject();
        templateSlot.addProperty("id", "in_0");
        templateSlot.addProperty("role", "input");
        templateSlot.addProperty("x", 27);
        templateSlot.addProperty("y", 47);
        templateSlot.addProperty("w", 18);
        templateSlot.addProperty("h", 18);
        slots.add(templateSlot);
        
        JsonObject baseSlot = new JsonObject();
        baseSlot.addProperty("id", "in_1");
        baseSlot.addProperty("role", "input");
        baseSlot.addProperty("x", 76);
        baseSlot.addProperty("y", 47);
        baseSlot.addProperty("w", 18);
        baseSlot.addProperty("h", 18);
        slots.add(baseSlot);
        
        JsonObject additionSlot = new JsonObject();
        additionSlot.addProperty("id", "in_2");
        additionSlot.addProperty("role", "input");
        additionSlot.addProperty("x", 115);
        additionSlot.addProperty("y", 47);
        additionSlot.addProperty("w", 18);
        additionSlot.addProperty("h", 18);
        slots.add(additionSlot);
        
        JsonObject outSlot = new JsonObject();
        outSlot.addProperty("id", "out_0");
        outSlot.addProperty("role", "output");
        outSlot.addProperty("x", 152);
        outSlot.addProperty("y", 47);
        outSlot.addProperty("w", 18);
        outSlot.addProperty("h", 18);
        slots.add(outSlot);
        layout.add("slots", slots);
        
        return layout;
    }
    
    private JsonObject createCampfireLayout(JsonObject layout) {
        JsonObject size = new JsonObject();
        size.addProperty("w", 176);
        size.addProperty("h", 85);
        layout.add("size", size);
        
        JsonArray slots = new JsonArray();
        JsonObject inSlot = new JsonObject();
        inSlot.addProperty("id", "in_0");
        inSlot.addProperty("role", "input");
        inSlot.addProperty("x", 56);
        inSlot.addProperty("y", 25);
        inSlot.addProperty("w", 18);
        inSlot.addProperty("h", 18);
        slots.add(inSlot);
        
        JsonObject outSlot = new JsonObject();
        outSlot.addProperty("id", "out_0");
        outSlot.addProperty("role", "output");
        outSlot.addProperty("x", 102);
        outSlot.addProperty("y", 25);
        outSlot.addProperty("w", 18);
        outSlot.addProperty("h", 18);
        slots.add(outSlot);
        layout.add("slots", slots);
        
        return layout;
    }
    
    private JsonObject createDefaultLayout(JsonObject layout) {
        JsonObject size = new JsonObject();
        size.addProperty("w", 176);
        size.addProperty("h", 85);
        layout.add("size", size);
        
        JsonArray slots = new JsonArray();
        JsonObject inSlot = new JsonObject();
        inSlot.addProperty("id", "in_0");
        inSlot.addProperty("role", "input");
        inSlot.addProperty("x", 30);
        inSlot.addProperty("y", 35);
        inSlot.addProperty("w", 18);
        inSlot.addProperty("h", 18);
        slots.add(inSlot);
        
        JsonObject outSlot = new JsonObject();
        outSlot.addProperty("id", "out_0");
        outSlot.addProperty("role", "output");
        outSlot.addProperty("x", 128);
        outSlot.addProperty("y", 35);
        outSlot.addProperty("w", 18);
        outSlot.addProperty("h", 18);
        slots.add(outSlot);
        layout.add("slots", slots);
        
        return layout;
    }
}
