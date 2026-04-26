package com.fortsumter.movabletracks.movement;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import com.fortsumter.movabletracks.MovableTracks;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.Carriage.DimensionalCarriageEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.entity.TrainRelocator;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.entity.TravellingPoint.IEdgePointListener;
import com.simibubi.create.content.trains.entity.TravellingPoint.ITurnListener;
import com.simibubi.create.content.trains.entity.TravellingPoint.ITrackSelector;
import com.simibubi.create.content.trains.entity.TravellingPoint.SteerDirection;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackGraphHelper;
import com.simibubi.create.content.trains.graph.TrackGraphLocation;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.track.ITrackBlock;

import net.createmod.catnip.data.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

final class TrainReleaseResolver {
    private TrainReleaseResolver() {}

    static boolean tryRelease(ServerLevel level, Train train) {
        LinkedHashSet<BlockPos> candidates = collectTrackCandidates(level, train);
        if (candidates.isEmpty()) {
            return false;
        }

        AttachedTrainState.ReleaseChoice choice = chooseBestRelease(level, train, candidates);
        if (choice == null) {
            return false;
        }

        if (TrainRelocator.relocate(train, level, choice.pos(), null, false, choice.look(), false)) {
            train.migrationCooldown = 0;
            MovableTracks.LOGGER.info("Released train {} back onto tracks at {}", train.id, choice.pos());
            return true;
        }

        return false;
    }

    @Nullable
    private static AttachedTrainState.ReleaseChoice chooseBestRelease(ServerLevel level, Train train, Set<BlockPos> candidates) {
        List<AttachedTrainState.CurrentCarriageState> currentState = snapshotCurrentState(train, level);
        if (currentState == null || currentState.isEmpty()) {
            return null;
        }

        Vec3 forwardLook = getReleaseLookVector(level, train);
        Vec3 reverseLook = forwardLook.scale(-1);
        AttachedTrainState.ReleaseChoice best = null;

        for (BlockPos candidate : candidates) {
            AttachedTrainState.ReleaseChoice simulatedForward =
                    simulateReleaseChoice(level, train, candidate, forwardLook, currentState);
            best = betterChoice(best, simulatedForward);

            AttachedTrainState.ReleaseChoice simulatedReverse =
                    simulateReleaseChoice(level, train, candidate, reverseLook, currentState);
            best = betterChoice(best, simulatedReverse);
        }

        return best;
    }

    @Nullable
    private static AttachedTrainState.ReleaseChoice betterChoice(@Nullable AttachedTrainState.ReleaseChoice current,
                                                                 @Nullable AttachedTrainState.ReleaseChoice contender) {
        if (contender == null) {
            return current;
        }
        if (current == null || contender.score() < current.score()) {
            return contender;
        }
        return current;
    }

    @Nullable
    private static AttachedTrainState.ReleaseChoice simulateReleaseChoice(ServerLevel level, Train train, BlockPos pos,
                                                                          Vec3 look,
                                                                          List<AttachedTrainState.CurrentCarriageState> currentState) {
        BlockState blockState = level.getBlockState(pos);
        if (!(blockState.getBlock() instanceof ITrackBlock track)) {
            return null;
        }

        Pair<Vec3, AxisDirection> nearestTrackAxis = track.getNearestTrackAxis(level, pos, blockState, look);
        TrackGraphLocation graphLocation =
                TrackGraphHelper.getGraphLocationAt(level, pos, nearestTrackAxis.getSecond(), nearestTrackAxis.getFirst());
        if (graphLocation == null) {
            return null;
        }

        TrackGraph graph = graphLocation.graph;
        TrackNode node1 = graph.locateNode(graphLocation.edge.getFirst());
        TrackNode node2 = graph.locateNode(graphLocation.edge.getSecond());
        if (node1 == null || node2 == null) {
            return null;
        }

        TrackEdge edge = graph.getConnectionsFrom(node1).get(node2);
        if (edge == null) {
            return null;
        }

        TravellingPoint probe = new TravellingPoint(node1, node2, edge, graphLocation.position, false);
        IEdgePointListener ignoreSignals = probe.ignoreEdgePoints();
        ITurnListener ignoreTurns = probe.ignoreTurns();
        ITrackSelector steer = probe.steer(SteerDirection.NONE, track.getUpNormal(level, pos, blockState));

        List<Vec3> recordedPointPositions = new ArrayList<>();
        boolean[] blocked = {false};
        boolean[] portal = {false};
        int[] reachablePointCount = {0};

        Consumer<TravellingPoint> recorder = travellingPoint -> recordedPointPositions.add(travellingPoint.getPosition(graph));

        train.forEachTravellingPointBackwards((travellingPoint, distance) -> {
            if (blocked[0]) {
                return;
            }

            probe.travel(graph, distance, steer, ignoreSignals, ignoreTurns, nodes -> {
                portal[0] = true;
                return true;
            });
            recorder.accept(probe);

            if (probe.blocked || portal[0]) {
                blocked[0] = true;
                return;
            }

            reachablePointCount[0]++;
        });

        if (blocked[0] || recordedPointPositions.isEmpty()) {
            return null;
        }

        for (int i = 0; i < recordedPointPositions.size() - 1; i++) {
            Vec3 start = recordedPointPositions.get(i);
            Vec3 end = recordedPointPositions.get(i + 1);
            boolean collided = train.findCollidingTrain(level, start, end, level.dimension()) != null;
            boolean unfinished = i >= reachablePointCount[0] - 1;
            if (collided || unfinished) {
                return null;
            }
        }

        List<Vec3> assignedPoints = new ArrayList<>(recordedPointPositions.size());
        for (int i = recordedPointPositions.size() - 1; i >= 0; i--) {
            assignedPoints.add(recordedPointPositions.get(i));
        }

        double score = scoreRelease(assignedPoints, currentState);
        if (Double.isInfinite(score)) {
            return null;
        }

        return new AttachedTrainState.ReleaseChoice(pos, look, score);
    }

    private static double scoreRelease(List<Vec3> assignedPoints,
                                       List<AttachedTrainState.CurrentCarriageState> currentState) {
        int requiredPointCount = currentState.stream().mapToInt(AttachedTrainState.CurrentCarriageState::pointCount).sum();
        if (assignedPoints.size() != requiredPointCount) {
            return Double.POSITIVE_INFINITY;
        }

        int pointIndex = 0;
        double score = 0;

        for (AttachedTrainState.CurrentCarriageState carriageState : currentState) {
            Vec3 leadingPointA = assignedPoints.get(pointIndex++);
            Vec3 leadingPointB = assignedPoints.get(pointIndex++);
            Vec3 simulatedLeadingAnchor = leadingPointA.add(leadingPointB).scale(0.5d);

            if (!carriageState.twoBogeys()) {
                score += carriageState.positionAnchor().distanceToSqr(simulatedLeadingAnchor);
                continue;
            }

            Vec3 trailingPointA = assignedPoints.get(pointIndex++);
            Vec3 trailingPointB = assignedPoints.get(pointIndex++);
            Vec3 simulatedTrailingAnchor = trailingPointA.add(trailingPointB).scale(0.5d);

            score += carriageState.leadingAnchor().distanceToSqr(simulatedLeadingAnchor);
            score += carriageState.trailingAnchor().distanceToSqr(simulatedTrailingAnchor);
        }

        return score;
    }

    @Nullable
    private static List<AttachedTrainState.CurrentCarriageState> snapshotCurrentState(Train train, ServerLevel level) {
        List<AttachedTrainState.CurrentCarriageState> states = new ArrayList<>();

        for (Carriage carriage : train.carriages) {
            DimensionalCarriageEntity dimensional = carriage.getDimensionalIfPresent(level.dimension());
            if (dimensional == null) {
                return null;
            }

            if (!carriage.isOnTwoBogeys()) {
                if (dimensional.positionAnchor == null) {
                    return null;
                }
                states.add(AttachedTrainState.CurrentCarriageState.single(dimensional.positionAnchor));
                continue;
            }

            Vec3 leadingAnchor = dimensional.rotationAnchors.getFirst();
            Vec3 trailingAnchor = dimensional.rotationAnchors.getSecond();
            if (leadingAnchor == null || trailingAnchor == null) {
                return null;
            }

            states.add(AttachedTrainState.CurrentCarriageState.doubleBogey(leadingAnchor, trailingAnchor));
        }

        return states;
    }

    private static LinkedHashSet<BlockPos> collectTrackCandidates(ServerLevel level, Train train) {
        LinkedHashSet<BlockPos> candidates = new LinkedHashSet<>();

        for (Carriage carriage : train.carriages) {
            DimensionalCarriageEntity dimensional = carriage.getDimensionalIfPresent(level.dimension());
            if (dimensional == null) {
                continue;
            }
            addTrackCandidatesAround(level, candidates, dimensional.rotationAnchors.getFirst());
            addTrackCandidatesAround(level, candidates, dimensional.rotationAnchors.getSecond());
            addTrackCandidatesAround(level, candidates, dimensional.positionAnchor);
        }

        return candidates;
    }

    private static void addTrackCandidatesAround(ServerLevel level, Set<BlockPos> candidates, @Nullable Vec3 anchor) {
        if (anchor == null) {
            return;
        }

        BlockPos origin = BlockPos.containing(anchor);
        for (int dx = -TrackTrainAttachmentManager.TRACK_SEARCH_RADIUS; dx <= TrackTrainAttachmentManager.TRACK_SEARCH_RADIUS; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -TrackTrainAttachmentManager.TRACK_SEARCH_RADIUS; dz <= TrackTrainAttachmentManager.TRACK_SEARCH_RADIUS; dz++) {
                    BlockPos candidate = origin.offset(dx, dy, dz);
                    if (level.getBlockState(candidate).getBlock() instanceof ITrackBlock) {
                        candidates.add(candidate);
                    }
                }
            }
        }
    }

    private static Vec3 getReleaseLookVector(ServerLevel level, Train train) {
        Vec3 front = null;
        Vec3 back = null;

        if (!train.carriages.isEmpty()) {
            DimensionalCarriageEntity frontDimensional = train.carriages.getFirst().getDimensionalIfPresent(level.dimension());
            DimensionalCarriageEntity backDimensional = train.carriages.getLast().getDimensionalIfPresent(level.dimension());
            if (frontDimensional != null) {
                front = firstNonNull(frontDimensional.rotationAnchors.getFirst(), frontDimensional.positionAnchor);
            }
            if (backDimensional != null) {
                back = firstNonNull(backDimensional.rotationAnchors.getSecond(), backDimensional.positionAnchor);
            }
        }

        Vec3 look = front != null && back != null ? front.subtract(back) : Vec3.ZERO;
        if (look.lengthSqr() < 1.0E-4 && !train.carriages.isEmpty()) {
            Vec3[] fallback = { null };
            train.carriages.getFirst().forEachPresentEntity(ce -> {
                if (fallback[0] == null) fallback[0] = ce.getLookAngle();
            });
            if (fallback[0] != null) {
                look = fallback[0];
            }
        }

        Vec3 horizontal = new Vec3(look.x, 0, look.z);
        if (horizontal.lengthSqr() > 1.0E-4) {
            return horizontal.normalize();
        }
        if (look.lengthSqr() > 1.0E-4) {
            return look.normalize();
        }
        return new Vec3(1, 0, 0);
    }

    @Nullable
    private static Vec3 firstNonNull(@Nullable Vec3 first, @Nullable Vec3 second) {
        return first != null ? first : second;
    }
}
