package me.duncanruns.compasstracker;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.Tuple;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.level.Level;
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

    private static final LinkedHashMap<Level, LinkedHashMap<UUID, BlockPos>> POSITIONS = new LinkedHashMap<>();
    private static final HashMap<UUID, ServerPlayer> ONLINE_TRACKED_PLAYERS = new HashMap<>();
    private static final HashMap<UUID, List<UUID>> TEAM_MAP = new HashMap<>();


    private Optional<Tuple<UUID, BlockPos>> updateForPlayer(Level world, UUID target) {
        return getPositionsMapForWorld(world)
                .entrySet().stream()
                .filter(e -> ONLINE_TRACKED_PLAYERS.containsKey(e.getKey()))
                .filter(e -> Objects.equals(target, e.getKey()))
                .map(e -> new Tuple<>(e.getKey(), e.getValue()))
                .findAny();
    }

    private Optional<Tuple<UUID, BlockPos>> cyclePlayer(Level world, UUID current, UUID exceptFor) {
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
        return Optional.of(new Tuple<>(entry.getKey(), entry.getValue()));
    }

    private Optional<Tuple<UUID, BlockPos>> getClosestPlayer(Level world, BlockPos pos, UUID exceptFor) {
        Stream<Map.Entry<UUID, BlockPos>> entryStream = getPositionsMapForWorld(world)
                .entrySet().stream()
                .filter(e -> ONLINE_TRACKED_PLAYERS.containsKey(e.getKey()))
                .filter(e -> !e.getKey().equals(exceptFor));
        if (ONLY_TRACK_ENEMIES) entryStream = entryStream
                .filter(e -> !TEAM_MAP.getOrDefault(exceptFor, List.of()).contains(e.getKey()));
        return entryStream
                .min(Comparator.comparingDouble(p -> p.getValue().distSqr(pos)))
                .map(e -> new Tuple<>(e.getKey(), e.getValue()));
    }

    private static @NotNull LinkedHashMap<UUID, BlockPos> getPositionsMapForWorld(Level world) {
        return POSITIONS.computeIfAbsent(world, w -> new LinkedHashMap<>());
    }

    private ItemStack createCompass() {
        ItemStack stack = new ItemStack(Items.COMPASS);
        CompoundTag compound = new CompoundTag();
        compound.putBoolean("Tracker", true);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(compound));
        return stack;
    }

    @Override
    public void onInitialize() {
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            PlayerList playerManager = server.getPlayerList();
            List<ServerPlayer> playerList = playerManager.getPlayers();
            ONLINE_TRACKED_PLAYERS.clear();
            ONLINE_TRACKED_PLAYERS.putAll(playerList.stream()
                    .filter(p -> Objects.requireNonNull(p.gameMode()).isSurvival())
                    .filter(LivingEntity::isAlive)
                    .filter(p -> !p.getTags().contains("untracked"))
                    .collect(Collectors.toMap(ServerPlayer::getUUID, p -> p)));
            ONLINE_TRACKED_PLAYERS.forEach((uuid, player) -> getPositionsMapForWorld(player.level())
                    .put(uuid, player.blockPosition())
            );


            TEAM_MAP.clear();
            server.getScoreboard().getPlayerTeams().forEach(team -> {
                List<UUID> l = new ArrayList<>(team.getPlayers()).stream().map(playerManager::getPlayerByName).filter(Objects::nonNull).map(Entity::getUUID).toList();
                l.forEach(s -> TEAM_MAP.put(Optional.ofNullable(playerManager.getPlayer(s)).map(Entity::getUUID).orElse(null), l));
            });
        });

        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world.isClientSide())
                return InteractionResult.PASS;

            ItemStack stack = player.getItemInHand(hand);
            if (!stack.is(Items.COMPASS))
                return InteractionResult.PASS;
            CompoundTag currentNBT = stack.getComponents().getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            if (!currentNBT.getBoolean("Tracker").orElse(false))
                return InteractionResult.PASS;

            Tuple<UUID, BlockPos> toTrack;
            if (ALLOW_SWITCHING) {
                UUID currentTrackedPlayer = currentNBT.getString("TrackedPlayer").map(UUID::fromString).orElse(null);
                if (player.isShiftKeyDown() || currentTrackedPlayer == null) {
                    toTrack = cyclePlayer(world, currentTrackedPlayer, player.getUUID()).orElse(null);
                } else {
                    toTrack = updateForPlayer(world, currentTrackedPlayer).orElse(null);
                }
            } else {
                toTrack = getClosestPlayer(world, player.blockPosition(), player.getUUID()).orElse(null);
            }
            if (toTrack == null)
                return InteractionResult.PASS;
            UUID closestPlayerUUID = toTrack.getA();
            BlockPos closestPlayerPos = toTrack.getB();
            Optional.ofNullable(ONLINE_TRACKED_PLAYERS.getOrDefault(closestPlayerUUID, null)).ifPresent(serverPlayerEntity -> {
                stack.set(DataComponents.CUSTOM_NAME, Component.nullToEmpty("Tracking " + serverPlayerEntity.getScoreboardName()));
                stack.set(DataComponents.LODESTONE_TRACKER, new LodestoneTracker(Optional.of(new GlobalPos(world.dimension(), closestPlayerPos)), false));
                CompoundTag compound = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
                compound.putString("TrackedPlayer", closestPlayerUUID.toString());
                stack.set(DataComponents.CUSTOM_DATA, CustomData.of(compound));
            });
            return InteractionResult.SUCCESS;
        });

        CommandRegistrationCallback.EVENT.register((commandDispatcher, commandRegistryAccess, registrationEnvironment) ->
                commandDispatcher.register(Commands.literal("compass").requires(CommandSourceStack::isPlayer).executes(context -> {
                    ServerPlayer player = context.getSource().getPlayer();
                    assert player != null;
                    player.addItem(createCompass());
                    return 1;
                })));

        LOGGER.info("Compass Tracker Initialized!");
    }

    public static void onDeath(ServerPlayer player) {
        if (!CLEAR_ON_DEATH) return;
        LinkedHashMap<UUID, BlockPos> positionsMap = getPositionsMapForWorld(player.level());
        positionsMap.remove(player.getUUID());
    }
}