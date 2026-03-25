package io.github.aeroseira.delightify_exporter.db;

import com.mojang.logging.LogUtils;
import io.github.aeroseira.delightify_exporter.model.ItemRow;
import io.github.aeroseira.delightify_exporter.model.ItemTagRow;
import io.github.aeroseira.delightify_exporter.model.ModRow;
import io.github.aeroseira.delightify_exporter.model.RecipeRow;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.sql.*;
import java.util.List;

import org.sqlite.JDBC;

public class SqliteDatabase implements AutoCloseable {

    private static final Logger LOGGER = LogUtils.getLogger();
    private final Connection connection;

    public SqliteDatabase(Path dbPath) throws SQLException {
        // 显式注册 SQLite 驱动（Forge 类加载器需要）
        try {
            DriverManager.registerDriver(new JDBC());
            LOGGER.debug("SQLite JDBC driver registered");
        } catch (SQLException e) {
            LOGGER.warn("Failed to register SQLite driver, may already be registered: {}", e.getMessage());
        }
        
        // 确保父目录存在
        dbPath.getParent().toFile().mkdirs();
        
        String url = "jdbc:sqlite:" + dbPath.toAbsolutePath();
        LOGGER.info("Opening SQLite database: {}", url);
        this.connection = DriverManager.getConnection(url);
        
        // 启用外键和WAL模式以提高性能
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON");
            stmt.execute("PRAGMA journal_mode = WAL");
            stmt.execute("PRAGMA synchronous = NORMAL");
        }
    }

    public void initializeSchema() throws SQLException {
        LOGGER.info("Initializing database schema...");
        
        try (Statement stmt = connection.createStatement()) {
            // Schema version table
            stmt.execute(Schema.createSchemaVersionTable());
            
            // Manifest table
            stmt.execute(Schema.createManifestTable());
            
            // Mods table
            stmt.execute(Schema.createModsTable());
            
            // Items table
            stmt.execute(Schema.createItemsTable());
            
            // Item tags table
            stmt.execute(Schema.createItemTagsTable());
            stmt.execute(Schema.createItemTagsIndex());
            
            // Recipes table
            stmt.execute(Schema.createRecipesTable());
            stmt.execute(Schema.createRecipesTypeIdIndex());
            stmt.execute(Schema.createRecipesModidIndex());
        }
        
        // Insert schema version
        try (PreparedStatement ps = connection.prepareStatement(Schema.insertSchemaVersion())) {
            ps.setInt(1, Schema.CURRENT_VERSION);
            ps.executeUpdate();
        }
        
        LOGGER.info("Schema initialized (version {})", Schema.CURRENT_VERSION);
    }

    public void insertManifestEntry(String key, String value) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(Schema.insertManifest())) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    public void insertMods(List<ModRow> mods) throws SQLException {
        connection.setAutoCommit(false);
        try (PreparedStatement ps = connection.prepareStatement(Schema.insertMod())) {
            for (ModRow mod : mods) {
                ps.setString(1, mod.modid());
                ps.setString(2, mod.version());
                ps.setString(3, mod.name());
                ps.addBatch();
            }
            ps.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
        LOGGER.info("Inserted {} mods", mods.size());
    }

    public void insertItems(List<ItemRow> items) throws SQLException {
        connection.setAutoCommit(false);
        try (PreparedStatement ps = connection.prepareStatement(Schema.insertItem())) {
            for (ItemRow item : items) {
                ps.setString(1, item.itemId());
                ps.setString(2, item.modid());
                ps.addBatch();
            }
            ps.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
        LOGGER.info("Inserted {} items", items.size());
    }

    public void insertItemTags(List<ItemTagRow> tags) throws SQLException {
        connection.setAutoCommit(false);
        try (PreparedStatement ps = connection.prepareStatement(Schema.insertItemTag())) {
            for (ItemTagRow tag : tags) {
                ps.setString(1, tag.tagId());
                ps.setString(2, tag.itemId());
                ps.addBatch();
            }
            ps.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
        LOGGER.info("Inserted {} item tag relations", tags.size());
    }

    public void insertRecipes(List<RecipeRow> recipes) throws SQLException {
        connection.setAutoCommit(false);
        try (PreparedStatement ps = connection.prepareStatement(Schema.insertRecipe())) {
            for (RecipeRow recipe : recipes) {
                ps.setString(1, recipe.recipeId());
                ps.setString(2, recipe.typeId());
                ps.setString(3, recipe.modid());
                ps.setString(4, recipe.hash());
                ps.setString(5, recipe.rawJson());
                ps.setInt(6, recipe.unparsed());
                ps.addBatch();
            }
            ps.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
        LOGGER.info("Inserted {} recipes", recipes.size());
    }

    @Override
    public void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
            LOGGER.info("Database connection closed");
        }
    }
}
