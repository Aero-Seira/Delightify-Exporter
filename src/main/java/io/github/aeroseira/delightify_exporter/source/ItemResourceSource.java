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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 收集物品资源：异步高性能渲染
 * 
 * 特性：
 * 1. 异步批量渲染，不阻塞游戏
 * 2. 实时显示进度
 * 3. 支持中途取消
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
        
        // 收集所有物品
        Map<String, ItemStack> renderTasks = new HashMap<>();
        int itemCount = 0;
        
        LOGGER.info("Collecting items...");
        
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);
            if (itemId == null) continue;
            
            String itemIdStr = itemId.toString();
            String modid = itemId.getNamespace();
            itemCount++;
            
            try {
                // 收集名称
                collectLocalization(resources, itemIdStr, modid, item);
                
                // 客户端：加入渲染队列
                if (isPhysicalClient) {
                    renderTasks.put(itemIdStr, new ItemStack(item));
                }
                
            } catch (Exception e) {
                LOGGER.warn("Failed to collect item {}: {}", itemIdStr, e.getMessage());
            }
        }
        
        LOGGER.info("Collected {} items for rendering", renderTasks.size());
        
        // 异步渲染
        if (isPhysicalClient && !renderTasks.isEmpty()) {
            renderAsync(resources, renderTasks);
        }
        
        if (!isPhysicalClient) {
            LOGGER.warn("Running on server - textures not available");
        }
        
        // 统计
        long textureCount = resources.stream().filter(r -> r.resourceType().equals("texture")).count();
        LOGGER.info("Collection complete: {} items, {} resources ({} textures)", 
            itemCount, resources.size(), textureCount);
        
        return resources;
    }
    
    /**
     * 异步渲染
     */
    private void renderAsync(List<ItemResourceRow> resources, Map<String, ItemStack> tasks) {
        LOGGER.info("Starting async render...");
        long startTime = System.currentTimeMillis();
        
        // 启动异步渲染
        CompletableFuture<Map<String, String>> future = ItemRenderHelper.startAsyncRender(
            tasks,
            progress -> {
                // 每10%打印一次进度
                int percent = (int) progress.getProgress();
                if (percent % 10 == 0 && progress.completed > 0) {
                    LOGGER.info("Progress: {}% ({}/{} items, {} it/s, batch: {})",
                        percent,
                        progress.completed,
                        progress.total,
                        String.format("%.1f", progress.itemsPerSecond),
                        progress.batchSize
                    );
                }
            }
        );
        
        // 等待完成（非阻塞轮询，保持游戏响应）
        while (!future.isDone()) {
            try {
                Thread.sleep(100);
                
                // 打印当前进度
                ItemRenderHelper.ProgressInfo progress = ItemRenderHelper.getCurrentProgress();
                if (progress != null) {
                    // 可以在这里更新 UI 或发送聊天消息
                }
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        // 获取结果
        try {
            Map<String, String> results = future.get(5, TimeUnit.MINUTES);
            long elapsed = System.currentTimeMillis() - startTime;
            
            // 将结果加入资源
            for (var entry : results.entrySet()) {
                String itemId = entry.getKey();
                String base64 = entry.getValue();
                String modid = itemId.split(":")[0];
                
                resources.add(new ItemResourceRow(itemId, "texture", modid, "inventory/render", base64));
            }
            
            LOGGER.info("Async render finished in {}ms: {} textures", elapsed, results.size());
            
        } catch (Exception e) {
            LOGGER.error("Async render failed: {}", e.getMessage());
        }
    }

    /**
     * 收集本地化名称
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
