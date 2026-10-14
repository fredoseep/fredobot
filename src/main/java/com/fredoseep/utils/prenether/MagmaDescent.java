package com.fredoseep.utils.prenether;

import com.fredoseep.algorithm.planning.WaterSteering;
import com.fredoseep.utils.player.ShaftRoof;
import com.fredoseep.behave.MovementController;
import com.fredoseep.excutor.PathExecutor;
import com.fredoseep.utils.bt.BtStuff;
import com.fredoseep.utils.player.MiningHelper;
import com.fredoseep.utils.player.ToolsHelper;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.options.KeyBinding;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.tag.FluidTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RayTraceContext;
import net.minecraft.world.World;

/** Default magma travel: approach the column, then descend and mine vertically. */
public final class MagmaDescent {
    private enum Phase { TO_SEABED,DESCENDING,DONE,FAILED }
    private Phase phase=Phase.TO_SEABED;
    private final World world;
    private final BlockPos goal,seabed,approach;
    private boolean approachRequested;
    private long failureSerial;
    private Vec3d progress;
    private int stagnantTicks, sealingTicks;
    public MagmaDescent(MinecraftClient client,BlockPos magma) {
        world=client.world;goal=magma.up().toImmutable();
        seabed=BtStuff.oceanFloorNoKelp(magma).up().toImmutable();
        BlockPos surface=seabed;
        while(surface.getY()<100 && world.getBlockState(surface).getFluidState().isIn(FluidTags.WATER)
                && world.getBlockState(surface.up()).getFluidState().isIn(FluidTags.WATER)) surface=surface.up();
        approach=client.player.getY()>=seabed.getY()?surface:seabed;
        System.out.println("FredoBot: MAGMA_DESCENT start seabed="+seabed.toShortString()+" goal="+goal.toShortString());
    }
    public String hudStatus(){return switch(phase) {
        case TO_SEABED -> "岩浆点：前往正上方水面/海床";
        case DESCENDING -> "岩浆点：居中并垂直下挖";
        case DONE -> "岩浆点：已抵达";
        case FAILED -> "岩浆点：失败";
    };}
    public boolean descending(){return phase==Phase.DESCENDING;}
    public boolean done(){return phase==Phase.DONE;}
    public boolean failed(){return phase==Phase.FAILED;}
    private void fail(MinecraftClient client,String reason) {
        phase=Phase.FAILED;release(client);
        System.out.println("FredoBot: MAGMA_DESCENT_FAILED reason="+reason);
    }
    public void tick(MinecraftClient client,PlayerEntity player,PathExecutor path) {
        if(client.world!=world || !player.isAlive()){fail(client,"WORLD_OR_PLAYER_CHANGED");return;}
        if(done() || failed())return;
        if(path.isBusy())return;
        if(!world.getChunkManager().isChunkLoaded(goal.getX()>>4,goal.getZ()>>4)) {
            fail(client,"COLUMN_NOT_LOADED");return;
        }
        double dx=goal.getX()+.5-player.getX(),dz=goal.getZ()+.5-player.getZ();
        if(phase==Phase.TO_SEABED) {
            if(approachRequested && path.searchFailedFor(world,approach,failureSerial)){fail(client,"SEABED_ROUTE_FAILED");return;}
            boolean centered=dx*dx+dz*dz<=.04 && Math.abs(player.getY()-approach.getY())<=.5;
            // Route completion uses water-pose tolerances. Exact centering belongs to DESCENDING,
            // not to the handoff from a successful approach route.
            boolean nearColumn=dx*dx+dz*dz<=2.25 && player.getY()<=approach.getY()+2
                    && player.getY()>=goal.getY()-.5;
            if(!centered && !(approachRequested && nearColumn)) {
                if(approachRequested){fail(client,"SEABED_HANDOFF_TOO_FAR pos="+player.getPos()+" seabed="+seabed.toShortString());return;}
                if(path.trySetGoal(approach,"magma vertical approach")) {
                    approachRequested=true;failureSerial=path.getSearchFailureSerial();
                }
                return;
            }
            phase=Phase.DESCENDING;progress=player.getPos();
            System.out.println("FredoBot: MAGMA_DESCENT handoff pos="+player.getPos()+" seabed="+seabed.toShortString()
                    +" horizontal_error="+Math.hypot(dx,dz)+" height_error="+(player.getY()-seabed.getY())
                    +"; descending column="+goal.getX()+","+goal.getZ());
        }
        release(client);
        if(player.hasVehicle())return; // PreNether recovers the boat before any descent/mining.
        if(dx*dx+dz*dz<=.04 && Math.abs(player.getY()-goal.getY())<=.3) {
            phase=Phase.DONE;System.out.println("FredoBot: MAGMA_DESCENT_DONE goal="+goal.toShortString());return;
        }
        if(player.getY()<goal.getY()-.5){fail(client,"DESCENT_OVERSHOT_GOAL");return;}
        if(player.getPos().squaredDistanceTo(progress)>.01){progress=player.getPos();stagnantTicks=0;}
        else if(++stagnantTicks>1200){fail(client,"NO_DESCENT_PROGRESS_60_SECONDS");return;}
        if(dx*dx+dz*dz>.04) {
            Vec3d v=player.getVelocity();
            WaterSteering.Aim aim=WaterSteering.aim(dx,0,dz,v.x,0,v.z,0,0,0,player.yaw);
            MovementController.setLookDirection(player,aim.yaw(),0);
            key(client.options.keyForward,true);key(client.options.keySprint,player.isTouchingWater());return;
        }
        BlockPos dig=new BlockPos(goal.getX(),player.getY()-.1,goal.getZ());
        if(dig.getY()<goal.getY())return; // Never break the destination magma block.
        var block=world.getBlockState(dig);
        if(block.getFluidState().isIn(FluidTags.LAVA) || block.getBlock()==Blocks.MAGMA_BLOCK || block.getBlock()==Blocks.BEDROCK) {
            fail(client,"UNSAFE_OR_UNBREAKABLE_COLUMN_BLOCK "+dig.toShortString());return;
        }
        if(!block.getCollisionShape(world,dig).isEmpty()) {
            if(block.getHardness(world,dig)<0){fail(client,"UNBREAKABLE_COLUMN_BLOCK");return;}
            ShaftRoof.Result roof=ShaftRoof.seal(client,player);
            if(roof!=ShaftRoof.Result.NOT_NEEDED) {
                if(roof==ShaftRoof.Result.NO_MATERIAL){fail(client,"ROOF_NO_BUILDING_BLOCK");return;}
                if(++sealingTicks>100){fail(client,"ROOF_NOT_CONFIRMED");return;}
                return; // Observe the placed roof in the world before breaking the next floor.
            }
            sealingTicks=0;
            float[] angle=MiningHelper.getValidMiningAngle(player,dig);
            MovementController.setLookDirection(player,angle[0],angle[1]);
            Vec3d eye=new Vec3d(player.getX(),player.getEyeY(),player.getZ()),look=player.getRotationVec(1F);
            var hit=world.rayTrace(new RayTraceContext(eye,new Vec3d(eye.x+look.x*4.5,eye.y+look.y*4.5,eye.z+look.z*4.5),
                    RayTraceContext.ShapeType.OUTLINE,RayTraceContext.FluidHandling.NONE,player));
            if(hit.getType()==HitResult.Type.BLOCK && hit.getBlockPos().equals(dig)) {
                ToolsHelper.equipBestTool(player,dig,false);
                client.interactionManager.updateBlockBreakingProgress(dig,hit.getSide());
                if(!player.handSwinging)player.swingHand(Hand.MAIN_HAND);
            }
        } else {
            MovementController.setLookDirection(player,player.yaw,90);
            key(client.options.keyForward,true);key(client.options.keySprint,true);key(client.options.keySneak,!player.isSwimming());
        }
    }
    private static void key(KeyBinding binding,boolean pressed){KeyBinding.setKeyPressed(binding.getDefaultKey(),pressed);}
    public static void release(MinecraftClient client) {
        key(client.options.keyForward,false);key(client.options.keySprint,false);
        key(client.options.keySneak,false);key(client.options.keyJump,false);key(client.options.keyAttack,false);
    }
}
