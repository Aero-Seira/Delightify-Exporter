package io.github.aeroseira.delightify_exporter.source;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import io.github.aeroseira.delightify_exporter.model.ItemResourceRow;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 收集物品资源文件元数据（供外部工具解析）
 * 特别处理方块类物品，提取正面顶层材质
 */
public class ItemResourceSource {

    private static final Logger LOGGER = LogUtils.getLogger();
    
    // 最大资源文件大小限制 (1MB)
    private static final int MAX_RESOURCE_SIZE = 1024 * 1024;
    
    // 标记是否在物理客户端
    private final boolean isPhysicalClient;
    
    // 缓存已经处理过的材质，避免重复
    private final java.util.Set<String> processedTextures = new java.util.HashSet<>();
    
    public ItemResourceSource() {
        this.isPhysicalClient = FMLEnvironment.dist == Dist.CLIENT;
        LOGGER.info("ItemResourceSource initialized, isPhysicalClient={}", isPhysicalClient);
    }

    public List<ItemResourceRow> collect(MinecraftServer server) {
        List<ItemResourceRow> resources = new ArrayList<>();
        int processedCount = 0;
        int successCount = 0;
        
        LOGGER.info("Starting item resource collection...");
        
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);
            if (itemId == null) continue;
            
            String itemIdStr = itemId.toString();
            String modid = itemId.getNamespace();
            String path = itemId.getPath();
            
            processedCount++;
            
            try {
                int beforeCount = resources.size();
                
                // 1. 收集本地化名称
                collectLocalization(resources, itemIdStr, modid, path, item);
                
                // 2. 收集模型文件信息（传入 Item 对象以便判断是否为方块）
                collectModelInfo(resources, itemIdStr, modid, path, server, item);
                
                if (resources.size() > beforeCount) {
                    successCount++;
                }
                
                // 每处理100个物品打印一次进度
                if (processedCount % 100 == 0) {
                    LOGGER.debug("Processed {} items, collected {} resources", processedCount, resources.size());
                }
                
            } catch (Exception e) {
                LOGGER.warn("Failed to collect resources for item {}: {}", itemIdStr, e.getMessage(), e);
            }
        }
        
        LOGGER.info("Resource collection complete: {} items processed, {} items with resources, {} total resources", 
            processedCount, successCount, resources.size());
        
        // 统计各类型资源数量
        long langCount = resources.stream().filter(r -> r.resourceType().equals("lang_name")).count();
        long langKeyCount = resources.stream().filter(r -> r.resourceType().equals("lang_key")).count();
        long modelCount = resources.stream().filter(r -> r.resourceType().equals("model")).count();
        long modelPathCount = resources.stream().filter(r -> r.resourceType().equals("model_path")).count();
        long modelGenCount = resources.stream().filter(r -> r.resourceType().equals("model_generated")).count();
        long modelParentCount = resources.stream().filter(r -> r.resourceType().equals("model_parent")).count();
        long textureCount = resources.stream().filter(r -> r.resourceType().equals("texture")).count();
        long texturePathCount = resources.stream().filter(r -> r.resourceType().equals("texture_path")).count();
        LOGGER.info("Resource breakdown:");
        LOGGER.info("  - Localization: {} names, {} keys", langCount, langKeyCount);
        LOGGER.info("  - Models: {} files, {} paths, {} generated, {} parents", 
            modelCount, modelPathCount, modelGenCount, modelParentCount);
        LOGGER.info("  - Textures: {} files, {} paths", textureCount, texturePathCount);
        
        return resources;
    }

    /**
     * 收集物品的本地化显示名称
     */
    private void collectLocalization(List<ItemResourceRow> resources, String itemId, String modid, String path, Item item) {
        try {
            // 通过 ItemStack 获取显示名称（使用当前语言环境）
            ItemStack stack = new ItemStack(item);
            Component displayName = stack.getHoverName();
            String name = displayName.getString();
            
            if (name != null && !name.isEmpty()) {
                resources.add(new ItemResourceRow(itemId, "lang_name", modid, "lang/current", name));
                LOGGER.debug("Found display name for {}: {}", itemId, name);
            }
            
            // 同时记录翻译键，方便外部工具查找
            String translationKey = item.getDescriptionId();
            resources.add(new ItemResourceRow(itemId, "lang_key", modid, "lang/key", translationKey));
            
        } catch (Exception e) {
            LOGGER.debug("Failed to get localization for {}: {}", itemId, e.getMessage());
        }
    }

    /**
     * 读取资源文件内容为字符串
     * 在物理客户端会使用客户端资源管理器
     */
    private String readResourceFile(MinecraftServer server, String namespace, String path) {
        try {
            ResourceLocation resourceLocation = ResourceLocation.fromNamespaceAndPath(namespace, path);
            LOGGER.debug("Attempting to read resource: {}", resourceLocation);
            
            // 首先尝试使用服务端资源管理器
            var optionalResource = server.getResourceManager().getResource(resourceLocation);
            
            // 如果在物理客户端且服务端找不到，尝试使用客户端资源管理器
            if (optionalResource.isEmpty() && isPhysicalClient) {
                try {
                    var clientResourceManager = Minecraft.getInstance().getResourceManager();
                    optionalResource = clientResourceManager.getResource(resourceLocation);
                    LOGGER.debug("Using client resource manager for {}", resourceLocation);
                } catch (Exception e) {
                    LOGGER.debug("Client resource manager also failed for {}: {}", resourceLocation, e.getMessage());
                }
            }
            
            if (optionalResource.isEmpty()) {
                LOGGER.debug("Resource not found: {}/{}", namespace, path);
                return null;
            }
            
            var resource = optionalResource.get();
            try (InputStream stream = resource.open();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                StringBuilder content = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line).append("\n");
                }
                String result = content.toString();
                LOGGER.debug("Successfully read resource {}/{} ({} chars)", namespace, path, result.length());
                return result;
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to read resource file {}/{}: {}", namespace, path, e.getMessage());
        }
        return null;
    }

    /**
     * 收集物品的模型文件和材质路径信息
     * 特别处理方块类物品，提取正面顶层材质
     */
    private void collectModelInfo(List<ItemResourceRow> resources, String itemId, String modid, String path, 
                                  MinecraftServer server, Item item) {
        // 检查是否是方块物品
        boolean isBlockItem = item instanceof BlockItem;
        
        if (isBlockItem) {
            // 对于方块物品，尝试从 blockstate 获取模型
            collectBlockModelInfo(resources, itemId, modid, path, server);
        } else {
            // 对于普通物品，使用标准物品模型路径
            collectItemModelInfo(resources, itemId, modid, path, server);
        }
    }
    
    /**
     * 收集方块类物品的模型信息
     * 优先提取正面顶层材质
     */
    private void collectBlockModelInfo(List<ItemResourceRow> resources, String itemId, String modid, String path, 
                                       MinecraftServer server) {
        // 1. 首先尝试读取 blockstate 文件
        String blockstatePath = "blockstates/" + path + ".json";
        String blockstateContent = readResourceFile(server, modid, blockstatePath);
        
        String modelPath = null;
        
        if (blockstateContent != null) {
            LOGGER.debug("Found blockstate for {}: {}", itemId, blockstatePath);
            resources.add(new ItemResourceRow(itemId, "blockstate", modid, blockstatePath, blockstateContent));
            
            // 解析 blockstate 获取默认模型
            modelPath = parseBlockstateForModel(blockstateContent);
        }
        
        // 如果没有找到 blockstate 或无法解析，使用默认方块模型路径
        if (modelPath == null) {
            modelPath = "models/block/" + path + ".json";
        }
        
        // 2. 读取模型文件并提取材质
        extractModelTextures(resources, itemId, modid, path, modelPath, server, true);
    }
    
    /**
     * 收集普通物品的模型信息
     */
    private void collectItemModelInfo(List<ItemResourceRow> resources, String itemId, String modid, String path, 
                                      MinecraftServer server) {
        String modelPath = "models/item/" + path + ".json";
        extractModelTextures(resources, itemId, modid, path, modelPath, server, false);
    }
    
    /**
     * 从 blockstate JSON 解析默认模型路径
     */
    private String parseBlockstateForModel(String blockstateContent) {
        try {
            JsonObject json = JsonParser.parseString(blockstateContent).getAsJsonObject();
            
            // 优先查找 "variants" -> "" (默认变体)
            if (json.has("variants")) {
                JsonObject variants = json.getAsJsonObject("variants");
                if (variants.has("")) {
                    JsonElement defaultVariant = variants.get("");
                    if (defaultVariant.isJsonObject()) {
                        JsonObject variantObj = defaultVariant.getAsJsonObject();
                        if (variantObj.has("model")) {
                            String modelRef = variantObj.get("model").getAsString();
                            // 转换引用为路径: "minecraft:block/stone" -> "models/block/stone.json"
                            return "models/" + modelRef.replace(":", "/") + ".json";
                        }
                    }
                }
            }
            
            // 查找 multipart 的第一个 apply
            if (json.has("multipart")) {
                // multipart 复杂，返回 null 让调用者使用默认路径
                return null;
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to parse blockstate: {}", e.getMessage());
        }
        return null;
    }
    
    /**
     * 从模型文件中提取纹理
     * @param isBlock 是否是方块（影响材质选择策略）
     */
    private void extractModelTextures(List<ItemResourceRow> resources, String itemId, String modid, String itemPath,
                                      String modelPath, MinecraftServer server, boolean isBlock) {
        LOGGER.debug("Extracting textures for {} from model: {}", itemId, modelPath);
        
        // 导出模型路径
        resources.add(new ItemResourceRow(itemId, "model_path", modid, modelPath,
            "assets/" + modid + "/" + modelPath));
        
        String modelContent = readResourceFile(server, modid, modelPath);
        
        if (modelContent != null) {
            resources.add(new ItemResourceRow(itemId, "model", modid, modelPath, modelContent));
            
            try {
                JsonObject modelJson = JsonParser.parseString(modelContent).getAsJsonObject();
                
                // 递归解析 parent 链获取 textures
                JsonObject resolvedTextures = resolveTexturesFromParentChain(
                    server, modid, modelJson, new java.util.HashSet<>()
                );
                
                // 如果解析到了 textures，添加到当前 modelJson 以便后续处理
                if (resolvedTextures != null && resolvedTextures.size() > 0) {
                    modelJson.add("textures", resolvedTextures);
                }
                
                // 处理父模型引用（记录 parent 信息）
                if (modelJson.has("parent")) {
                    String parent = modelJson.get("parent").getAsString();
                    resources.add(new ItemResourceRow(itemId, "model_parent", modid, modelPath, parent));
                }
                
                // 提取并处理纹理
                if (modelJson.has("textures")) {
                    JsonObject textures = modelJson.getAsJsonObject("textures");
                    
                    // 根据是否是方块选择不同的材质提取策略
                    String selectedTexture = selectBestTexture(textures, isBlock);
                    
                    if (selectedTexture != null) {
                        // 解析完整材质路径
                        String fullTexturePath = resolveTexturePath(selectedTexture);
                        String textureNamespace = fullTexturePath.split(":")[0];
                        String textureName = fullTexturePath.split(":", 2)[1];
                        String textureRelativePath = "textures/" + textureName + ".png";
                        
                        // 导出主要纹理路径
                        resources.add(new ItemResourceRow(
                            itemId,
                            "texture_main",
                            textureNamespace,
                            textureRelativePath,
                            isBlock ? "top/north" : "layer0"
                        ));
                        
                        // 读取主要材质文件
                        String textureBase64 = readTextureAsBase64(server, textureNamespace, textureRelativePath);
                        if (textureBase64 != null) {
                            LOGGER.info("Loaded main texture for {}: {} chars base64", itemId, textureBase64.length());
                            resources.add(new ItemResourceRow(
                                itemId,
                                "texture",
                                textureNamespace,
                                textureRelativePath,
                                textureBase64
                            ));
                        }
                        
                        // 记录已处理的材质
                        processedTextures.add(textureNamespace + ":" + textureName);
                    }
                    
                    // 导出所有纹理路径供参考
                    for (Map.Entry<String, JsonElement> entry : textures.entrySet()) {
                        String textureVar = entry.getKey();
                        String texturePath = entry.getValue().getAsString();
                        String fullPath = resolveTexturePath(texturePath);
                        String ns = fullPath.split(":")[0];
                        String tp = "textures/" + fullPath.split(":", 2)[1] + ".png";
                        
                        resources.add(new ItemResourceRow(
                            itemId,
                            "texture_path",
                            ns,
                            tp,
                            textureVar
                        ));
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("Failed to parse model for {}: {}", itemId, e.getMessage());
            }
        } else {
            // 模型不存在，使用默认路径
            LOGGER.debug("Model not found for {}, using fallback", itemId);
            resources.add(new ItemResourceRow(itemId, "model_generated", modid, modelPath, "builtin/generated"));
            
            // 默认纹理路径
            String defaultPath = isBlock ? "textures/block/" + itemPath + ".png" : "textures/item/" + itemPath + ".png";
            resources.add(new ItemResourceRow(itemId, "texture_path", modid, defaultPath, "default"));
        }
    }
    
    /**
     * 递归解析 parent 模型链，获取 textures
     * @param server Minecraft 服务器
     * @param currentNamespace 当前模型所在的命名空间
     * @param currentModelJson 当前模型 JSON
     * @param visited 已访问的模型路径（防止循环引用）
     * @return 解析到的 textures，如果没有则返回 null
     */
    private JsonObject resolveTexturesFromParentChain(MinecraftServer server, String currentNamespace, 
                                                       JsonObject currentModelJson, 
                                                       java.util.Set<String> visited) {
        // 如果当前模型有 textures，直接返回
        if (currentModelJson.has("textures")) {
            return currentModelJson.getAsJsonObject("textures");
        }
        
        // 如果没有 parent，无法继续解析
        if (!currentModelJson.has("parent")) {
            return null;
        }
        
        String parentRef = currentModelJson.get("parent").getAsString();
        
        // 解析 parent 的命名空间和路径
        String parentNamespace = currentNamespace;
        String parentPath = parentRef;
        
        if (parentRef.contains(":")) {
            String[] parts = parentRef.split(":", 2);
            parentNamespace = parts[0];
            parentPath = parts[1];
        }
        
        // 构建完整的模型路径
        String parentModelPath = "models/" + parentPath + ".json";
        String visitedKey = parentNamespace + ":" + parentModelPath;
        
        // 检查循环引用
        if (visited.contains(visitedKey)) {
            LOGGER.debug("Detected circular reference in parent chain: {}", visitedKey);
            return null;
        }
        
        visited.add(visitedKey);
        
        // 读取 parent 模型
        String parentContent = readResourceFile(server, parentNamespace, parentModelPath);
        
        if (parentContent == null) {
            LOGGER.debug("Parent model not found: {}", visitedKey);
            return null;
        }
        
        try {
            JsonObject parentJson = JsonParser.parseString(parentContent).getAsJsonObject();
            
            // 递归解析 parent 的 parent
            JsonObject parentTextures = resolveTexturesFromParentChain(server, parentNamespace, parentJson, visited);
            
            // 如果 parent 有 textures，返回它们
            if (parentTextures != null && parentTextures.size() > 0) {
                return parentTextures;
            }
            
            // 否则返回 null（继续向上查找的任务已经完成）
            return null;
            
        } catch (Exception e) {
            LOGGER.debug("Failed to parse parent model {}: {}", visitedKey, e.getMessage());
            return null;
        }
    }
    
    /**
     * 从纹理映射中选择最佳材质
     * 对于方块：优先 north（正面）> up（顶层）> side > all > particle > 第一个
     * 对于物品：优先 layer0 > 第一个
     */
    private String selectBestTexture(JsonObject textures, boolean isBlock) {
        if (isBlock) {
            // 方块材质优先级 - 正面(north)优先级最高，方便展示
            String[] priority = {"north", "south", "east", "west", "side", "up", "top", "down", "bottom", "all", "particle", "end", "cross"};
            for (String key : priority) {
                if (textures.has(key)) {
                    LOGGER.debug("Selected block texture: {}", key);
                    return textures.get(key).getAsString();
                }
            }
        } else {
            // 物品材质优先级
            if (textures.has("layer0")) {
                return textures.get("layer0").getAsString();
            }
        }
        
        // 默认返回第一个可用的
        for (Map.Entry<String, JsonElement> entry : textures.entrySet()) {
            return entry.getValue().getAsString();
        }
        
        return null;
    }

    /**
     * 读取材质图片文件并转为 Base64 字符串
     * 在物理客户端会使用客户端资源管理器
     */
    private String readTextureAsBase64(MinecraftServer server, String namespace, String path) {
        try {
            ResourceLocation resourceLocation = ResourceLocation.fromNamespaceAndPath(namespace, path);
            LOGGER.debug("Attempting to read texture: {}", resourceLocation);
            
            // 首先尝试使用服务端资源管理器
            var optionalResource = server.getResourceManager().getResource(resourceLocation);
            
            // 如果在物理客户端且服务端找不到，尝试使用客户端资源管理器
            if (optionalResource.isEmpty() && isPhysicalClient) {
                try {
                    var clientResourceManager = Minecraft.getInstance().getResourceManager();
                    optionalResource = clientResourceManager.getResource(resourceLocation);
                    LOGGER.debug("Using client resource manager for texture {}", resourceLocation);
                } catch (Exception e) {
                    LOGGER.debug("Client resource manager also failed for texture {}: {}", resourceLocation, e.getMessage());
                }
            }
            
            if (optionalResource.isEmpty()) {
                LOGGER.debug("Texture not found: {}/{}", namespace, path);
                return null;
            }
            
            var resource = optionalResource.get();
            try (InputStream stream = resource.open();
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                
                byte[] buffer = new byte[8192];
                int bytesRead;
                int totalBytes = 0;
                
                while ((bytesRead = stream.read(buffer)) != -1) {
                    totalBytes += bytesRead;
                    if (totalBytes > MAX_RESOURCE_SIZE) {
                        LOGGER.warn("Texture file {}/{} exceeds size limit (>{}, skipping", namespace, path, MAX_RESOURCE_SIZE);
                        return null;
                    }
                    baos.write(buffer, 0, bytesRead);
                }
                
                String base64 = Base64.getEncoder().encodeToString(baos.toByteArray());
                LOGGER.info("Successfully read texture {}/{} ({} bytes -> {} base64 chars)", 
                    namespace, path, totalBytes, base64.length());
                return base64;
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to read texture file {}/{}: {}", namespace, path, e.getMessage());
        }
        return null;
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
