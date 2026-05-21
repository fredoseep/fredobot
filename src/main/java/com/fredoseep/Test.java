package com.fredoseep;


import com.fredoseep.excutor.BotEngine;
import com.fredoseep.excutor.PathExecutor;
import com.fredoseep.utils.player.PlayerHelper;
import com.fredoseep.utils.player.RelevantDirectionHelper;
import com.fredoseep.utils.prenether.NetherPortalBuilding;
import com.fredoseep.utils.prenether.PreNether;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.IdentityPairList;

public class Test {
    public static CurrentTestingMission currentMission = CurrentTestingMission.IDLE;
    public static void testBlockPlace(){
      tryPlaceDoor(MinecraftClient.getInstance().player,new BlockPos(0,3,0),Direction.NORTH,Direction.WEST);
    }
    public enum CurrentTestingMission{
        IDLE,BLOCK_PLACING,PORTAL_BUILDING;
    }

    public static void reset(){
        currentMission =  CurrentTestingMission.IDLE;
        NetherPortalBuilding.resetState();
    }
    public static void tick(){
        switch (currentMission){
            case IDLE: return;
            case BLOCK_PLACING:
                testBlockPlace();
                reset();
                return;
            case PORTAL_BUILDING:
                testPortalBuilding();
                return;
        }

    }
    private static void tryPlaceDoor(PlayerEntity player, BlockPos basePos, Direction doorOutFacingDirection, Direction hingeOffsetDirection) {
        System.out.println("Fredodebug: hOffset: X: "+hingeOffsetDirection.getOffsetX()+" Z: "+hingeOffsetDirection.getOffsetZ()+" dOffset: X: "+ doorOutFacingDirection.getOffsetX()+" Z: "+ doorOutFacingDirection.getOffsetZ());
        double hitX = basePos.getX() + 0.5 + hingeOffsetDirection.getOffsetX() * 0.25 - doorOutFacingDirection.getOffsetX() * 0.25;
        double hitY = basePos.getY() + 1.0;
        double hitZ = basePos.getZ() + 0.5 + hingeOffsetDirection.getOffsetZ() * 0.25 - doorOutFacingDirection.getOffsetZ() * 0.25;
        Vec3d hitVec = new Vec3d(hitX, hitY, hitZ);

        BlockHitResult hitResult = new BlockHitResult(
                hitVec,
                Direction.UP,
                basePos,
                false
        );
        player.inventory.selectedSlot = 8;
        MinecraftClient client = MinecraftClient.getInstance();
        System.out.println("Fredodebug: trying to placing the door, Door pos: " + basePos.up().toShortString() + " result: " + client.interactionManager.interactBlock((ClientPlayerEntity) player, client.world, Hand.MAIN_HAND, hitResult));
    }

    public static void testPortalBuilding(){
        MinecraftClient client = MinecraftClient.getInstance();
        PreNether.magmaPos = new BlockPos(44,10,24);
        PreNether.alignedMagmaPos = new BlockPos(44,10,25);
        PreNether.fromMagmaToAligned = RelevantDirectionHelper.getDirectionBetween(PreNether.magmaPos,PreNether.alignedMagmaPos);
        PathExecutor pathExecutor = BotEngine.getInstance().getModule(PathExecutor.class);
        if(NetherPortalBuilding.currentTBOState== NetherPortalBuilding.TwoByOneBuildState.IDLE){
        if(pathExecutor.isBusy())return;
        if(!PlayerHelper.isNear(client.player,PreNether.magmaPos.up(),1)){
            pathExecutor.setGoal(PreNether.magmaPos.up(),"Testing Magma Pos");
            return;
        }
        }
        NetherPortalBuilding.twoByOneBuild();
    }

}
