package io.github.aeroseira.delightify_exporter.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import io.github.aeroseira.delightify_exporter.model.RecipeViewRow;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 客户端专用：配方视图采集器
 * 
 * 从 JEI 获取配方类别的布局信息（槽位位置、背景纹理等）
 * 仅在物理客户端可用
 * 
 * 注意：此类使用反射访问 JEI，避免硬编码依赖
 */
@OnlyIn(Dist.CLIENT)
public class RecipeViewSource {
    
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    
    // JEI 是否可用
    private final boolean jeiAvailable;
    
    public RecipeViewSource() {
        this.jeiAvailable = FMLEnvironment.dist == Dist.CLIENT 
            && ModList.get().isLoaded("jei");
    }
    
    /**
     * 采集配方视图数据
     * 
     * @param typeIds 需要采集的配方类型ID集合
     * @return 配方视图数据列表
     */
    public List<RecipeViewRow> collect(Set<String> typeIds) {
        List<RecipeViewRow> results = new ArrayList<>();
        
        if (!jeiAvailable) {
            LOGGER.info("JEI not available, generating unavailable rows for {} type(s)", typeIds.size());
            for (String typeId : typeIds) {
                results.add(RecipeViewRow.unavailable(typeId, 
                    ModList.get().isLoaded("jei") ? "jei_runtime_unavailable" : "jei_not_loaded"));
            }
            return results;
        }
        
        LOGGER.info("Collecting recipe views for {} type(s)...", typeIds.size());
        
        // 尝试通过反射获取 JEI 数据
        boolean jeiReflectionSuccess = tryCollectFromJei(typeIds, results);
        
        // 对于没有 JEI 数据或 JEI 采集失败的类型，使用内置模板
        for (String typeId : typeIds) {
            boolean alreadyCollected = results.stream()
                .anyMatch(r -> r.typeId().equals(typeId));
            
            if (!alreadyCollected) {
                RecipeViewRow templateRow = createTemplateRow(typeId);
                if (templateRow != null) {
                    results.add(templateRow);
                    LOGGER.debug("Using built-in template for {}", typeId);
                } else {
                    results.add(RecipeViewRow.unavailable(typeId, "no_template"));
                }
            }
        }
        
        LOGGER.info("Collected {} recipe view entries (JEI reflection: {})", 
            results.size(), jeiReflectionSuccess ? "success" : "fallback");
        return results;
    }
    
    /**
     * 尝试通过反射从 JEI 采集数据
     */
    private boolean tryCollectFromJei(Set<String> typeIds, List<RecipeViewRow> results) {
        try {
            // 获取 JEI 运行时类
            Class<?> internalClass = Class.forName("mezz.jei.common.Internal");
            Object runtime = internalClass.getMethod("getJeiRuntime").invoke(null);
            
            if (runtime == null) {
                LOGGER.warn("JEI runtime is null");
                return false;
            }
            
            // 获取 RecipeManager
            Object recipeManager = runtime.getClass().getMethod("getRecipeManager").invoke(runtime);
            
            // 获取所有类别
            @SuppressWarnings("unchecked")
            List<Object> categories = (List<Object>) recipeManager.getClass()
                .getMethod("getRecipeCategories")
                .invoke(recipeManager);
            
            LOGGER.debug("Found {} JEI categories", categories.size());
            
            // 处理每个类别
            for (Object category : categories) {
                try {
                    // 获取 RecipeType
                    Object recipeType = category.getClass().getMethod("getRecipeType").invoke(category);
                    Object uid = recipeType.getClass().getMethod("getUid").invoke(recipeType);
                    String typeId = uid.toString();
                    
                    if (!typeIds.contains(typeId)) {
                        continue;
                    }
                    
                    // 提取布局信息
                    RecipeViewRow row = extractFromCategoryReflection(typeId, category);
                    if (row != null) {
                        results.add(row);
                    }
                    
                } catch (Exception e) {
                    LOGGER.debug("Failed to process JEI category: {}", e.getMessage());
                }
            }
            
            return true;
            
        } catch (Exception e) {
            LOGGER.debug("JEI reflection failed: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * 通过反射从 JEI 类别提取布局
     */
    @Nullable
    private RecipeViewRow extractFromCategoryReflection(String typeId, Object category) {
        try {
            JsonObject layout = new JsonObject();
            layout.addProperty("schema", RecipeViewRow.CURRENT_SCHEMA_VERSION);
            layout.addProperty("type_id", typeId);
            layout.addProperty("unavailable", false);
            layout.addProperty("source", "jei");
            
            // 获取背景
            String backgroundRef = null;
            try {
                Object background = category.getClass().getMethod("getBackground").invoke(category);
                if (background != null) {
                    int width = (int) background.getClass().getMethod("getWidth").invoke(background);
                    int height = (int) background.getClass().getMethod("getHeight").invoke(background);
                    
                    JsonObject size = new JsonObject();
                    size.addProperty("w", width);
                    size.addProperty("h", height);
                    layout.add("size", size);
                    
                    // 尝试获取纹理路径（通过反射）
                    backgroundRef = tryGetBackgroundTexture(background);
                }
            } catch (Exception e) {
                LOGGER.debug("Could not extract background for {}: {}", typeId, e.getMessage());
            }
            
            // 如果没有获取到背景引用，使用默认
            if (backgroundRef == null) {
                backgroundRef = getDefaultBackgroundRef(typeId);
                if (backgroundRef != null) {
                    LOGGER.debug("Using default background for {}: {}", typeId, backgroundRef);
                }
            }
            
            // 如果没有尺寸，使用默认
            if (!layout.has("size")) {
                JsonObject size = new JsonObject();
                size.addProperty("w", 176);
                size.addProperty("h", 85);
                layout.add("size", size);
            }
            
            // 使用内置模板创建槽位
            JsonArray slots = createDefaultSlots(typeId);
            layout.add("slots", slots);
            
            // Widgets
            JsonArray widgets = createDefaultWidgets(typeId);
            if (widgets.size() > 0) {
                layout.add("widgets", widgets);
            }
            
            String layoutJson = GSON.toJson(layout);
            return new RecipeViewRow(typeId, layoutJson, backgroundRef, RecipeViewRow.CURRENT_SCHEMA_VERSION);
            
        } catch (Exception e) {
            LOGGER.debug("Failed to extract from category {}: {}", typeId, e.getMessage());
            return null;
        }
    }
    
    /**
     * 尝试获取背景纹理路径
     */
    @Nullable
    private String tryGetBackgroundTexture(Object drawable) {
        try {
            Class<?> clazz = drawable.getClass();
            LOGGER.debug("Trying to extract texture from {}" , clazz.getName());
            
            // 尝试 1: getResourceLocation() 方法
            try {
                Object location = clazz.getMethod("getResourceLocation").invoke(drawable);
                if (location != null) {
                    String path = location.toString();
                    LOGGER.debug("Found texture via getResourceLocation(): {}", path);
                    return path;
                }
            } catch (NoSuchMethodException ignored) {
            }
            
            // 尝试 2: resourceLocation 字段
            try {
                java.lang.reflect.Field field = clazz.getDeclaredField("resourceLocation");
                field.setAccessible(true);
                Object location = field.get(drawable);
                if (location != null) {
                    String path = location.toString();
                    LOGGER.debug("Found texture via resourceLocation field: {}", path);
                    return path;
                }
            } catch (NoSuchFieldException ignored) {
            }
            
            // 尝试 3: location 字段
            try {
                java.lang.reflect.Field field = clazz.getDeclaredField("location");
                field.setAccessible(true);
                Object location = field.get(drawable);
                if (location != null) {
                    String path = location.toString();
                    LOGGER.debug("Found texture via location field: {}", path);
                    return path;
                }
            } catch (NoSuchFieldException ignored) {
            }
            
            // 尝试 4: texture 字段
            try {
                java.lang.reflect.Field field = clazz.getDeclaredField("texture");
                field.setAccessible(true);
                Object location = field.get(drawable);
                if (location != null) {
                    String path = location.toString();
                    LOGGER.debug("Found texture via texture field: {}", path);
                    return path;
                }
            } catch (NoSuchFieldException ignored) {
            }
            
            // 尝试 5: 遍历所有字段查找 ResourceLocation 类型
            for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
                field.setAccessible(true);
                Object value = field.get(drawable);
                if (value != null && value.getClass().getSimpleName().equals("ResourceLocation")) {
                    String path = value.toString();
                    LOGGER.debug("Found texture via field scan ({}): {}", field.getName(), path);
                    return path;
                }
            }
            
            LOGGER.debug("Could not find texture in {}", clazz.getName());
            
        } catch (Exception e) {
            LOGGER.debug("Could not extract background texture: {}", e.getMessage());
        }
        return null;
    }
    
    /**
     * 获取默认背景纹理引用
     */
    @Nullable
    private String getDefaultBackgroundRef(String typeId) {
        return switch (typeId) {
            case "minecraft:crafting_shaped", "minecraft:crafting_shapeless" ->
                "minecraft:textures/gui/container/crafting_table.png";
            case "minecraft:smelting", "minecraft:blasting", "minecraft:smoking" ->
                "minecraft:textures/gui/container/furnace.png";
            case "minecraft:stonecutting" ->
                "minecraft:textures/gui/container/stonecutter.png";
            case "minecraft:smithing_transform", "minecraft:smithing_trim" ->
                "minecraft:textures/gui/container/smithing_table.png";
            case "minecraft:campfire_cooking" ->
                "minecraft:textures/gui/container/campfire.png";
            default -> null;
        };
    }
    
    /**
     * 创建默认槽位布局（基于原版模板）
     */
    private JsonArray createDefaultSlots(String typeId) {
        JsonArray slots = new JsonArray();
        
        // 根据 type_id 使用不同的模板
        if (typeId.equals("minecraft:crafting_shaped") || typeId.equals("minecraft:crafting_shapeless")) {
            // 3x3 输入槽
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
            // 输出槽
            JsonObject outSlot = new JsonObject();
            outSlot.addProperty("id", "out_0");
            outSlot.addProperty("role", "output");
            outSlot.addProperty("x", 124);
            outSlot.addProperty("y", 35);
            outSlot.addProperty("w", 18);
            outSlot.addProperty("h", 18);
            slots.add(outSlot);
            
        } else if (typeId.equals("minecraft:smelting") || 
                   typeId.equals("minecraft:blasting") || 
                   typeId.equals("minecraft:smoking")) {
            // 熔炉：输入 + 燃料 + 输出
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
            
        } else if (typeId.equals("minecraft:stonecutting")) {
            // 切石机
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
            
        } else if (typeId.equals("minecraft:smithing_transform") || 
                   typeId.equals("minecraft:smithing_trim")) {
            // 锻造台
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
            
        } else if (typeId.equals("minecraft:campfire_cooking")) {
            // 营火
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
        }
        
        return slots;
    }
    
    /**
     * 创建默认控件（箭头、火焰等）
     */
    private JsonArray createDefaultWidgets(String typeId) {
        JsonArray widgets = new JsonArray();
        
        if (typeId.equals("minecraft:crafting_shaped") || typeId.equals("minecraft:crafting_shapeless")) {
            JsonObject arrow = new JsonObject();
            arrow.addProperty("kind", "arrow");
            arrow.addProperty("x", 90);
            arrow.addProperty("y", 35);
            arrow.addProperty("style", "crafting");
            widgets.add(arrow);
            
        } else if (typeId.equals("minecraft:smelting") || 
                   typeId.equals("minecraft:blasting") || 
                   typeId.equals("minecraft:smoking")) {
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
        }
        
        return widgets;
    }
    
    /**
     * 为已知原版类型创建模板行
     */
    @Nullable
    private RecipeViewRow createTemplateRow(String typeId) {
        // 只处理已知的原版类型
        Set<String> knownTypes = Set.of(
            "minecraft:crafting_shaped",
            "minecraft:crafting_shapeless",
            "minecraft:smelting",
            "minecraft:blasting",
            "minecraft:smoking",
            "minecraft:campfire_cooking",
            "minecraft:stonecutting",
            "minecraft:smithing_transform",
            "minecraft:smithing_trim"
        );
        
        if (!knownTypes.contains(typeId)) {
            return null;
        }
        
        JsonObject layout = new JsonObject();
        layout.addProperty("schema", RecipeViewRow.CURRENT_SCHEMA_VERSION);
        layout.addProperty("type_id", typeId);
        layout.addProperty("unavailable", false);
        layout.addProperty("source", "built-in_template");
        
        // 尺寸
        JsonObject size = new JsonObject();
        size.addProperty("w", 176);
        size.addProperty("h", 85);
        layout.add("size", size);
        
        // 槽位
        layout.add("slots", createDefaultSlots(typeId));
        
        // Widgets
        JsonArray widgets = createDefaultWidgets(typeId);
        if (widgets.size() > 0) {
            layout.add("widgets", widgets);
        }
        
        String layoutJson = GSON.toJson(layout);
        String backgroundRef = getDefaultBackgroundRef(typeId);
        return new RecipeViewRow(typeId, layoutJson, backgroundRef, RecipeViewRow.CURRENT_SCHEMA_VERSION);
    }
}
