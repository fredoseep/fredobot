package com.fredoseep.algorithm.planning;

import com.fredoseep.algorithm.SimplePathfinder;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.tag.FluidTags;
import net.minecraft.world.World;
import java.util.*;

/** Capture only on the client thread, before releasing keys/replanning. Contains no live objects. */
public final class StuckDump {
    private StuckDump() {}
    private static List<Integer> pos(BlockPos p){return List.of(p.getX(),p.getY(),p.getZ());}
    public static List<Double> box(Box b){return List.of(b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ);}
    private static Map<String,Object> cell(World world,BlockPos p) {
        Map<String,Object> out=new LinkedHashMap<>();out.put("pos",pos(p));
        if(p.getY()<0 || p.getY()>=256){out.put("availability","OUTSIDE_BUILD_HEIGHT");return out;}
        if(!world.getChunkManager().isChunkLoaded(p.getX()>>4,p.getZ()>>4)){
            out.put("availability","UNLOADED");return out;
        }
        out.put("availability","LOADED");var state=world.getBlockState(p);out.put("block_state",state.toString());
        out.put("water",state.getFluidState().isIn(FluidTags.WATER));
        out.put("lava",state.getFluidState().isIn(FluidTags.LAVA));
        var flow=state.getFluidState().getVelocity(world,p);
        out.put("fluid_velocity",List.of(flow.x,flow.y,flow.z));
        out.put("replaceable",state.getMaterial().isReplaceable());
        List<Object> local=new ArrayList<>(),absolute=new ArrayList<>();
        for(Box b:state.getCollisionShape(world,p).getBoundingBoxes()) {
            local.add(box(b));absolute.add(box(b.offset(p.getX(),p.getY(),p.getZ())));
        }
        out.put("collision_boxes_local",local);out.put("collision_boxes_world",absolute);return out;
    }
    public static Map<String,Object> capture(World world,PlayerEntity player,List<SimplePathfinder.Node> path,
            int index,BlockPos goal,boolean actionPlanner,int priorStuckCount,int windowTicks,
            double dx,double dy,double dz,double minMovement,Map<String,Boolean> keys) {
        Map<String,Object> out=new LinkedHashMap<>();out.put("schema","fredobot-stuck-v1");
        out.put("captured_at",java.time.Instant.now().toString());out.put("path_source",actionPlanner?"ACTION_PLANNER":"LEGACY_OR_CUSTOM");
        out.put("path_index",index);out.put("path_size",path.size());out.put("consecutive_stuck_count",priorStuckCount+1);
        out.put("strict_reach_mode",priorStuckCount>=2);out.put("goal",goal==null?null:pos(goal));
        out.put("window_ticks",windowTicks);out.put("min_movement_blocks",minMovement);
        out.put("observed_displacement",List.of(dx,dy,dz));out.put("keys",Map.copyOf(keys));
        Map<String,Object> body=new LinkedHashMap<>();body.put("pos",List.of(player.getX(),player.getY(),player.getZ()));
        var velocity=player.getVelocity();body.put("velocity",List.of(velocity.x,velocity.y,velocity.z));
        var look=player.getRotationVec(1F);body.put("look_vector",List.of(look.x,look.y,look.z));
        body.put("bounding_box",box(player.getBoundingBox()));body.put("progress_reach_box",box(PathReachBoxes.player(player,priorStuckCount>=2)));
        body.put("grounded",player.isOnGround());body.put("touching_water",player.isTouchingWater());
        body.put("swimming",player.isSwimming());body.put("submerged_water",player.isSubmergedIn(FluidTags.WATER));
        body.put("horizontal_collision",player.horizontalCollision);body.put("vertical_collision",player.verticalCollision);
        body.put("vehicle_box",player.hasVehicle()?box(player.getVehicle().getBoundingBox()):null);out.put("player",body);
        // Even-sized cube: X/Z -2..+1, Y -1..+2, so the supporting floor is included.
        BlockPos origin=player.getBlockPos().add(-2,-1,-2);out.put("cube_origin",pos(origin));out.put("cube_size",List.of(4,4,4));
        List<Object> cells=new ArrayList<>();
        for(int x=0;x<4;x++)for(int y=0;y<4;y++)for(int z=0;z<4;z++)cells.add(cell(world,origin.add(x,y,z)));
        out.put("environment",cells);
        List<Object> nodes=new ArrayList<>();
        for(int i=0;i<path.size();i++) {
            var n=path.get(i);Map<String,Object> node=new LinkedHashMap<>();node.put("index",i);node.put("pos",pos(n.pos));
            node.put("state",n.state.name());node.put("parent",n.parent==null?null:pos(n.parent.pos));
            node.put("reach_box",box(PathReachBoxes.node(path,i,priorStuckCount>=2)));
            if(PathReachBoxes.plannedWater(n)) {
                node.put("requires_water_feet_position",true);
                node.put("water_position_reached",PathReachBoxes.waterPositionReached(player.getX(),player.getY(),player.getZ(),n));
                node.put("water_horizontal_limit_squared",.12);node.put("water_vertical_lower_limit",-.25);
                node.put("water_vertical_upper_limit",PathReachBoxes.waterUpperLimit(n));
            }
            node.put("is_final",i==path.size()-1);
            if(n.plannedAction!=null && n.state==SimplePathfinder.MovementState.WALKING && i+1<path.size()) {
                var next=path.get(i+1);
                if(next.state==SimplePathfinder.MovementState.JUMPING_UP && next.plannedAction!=null && !next.plannedAction.isShoreExit()) {
                    node.put("approach_horizontal_squared_limit",.06);node.put("approach_vertical_limit",.3);
                    node.put("approach_max_horizontal_speed",.1);
                    node.put("approach_requires_grounded",true);
                }
            }
            if(i==path.size()-1) {
                node.put("final_horizontal_squared_limit",PathReachBoxes.finalHorizontalLimit(n,priorStuckCount>=2));
                node.put("final_vertical_limit",PathReachBoxes.plannedWater(n)?PathReachBoxes.waterUpperLimit(n):.5);
            }
            node.put("action_ticks",n.actionTicks);node.put("material_penalty",n.materialPenalty);
            if(n.plannedAction!=null) {
                var a=n.plannedAction;Map<String,Object> action=new LinkedHashMap<>();action.put("kind",a.kind);
                action.put("breaks",a.breaks.stream().map(StuckDump::pos).toList());
                action.put("tools",a.tools.stream().map(Object::toString).toList());
                action.put("material",a.material==null?null:a.material.toString());node.put("action",action);
            } else node.put("action",null);
            nodes.add(node);
        }
        out.put("path",nodes);
        if(index>=0 && index<path.size()) {
            var current=path.get(index);List<Object> targetCells=new ArrayList<>();
            targetCells.add(cell(world,current.pos.down()));targetCells.add(cell(world,current.pos));targetCells.add(cell(world,current.pos.up()));
            if(current.plannedAction!=null) for(BlockPos p:current.plannedAction.breaks)targetCells.add(cell(world,p));
            out.put("current_node_environment",targetCells);
        }
        return out;
    }
}
