package moze_intel.projecte.market;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.nio.file.Files;
import moze_intel.projecte.config.ProjectEConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;

public final class MarketCommand {
    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("market")
            .then(Commands.literal("balance").executes(c -> {
                var player = c.getSource().getPlayerOrException();
                var inv = new moze_intel.projecte.gameObjs.container.inventory.TransmutationInventory(player);
                c.getSource().sendSuccess(() -> Component.literal("Real available EMC: " + inv.getMarketAvailableEmc().toPlainString()
                    + "; indexed EMC: " + MarketService.format(player,inv.getMarketAvailableEmc())
                    + "; denomination scale: " + MarketService.scale(player)),false); return 1;
            }))
            .then(Commands.literal("inspect").executes(c -> inspect(c.getSource(), BuiltInRegistries.ITEM.getKey(c.getSource().getPlayerOrException().getMainHandItem().getItem()).toString()))
                .then(Commands.argument("item", StringArgumentType.string()).executes(c -> inspect(c.getSource(),StringArgumentType.getString(c,"item")))))
            .then(Commands.literal("dump").requires(s -> s.hasPermission(2)).executes(c -> {
                var path = ProjectEConfig.CONFIG_DIR.resolve("market-debug.json");
                try { Files.writeString(path,MarketConfig.GSON.toJson(MarketService.dump(c.getSource().getServer()))); }
                catch (Exception e) { c.getSource().sendFailure(Component.literal(e.getMessage())); return 0; }
                c.getSource().sendSuccess(()->Component.literal("Market valuations and inventory exported to " + path),false); return 1;
            }));
    }
    private static int inspect(CommandSourceStack source,String id) {
        source.sendSuccess(()->Component.literal(MarketConfig.GSON.toJson(MarketService.inspect(source.getServer(),id))),false); return 1;
    }
}
