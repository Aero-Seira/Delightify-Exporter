package io.github.aeroseira.delightify_exporter.export;

import com.mojang.logging.LogUtils;
import io.github.aeroseira.delightify_exporter.db.SqliteDatabase;
import io.github.aeroseira.delightify_exporter.model.ItemResourceRow;
import io.github.aeroseira.delightify_exporter.model.ItemRow;
import io.github.aeroseira.delightify_exporter.model.ItemTagRow;
import io.github.aeroseira.delightify_exporter.model.ModRow;
import io.github.aeroseira.delightify_exporter.model.RecipeRow;
import io.github.aeroseira.delightify_exporter.model.RecipeViewRow;
import io.github.aeroseira.delightify_exporter.source.ItemRegistrySource;
import io.github.aeroseira.delightify_exporter.source.ItemResourceSource;
import io.github.aeroseira.delightify_exporter.source.ItemTagSource;
import io.github.aeroseira.delightify_exporter.source.ModListSource;
import io.github.aeroseira.delightify_exporter.source.RecipeSource;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class ExporterService {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String OUTPUT_DIR = "delightify-exporter";
    private static final DateTimeFormatter FILE_NAME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    public void dump(MinecraftServer server) {
        long startTime = System.currentTimeMillis();
        
        // 确定输出路径: <game_root>/delightify-exporter/export_<timestamp>.sqlite
        Path gameRoot = server.getServerDirectory().toPath();
        Path outputDir = gameRoot.resolve(OUTPUT_DIR);
        String timestamp = LocalDateTime.now().format(FILE_NAME_FORMATTER);
        String dbFileName = "export_" + timestamp + ".sqlite";
        Path dbPath = outputDir.resolve(dbFileName);
        
        LOGGER.info("Starting export to: {}", dbPath);
        
        try (SqliteDatabase db = new SqliteDatabase(dbPath)) {
            // 初始化 schema
            db.initializeSchema();
            
            // 写入 manifest
            writeManifest(db, server);
            
            // 导出 mods
            long modsStart = System.currentTimeMillis();
            List<ModRow> mods = new ModListSource().collect();
            db.insertMods(mods);
            LOGGER.info("Exported {} mods in {}ms", mods.size(), System.currentTimeMillis() - modsStart);
            
            // 导出 items
            long itemsStart = System.currentTimeMillis();
            List<ItemRow> items = new ItemRegistrySource().collect();
            db.insertItems(items);
            LOGGER.info("Exported {} items in {}ms", items.size(), System.currentTimeMillis() - itemsStart);
            
            // 导出 item tags
            long tagsStart = System.currentTimeMillis();
            List<ItemTagRow> tags = new ItemTagSource().collect(server);
            db.insertItemTags(tags);
            LOGGER.info("Exported {} item tag relations in {}ms", tags.size(), System.currentTimeMillis() - tagsStart);
            
            // 导出 recipes
            long recipesStart = System.currentTimeMillis();
            List<RecipeRow> recipes = new RecipeSource().collect(server);
            db.insertRecipes(recipes);
            LOGGER.info("Exported {} recipes in {}ms", recipes.size(), System.currentTimeMillis() - recipesStart);
            
            // 导出物品资源元数据
            long resourcesStart = System.currentTimeMillis();
            List<ItemResourceRow> resources = new ItemResourceSource().collect(server);
            db.insertItemResources(resources);
            LOGGER.info("Exported {} item resources in {}ms", resources.size(), System.currentTimeMillis() - resourcesStart);
            
            // 导出配方视图 (M4)
            long recipeViewsStart = System.currentTimeMillis();
            exportRecipeViews(db, recipes);
            LOGGER.info("Exported recipe views in {}ms", System.currentTimeMillis() - recipeViewsStart);
            
            long totalTime = System.currentTimeMillis() - startTime;
            LOGGER.info("Export completed in {}ms", totalTime);
            LOGGER.info("Output: {}", dbPath);
            
        } catch (Exception e) {
            LOGGER.error("Export failed", e);
            throw new RuntimeException("Export failed: " + e.getMessage(), e);
        }
    }

    private void writeManifest(SqliteDatabase db, MinecraftServer server) throws Exception {
        // 导出时间 (UTC)
        String exportedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        db.insertManifestEntry("exported_at_utc", exportedAt);
        
        // Minecraft 版本
        String mcVersion = server.getServerVersion();
        db.insertManifestEntry("minecraft_version", mcVersion);
        
        // Forge 版本
        String forgeVersion = ModList.get().getModContainerById("forge")
            .map(container -> container.getModInfo().getVersion().toString())
            .orElse("unknown");
        db.insertManifestEntry("forge_version", forgeVersion);
        
        // 世界名称
        String worldName = server.getWorldData().getLevelName();
        db.insertManifestEntry("world_name", worldName);
        
        // Mod 列表哈希 (简单用 mod 数量作为参考)
        int modCount = ModList.get().size();
        db.insertManifestEntry("mod_count", String.valueOf(modCount));
        
        LOGGER.info("Manifest written: mc={}, forge={}, world={}, mods={}", 
            mcVersion, forgeVersion, worldName, modCount);
    }
    
    /**
     * 导出配方视图 (M4)
     */
    private void exportRecipeViews(SqliteDatabase db, List<RecipeRow> recipes) throws Exception {
        // 收集所有唯一的 type_id（过滤掉 null）
        Set<String> typeIds = recipes.stream()
            .map(RecipeRow::typeId)
            .filter(typeId -> typeId != null && !typeId.isEmpty())
            .collect(Collectors.toSet());
        
        LOGGER.info("Collecting recipe views for {} type(s) from {} recipes...", typeIds.size(), recipes.size());
        
        if (typeIds.isEmpty()) {
            LOGGER.warn("No recipe type IDs found, skipping recipe views export");
            return;
        }
        
        List<RecipeViewRow> views;
        
        if (FMLEnvironment.dist == Dist.CLIENT) {
            // 客户端：使用 JEI 采集
            LOGGER.info("Running on client, using JEI collection");
            views = collectRecipeViewsClient(typeIds);
        } else {
            // 服务端：创建不可用的占位行
            LOGGER.info("Running on dedicated server, creating unavailable placeholders");
            views = typeIds.stream()
                .map(typeId -> RecipeViewRow.unavailable(typeId, "no_client"))
                .toList();
        }
        
        LOGGER.info("Collected {} recipe view rows, inserting into database...", views.size());
        db.insertRecipeViews(views);
    }
    
    /**
     * 客户端环境下从 JEI 采集配方视图
     */
    private List<RecipeViewRow> collectRecipeViewsClient(Set<String> typeIds) {
        try {
            // 使用反射加载客户端专用的 RecipeViewSource 类
            // 避免服务端加载时找不到客户端类
            LOGGER.info("Loading RecipeViewSource via reflection...");
            Class<?> clazz = Class.forName("io.github.aeroseira.delightify_exporter.client.RecipeViewSource");
            LOGGER.info("RecipeViewSource class loaded successfully");
            
            Object source = clazz.getDeclaredConstructor().newInstance();
            LOGGER.info("RecipeViewSource instance created");
            
            @SuppressWarnings("unchecked")
            List<RecipeViewRow> result = (List<RecipeViewRow>) clazz
                .getMethod("collect", Set.class)
                .invoke(source, typeIds);
            
            LOGGER.info("RecipeViewSource.collect returned {} rows", result != null ? result.size() : "null");
            return result != null ? result : List.of();
            
        } catch (Exception e) {
            LOGGER.error("Failed to collect recipe views from JEI: {}", e.getMessage(), e);
            // 返回不可用的占位行
            return typeIds.stream()
                .map(typeId -> RecipeViewRow.unavailable(typeId, "collection_failed"))
                .toList();
        }
    }
}
