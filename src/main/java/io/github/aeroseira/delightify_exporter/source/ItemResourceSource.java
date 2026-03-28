package io.github.aeroseira.delightify_exporter.source;

import com.mojang.logging.LogUtils;
import io.github.aeroseira.delightify_exporter.client.ItemRenderHelper;
import io.github.aeroseira.delightify_exporter.model.ItemResourceRow;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 收集物品资源：只导出物品栏渲染结果
 * 
 * 简化策略：
 * 1. 收集所有物品的本地化名称
 * 2. 客户端环境：批量渲染物品栏样式图片
 * 3. 服务端环境：跳过（因为无法渲染）
 * 
 * 只导出 texture 字段，前端直接使用渲染好的图片
 */
public class ItemResourceSource {

    private static final Logger LOGGER = LogUtils.getLogger();
    private final boolean isPhysicalClient;
    
    public ItemResourceSource() {
        this.isPhysicalClient = FMLEnvironment.dist == Dist.CLIENT;
        LOGGER.info("ItemResourceSource initialized, isPhysicalClient={}", isPhysicalClient);
    }

    public List<ItemResourceRow> collect(MinecraftServer server) {
        List<ItemResourceRow> resources = new ArrayList<>();
        
        // 第一阶段：收集所有物品信息和待渲染任务
        Map<String, ItemStack> renderTasks = new HashMap<>();
        
        int processedCount = 0;
        int itemCount = 0;
        
        LOGGER.info("Starting item collection...");
        
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);
            if (itemId == null) continue;
            
            String itemIdStr = itemId.toString();
            String modid = itemId.getNamespace();
            
            processedCount++;
            itemCount++;
            
            try {
                // 1. 收集本地化名称
                collectLocalization(resources, itemIdStr, modid, item);
                
                // 2. 客户端环境：加入批量渲染队列
                if (isPhysicalClient) {
                    renderTasks.put(itemIdStr, new ItemStack(item));
                }
                
                // 每100个打印进度
                if (processedCount % 100 == 0) {
                    LOGGER.debug("Collected {} items, {} pending render", processedCount, renderTasks.size());
                }
                
            } catch (Exception e) {
                LOGGER.warn("Failed to collect item {}: {}", itemIdStr, e.getMessage());
            }
        }
        
        // 第二阶段：批量渲染（客户端环境）
        if (isPhysicalClient && !renderTasks.isEmpty()) {
            LOGGER.info("Batch rendering {} items...", renderTasks.size());
            long startTime = System.currentTimeMillis();
            
            Map<String, String> renderResults = ItemRenderHelper.batchRender(renderTasks);
            
            long elapsed = System.currentTimeMillis() - startTime;
            LOGGER.info("Batch render completed in {}ms: {}/{} items", 
                elapsed, renderResults.size(), renderTasks.size());
            
            // 将渲染结果加入资源
            for (var entry : renderResults.entrySet()) {
                String itemId = entry.getKey();
                String base64 = entry.getValue();
                String modid = itemId.split(":")[0];
                
                // 只导出 texture 字段
                resources.add(new ItemResourceRow(itemId, "texture", modid, "inventory/render", base64));
            }
            
            // 记录渲染失败的物品
            int failedCount = renderTasks.size() - renderResults.size();
            if (failedCount > 0) {
                LOGGER.warn("{} items failed to render", failedCount);
            }
        }
        
        if (!isPhysicalClient) {
            LOGGER.warn("Running on server - no texture rendering available. Run with client to get textures.");
        }
        
        LOGGER.info("Collection complete: {} items, {} resources (including {} textures)", 
            itemCount, resources.size(), 
            resources.stream().filter(r -> r.resourceType().equals("texture")).count());
        
        return resources;
    }

    /**
     * 收集物品的本地化显示名称
     */
    private void collectLocalization(List<ItemResourceRow> resources, String itemId, String modid, Item item) {
        try {
            ItemStack stack = new ItemStack(item);
            Component displayName = stack.getHoverName();
            String name = displayName.getString();
            
            if (name != null && !name.isEmpty()) {
                resources.add(new ItemResourceRow(itemId, "lang_name", modid, "lang/current", name));
            }
            
            String translationKey = item.getDescriptionId();
            resources.add(new ItemResourceRow(itemId, "lang_key", modid, "lang/key", translationKey));
            
        } catch (Exception e) {
            LOGGER.debug("Failed to get localization for {}: {}", itemId, e.getMessage());
        }
    }
}
