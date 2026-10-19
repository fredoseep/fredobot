package com.fredoseep.motion.sim;

import com.fredoseep.motion.GroundSteering;
import com.fredoseep.motion.LocalGroundSearch;
import com.fredoseep.motion.MotionInput;
import com.fredoseep.motion.MotionPlan;
import com.fredoseep.motion.MotionState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.tag.FluidTags;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RayTraceContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Incremental real player simulation plus the same water ray/hull checks used by a boat placement. */
public final class BoatLaunchSimulation {
    public record Launch(MotionPlan approach,float yaw,float pitch,Vec3d position){}
    private final MinecraftClient client;
    private final GroundAgent player;
    private final MotionState initial;
    private final float yaw;
    private final List<MotionPlan.Frame> frames=new ArrayList<>();
    private int pitchIndex,advanceTicks;
    private Launch result;
    private boolean finished;
    public BoatLaunchSimulation(MinecraftClient c,LocalGroundSearch.Goal goal){
        client=c;player=FusionWorld.in(c.world,Map.of(),()->new GroundAgent(c,c.player));initial=player.state();
        yaw=GroundSteering.direction(goal.x()-player.getX(),goal.z()-player.getZ());
    }
    public void step(){
        if(finished)return;
        // High to low view, spread over render frames; never brute-force click the live world.
        float pitch=-15+pitchIndex*5;
        Vec3d pos=placement(client,player,yaw,pitch);
        if(pos!=null){result=new Launch(frames.isEmpty()?null:new MotionPlan("fusion-boat-launch-approach",initial,frames,.18,.12),yaw,pitch,pos);finished=true;return;}
        if(++pitchIndex<=20)return;
        pitchIndex=0;
        if(advanceTicks++>=16){finished=true;return;}
        MotionInput input=new MotionInput(true,false,false,false,player.isTouchingWater(),false,false,yaw,10);
        MotionState after=FusionWorld.in(client.world,Map.of(),()->player.step(input));frames.add(new MotionPlan.Frame(input,after));
    }
    public boolean finished(){return finished;}
    public Launch result(){return result;}
    public static Vec3d placement(MinecraftClient c,ClientPlayerEntity player,float yaw,float pitch){
        double y=Math.toRadians(yaw),p=Math.toRadians(pitch);
        Vec3d eye=new Vec3d(player.getX(),player.getEyeY(),player.getZ());
        Vec3d end=eye.add(-Math.sin(y)*Math.cos(p)*5,-Math.sin(p)*5,Math.cos(y)*Math.cos(p)*5);
        BlockHitResult hit=c.world.rayTrace(new RayTraceContext(eye,end,RayTraceContext.ShapeType.OUTLINE,RayTraceContext.FluidHandling.SOURCE_ONLY,player));
        if(hit.getType()!=HitResult.Type.BLOCK || !c.world.getBlockState(hit.getBlockPos()).getFluidState().isIn(FluidTags.WATER))return null;
        Vec3d pos=hit.getPos();BoatEntity ghost=new BoatEntity(c.world,pos.x,pos.y,pos.z);ghost.yaw=yaw;
        Box hull=ghost.getBoundingBox().expand(-.1);
        if(hull.intersects(player.getBoundingBox()) || c.world.getBlockCollisions(ghost,hull).anyMatch(s->!s.isEmpty()))return null;
        if(!c.world.getEntities(c.player,hull,e->e.isAlive()).isEmpty())return null;
        // BoatItem also rejects an entity intersecting the eye ray near the player.
        if(!c.world.getEntities(c.player,player.getBoundingBox().stretch(end.subtract(eye)).expand(1),e->e.isAlive()
                && e.getBoundingBox().expand(e.getTargetingMargin()).contains(eye)).isEmpty())return null;
        return pos;
    }
}
