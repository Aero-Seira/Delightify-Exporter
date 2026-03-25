package io.github.aeroseira.delightify_exporter;

import com.mojang.logging.LogUtils;
import io.github.aeroseira.delightify_exporter.command.ExportCommand;
import io.github.aeroseira.delightify_exporter.export.ExporterService;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(DelightifyExporter.MODID)
public class DelightifyExporter {

    public static final String MODID = "delightify_exporter";
    private static final Logger LOGGER = LogUtils.getLogger();

    public DelightifyExporter() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        
        modEventBus.addListener(this::commonSetup);
        
        MinecraftForge.EVENT_BUS.register(this);
        
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        
        LOGGER.info("DelightifyExporter initialized");
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("DelightifyExporter common setup");
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        LOGGER.info("DelightifyExporter ready on server - running auto-export test");
        
        // 自动执行一次导出测试
        try {
            ExporterService exporter = new ExporterService();
            exporter.dump(event.getServer());
            LOGGER.info("Auto-export test completed successfully");
        } catch (Exception e) {
            LOGGER.error("Auto-export test failed", e);
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        ExportCommand.register(event.getDispatcher());
        LOGGER.info("Registered /delightify_export command");
    }
}
