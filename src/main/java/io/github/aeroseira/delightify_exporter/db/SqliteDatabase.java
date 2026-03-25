package io.github.aeroseira.delightify_exporter.db;

import com.mojang.logging.LogUtils;
import io.github.aeroseira.delightify_exporter.model.ItemResourceRow;
import io.github.aeroseira.delightify_exporter.model.ItemRow;
import io.github.aeroseira.delightify_exporter.model.ItemTagRow;
import io.github.aeroseira.delightify_exporter.model.ModRow;
import io.github.aeroseira.delightify_exporter.model.RecipeRow;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.sql.*;
import java.util.List;
import java.util.Properties;

public class SqliteDatabase implements AutoCloseable {

    private static final Logger LOGGER = LogUtils.getLogger();
    private final Connection connection;

    public SqliteDatabase(Path dbPath) throws SQLException {
        // 显式加载 SQLite 驱动类（尝试多种类加载器以兼容 Forge 模块化系统）
        Class<?> driverClass = loadDriverClass();
        if (driverClass == null) {
            throw new RuntimeException("SQLite JDBC driver not found in classpath. Ensure sqlite-jdbc is included in dependencies.");
        }
        
        try {
            LOGGER.debug("SQLite JDBC driver class loaded: {}", driverClass.getName());
            // 注册驱动到 DriverManager
            Driver driver = (Driver) driverClass.getDeclaredConstructor().newInstance();
            DriverManager.registerDriver(new DriverWrapper(driver));
        } catch (Exception e) {
            LOGGER.error("Failed to instantiate SQLite JDBC driver", e);
            throw new RuntimeException("Failed to instantiate SQLite JDBC driver", e);
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

    /**
     * 尝试使用多种类加载器加载 SQLite JDBC 驱动类
     * @return 驱动类，如果所有方式都失败则返回 null
     */
    private static Class<?> loadDriverClass() {
        String driverClassName = "org.sqlite.JDBC";
        
        // 尝试 1: 线程上下文类加载器
        try {
            ClassLoader tcl = Thread.currentThread().getContextClassLoader();
            LOGGER.debug("Trying to load driver with Thread Context ClassLoader: {}", tcl);
            Class<?> clazz = Class.forName(driverClassName, true, tcl);
            LOGGER.debug("Driver loaded with Thread Context ClassLoader");
            return clazz;
        } catch (ClassNotFoundException e) {
            LOGGER.debug("Failed to load driver with Thread Context ClassLoader: {}", e.getMessage());
        }
        
        // 尝试 2: SqliteDatabase 类的类加载器
        try {
            ClassLoader cl = SqliteDatabase.class.getClassLoader();
            LOGGER.debug("Trying to load driver with SqliteDatabase ClassLoader: {}", cl);
            Class<?> clazz = Class.forName(driverClassName, true, cl);
            LOGGER.debug("Driver loaded with SqliteDatabase ClassLoader");
            return clazz;
        } catch (ClassNotFoundException e) {
            LOGGER.debug("Failed to load driver with SqliteDatabase ClassLoader: {}", e.getMessage());
        }
        
        // 尝试 3: 系统类加载器
        try {
            ClassLoader scl = ClassLoader.getSystemClassLoader();
            LOGGER.debug("Trying to load driver with System ClassLoader: {}", scl);
            Class<?> clazz = Class.forName(driverClassName, true, scl);
            LOGGER.debug("Driver loaded with System ClassLoader");
            return clazz;
        } catch (ClassNotFoundException e) {
            LOGGER.debug("Failed to load driver with System ClassLoader: {}", e.getMessage());
        }
        
        // 尝试 4: 默认 Class.forName
        try {
            LOGGER.debug("Trying to load driver with Class.forName()");
            Class<?> clazz = Class.forName(driverClassName);
            LOGGER.debug("Driver loaded with Class.forName()");
            return clazz;
        } catch (ClassNotFoundException e) {
            LOGGER.debug("Failed to load driver with Class.forName(): {}", e.getMessage());
        }
        
        LOGGER.error("All attempts to load SQLite JDBC driver failed");
        return null;
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
            
            // Item resources table
            stmt.execute(Schema.createItemResourcesTable());
            stmt.execute(Schema.createItemResourcesIndex());
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

    public void insertItemResources(List<ItemResourceRow> resources) throws SQLException {
        connection.setAutoCommit(false);
        try (PreparedStatement ps = connection.prepareStatement(Schema.insertItemResource())) {
            for (ItemResourceRow resource : resources) {
                ps.setString(1, resource.itemId());
                ps.setString(2, resource.resourceType());
                ps.setString(3, resource.namespace());
                ps.setString(4, resource.path());
                ps.setString(5, resource.content());
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
        LOGGER.info("Inserted {} item resources", resources.size());
    }

    @Override
    public void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
            LOGGER.info("Database connection closed");
        }
    }
    
    /**
     * 驱动包装器，用于解决 Forge 模块化类加载器问题
     */
    private static class DriverWrapper implements Driver {
        private final Driver delegate;
        
        DriverWrapper(Driver delegate) {
            this.delegate = delegate;
        }
        
        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            return delegate.connect(url, info);
        }
        
        @Override
        public boolean acceptsURL(String url) throws SQLException {
            return delegate.acceptsURL(url);
        }
        
        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
            return delegate.getPropertyInfo(url, info);
        }
        
        @Override
        public int getMajorVersion() {
            return delegate.getMajorVersion();
        }
        
        @Override
        public int getMinorVersion() {
            return delegate.getMinorVersion();
        }
        
        @Override
        public boolean jdbcCompliant() {
            return delegate.jdbcCompliant();
        }
        
        @Override
        public java.util.logging.Logger getParentLogger() {
            return java.util.logging.Logger.getLogger(SqliteDatabase.class.getName());
        }
    }
}
