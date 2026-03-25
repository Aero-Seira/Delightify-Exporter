package io.github.aeroseira.delightify_exporter.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;
import io.github.aeroseira.delightify_exporter.export.ExporterService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

public class ExportCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("delightify_export")
                .requires(source -> source.hasPermission(2)) // 需要权限等级 2 (OP)
                .then(Commands.literal("dump")
                    .executes(ExportCommand::executeDump)
                )
        );
    }

    private static int executeDump(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        LOGGER.info("Export command initiated by {}", source.getTextName());
        source.sendSuccess(() -> Component.literal("§a[DelightifyExporter] 开始导出数据..."), true);
        
        try {
            var server = source.getServer();
            ExporterService exporter = new ExporterService();
            exporter.dump(server);
            
            source.sendSuccess(() -> Component.literal("§a[DelightifyExporter] 导出完成！"), true);
            return 1;
        } catch (Exception e) {
            LOGGER.error("Export failed", e);
            source.sendFailure(Component.literal("§c[DelightifyExporter] 导出失败: " + e.getMessage()));
            return 0;
        }
    }
}
