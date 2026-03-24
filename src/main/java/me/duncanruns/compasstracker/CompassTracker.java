package me.duncanruns.compasstracker;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LodestoneTrackerComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Pair;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CompassTracker implements ModInitializer {
    public static final String MOD_ID = "compass-tracker";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final boolean ALLOW_SWITCHING = true;
    private static final boolean ONLY_TRACK_ENEMIES = true;
    private static final boolean CLEAR_ON_DEATH = false;

    private static final LinkedHashMap<World, LinkedHashMap<UUID, BlockPos>> POSITIONS = new LinkedHashMap<>();
    private static final HashMap<UUID, ServerPlayerEntity> ONLINE_TRACKED_PLAYERS = new HashMap<>();
    private static final HashMap<UUID, List<UUID>> TEAM_MAP = new HashMap<>();


    private Optional<Pair<UUID, BlockPos>> updateForPlayer(World world, UUID target) {
        return getPositionsMapForWorld(world)
                .entrySet().stream()
                .filter(e -> ONLINE_TRACKED_PLAYERS.containsKey(e.getKey()))
                .filter(e -> Objects.equals(target, e.getKey()))
                .map(e -> new Pair<>(e.getKey(), e.getValue()))
                .findAny();
    }

    private Optional<Pair<UUID, BlockPos>> cyclePlayer(World world, UUID current, UUID exceptFor) {
        Stream<Map.Entry<UUID, BlockPos>> entryStream = getPositionsMapForWorld(world)
                .entrySet().stream()
                .filter(e -> ONLINE_TRACKED_PLAYERS.containsKey(e.getKey()))
                .filter(e -> !e.getKey().equals(exceptFor));
        if (ONLY_TRACK_ENEMIES) entryStream = entryStream
                .filter(e -> !TEAM_MAP.getOrDefault(exceptFor, List.of()).contains(e.getKey()));
        List<Map.Entry<UUID, BlockPos>> otherPlayersInDimension = entryStream
                .toList();

        if (otherPlayersInDimension.isEmpty()) return Optional.empty();

        int currentIndex = -1;
        if (current != null) {
            for (int i = 0; i < otherPlayersInDimension.size(); i++) {
                if (!Objects.equals(otherPlayersInDimension.get(i).getKey(), current)) continue;
                currentIndex = i;
                break;
            }
        }
        Map.Entry<UUID, BlockPos> entry = otherPlayersInDimension.get((currentIndex + 1) % otherPlayersInDimension.size());
        return Optional.of(new Pair<>(entry.getKey(), entry.getValue()));
    }

    private Optional<Pair<UUID, BlockPos>> getClosestPlayer(World world, BlockPos pos, UUID exceptFor) {
        Stream<Map.Entry<UUID, BlockPos>> entryStream = getPositionsMapForWorld(world)
                .entrySet().stream()
                .filter(e -> ONLINE_TRACKED_PLAYERS.containsKey(e.getKey()))
                .filter(e -> !e.getKey().equals(exceptFor));
        if (ONLY_TRACK_ENEMIES) entryStream = entryStream
                .filter(e -> !TEAM_MAP.getOrDefault(exceptFor, List.of()).contains(e.getKey()));
        return entryStream
                .min(Comparator.comparingDouble(p -> p.getValue().getSquaredDistance(pos)))
                .map(e -> new Pair<>(e.getKey(), e.getValue()));
    }

    private static @NotNull LinkedHashMap<UUID, BlockPos> getPositionsMapForWorld(World world) {
        return POSITIONS.computeIfAbsent(world, w -> new LinkedHashMap<>());
    }

    private ItemStack createCompass() {
        ItemStack stack = new ItemStack(Items.COMPASS);
        NbtCompound compound = new NbtCompound();
        compound.putBoolean("Tracker", true);
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(compound));
        return stack;
    }

    @Override
    public void onInitialize() {
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            PlayerManager playerManager = server.getPlayerManager();
            List<ServerPlayerEntity> playerList = playerManager.getPlayerList();
            ONLINE_TRACKED_PLAYERS.clear();
            ONLINE_TRACKED_PLAYERS.putAll(playerList.stream()
                    .filter(p -> Objects.requireNonNull(p.getGameMode()).isSurvivalLike())
                    .filter(LivingEntity::isAlive)
                    .filter(p -> !p.getCommandTags().contains("untracked"))
                    .collect(Collectors.toMap(ServerPlayerEntity::getUuid, p -> p)));
            ONLINE_TRACKED_PLAYERS.forEach((uuid, player) -> getPositionsMapForWorld(player.getEntityWorld())
                    .put(uuid, player.getBlockPos())
            );


            TEAM_MAP.clear();
            server.getScoreboard().getTeams().forEach(team -> {
                List<UUID> l = new ArrayList<>(team.getPlayerList()).stream().map(playerManager::getPlayer).filter(Objects::nonNull).map(Entity::getUuid).toList();
                l.forEach(s -> TEAM_MAP.put(Optional.ofNullable(playerManager.getPlayer(s)).map(Entity::getUuid).orElse(null), l));
            });
        });

        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world.isClient())
                return ActionResult.PASS;

            ItemStack stack = player.getStackInHand(hand);
            if (!stack.isOf(Items.COMPASS))
                return ActionResult.PASS;
            NbtCompound currentNBT = stack.getComponents().getOrDefault(DataComponentTypes.CUSTOM_DATA, NbtComponent.DEFAULT).copyNbt();
            if (!currentNBT.getBoolean("Tracker").orElse(false))
                return ActionResult.PASS;

            Pair<UUID, BlockPos> toTrack;
            if (ALLOW_SWITCHING) {
                UUID currentTrackedPlayer = currentNBT.getString("TrackedPlayer").map(UUID::fromString).orElse(null);
                if (player.isSneaking() || currentTrackedPlayer == null) {
                    toTrack = cyclePlayer(world, currentTrackedPlayer, player.getUuid()).orElse(null);
                } else {
                    toTrack = updateForPlayer(world, currentTrackedPlayer).orElse(null);
                }
            } else {
                toTrack = getClosestPlayer(world, player.getBlockPos(), player.getUuid()).orElse(null);
            }
            if (toTrack == null)
                return ActionResult.PASS;
            UUID closestPlayerUUID = toTrack.getLeft();
            BlockPos closestPlayerPos = toTrack.getRight();
            Optional.ofNullable(ONLINE_TRACKED_PLAYERS.getOrDefault(closestPlayerUUID, null)).ifPresent(serverPlayerEntity -> {
                stack.set(DataComponentTypes.CUSTOM_NAME, Text.of("Tracking " + serverPlayerEntity.getNameForScoreboard()));
                stack.set(DataComponentTypes.LODESTONE_TRACKER, new LodestoneTrackerComponent(Optional.of(new GlobalPos(world.getRegistryKey(), closestPlayerPos)), false));
                NbtCompound compound = stack.getOrDefault(DataComponentTypes.CUSTOM_DATA, NbtComponent.DEFAULT).copyNbt();
                compound.putString("TrackedPlayer", closestPlayerUUID.toString());
                stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(compound));
            });
            return ActionResult.SUCCESS;
        });

        CommandRegistrationCallback.EVENT.register((commandDispatcher, commandRegistryAccess, registrationEnvironment) ->
                commandDispatcher.register(CommandManager.literal("compass").requires(ServerCommandSource::isExecutedByPlayer).executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayer();
                    assert player != null;
                    player.giveItemStack(createCompass());
                    return 1;
                })));

        LOGGER.info("Compass Tracker Initialized!");
    }

    public static void onDeath(ServerPlayerEntity player) {
        if (!CLEAR_ON_DEATH) return;
        LinkedHashMap<UUID, BlockPos> positionsMap = getPositionsMapForWorld(player.getEntityWorld());
        positionsMap.remove(player.getUuid());
    }
}