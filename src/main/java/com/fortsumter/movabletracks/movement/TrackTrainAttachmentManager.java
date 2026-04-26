package com.fortsumter.movabletracks.movement;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.Create;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.track.ITrackBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import static com.fortsumter.movabletracks.movement.TrainAttachmentController.suppressDerailedState;

public final class TrackTrainAttachmentManager {
    static final int REATTACH_SUPPRESSION_TICKS = 72_000;
    static final int RELEASE_RETRY_TICKS = 20;
    static final int TRACK_SEARCH_RADIUS = 2;
    static final double TRACK_ANCHOR_MAX_DISTANCE_SQR = 2.25d;

    private static final Map<ResourceKey<Level>, Map<UUID, WeakReference<AbstractContraptionEntity>>> TRACK_CONTRAPTIONS =
            new HashMap<>();
    private static final Map<UUID, AttachedTrainState> ATTACHED_TRAINS = new HashMap<>();
    private static boolean registered;

    private TrackTrainAttachmentManager() {}

    public static void register() {
        if (registered) {
            return;
        }

        registered = true;
        NeoForge.EVENT_BUS.addListener(TrackTrainAttachmentManager::onEntityJoin);
        NeoForge.EVENT_BUS.addListener(TrackTrainAttachmentManager::onEntityLeave);
        NeoForge.EVENT_BUS.addListener(TrackTrainAttachmentManager::onLevelUnload);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, TrackTrainAttachmentManager::onLevelTick);
    }

    private static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof AbstractContraptionEntity contraption) || !carriesTracks(contraption)) {
            return;
        }

        TRACK_CONTRAPTIONS.computeIfAbsent(event.getLevel().dimension(), $ -> new HashMap<>())
                .put(contraption.getUUID(), new WeakReference<>(contraption));
    }

    private static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof AbstractContraptionEntity contraption)) {
            return;
        }

        Map<UUID, WeakReference<AbstractContraptionEntity>> tracked = TRACK_CONTRAPTIONS.get(event.getLevel().dimension());
        if (tracked != null) {
            tracked.remove(contraption.getUUID());
            if (tracked.isEmpty()) {
                TRACK_CONTRAPTIONS.remove(event.getLevel().dimension());
            }
        }
    }

    private static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            return;
        }

        Level level = (Level) event.getLevel();
        TRACK_CONTRAPTIONS.remove(level.dimension());
        ATTACHED_TRAINS.entrySet().removeIf(entry -> entry.getValue().dimension().equals(level.dimension()));
    }

    private static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel().isClientSide()) {
            return;
        }

        ServerLevel level = (ServerLevel) event.getLevel();
        Map<UUID, WeakReference<AbstractContraptionEntity>> tracked = TRACK_CONTRAPTIONS.get(level.dimension());
        boolean hasAttachments = ATTACHED_TRAINS.values().stream().anyMatch(attachment -> attachment.dimension().equals(level.dimension()));
        if ((tracked == null || tracked.isEmpty()) && !hasAttachments) {
            return;
        }

        updateAttachedTrains(level, tracked);
        List<AbstractContraptionEntity> contraptions = pruneContraptions(level, tracked);
        attachEligibleTrains(level, contraptions);
    }

    private static List<AbstractContraptionEntity> pruneContraptions(ServerLevel level,
                                                                     @Nullable Map<UUID, WeakReference<AbstractContraptionEntity>> tracked) {
        if (tracked == null || tracked.isEmpty()) {
            return List.of();
        }

        List<AbstractContraptionEntity> alive = new ArrayList<>();
        tracked.entrySet().removeIf(entry -> {
            AbstractContraptionEntity contraption = entry.getValue().get();
            if (contraption == null || !contraption.isAlive() || contraption.level() != level || !carriesTracks(contraption)) {
                return true;
            }
            alive.add(contraption);
            return false;
        });

        if (tracked.isEmpty()) {
            TRACK_CONTRAPTIONS.remove(level.dimension());
        }

        return alive;
    }

    private static void updateAttachedTrains(ServerLevel level,
                                             @Nullable Map<UUID, WeakReference<AbstractContraptionEntity>> tracked) {
        ATTACHED_TRAINS.entrySet().removeIf(entry -> {
            AttachedTrainState attachment = entry.getValue();
            if (!attachment.dimension().equals(level.dimension())) {
                return false;
            }

            Train train = Create.RAILWAYS.trains.get(attachment.trainId());
            if (train != null) {
                suppressDerailedState(train);
            }

            if (train == null || train.invalid) {
                return true;
            }

            AbstractContraptionEntity contraption = resolveContraption(tracked, attachment.contraptionId());
            if (contraption != null && contraption.isAlive() && contraption.level() == level && carriesTracks(contraption)) {
                attachment.resetReleaseAttempts();
                TrainAttachmentController.applyAttachment(level, train, contraption, attachment);
                return false;
            }

            if (contraption != null && contraption.level() == level) {
                TrainAttachmentController.applyAttachment(level, train, contraption, attachment);
            }

            if (TrainReleaseResolver.tryRelease(level, train)) {
                return true;
            }

            if (attachment.incrementReleaseAttempts() <= RELEASE_RETRY_TICKS) {
                return false;
            }

            train.derailed = true;
            train.status.failedMigration();
            train.migrationCooldown = 0;
            return true;
        });
    }

    private static void attachEligibleTrains(ServerLevel level, List<AbstractContraptionEntity> contraptions) {
        if (contraptions.isEmpty()) {
            return;
        }

        Set<UUID> attachedTrainIds = ATTACHED_TRAINS.keySet();
        for (Train train : Create.RAILWAYS.trains.values()) {
            if (attachedTrainIds.contains(train.id) || train.invalid) {
                continue;
            }
            if (train.getPresentDimensions().size() != 1 || !train.getPresentDimensions().contains(level.dimension())) {
                continue;
            }

            for (AbstractContraptionEntity contraption : contraptions) {
                AttachedTrainState snapshot = TrainAttachmentController.createSnapshot(train, level, contraption);
                if (snapshot == null) {
                    continue;
                }

                TrainAttachmentController.beginAttachment(level, train, contraption, snapshot);
                ATTACHED_TRAINS.put(train.id, snapshot);
                break;
            }
        }
    }

    @Nullable
    private static AbstractContraptionEntity resolveContraption(
            @Nullable Map<UUID, WeakReference<AbstractContraptionEntity>> tracked, UUID contraptionId) {
        if (tracked == null) {
            return null;
        }
        WeakReference<AbstractContraptionEntity> reference = tracked.get(contraptionId);
        return reference == null ? null : reference.get();
    }

    static boolean isTrackBlock(AbstractContraptionEntity contraption, BlockPos localPos) {
        Contraption data = contraption.getContraption();
        if (data == null) {
            return false;
        }

        StructureBlockInfo info = data.getBlocks().get(localPos);
        return info != null && info.state().getBlock() instanceof ITrackBlock;
    }

    private static boolean carriesTracks(AbstractContraptionEntity contraption) {
        Contraption data = contraption.getContraption();
        if (data == null) {
            return false;
        }

        for (StructureBlockInfo info : data.getBlocks().values()) {
            if (info.state().getBlock() instanceof ITrackBlock) {
                return true;
            }
        }
        return false;
    }
}
