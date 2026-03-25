package io.github.aeroseira.delightify_exporter.model;

/**
 * 物品资源文件元数据（标识符形式，供外部工具解析）
 * 
 * @param itemId       物品ID (namespace:path)
 * @param resourceType 资源类型:
 *                     - lang_name: 本地化显示名称
 *                     - model: 模型文件路径
 *                     - texture: 材质文件路径
 *                     - particle: 粒子材质路径
 * @param namespace    资源命名空间 (如: minecraft, forge)
 * @param path         资源路径 (相对于assets/<namespace>/)
 *                     如: models/item/diamond.json, textures/item/diamond.png
 * @param content      内容摘要:
 *                     - lang_name: 本地化文本 (如: "钻石")
 *                     - model: 父模型路径
 *                     - texture: 纹理变量名
 */
public record ItemResourceRow(
    String itemId,
    String resourceType,
    String namespace,
    String path,
    String content
) {
}
