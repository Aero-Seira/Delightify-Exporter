package io.github.aeroseira.delightify_exporter.export;

import com.mojang.logging.LogUtils;
import io.github.aeroseira.delightify_exporter.db.SqliteDatabase;
import io.github.aeroseira.delightify_exporter.model.ItemResourceRow;
import io.github.aeroseira.delightify_exporter.model.ItemRow;
import io.github.aeroseira.delightify_exporter.model.ItemTagRow;
import io.github.aeroseira.delightify_exporter.model.ModRow;
import io.github.aeroseira.delightify_exporter.model.RecipeRow;
import io.github.aeroseira.delightify_exporter.source.ItemRegistrySource;
import io.github.aeroseira.delightify_exporter.source.ItemResourceSource;
import io.github.aeroseira.delightify_exporter.source.ItemTagSource;
import io.github.aeroseira.delightify_exporter.source.ModListSource;
import io.github.aeroseira.delightify_exporter.source.RecipeSource;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

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
}
