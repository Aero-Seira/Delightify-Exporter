package io.github.aeroseira.delightify_exporter.source;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import io.github.aeroseira.delightify_exporter.model.ItemResourceRow;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 收集物品资源文件元数据（供外部工具解析）
 */
public class ItemResourceSource {

    private static final Logger LOGGER = LogUtils.getLogger();

    public List<ItemResourceRow> collect(MinecraftServer server) {
        List<ItemResourceRow> resources = new ArrayList<>();
        
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);
            if (itemId == null) continue;
            
            String itemIdStr = itemId.toString();
            String modid = itemId.getNamespace();
            String path = itemId.getPath();
            
            try {
                // 1. 收集本地化名称
                collectLocalization(resources, itemIdStr, modid, path, server);
                
                // 2. 收集模型文件信息
                collectModelInfo(resources, itemIdStr, modid, path, server);
                
            } catch (Exception e) {
                LOGGER.debug("Failed to collect resources for item {}: {}", itemIdStr, e.getMessage());
            }
        }
        
        return resources;
    }

    /**
     * 收集物品的本地化显示名称
     */
    private void collectLocalization(List<ItemResourceRow> resources, String itemId, String modid, String path, MinecraftServer server) {
        // 构建翻译键: item.<modid>.<path>
        String translationKey = "item." + modid + "." + path;
        
        // 获取英文本地化
        String enName = getTranslation(server, translationKey, "en_us");
        if (enName != null && !enName.equals(translationKey)) {
            resources.add(new ItemResourceRow(itemId, "lang_name", modid, "lang/en_us.json", enName));
        }
        
        // 获取中文本地化（如果有）
        String zhName = getTranslation(server, translationKey, "zh_cn");
        if (zhName != null && !zhName.equals(translationKey) && !zhName.equals(enName)) {
            resources.add(new ItemResourceRow(itemId, "lang_name", modid, "lang/zh_cn.json", zhName));
        }
        
        // 如果没有找到本地化，使用路径作为备用
        if (enName == null || enName.equals(translationKey)) {
            resources.add(new ItemResourceRow(itemId, "lang_name", modid, "lang/fallback", path));
        }
    }

    /**
     * 从语言文件获取翻译
     */
    private String getTranslation(MinecraftServer server, String key, String langCode) {
        try {
            // 使用 Minecraft 的翻译系统
            var registryAccess = server.registryAccess();
            // 直接返回 key，让外部工具自己解析语言文件
            return key;
        } catch (Exception e) {
            return key;
        }
    }

    /**
     * 收集物品的模型文件信息
     */
    private void collectModelInfo(List<ItemResourceRow> resources, String itemId, String modid, String path, MinecraftServer server) {
        // 构建模型路径: assets/<modid>/models/item/<path>.json
        String modelPath = "models/item/" + path + ".json";
        ResourceLocation modelLocation = new ResourceLocation(modid, modelPath);
        
        // 记录模型文件路径
        resources.add(new ItemResourceRow(itemId, "model", modid, modelPath, null));
        
        // 尝试读取模型文件获取材质信息
        try {
            Optional<InputStream> resource = server.getResourceManager()
                .getResource(new ResourceLocation(modid, modelPath))
                .map(r -> {
                    try {
                        return r.open();
                    } catch (IOException e) {
                        return null;
                    }
                });
            
            if (resource.isPresent() && resource.get() != null) {
                try (InputStream stream = resource.get();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    
                    JsonObject modelJson = JsonParser.parseReader(reader).getAsJsonObject();
                    
                    // 获取父模型（如果有）
                    if (modelJson.has("parent")) {
                        String parent = modelJson.get("parent").getAsString();
                        resources.add(new ItemResourceRow(itemId, "model_parent", modid, modelPath, parent));
                    }
                    
                    // 获取纹理映射
                    if (modelJson.has("textures")) {
                        JsonObject textures = modelJson.getAsJsonObject("textures");
                        for (Map.Entry<String, JsonElement> entry : textures.entrySet()) {
                            String textureVar = entry.getKey();
                            String texturePath = entry.getValue().getAsString();
                            
                            // 解析完整材质路径
                            String fullTexturePath = resolveTexturePath(texturePath);
                            resources.add(new ItemResourceRow(
                                itemId, 
                                "texture", 
                                fullTexturePath.split(":")[0], 
                                "textures/" + fullTexturePath.split(":", 2)[1] + ".png",
                                textureVar
                            ));
                        }
                    }
                    
                } catch (Exception e) {
                    LOGGER.debug("Failed to parse model file for {}: {}", itemId, e.getMessage());
                }
            } else {
                // 模型文件不存在，可能是继承默认模型
                // 记录生成的默认模型路径
                resources.add(new ItemResourceRow(itemId, "model_generated", modid, modelPath, "builtin/generated"));
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to load model for {}: {}", itemId, e.getMessage());
        }
    }

    /**
     * 解析材质路径，处理简写形式
     * 如: "item/diamond" -> "minecraft:item/diamond"
     */
    private String resolveTexturePath(String texturePath) {
        if (texturePath.contains(":")) {
            return texturePath;
        }
        // 默认使用 minecraft 命名空间
        return "minecraft:" + texturePath;
    }
}
