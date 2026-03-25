package io.github.aeroseira.delightify_exporter.source;

import io.github.aeroseira.delightify_exporter.model.ModRow;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModInfo;

import java.util.ArrayList;
import java.util.List;

public class ModListSource {

    public List<ModRow> collect() {
        List<ModRow> mods = new ArrayList<>();
        
        ModList.get().forEachModContainer((modId, container) -> {
            IModInfo info = container.getModInfo();
            String version = info.getVersion().toString();
            String name = info.getDisplayName();
            mods.add(new ModRow(modId, version, name));
        });
        
        return mods;
    }
}
