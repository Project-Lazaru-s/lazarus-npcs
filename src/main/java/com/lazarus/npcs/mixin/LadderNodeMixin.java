package com.lazarus.npcs.mixin;

import com.lazarus.npcs.NpcAiHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla walkers never plan a path up a ladder. For Lazarus human NPCs only, a climbable column becomes a chain of
 * vertical nodes (and the top of a ladder can be entered from beside it). Following the path needs nothing else: the
 * move control jumps toward a node straight above, and a jumping entity on a climbable block climbs.
 */
@Mixin(WalkNodeEvaluator.class)
abstract class LadderNodeMixin extends NodeEvaluator {
    @Inject(method = "getNeighbors", at = @At("RETURN"), cancellable = true)
    private void lazarusnpcs$ladders(Node[] neighbors, Node node, CallbackInfoReturnable<Integer> result) {
        if (mob == null || !NpcAiHooks.climbsLadders(mob)) return;
        int count = result.getReturnValue();
        BlockPos at = new BlockPos(node.x, node.y, node.z);
        if (climbable(at) && open(at.above()) && open(at.above(2))) count = add(neighbors, count, at.above());
        if (climbable(at.below())) count = add(neighbors, count, at.below());
        for (Direction side : Direction.Plane.HORIZONTAL) { // step from a ledge onto the top rung below it
            BlockPos rung = at.relative(side).below();
            if (!climbable(at) && climbable(rung) && open(rung.above()) && open(rung.above(2)))
                count = add(neighbors, count, rung);
        }
        result.setReturnValue(count);
    }

    /** A repath while hanging on a ladder starts where the NPC is, not at the floor below it. */
    @Inject(method = "getStart", at = @At("RETURN"), cancellable = true)
    private void lazarusnpcs$startOnLadder(CallbackInfoReturnable<Node> result) {
        if (mob == null || mob.onGround() || !mob.onClimbable() || !NpcAiHooks.climbsLadders(mob)) return;
        Node start = getNode(mob.blockPosition());
        start.type = BlockPathTypes.WALKABLE;
        start.costMalus = 0;
        result.setReturnValue(start);
    }

    private boolean climbable(BlockPos pos) { return level.getBlockState(pos).is(BlockTags.CLIMBABLE); }

    private boolean open(BlockPos pos) {
        return climbable(pos) || level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    private int add(Node[] neighbors, int count, BlockPos pos) {
        if (count >= neighbors.length) return count;
        Node next = getNode(pos);
        if (next.closed) return count;
        for (int i = 0; i < count; i++) if (neighbors[i] == next) return count;
        next.type = BlockPathTypes.WALKABLE;
        next.costMalus = Math.max(next.costMalus, 0);
        neighbors[count] = next;
        return count + 1;
    }
}
