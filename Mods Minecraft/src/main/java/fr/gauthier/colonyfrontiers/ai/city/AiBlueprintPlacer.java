package fr.gauthier.colonyfrontiers.ai.city;

import com.minecolonies.api.colony.IColony;
import fr.gauthier.colonyfrontiers.util.CfLogger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Place les blocs "hut" de MineColonies dans le monde pour initialiser physiquement
 * les bâtiments d'une cité IA.
 *
 * Mécanisme MineColonies :
 *   Quand un bloc `minecolonies:blockhut{type}` est posé dans une colonie,
 *   MineColonies détecte automatiquement le placement, crée l'entrée IBuilding
 *   dans la colonie et assigne un Builder pour construire le blueprint correspondant.
 *   Le Builder choisit le schéma depuis `blueprints/minecolonies/{pack}/{category}/{type}1.blueprint`.
 *
 * Disposition en anneaux concentriques autour du Town Hall :
 *   Anneau 0 : Town Hall (centre)
 *   Anneau 1 : Builder (rayon 20)
 *   Anneau 2+ : bâtiments du buildOrder par ordre de priorité (rayon 40, 60, ...)
 *
 * Terrain :
 *   Avant de poser un bloc, on prépare le sol (terre/gravier si nécessaire)
 *   et on supprime les blocs flottants au-dessus pour éviter les conflits de placement.
 */
public class AiBlueprintPlacer {

    private static final Logger LOG = LoggerFactory.getLogger("ColonyFrontiers/Placer");

    /** Espacement entre bâtiments sur l'anneau (blocs). */
    private static final int RING_SPACING  = 20;
    /** Rayon du premier anneau (Builder). */
    private static final int RING_1_RADIUS = 20;

    // ── Mapping buildingId → nom du bloc hut MineColonies ─────────────────
    // Format : "minecolonies:blockhut{id}"
    // Certains IDs ont un mapping non-trivial (ex: "builder" → "blockhutbuilder").

    private static final Map<String, String> HUT_BLOCK_MAP = new HashMap<>();

    static {
        // Directs (id == suffix du bloc)
        for (String id : new String[]{
            "builder", "townhall", "warehouse", "guardtower", "barracks",
            "barrackstower", "combatacademy", "archery", "residence",
            "farmer", "cook", "deliveryman", "lumberjack", "miner",
            "blacksmith", "sawmill", "fisherman", "shepherd", "cowboy",
            "swineherder", "chickenherder", "composter", "florist",
            "enchanter", "library", "university", "hospital", "school",
            "sifter", "crusher", "smeltery", "stonemason", "stonesmeltery",
            "dyer", "glassblower", "fletcher", "mechanic", "netherworker",
            "graveyard", "mysticalsite", "beekeeper", "tavern",
            "gatehouse", "alchemist", "kitchen"
        }) {
            HUT_BLOCK_MAP.put(id, "minecolonies:blockhut" + id);
        }
        // Cas spéciaux
        HUT_BLOCK_MAP.put("plantation", "minecolonies:blockhutplantation");
        HUT_BLOCK_MAP.put("rabbithutch", "minecolonies:blockhutrabbithutch");
    }

    // ── API PUBLIQUE ──────────────────────────────────────────────────────

    /**
     * Place le Town Hall physique au centre de la cité et retourne la position.
     * Doit être appelé juste après createColony().
     */
    public static BlockPos placeTownHall(ServerLevel level, BlockPos center, IColony colony) {
        BlockPos pos = findGroundPos(level, center);
        String blockId = HUT_BLOCK_MAP.get("townhall");
        if (placeHutBlock(level, pos, blockId)) {
            LOG.info("[CF:Placer] TOWNHALL posé en {}", pos);
            CfLogger.log("PLACE_TOWNHALL pos={} colonyId={}", pos, colony.getID());
        }
        return pos;
    }

    /**
     * Place un bâtiment du buildOrder à une position calculée autour du centre.
     *
     * @param buildIndex index dans le buildOrder (0 = premier bâtiment, après TH)
     * @param buildingId identifiant MineColonies du bâtiment (ex: "builder", "guardtower")
     */
    public static boolean placeBuilding(ServerLevel level, BlockPos townHallPos,
                                         String buildingId, int buildIndex, IColony colony) {
        String blockId = HUT_BLOCK_MAP.get(buildingId);
        if (blockId == null) {
            LOG.warn("[CF:Placer] bloc inconnu pour buildingId={}", buildingId);
            return false;
        }

        BlockPos target = computePlacementPos(level, townHallPos, buildIndex);
        if (target == null) {
            LOG.warn("[CF:Placer] aucune position valide pour {} index={}", buildingId, buildIndex);
            return false;
        }

        boolean placed = placeHutBlock(level, target, blockId);
        if (placed) {
            LOG.info("[CF:Placer] {} posé en {} (index={})", buildingId, target, buildIndex);
            CfLogger.log("PLACE_BUILDING building={} pos={} colonyId={}",
                    buildingId, target, colony.getID());
        }
        return placed;
    }

    // ── CALCUL DE POSITION ─────────────────────────────────────────────────
    // Disposition en spirale autour du Town Hall.
    // Anneau 1 (buildIndex 0) : rayon 20, angle 0°
    // Chaque bâtiment suivant : +45° sur le même anneau, puis anneau suivant

    private static BlockPos computePlacementPos(ServerLevel level, BlockPos thPos, int buildIndex) {
        int building = buildIndex + 1; // +1 car index 0 = Builder = anneau 1
        int ring     = (building / 8) + 1;       // 8 bâtiments par anneau
        int slot     = building % 8;
        double angle = (Math.PI * 2 / 8) * slot;
        int radius   = ring * RING_SPACING + RING_1_RADIUS;

        int dx = (int) Math.round(Math.cos(angle) * radius);
        int dz = (int) Math.round(Math.sin(angle) * radius);

        // Cherche la surface à cet offset
        BlockPos candidate = findGroundPos(level, new BlockPos(
                thPos.getX() + dx, thPos.getY(), thPos.getZ() + dz));

        return candidate;
    }

    // ── PLACEMENT PHYSIQUE DU BLOC ─────────────────────────────────────────

    private static boolean placeHutBlock(ServerLevel level, BlockPos pos, String blockId) {
        Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(blockId));
        if (block == null) {
            LOG.warn("[CF:Placer] bloc introuvable dans le registre: {}", blockId);
            return false;
        }

        try {
            // Prépare le sol : s'assure qu'il y a une surface solide
            prepareGround(level, pos);

            // Pose le bloc hut en regardant vers le sud (convention MineColonies)
            BlockState state = block.defaultBlockState();
            // Si le bloc a une propriété FACING, on la positionne
            if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING)) {
                state = state.setValue(
                        net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING,
                        Direction.SOUTH);
            }
            level.setBlock(pos, state, 3); // flag 3 = UPDATE_CLIENTS | UPDATE_NEIGHBORS
            return true;
        } catch (Exception e) {
            LOG.error("[CF:Placer] erreur placement {} en {}: {}", blockId, pos, e.getMessage());
            return false;
        }
    }

    // ── TERRAIN ───────────────────────────────────────────────────────────

    /**
     * Trouve la position de sol à placer le bloc :
     * - Y le plus bas sans eau ni vide direct
     * - Aplanit 1 bloc si nécessaire
     */
    private static BlockPos findGroundPos(ServerLevel level, BlockPos hint) {
        int y = level.getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG,
                hint.getX(), hint.getZ());
        // Sur superflat y=4, on pose le bloc à y=4 (sur la surface)
        y = Math.max(level.getMinBuildHeight() + 1, y);
        return new BlockPos(hint.getX(), y, hint.getZ());
    }

    /**
     * S'assure que la position est posable :
     * - Enlève les blocs non-solides en pos (plante, eau, etc.)
     * - Pose de la terre si le bloc en-dessous est de l'air
     */
    private static void prepareGround(ServerLevel level, BlockPos pos) {
        // Nettoie les 2 blocs verticaux à l'emplacement du bâtiment
        for (int dy = 0; dy <= 1; dy++) {
            BlockPos above = pos.above(dy);
            BlockState state = level.getBlockState(above);
            if (!state.isAir() && !state.isSolidRender(level, above)) {
                level.removeBlock(above, false);
            }
        }
        // Remplit le sol si vide
        BlockPos below = pos.below();
        if (level.getBlockState(below).isAir()) {
            level.setBlock(below, net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState(), 3);
        }
    }
}
