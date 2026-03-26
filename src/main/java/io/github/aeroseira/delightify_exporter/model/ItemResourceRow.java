package io.github.aeroseira.delightify_exporter.model;

/**
 * 物品资源文件内容（直接存储在数据库中）
 * 
 * @param itemId       物品ID (namespace:path)
 * @param resourceType 资源类型:
 *                     - lang_name: 本地化显示名称
 *                     - model: 模型文件内容 (JSON)
 *                     - texture: 材质文件内容 (Base64 PNG)
 *                     - model_parent: 父模型引用
 * @param namespace    资源命名空间 (如: minecraft, forge)
 * @param path         资源路径 (相对于assets/<namespace>/)
 *                     如: models/item/diamond.json, textures/item/diamond.png
 * @param content      资源内容:
 *                     - lang_name: 本地化文本 (如: "钻石")
 *                     - model: 完整的模型 JSON 内容
 *                     - texture: Base64 编码的 PNG 图片
 */
public record ItemResourceRow(
    String itemId,
    String resourceType,
    String namespace,
    String path,
    String content
) {
}
