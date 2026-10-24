package com.fredoseep.utils.prenether;

import com.fredoseep.algorithm.planning.ShaftCenterControl;
import com.fredoseep.algorithm.planning.ShaftWaterCenterControl;
import com.fredoseep.algorithm.planning.MagmaEntryControl;
import com.fredoseep.algorithm.planning.ShaftColumnLock;
import com.fredoseep.logging.GlobalStateLogger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import com.fredoseep.utils.player.ShaftRoof;
import com.fredoseep.excutor.PathExecutor;
import com.fredoseep.utils.bt.BtStuff;
import com.fredoseep.utils.player.ToolsHelper;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.options.KeyBinding;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.tag.FluidTags;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Direction;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
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
    private int stagnantTicks, sealingTicks,entryStableTicks,entryAlignTicks;
    private final ShaftColumnLock columnLock=new ShaftColumnLock();
    private String lastAction="";
    private BlockPos lastActionBlock;
    private int lastStatusAge;
    public MagmaDescent(MinecraftClient client,BlockPos magma) {
        this(client,magma,null);
    }
    public MagmaDescent(MinecraftClient client,BlockPos magma,BlockPos departedDoor) {
        world=client.world;goal=magma.up().toImmutable();
        seabed=BtStuff.oceanFloorNoKelp(magma).up().toImmutable();
        double doorDistance=departedDoor==null?Double.POSITIVE_INFINITY:Math.sqrt(
                Math.pow((double)departedDoor.getX()-seabed.getX(),2)
                +Math.pow((double)departedDoor.getY()-seabed.getY(),2)
                +Math.pow((double)departedDoor.getZ()-seabed.getZ(),2));
        boolean directSeabed=MagmaEntryControl.nearDoor(departedDoor,seabed,client.player.getPos());
        BlockPos surface=seabed;
        while(!directSeabed && surface.getY()<100 && world.getBlockState(surface).getFluidState().isIn(FluidTags.WATER)
                && world.getBlockState(surface.up()).getFluidState().isIn(FluidTags.WATER)) surface=surface.up();
        approach=directSeabed?new BlockPos(goal.getX(),MagmaEntryControl.entryY(seabed.getY(),client.player.getY()),goal.getZ())
                :client.player.getY()<seabed.getY()?seabed:surface;
        Map<String,Object> choice=new LinkedHashMap<>();
        choice.put("mode",directSeabed?"NEAR_DOOR_CURRENT_DEPTH":"DEFAULT_APPROACH");
        choice.put("door",departedDoor==null?null:departedDoor.toShortString());
        choice.put("door_to_seabed_distance",departedDoor==null?null:doorDistance);
        choice.put("door_horizontal_distance",departedDoor==null?null:Math.hypot(departedDoor.getX()-seabed.getX(),departedDoor.getZ()-seabed.getZ()));
        choice.put("seabed",seabed.toShortString());choice.put("approach",approach.toShortString());
        GlobalStateLogger.logCustomEvent("magma_approach_selected",choice);
        System.out.println("FredoBot: MAGMA_DESCENT start seabed="+seabed.toShortString()+" goal="+goal.toShortString());
    }
    public String hudStatus(){return switch(phase) {
        case TO_SEABED -> "岩浆点：前往正上方水面/海床";
        case DESCENDING -> entryAlignTicks>0?"岩浆点：精确居中并停稳":"岩浆点：居中并垂直下挖";
        case DONE -> "岩浆点：已抵达";
        case FAILED -> "岩浆点：失败";
    };}
    public boolean descending(){return phase==Phase.DESCENDING;}
    public boolean done(){return phase==Phase.DONE;}
    public boolean failed(){return phase==Phase.FAILED;}
    private void fail(MinecraftClient client,String reason) {
        phase=Phase.FAILED;release(client);
        if(client.player!=null)client.player.setSprinting(false);
        status(client.player,"FAILED: "+reason,null);
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
            status(player,"DESCENT_STARTED",null);
            System.out.println("FredoBot: MAGMA_DESCENT handoff pos="+player.getPos()+" seabed="+seabed.toShortString()
                    +" horizontal_error="+Math.hypot(dx,dz)+" height_error="+(player.getY()-seabed.getY())
                    +"; descending column="+goal.getX()+","+goal.getZ());
        }
        release(client);
        if(player.hasVehicle())return; // PreNether recovers the boat before any descent/mining.
        double dy=goal.getY()-player.getY();
        if(dx*dx+dz*dz<=1.44 && Math.abs(dy)<=.35){
            player.setSprinting(false);
            var velocity=player.getVelocity();
            boolean supported=!world.getBlockState(goal.down()).getCollisionShape(world,goal.down()).isEmpty();
            boolean settled=MagmaEntryControl.ready(dx,dy,dz,velocity.x,velocity.y,velocity.z,supported);
            entryStableTicks=settled?entryStableTicks+1:0;
            if(entryStableTicks>=MagmaEntryControl.SETTLE_TICKS){
                phase=Phase.DONE;status(player,"DONE",null);
                System.out.println("FredoBot: MAGMA_DESCENT_DONE goal="+goal.toShortString());return;
            }
            if(++entryAlignTicks>200){fail(client,"PORTAL_ENTRY_NOT_SETTLED");return;}
            if(!settled){
                boolean wet=player.isTouchingWater() || player.isSwimming();
                var input=wet?ShaftWaterCenterControl.align(dx,dz,velocity.x,velocity.z,player.yaw,true)
                        :MagmaEntryControl.align(dx,dz,velocity.x,velocity.z,player.yaw);
                if(wet){player.yaw=input.yaw();player.pitch=input.pitch();}
                key(client.options.keyForward,input.forward());key(client.options.keyBack,input.back());
                key(client.options.keyLeft,input.left());key(client.options.keyRight,input.right());
            }
            key(client.options.keySneak,true);
            status(player,"PORTAL_ENTRY_ALIGNMENT",null);return;
        }
        entryStableTicks=0;entryAlignTicks=0;
        if(player.getY()<goal.getY()-.5){fail(client,"DESCENT_OVERSHOT_GOAL");return;}
        // Horizontal circles are not descent progress.
        if(player.getY()<progress.y-.1){progress=player.getPos();stagnantTicks=0;}
        else if(++stagnantTicks>200){fail(client,"NO_VERTICAL_PROGRESS_10_SECONDS");return;}
        if(stagnantTicks==80)dumpStall(player);
        var body=player.getBoundingBox();var velocity=player.getVelocity();
        boolean waterDrive=player.isTouchingWater() || player.isSwimming();
        boolean locked=columnLock.update(dx,dz,Math.max(body.maxX-body.minX,body.maxZ-body.minZ)*.5);
        if(!locked){
            if(waterDrive){
                var input=ShaftWaterCenterControl.align(dx,dz,velocity.x,velocity.z,player.yaw,false);
                player.yaw=input.yaw();player.pitch=input.pitch();
                swimDown(client,player);status(player,"SWIM_TO_COLUMN",null);
            }else{
                var input=ShaftCenterControl.align(dx,dz,velocity.x,velocity.z,
                        Math.max(body.maxX-body.minX,body.maxZ-body.minZ)*.5,player.yaw).input();
                player.pitch=0;
                key(client.options.keyForward,input.forward());key(client.options.keyBack,input.back());
                key(client.options.keyLeft,input.left());key(client.options.keyRight,input.right());
                status(player,"DRY_COLUMN_ALIGNMENT",null);
            }
            return;
        }
        // Continue straight down even while touching the floor: do not brake out of swimming.
        player.pitch=90;
        if(waterDrive)swimDown(client,player);
        // Clear plants in the descent column even when their physical collision is empty.
        if(clearWaterPlant(client,player))return;
        BlockPos dig=new BlockPos(goal.getX(),player.getY()-.1,goal.getZ());
        if(dig.getY()<goal.getY())return; // Never break the destination magma block.
        var block=world.getBlockState(dig);
        if(block.getFluidState().isIn(FluidTags.LAVA) || block.getBlock()==Blocks.MAGMA_BLOCK || block.getBlock()==Blocks.BEDROCK) {
            fail(client,"UNSAFE_OR_UNBREAKABLE_COLUMN_BLOCK "+dig.toShortString());return;
        }
        if(!block.getCollisionShape(world,dig).isEmpty()) {
            // A fluid-boundary crossing can restore STANDING while landing on the next floor.
            // Keep requesting downward sprint, but never require a compact pose to mine:
            // ShaftRoof uses the actual body height and retains standing clearance when needed.
            if(block.getHardness(world,dig)<0){fail(client,"UNBREAKABLE_COLUMN_BLOCK");return;}
            ShaftRoof.Result roof=ShaftRoof.seal(client,player);
            if(roof!=ShaftRoof.Result.NOT_NEEDED) {
                if(roof==ShaftRoof.Result.NO_MATERIAL){fail(client,"ROOF_NO_BUILDING_BLOCK");return;}
                // Placement may look upward. Pause forward thrust for this interaction only,
                // retaining the sprint request and the actual compact body pose.
                key(client.options.keyForward,false);
                status(player,"SEALING_"+roof,dig);
                if(++sealingTicks>100){fail(client,"ROOF_NOT_CONFIRMED");return;}
                return; // Observe the placed roof in the world before breaking the next floor.
            }
            sealingTicks=0;
            status(player,waterDrive && body.maxY-body.minY>1.0?"MINING_STANDING_WATER":"MINING",dig);
            player.pitch=90;
            mineColumnBlock(client,player,dig);
        } else {
            status(player,"WATER_DESCENT",dig);
            player.pitch=90;
            if(waterDrive)swimDown(client,player);
        }
    }
    private boolean clearWaterPlant(MinecraftClient client,PlayerEntity player){
        // Check only the occupied column near the body, never raycast through plants.
        int bottom=Math.max(goal.getY(),(int)Math.floor(player.getY()-.1));
        int top=(int)Math.floor(player.getBoundingBox().maxY);
        for(int y=top;y>=bottom;y--){
            BlockPos pos=new BlockPos(goal.getX(),y,goal.getZ());
            BlockState state=world.getBlockState(pos);
            if(state.getBlock()!=Blocks.SEAGRASS && state.getBlock()!=Blocks.TALL_SEAGRASS
                    && state.getBlock()!=Blocks.KELP && state.getBlock()!=Blocks.KELP_PLANT)continue;
            if(mineColumnBlock(client,player,pos)){
                status(player,"CLEARING_WATER_PLANT",pos);return true;
            }
        }
        return false;
    }
    private boolean mineColumnBlock(MinecraftClient client,PlayerEntity player,BlockPos pos){
        if(pos.getX()!=goal.getX() || pos.getZ()!=goal.getZ() || pos.getY()<goal.getY())return false;
        Vec3d eye=new Vec3d(player.getX(),player.getEyeY(),player.getZ());
        if(eye.squaredDistanceTo(Vec3d.ofCenter(pos))>20.25)return false;
        ToolsHelper.equipBestTool(player,pos,false);
        client.interactionManager.updateBlockBreakingProgress(pos,Direction.UP);
        if(!player.handSwinging)player.swingHand(Hand.MAIN_HAND);
        return true;
    }
    private static void swimDown(MinecraftClient client,PlayerEntity player){
        key(client.options.keyForward,true);key(client.options.keySprint,true);
        // Sneak only to immerse the head; holding it once submerged interferes with sprint entry.
        boolean standing=player.getBoundingBox().maxY-player.getBoundingBox().minY>1.0;
        key(client.options.keySneak,standing && !player.isSwimming() && !player.isSubmergedIn(FluidTags.WATER));
    }
    private void status(PlayerEntity player,String action,BlockPos block){
        if(player==null)return;
        if(action.equals(lastAction) && java.util.Objects.equals(block,lastActionBlock) && player.age-lastStatusAge<40)return;
        lastAction=action;lastActionBlock=block;lastStatusAge=player.age;
        Map<String,Object> data=new LinkedHashMap<>();
        data.put("action",action);data.put("phase",phase.name());data.put("pos",player.getPos().toString());
        data.put("pose",player.getPose().name());data.put("velocity",player.getVelocity().toString());
        data.put("body_height",player.getBoundingBox().maxY-player.getBoundingBox().minY);
        data.put("air",player.getAir());data.put("yaw",player.yaw);data.put("pitch",player.pitch);
        data.put("column",goal.toShortString());data.put("dig",block==null?null:block.toShortString());
        data.put("no_vertical_progress_ticks",stagnantTicks);data.put("sealing_ticks",sealingTicks);
        GlobalStateLogger.logCustomEvent("magma_descent_state",data);
    }
    private void dumpStall(PlayerEntity player){
        BlockPos feet=player.getBlockPos();var cells=new ArrayList<Object>();
        for(int x=-1;x<=1;x++)for(int y=-1;y<=2;y++)for(int z=-1;z<=1;z++){
            BlockPos p=feet.add(x,y,z);var state=world.getBlockState(p);
            cells.add(Map.of("pos",p.toShortString(),"block",state.toString(),
                    "collision",state.getCollisionShape(world,p).getBoundingBoxes().toString()));
        }
        GlobalStateLogger.logCustomEvent("magma_descent_stalled",Map.of("pos",player.getPos().toString(),
                "action",lastAction,"pose",player.getPose().name(),"environment",cells));
    }
    private static void key(KeyBinding binding,boolean pressed){KeyBinding.setKeyPressed(binding.getDefaultKey(),pressed);}
    public static void release(MinecraftClient client) {
        key(client.options.keyForward,false);key(client.options.keyBack,false);
        key(client.options.keyLeft,false);key(client.options.keyRight,false);key(client.options.keySprint,false);
        key(client.options.keySneak,false);key(client.options.keyJump,false);key(client.options.keyAttack,false);
    }
}
