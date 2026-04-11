package io.github.aeroseira.delightify_exporter.model;

import javax.annotation.Nullable;

/**
 * 配方视图数据行
 * 
 * @param typeId     配方类型ID (namespace:path)
 * @param layoutJson 布局JSON字符串
 * @param base64Png  Base64编码的PNG图片
 * @param version    schema版本号
 */
public record RecipeViewRow(
    String typeId,
    String layoutJson,
    @Nullable String base64Png,
    int version
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    
    /**
     * 创建不可用的占位行
     */
    public static RecipeViewRow unavailable(String typeId, String reason) {
        String json = String.format(
            "{\"schema\":1,\"type_id\":\"%s\",\"unavailable\":true,\"reason\":\"%s\",\"slots\":[],\"size\":{\"w\":0,\"h\":0}}",
            typeId, reason
        );
        return new RecipeViewRow(typeId, json, null, CURRENT_SCHEMA_VERSION);
    }
}
