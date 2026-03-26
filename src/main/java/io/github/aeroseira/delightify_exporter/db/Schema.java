package io.github.aeroseira.delightify_exporter.db;

public class Schema {

    public static final int CURRENT_VERSION = 3;

    public static String createSchemaVersionTable() {
        return """
            CREATE TABLE IF NOT EXISTS schema_version (
                version INTEGER PRIMARY KEY
            )
            """;
    }

    public static String insertSchemaVersion() {
        return "INSERT OR REPLACE INTO schema_version (version) VALUES (?)";
    }

    public static String createManifestTable() {
        return """
            CREATE TABLE IF NOT EXISTS manifest (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
            """;
    }

    public static String insertManifest() {
        return "INSERT OR REPLACE INTO manifest (key, value) VALUES (?, ?)";
    }

    public static String createModsTable() {
        return """
            CREATE TABLE IF NOT EXISTS mods (
                modid TEXT PRIMARY KEY,
                version TEXT,
                name TEXT
            )
            """;
    }

    public static String insertMod() {
        return "INSERT OR REPLACE INTO mods (modid, version, name) VALUES (?, ?, ?)";
    }

    public static String createItemsTable() {
        return """
            CREATE TABLE IF NOT EXISTS items (
                item_id TEXT PRIMARY KEY,
                modid TEXT NOT NULL
            )
            """;
    }

    public static String insertItem() {
        return "INSERT OR REPLACE INTO items (item_id, modid) VALUES (?, ?)";
    }

    public static String createItemTagsTable() {
        return """
            CREATE TABLE IF NOT EXISTS item_tags (
                tag_id TEXT NOT NULL,
                item_id TEXT NOT NULL,
                PRIMARY KEY (tag_id, item_id)
            )
            """;
    }

    public static String insertItemTag() {
        return "INSERT OR REPLACE INTO item_tags (tag_id, item_id) VALUES (?, ?)";
    }

    public static String createItemTagsIndex() {
        return "CREATE INDEX IF NOT EXISTS idx_item_tags_item_id ON item_tags(item_id)";
    }

    public static String createRecipesTable() {
        return """
            CREATE TABLE IF NOT EXISTS recipes (
                recipe_id TEXT PRIMARY KEY,
                type_id TEXT NOT NULL,
                modid TEXT NOT NULL,
                hash TEXT NOT NULL,
                raw_json TEXT,
                unparsed INTEGER NOT NULL
            )
            """;
    }

    public static String insertRecipe() {
        return """
            INSERT OR REPLACE INTO recipes 
            (recipe_id, type_id, modid, hash, raw_json, unparsed) 
            VALUES (?, ?, ?, ?, ?, ?)
            """;
    }

    public static String createRecipesTypeIdIndex() {
        return "CREATE INDEX IF NOT EXISTS idx_recipes_type_id ON recipes(type_id)";
    }

    public static String createRecipesModidIndex() {
        return "CREATE INDEX IF NOT EXISTS idx_recipes_modid ON recipes(modid)";
    }

    // Item Resources 表 - 存储物品资源文件内容
    public static String createItemResourcesTable() {
        return """
            CREATE TABLE IF NOT EXISTS item_resources (
                item_id TEXT NOT NULL,
                resource_type TEXT NOT NULL,
                namespace TEXT NOT NULL,
                path TEXT NOT NULL,
                content TEXT,
                PRIMARY KEY (item_id, resource_type, namespace, path)
            )
            """;
    }

    public static String insertItemResource() {
        return """
            INSERT OR REPLACE INTO item_resources 
            (item_id, resource_type, namespace, path, content) 
            VALUES (?, ?, ?, ?, ?)
            """;
    }

    public static String createItemResourcesIndex() {
        return "CREATE INDEX IF NOT EXISTS idx_item_resources_item_id ON item_resources(item_id)";
    }
}
