package fr.gauthier.colonyfrontiers.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import fr.gauthier.colonyfrontiers.ai.city.AiCityData;
import fr.gauthier.colonyfrontiers.ai.city.AiCityRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import fr.gauthier.colonyfrontiers.ColonyFrontiers;

import java.util.Comparator;
import java.util.List;

/**
 * Commandes de debug Colony Frontiers.
 *
 *   /cf cities            — liste toutes les cités enregistrées
 *   /cf nearest           — affiche la cité la plus proche avec un lien /tp cliquable
 *   /cf status            — affiche le nombre de régions vérifiées et de cités
 */
@Mod.EventBusSubscriber(modid = ColonyFrontiers.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class CfCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(
            Commands.literal("cf")
                .then(Commands.literal("cities")
                    .executes(CfCommand::cmdCities))
                .then(Commands.literal("nearest")
                    .executes(CfCommand::cmdNearest))
                .then(Commands.literal("status")
                    .executes(CfCommand::cmdStatus))
        );
    }

    // ── /cf cities ────────────────────────────────────────────────────────

    private static int cmdCities(CommandContext<CommandSourceStack> ctx) {
        ServerLevel overworld = ctx.getSource().getServer()
                .getLevel(net.minecraft.world.level.Level.OVERWORLD);
        if (overworld == null) { sendError(ctx, "Overworld introuvable."); return 0; }

        AiCityRegistry registry = AiCityRegistry.get(overworld);
        List<AiCityData> cities = registry.getCities();

        if (cities.isEmpty()) {
            sendMsg(ctx, "§e[CF] Aucune cité enregistrée. Explorez le monde pour en générer.");
            return 0;
        }

        sendMsg(ctx, "§6§l[CF] Cités IA enregistrées (" + cities.size() + ") :");

        BlockPos playerPos = BlockPos.containing(ctx.getSource().getPosition());

        cities.stream()
            .sorted(Comparator.comparingDouble(d -> d.center.distSqr(playerPos)))
            .forEach(data -> {
                int dist = (int) Math.sqrt(data.center.distSqr(playerPos));
                String status = data.colonyId == -1
                        ? "§c[en attente]" : "§a[actif id=" + data.colonyId + "]";
                String tier = "§bTier " + data.currentTier;
                String arch = "§d" + data.archetype.name();

                // Ligne cliquable — clic = /tp x y z
                String tpCmd = "/tp " + data.center.getX() + " " + data.center.getY()
                        + " " + data.center.getZ();
                MutableComponent line = Component.literal(
                        "  § " + data.center.getX() + " §7/ §f" + data.center.getZ()
                        + " §7(dist §f" + dist + "§7) " + tier + " " + arch + " " + status
                        + " §7[clic → tp]")
                        .withStyle(style -> style
                                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, tpCmd))
                                .withColor(net.minecraft.network.chat.TextColor.fromLegacyFormat(
                                        ChatFormatting.WHITE)));
                ctx.getSource().sendSuccess(() -> line, false);
            });

        return cities.size();
    }

    // ── /cf nearest ───────────────────────────────────────────────────────

    private static int cmdNearest(CommandContext<CommandSourceStack> ctx) {
        ServerLevel overworld = ctx.getSource().getServer()
                .getLevel(net.minecraft.world.level.Level.OVERWORLD);
        if (overworld == null) { sendError(ctx, "Overworld introuvable."); return 0; }

        AiCityRegistry registry = AiCityRegistry.get(overworld);
        List<AiCityData> cities = registry.getCities();

        if (cities.isEmpty()) {
            sendMsg(ctx, "§e[CF] Aucune cité connue. Explorez pour en découvrir.");
            return 0;
        }

        BlockPos playerPos = BlockPos.containing(ctx.getSource().getPosition());

        AiCityData nearest = cities.stream()
                .min(Comparator.comparingDouble(d -> d.center.distSqr(playerPos)))
                .orElse(null);

        if (nearest == null) return 0;

        int dist = (int) Math.sqrt(nearest.center.distSqr(playerPos));
        String status = nearest.colonyId == -1 ? "§cen attente de matérialisation"
                : "§acolonie active (id=" + nearest.colonyId + ")";

        String tpCmd = "/tp " + nearest.center.getX() + " " + nearest.center.getY()
                + " " + nearest.center.getZ();

        sendMsg(ctx, "§6§l[CF] Cité la plus proche :");
        sendMsg(ctx, "  Coordonnées : §f" + nearest.center.getX()
                + " §7/ §f" + nearest.center.getZ());
        sendMsg(ctx, "  Distance    : §f" + dist + " blocs");
        sendMsg(ctx, "  Tier        : §b" + nearest.currentTier);
        sendMsg(ctx, "  Archétype   : §d" + nearest.archetype.name());
        sendMsg(ctx, "  Statut      : " + status);

        // Lien /tp cliquable
        MutableComponent tpLink = Component.literal("  §a[Cliquez ici pour vous téléporter]")
                .withStyle(style -> style
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, tpCmd))
                        .withColor(net.minecraft.network.chat.TextColor.fromLegacyFormat(
                                ChatFormatting.GREEN)));
        ctx.getSource().sendSuccess(() -> tpLink, false);

        // Rappel : si colonyId == -1, il faut s'approcher pour la matérialiser
        if (nearest.colonyId == -1) {
            sendMsg(ctx, "  §eApprochez-vous à moins de 300 blocs pour créer la colonie.");
        }

        return 1;
    }

    // ── /cf status ────────────────────────────────────────────────────────

    private static int cmdStatus(CommandContext<CommandSourceStack> ctx) {
        ServerLevel overworld = ctx.getSource().getServer()
                .getLevel(net.minecraft.world.level.Level.OVERWORLD);
        if (overworld == null) { sendError(ctx, "Overworld introuvable."); return 0; }

        AiCityRegistry registry = AiCityRegistry.get(overworld);
        long total       = registry.getCities().size();
        long materialized = registry.getCities().stream().filter(d -> d.colonyId != -1).count();
        long pending     = total - materialized;

        sendMsg(ctx, "§6§l[CF] État du Module 1 :");
        sendMsg(ctx, "  Cités totales       : §f" + total);
        sendMsg(ctx, "  Matérialisées (MC)  : §a" + materialized);
        sendMsg(ctx, "  En attente          : §e" + pending);
        sendMsg(ctx, "  §7Utilisez §f/cf cities §7pour voir les coordonnées.");
        return 1;
    }

    // ── HELPERS ───────────────────────────────────────────────────────────

    private static void sendMsg(CommandContext<CommandSourceStack> ctx, String msg) {
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
    }

    private static void sendError(CommandContext<CommandSourceStack> ctx, String msg) {
        ctx.getSource().sendFailure(Component.literal("§c[CF] " + msg));
    }
}
