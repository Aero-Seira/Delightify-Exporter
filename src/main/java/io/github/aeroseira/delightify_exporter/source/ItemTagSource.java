package io.github.aeroseira.delightify_exporter.source;

import io.github.aeroseira.delightify_exporter.model.ItemTagRow;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ItemTagSource {

    public List<ItemTagRow> collect(MinecraftServer server) {
        List<ItemTagRow> results = new ArrayList<>();
        var registry = server.registryAccess().registryOrThrow(Registries.ITEM);
        
        // 获取所有 tag
        for (TagKey<Item> tagKey : registry.getTagNames().toList()) {
            ResourceLocation tagId = tagKey.location();
            String tagIdStr = tagId.toString();
            
            // 获取该 tag 下的所有 items (已解析)
            registry.getTag(tagKey).ifPresent(tag -> {
                tag.forEach(holder -> {
                    ResourceLocation itemId = holder.value().builtInRegistryHolder().key().location();
                    results.add(new ItemTagRow(tagIdStr, itemId.toString()));
                });
            });
        }
        
        return results;
    }
}
