package io.github.aeroseira.delightify_exporter.source;

import io.github.aeroseira.delightify_exporter.model.ItemRow;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.List;

public class ItemRegistrySource {

    public List<ItemRow> collect() {
        List<ItemRow> items = new ArrayList<>();
        
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (id != null) {
                String itemId = id.toString();
                String modid = id.getNamespace();
                items.add(new ItemRow(itemId, modid));
            }
        }
        
        return items;
    }
}
