package com.fredoseep.motion.sim;

import com.fredoseep.algorithm.planning.MiningTime;
import com.fredoseep.algorithm.planning.PlannerSettings;
import com.fredoseep.motion.FusionSearch;
import com.fredoseep.motion.FusionDivePolicy;
import com.fredoseep.motion.FusionBreathingSearch;
import com.fredoseep.motion.FusionEscapeGeometry;
import com.fredoseep.motion.FusionGuidance;
import com.fredoseep.motion.LocalGroundSearch;
import com.fredoseep.motion.MotionPlan;
import com.fredoseep.motion.GroundSteering;
import net.minecraft.entity.EntityPose;
import com.fredoseep.motion.MotionInput;
import com.fredoseep.motion.MotionState;
import com.fredoseep.mixin.MotionClientAccess;
import com.fredoseep.mixin.MotionLivingAccess;
import com.fredoseep.utils.player.InventoryHelper;
import com.fredoseep.utils.player.MiningAim;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FallingBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RayTraceContext;
import net.minecraft.world.World;
import net.minecraft.tag.BlockTags;
import net.minecraft.tag.FluidTags;

/** Real vanilla movement and verified local edits over an isolated terrain map. */
public final class FusionAgent implements FusionSearch.Agent {
    public record Edit(String kind,BlockPos pos,BlockState before,BlockState after,Item item,int toolSlot,
                       int ticks,double price,BlockHitResult hit,MotionState initial,MotionPlan hold) implements FusionSearch.Interaction {
        public boolean airbornePlacement(){return kind.equals("PLACE") && hold!=null && hold.id().equals("fusion-pillar-place");}
        public Edit(String kind,BlockPos pos,BlockState before,BlockState after,Item item,int toolSlot,
                    int ticks,double price,BlockHitResult hit,MotionState initial){this(kind,pos,before,after,item,toolSlot,ticks,price,hit,initial,null);}
    }
    private record Corridor(BlockPos stand,List<BlockPos> blocks) implements FusionSearch.Interaction {
        public int ticks(){return 0;}public double price(){return 0;}public String kind(){return "CLEARANCE_REQUEST";}
    }
    private record Escape(Direction direction) implements FusionSearch.Interaction {
        public int ticks(){return 0;}public double price(){return 0;}public String kind(){return "ESCAPE_REQUEST";}
    }
    private record GoalConnection(BlockPos stand,Item material) implements FusionSearch.Interaction {
        public int ticks(){return 0;}public double price(){return 0;}public String kind(){return "GOAL_CONNECTION_REQUEST";}
    }
    private final MinecraftClient client;
    private final World world;
    private final Set<BlockPos> blacklist;
    private final GroundAgent agent;
    private final Map<BlockPos,BlockState> edits;
    private final Map<Item,Integer> spent;
    private final Map<Integer,Integer> wear;
    private final int buildingQuota,pillaringQuota;
    private final double localEscapeHeight,escapeOriginX,escapeOriginZ;
    private boolean waterSampled;private FusionGuidance.Water sampledWater;
    private final Map<String,Integer> editSkips=new HashMap<>();
    private String breakRejection="UNKNOWN";
    public FusionAgent(MinecraftClient c,Set<BlockPos> blacklist){
        client=c;world=c.world;this.blacklist=Set.copyOf(blacklist);edits=Map.of();spent=Map.of();wear=Map.of();
        var counts=InventoryHelper.countAvailableBuildingBlocks(c.player);
        buildingQuota=counts.bridgingBlocks;pillaringQuota=counts.pillaringBlocks;
        agent=FusionWorld.in(world,edits,()->new GroundAgent(c,c.player));
        localEscapeHeight=surfaceAroundStart();escapeOriginX=agent.getX();escapeOriginZ=agent.getZ();
    }
    private FusionAgent(FusionAgent from,Map<BlockPos,BlockState> edits,Map<Item,Integer> spent,Map<Integer,Integer> wear){
        this(from,edits,spent,wear,false);
    }
    private FusionAgent(FusionAgent from,Map<BlockPos,BlockState> edits,Map<Item,Integer> spent,Map<Integer,Integer> wear,boolean freshRoot){
        client=from.client;world=from.world;blacklist=from.blacklist;this.edits=Map.copyOf(edits);
        this.spent=Map.copyOf(spent);this.wear=Map.copyOf(wear);buildingQuota=from.buildingQuota;pillaringQuota=from.pillaringQuota;
        agent=FusionWorld.in(world,this.edits,from.agent::copy);
        localEscapeHeight=freshRoot?FusionWorld.in(world,this.edits,this::surfaceAroundStart):from.localEscapeHeight;
        escapeOriginX=freshRoot?agent.getX():from.escapeOriginX;escapeOriginZ=freshRoot?agent.getZ():from.escapeOriginZ;
    }
    public FusionAgent copy(){return new FusionAgent(this,edits,spent,wear);}
    public FusionAgent freshSearchRoot(){return new FusionAgent(this,edits,spent,wear,true);}
    public MotionState state(){return agent.state();}
    public boolean water(){return agent.isTouchingWater() || agent.isSwimming();}
    public boolean climbing(){return agent.isClimbing();}
    public boolean horizontalCollision(){return agent.horizontalCollision();}
    public boolean submerged(){return agent.isSubmergedInWater();}
    public int availableAir(){return agent.getAir();}
    public boolean checkpointSafe(){return FusionSafety.checkpoint(agent);}
    public boolean pitExitSafe(double originY,double rimY){return FusionWorld.in(world,edits,()->FusionSafety.pitExit(agent,originY,rimY));}
    public double escapeHeight(){return localEscapeHeight;}
    public Map<String,Integer> interactionDiagnostics(){return Map.copyOf(editSkips);}
    public FusionGuidance.Water waterProfile(){
        if(!water())return null;
        if(!waterSampled){
            sampledWater=FusionWorld.in(world,edits,()->{
                FusionGuidance.Water profile=waterColumn(agent.getX(),agent.getY(),agent.getZ());
                return profile!=null || !agent.isSubmergedInWater()?profile:waterColumn(agent.getX(),agent.getEyeY(),agent.getZ());
            });waterSampled=true;
        }
        return sampledWater;
    }
    public boolean underwaterGoal(LocalGroundSearch.Goal goal){
        FusionGuidance.Water profile=waterColumn(goal.x(),goal.y(),goal.z());
        return profile!=null && goal.y()<profile.surface()-1;
    }
    private FusionGuidance.Water waterColumn(double x,double y,double z){
        BlockPos cell=new BlockPos(x,y,z);
        if(!world.getBlockState(cell).getFluidState().isIn(FluidTags.WATER))cell=cell.down();
        if(!world.getBlockState(cell).getFluidState().isIn(FluidTags.WATER))return null;
        // Ravine-floor targets can be over fifty blocks below the surface. A 16-block cap
        // incorrectly classified them as dry goals and lost guidance halfway through the dive.
        BlockPos top=cell;
        while(top.getY()<254 && world.getBlockState(top.up()).getFluidState().isIn(FluidTags.WATER))top=top.up();
        if(world.getBlockState(top.up()).getFluidState().isIn(FluidTags.WATER))return null;
        double surface=top.getY()+world.getBlockState(top).getFluidState().getHeight(world,top);
        BlockPos bottom=cell;for(int i=0;i<4 && bottom.getY()>0 && world.getBlockState(bottom.down()).getFluidState().isIn(FluidTags.WATER);i++)bottom=bottom.down();
        return new FusionGuidance.Water(surface,bottom.getY(),agent.getEyeY(),agent.isSwimming(),agent.isSubmergedInWater());
    }
    private FusionEscapeGeometry.Kind sample(int x,int y,int z){
        if(y<0 || y>254 || !world.getChunkManager().isChunkLoaded(x>>4,z>>4))return FusionEscapeGeometry.Kind.BLOCKED;
        BlockPos pos=new BlockPos(x,y,z);BlockState b=world.getBlockState(pos);
        if(!b.getFluidState().isEmpty() || b.isIn(BlockTags.LEAVES) || b.isIn(BlockTags.LOGS))return FusionEscapeGeometry.Kind.BLOCKED;
        if(b.getCollisionShape(world,pos).isEmpty())return FusionEscapeGeometry.Kind.EMPTY;
        if(!Block.isShapeFullCube(b.getCollisionShape(world,pos)))return FusionEscapeGeometry.Kind.BLOCKED;
        return b.getBlock() instanceof FallingBlock?FusionEscapeGeometry.Kind.GRAVITY:FusionEscapeGeometry.Kind.SOLID;
    }
    /** Client heightmaps may be unavailable; inspect loaded physical columns instead. */
    private double surfaceAroundStart(){
        BlockPos p=agent.getBlockPos();
        return FusionEscapeGeometry.surroundingSurface(this::sample,p.getX(),p.getZ(),agent.getY());
    }
    public Object contextKey(){return List.of(edits,spent,wear,agent.getPose(),agent.isSwimming(),agent.isSubmergedInWater(),
            Math.max(0,agent.getAir()/20),List.of(agent.isSprinting(),agent.isClimbing(),((MotionClientAccess)(Object)agent).fredobot$getSprintWindow(),
                    ((MotionLivingAccess)(Object)agent).fredobot$getJumpCooldown()),agent.input.jumping,agent.input.sneaking);}
    public MotionState step(MotionInput input){return stepWithAirGuard(input,FusionDivePolicy.AIR_RESERVE);}
    public MotionState stepBreathing(MotionInput input,boolean emergency){
        if(!emergency)return step(input);
        if(!FusionBreathingSearch.verticalAscent(input) && agent.isSubmergedInWater())
            throw new IllegalArgumentException("EMERGENCY_ASCENT_INPUT");
        return stepWithAirGuard(input,Integer.MIN_VALUE);
    }
    private MotionState stepWithAirGuard(MotionInput input,int reserve){return FusionWorld.in(world,edits,()->{
        MotionState s=agent.step(input);waterSampled=false;sampledWater=null;
        if(blacklist.contains(agent.getBlockPos()))throw new IllegalArgumentException("BLACKLISTED_CELL");
        if(agent.getAir()<reserve)throw new IllegalArgumentException("AIR_RESERVE");
        return s;
    });}
    public List<Edit> interactions(){return singleEdits(2);}
    private List<Edit> singleEdits(int reachColumns){return FusionWorld.in(world,edits,()->{
        List<Edit> result=new ArrayList<>();MotionState s=state();editSkips.clear();
        if(!miningStance() || edits.size()>=8 || !PlannerSettings.DEFAULT.allowWorldEdits){
            editSkips.put(water()?"WATER_NEEDS_UPRIGHT_STOP":!s.grounded()?"AIRBORNE":Math.hypot(s.vx(),s.vz())>.015?"MOVING":"EDIT_LIMIT_OR_DISABLED",1);return result;
        }
        BlockPos feet=agent.getBlockPos();
        // Own-column headroom was absent from the four horizontal-neighbour loops.
        for(int dy:new int[]{2,3}){Edit head=breaking(feet.up(dy));if(head!=null)result.add(head);}
        for(Direction d:new Direction[]{Direction.NORTH,Direction.SOUTH,Direction.WEST,Direction.EAST}){
            // Clear a reachable upper lip before digging the lower wall; fractional chest support is intentional.
            for(int distance=1;distance<=reachColumns;distance++)for(int dy:new int[]{3,2,1,0,-1}){
                BlockPos p=feet.add(d.getOffsetX()*distance,dy,d.getOffsetZ()*distance);
                Edit edit=breaking(p);if(edit!=null)result.add(edit);
            }
            if(water())continue;
            for(int dy=-1;dy<=0;dy++)result.addAll(placing(feet.offset(d).add(0,dy,0)));

        }
        return result;
    });}
    private List<Edit> placing(BlockPos pos){
        List<Edit> result=new ArrayList<>();BlockState before=world.getBlockState(pos);
        if(edits.size()>=8 || blacklist.contains(pos) || !before.isAir() || !staticCell(pos,before)
                || agent.getBoundingBox().intersects(new Box(pos)))return result;
        BlockHitResult hit=placementHit(pos);if(hit==null)return result;
        int total=spent.values().stream().mapToInt(Integer::intValue).sum();
        int nonGravity=spent.entrySet().stream().filter(e->e.getKey() instanceof BlockItem b && !(b.getBlock() instanceof FallingBlock))
                .mapToInt(Map.Entry::getValue).sum();
        for(var material:FusionMaterials.choices(agent,world,pos)){
            if(total>=pillaringQuota || (!material.gravity() && nonGravity>=buildingQuota)
                    || material.count()<=spent.getOrDefault(material.item(),0))continue;
            result.add(new Edit("PLACE",pos,before,material.state(),material.item(),-1,4,
                    material.price()*PlannerSettings.DEFAULT.materialPriceTicks,hit,state()));
        }
        return List.copyOf(result);
    }
    private Edit breaking(BlockPos p){
        breakRejection="BLACKLIST_OR_NON_FULL";
        BlockState b=world.getBlockState(p);
        if(blacklist.contains(p))return null;
        if(!Block.isShapeFullCube(b.getCollisionShape(world,p)))return null;
        String reason=FusionSafety.miningRejection(world,client.player,p,b);
        if(reason!=null){breakRejection=reason;editSkips.merge(reason,1,Integer::sum);return null;}
        BlockHitResult hit=MiningAim.visibleFace(world,agent,p);if(hit==null){breakRejection="OCCLUDED_OR_REACH";editSkips.merge(breakRejection,1,Integer::sum);return null;}
        int bestTicks=Integer.MAX_VALUE,bestSlot=-1;Item item=null;
        for(int i=-1;i<agent.inventory.main.size();i++){
            ItemStack stack=i<0?ItemStack.EMPTY:agent.inventory.main.get(i);
            if(stack.isEmpty()){
                boolean hand=false;for(int h=0;h<9;h++)if(agent.inventory.main.get(h).isEmpty())hand=true;
                if(!hand)continue;
            }
            if(stack.isDamageable() && stack.getMaxDamage()-stack.getDamage()-wear.getOrDefault(i,0)<=1)continue;
            float speed=stack.getMiningSpeedMultiplier(b);int efficiency=EnchantmentHelper.getLevel(Enchantments.EFFICIENCY,stack);
            if(speed>1 && efficiency>0 && !stack.isEmpty())speed+=efficiency*efficiency+1;
            if(!agent.isOnGround())speed/=5;
            int ticks=MiningTime.ticks(b.getHardness(world,p),speed,!b.isToolRequired() || stack.isEffectiveOn(b));
            if(ticks<bestTicks){bestTicks=ticks;bestSlot=i;item=stack.isEmpty()?null:stack.getItem();}
        }
        if(bestTicks>200){breakRejection="NO_TOOL_OR_TOO_SLOW";editSkips.merge(breakRejection,1,Integer::sum);return null;}
        return new Edit("BREAK",p,b,Blocks.AIR.getDefaultState(),item,bestSlot,bestTicks+8,0,hit,state());
    }
    private boolean miningStance(){
        MotionState s=state();if(Math.hypot(s.vx(),s.vz())>.015)return false;
        if(!water())return s.grounded();
        return agent.getPose()==EntityPose.STANDING && !agent.isSwimming() && !agent.isSubmergedInWater()
                && Math.abs(s.vy())<=.1 && agent.getAir()>=200;
    }
    @Override public List<? extends FusionSearch.Interaction> interactions(LocalGroundSearch.Goal goal){
        List<Edit> singles=interactions();
        if(!miningStance() || !PlannerSettings.DEFAULT.allowWorldEdits || edits.size()>=8)return singles;
        return FusionWorld.in(world,edits,()->{
            List<FusionSearch.Interaction> result=new ArrayList<>();BlockPos feet=agent.getBlockPos();
            if(!water() && Double.isFinite(localEscapeHeight) && state().y()<localEscapeHeight-.2
                    && goal.y()>state().y()+.5 && Math.hypot(state().x()-escapeOriginX,state().z()-escapeOriginZ)<8){
                List<Direction> exits=new ArrayList<>(List.of(Direction.NORTH,Direction.SOUTH,Direction.WEST,Direction.EAST));
                exits.sort((a,b)->Double.compare(-((goal.x()-state().x())*a.getOffsetX()+(goal.z()-state().z())*a.getOffsetZ()),
                        -((goal.x()-state().x())*b.getOffsetX()+(goal.z()-state().z())*b.getOffsetZ())));
                for(Direction d:exits)result.add(new Escape(d));
            }
            BlockPos target=new BlockPos(goal.x(),goal.y(),goal.z());
            if(!water() && Math.hypot(goal.x()-state().x(),goal.z()-state().z())<=4.5
                    && Math.abs(goal.y()-state().y())<=1.25 && Math.abs(goal.y()-Math.rint(goal.y()))<1e-6){
                BlockPos floor=target.down();boolean supported=Block.isShapeFullCube(world.getBlockState(floor).getCollisionShape(world,floor));
                boolean blocked=!world.getBlockState(target).getCollisionShape(world,target).isEmpty()
                        || !world.getBlockState(target.up()).getCollisionShape(world,target.up()).isEmpty();
                if(!supported && world.getBlockState(floor).isAir()){
                    for(var material:FusionMaterials.choices(agent,world,floor))
                        if(material.count()>spent.getOrDefault(material.item(),0))result.add(new GoalConnection(target,material.item()));
                }else if(supported && (blocked || goal.y()<state().y()-.2))result.add(new GoalConnection(target,null));
            }
            int localRequests=0;
            double dx=goal.x()-state().x(),dz=goal.z()-state().z();
            List<Direction> forward=new ArrayList<>();
            if(Math.abs(dx)>.1)forward.add(dx>0?Direction.EAST:Direction.WEST);
            if(Math.abs(dz)>.1)forward.add(dz>0?Direction.SOUTH:Direction.NORTH);
            forward.sort((a,b)->Double.compare(-(dx*a.getOffsetX()+dz*a.getOffsetZ()),-(dx*b.getOffsetX()+dz*b.getOffsetZ())));
            for(int y:FusionEscapeGeometry.standLevels(state().y(),goal.y()))for(Direction d:forward)for(int distance:new int[]{3,2,1}){
                if(localRequests>=6)break;
                List<BlockPos> blocks=new ArrayList<>();boolean valid=true;
                for(int step=1;step<=distance && valid;step++){
                    BlockPos stand=new BlockPos(feet.getX()+d.getOffsetX()*step,y,feet.getZ()+d.getOffsetZ()*step);
                    if(!Block.isShapeFullCube(world.getBlockState(stand.down()).getCollisionShape(world,stand.down()))){valid=false;break;}
                    // Descending starts with the old body height: clear its head sweep too.
                    for(int height=FusionEscapeGeometry.passageTop(state().y(),y);height>=y;height--){
                        BlockPos cell=new BlockPos(stand.getX(),height,stand.getZ());BlockState b=world.getBlockState(cell);
                        if(b.getCollisionShape(world,cell).isEmpty())continue;
                        if(blacklist.contains(cell) || !Block.isShapeFullCube(b.getCollisionShape(world,cell))){valid=false;break;}
                        blocks.add(cell);
                    }
                }
                // Small local connection only, never excavate a whole geometric route first.
                if(valid && !blocks.isEmpty() && blocks.size()<=2 && edits.size()+blocks.size()<=8){
                    Corridor request=new Corridor(new BlockPos(feet.getX()+d.getOffsetX()*distance,y,feet.getZ()+d.getOffsetZ()*distance),List.copyOf(blocks));
                    if(!result.contains(request)){result.add(request);localRequests++;}
                }
            }
            result.addAll(singles);return List.copyOf(result);
        });
    }
    @Override public FusionSearch.Interaction prepare(FusionSearch.Interaction action){
        if(action instanceof GoalConnection connection)return prepareGoalConnection(connection);
        if(action instanceof Escape escape)return prepareEscape(escape);
        if(action instanceof Corridor corridor)return prepareCorridor(corridor);
        Edit edit=(Edit)action;
        if(!water() || edit.hold()!=null)return edit;
        if(!edit.kind().equals("BREAK") || !miningStance())throw new IllegalArgumentException("aquatic edit requires upright breathable stop");
        FusionAgent next=copy();List<MotionPlan.Frame> frames=new ArrayList<>();boolean ground=state().grounded();
        for(int i=0;i<edit.ticks();i++){
            float[] look=look(next.agent,edit.hit().getPos());MotionState s=next.state();
            MotionInput input=new MotionInput(false,false,false,false,next.water() && !s.grounded() && s.vy()<-.02,false,false,look[0],look[1]);
            MotionState after=next.step(input);
            if(after.grounded()!=ground || next.agent.getPose()!=EntityPose.STANDING || next.agent.isSubmergedInWater()
                    || Math.hypot(after.vx(),after.vz())>.035)throw new IllegalArgumentException("aquatic edit stance changes mining speed/pose");
            frames.add(new MotionPlan.Frame(input,after));
        }
        return new Edit(edit.kind(),edit.pos(),edit.before(),edit.after(),edit.item(),edit.toolSlot(),edit.ticks(),edit.price(),edit.hit(),edit.initial(),
                new MotionPlan("fusion-aquatic-edit-hold",state(),frames,.20,.15));
    }
    private FusionSearch.Program prepareGoalConnection(GoalConnection connection){
        List<BlockPos> pending=new ArrayList<>();
        for(int y=FusionEscapeGeometry.passageTop(state().y(),connection.stand().getY());y>=connection.stand().getY();y--)
            pending.add(new BlockPos(connection.stand().getX(),y,connection.stand().getZ()));
        List<FusionSearch.Leg> clearance=prepareClearance(pending);
        FusionAgent next=clearance.isEmpty()?copy():apply(new FusionSearch.Program(clearance));
        List<FusionSearch.Leg> steps=new ArrayList<>(clearance);
        if(connection.material()!=null){
            BlockPos support=connection.stand().down();
            if(next.agent.getBoundingBox().intersects(new Box(support))){
                FusionSearch.Program pillar=next.preparePillar(connection.stand(),connection.material());
                steps.addAll(pillar.steps());return new FusionSearch.Program(steps);
            }
            FusionAgent current=next;
            Edit place=FusionWorld.in(world,next.edits,()->current.placing(connection.stand().down()).stream()
                    .filter(e->e.item()==connection.material()).findFirst().orElseThrow(()->new IllegalArgumentException("goal support cannot be placed from this stance")));
            next=next.apply(place);steps.add(new FusionSearch.Leg(null,place));
        }
        FusionSearch.Program arrival=next.prepareCorridor(new Corridor(connection.stand(),List.of()));
        steps.addAll(arrival.steps());return new FusionSearch.Program(steps);
    }
    /** Clear first, then simulate jump -> airborne placement -> supported landing as one verified program. */
    private FusionSearch.Program preparePillar(BlockPos stand,Item material){
        BlockPos floor=stand.down();MotionState initial=state();
        if(water() || !initial.grounded() || Math.abs(stand.getY()-initial.y()-1)>.05
                || Math.hypot(initial.x()-stand.getX()-.5,initial.z()-stand.getZ()-.5)>.18)
            throw new IllegalArgumentException("pillar requires centred dry one-block rise");
        FusionAgent next=copy();List<MotionPlan.Frame> jump=new ArrayList<>();boolean airborne=false;
        for(int tick=0;tick<24;tick++){
            MotionState before=next.state();float[] aim=look(next.agent,Vec3d.ofCenter(floor.down()).add(0,.5,0));
            MotionInput input=new MotionInput(false,false,false,false,!airborne && before.grounded(),false,false,aim[0],aim[1]);
            MotionState after=next.step(input);jump.add(new MotionPlan.Frame(input,after));airborne|=!after.grounded();
            FusionAgent current=next;
            Edit place=FusionWorld.in(world,next.edits,()->current.placing(floor).stream()
                    .filter(e->e.item()==material).findFirst().orElse(null));
            if(place!=null){
                MotionState placeStart=next.state();
                // Place before simulating the hold: landing must collide with the new block.
                FusionAgent landing=next.applyPillarBlock(place);List<MotionPlan.Frame> hold=new ArrayList<>();int settled=0;
                float[] look=look(next.agent,place.hit().getPos());
                for(int wait=0;wait<40;wait++){
                    MotionInput still=new MotionInput(false,false,false,false,false,false,false,look[0],look[1]);
                    MotionState state=landing.step(still);hold.add(new MotionPlan.Frame(still,state));
                    boolean landed=state.grounded() && Math.abs(state.y()-stand.getY())<.05
                            && Math.hypot(state.x()-stand.getX()-.5,state.z()-stand.getZ()-.5)<=.18
                            && Math.hypot(state.vx(),state.vz())<=.035;
                    settled=landed?settled+1:0;
                    if(settled>=4){
                        Edit timed=new Edit("PLACE",place.pos(),place.before(),place.after(),place.item(),place.toolSlot(),
                                hold.size(),place.price(),place.hit(),placeStart,
                                new MotionPlan("fusion-pillar-place",placeStart,hold,.15,.12));
                        return new FusionSearch.Program(List.of(
                                new FusionSearch.Leg(new MotionPlan("fusion-pillar-jump",initial,jump,.15,.12),null),
                                new FusionSearch.Leg(null,timed)));
                    }
                    if(state.y()<stand.getY()-.15)throw new IllegalArgumentException("pillar missed support landing");
                }
                throw new IllegalArgumentException("pillar landing did not settle");
            }
            if(airborne && after.grounded())break;
        }
        throw new IllegalArgumentException("pillar jump has no clear reachable placement window");
    }
    private FusionAgent applyPillarBlock(Edit edit){
        if(!edit.kind().equals("PLACE"))throw new IllegalArgumentException("pillar needs placement");
        return FusionWorld.in(world,edits,()->{
            if(world.getBlockState(edit.pos())!=edit.before() || agent.getBoundingBox().intersects(new Box(edit.pos())))
                throw new IllegalArgumentException("pillar placement body/precondition changed");
            Map<BlockPos,BlockState> changes=new HashMap<>(edits);changes.put(edit.pos(),edit.after());
            Map<Item,Integer> used=new HashMap<>(spent);used.merge(edit.item(),1,Integer::sum);
            return new FusionAgent(this,changes,used,wear);
        });
    }
    private FusionSearch.Program prepareEscape(Escape escape){
        FusionAgent next=copy();List<FusionSearch.Leg> steps=new ArrayList<>();double initialY=state().y();
        for(int hop=0;hop<6;hop++){
            FusionAgent current=next;
            Corridor corridor=FusionWorld.in(world,next.edits,()->{
                BlockPos p=current.agent.getBlockPos().offset(escape.direction());
                int surface=FusionEscapeGeometry.surface(current::sample,p.getX(),p.getZ(),initialY);
                if(surface<0)throw new IllegalArgumentException("pit escape has no open neighbouring surface");
                int y=Math.min(surface,(int)Math.ceil(current.state().y()-1e-4)+1);
                if(y<current.state().y()-.05)throw new IllegalArgumentException("pit escape would descend");
                var cells=FusionEscapeGeometry.clearance(current::sample,p.getX(),y,p.getZ(),8-current.edits.size());
                if(cells==null)throw new IllegalArgumentException("pit escape unsupported/too many clearance blocks");
                return new Corridor(new BlockPos(p.getX(),y,p.getZ()),cells.stream().map(c->new BlockPos(c.x(),c.y(),c.z())).toList());
            });
            FusionSearch.Program part=next.prepareCorridor(corridor);next=next.apply(part);steps.addAll(part.steps());
            if(next.state().y()>=localEscapeHeight-.15 && next.pitExitSafe(initialY,localEscapeHeight)){
                if(next.state().y()<initialY+.5)throw new IllegalArgumentException("pit escape has no upward progress");
                return new FusionSearch.Program(steps,true);
            }
        }
        throw new IllegalArgumentException("pit escape exceeds six simulated landings");
    }
    /** Peel reachable cover first; an occluded high leaf must not reject a reachable lower leaf. */
    private List<FusionSearch.Leg> prepareClearance(List<BlockPos> blocks){
        FusionAgent next=copy();List<FusionSearch.Leg> steps=new ArrayList<>();List<BlockPos> pending=new ArrayList<>(blocks);
        while(!pending.isEmpty()){
            FusionAgent snapshot=next;
            FusionWorld.in(world,snapshot.edits,()->{pending.removeIf(p->world.getBlockState(p).getCollisionShape(world,p).isEmpty());return null;});
            if(pending.isEmpty())break;
            FusionAgent current=next;
            Edit chosen=FusionWorld.in(world,next.edits,()->{
                for(BlockPos p:pending){if(world.getBlockState(p).getCollisionShape(world,p).isEmpty())continue;Edit candidate=current.breaking(p);if(candidate!=null)return candidate;}
                return null;
            });
            if(chosen==null)throw new IllegalArgumentException("clearance "+current.breakRejection+" @ "+pending.get(0).toShortString());
            if(next.edits.size()>=8)throw new IllegalArgumentException("clearance edit limit");
            Edit edit=(Edit)next.prepare(chosen);next=next.apply(edit);steps.add(new FusionSearch.Leg(null,edit));pending.remove(edit.pos());
        }
        return List.copyOf(steps);
    }
    private FusionSearch.Program prepareCorridor(Corridor corridor){
        List<FusionSearch.Leg> clearance=prepareClearance(corridor.blocks());
        FusionAgent next=clearance.isEmpty()?copy():apply(new FusionSearch.Program(clearance));
        List<FusionSearch.Leg> steps=new ArrayList<>(clearance);
        MotionState initial=next.state();List<MotionPlan.Frame> frames=new ArrayList<>();
        double tx=corridor.stand().getX()+.5,tz=corridor.stand().getZ()+.5;int ty=corridor.stand().getY();
        for(int i=0;i<48;i++){
            MotionState s=next.state();double dx=tx-s.x(),dz=tz-s.z(),length=Math.max(.001,Math.hypot(dx,dz));
            float yaw=GroundSteering.direction(dx,dz);boolean jump=ty>s.y()+.15 && (s.grounded() || next.water());MotionInput input;
            if(next.water())input=new MotionInput(true,false,false,false,jump,false,true,yaw,-25);
            else{double speed=Math.min(.28,length*.65);input=GroundSteering.accelerate((dx/length*speed-s.vx())*.65,(dz/length*speed-s.vz())*.65,yaw,jump,true);}
            MotionState after=next.step(input);frames.add(new MotionPlan.Frame(input,after));
            if(after.grounded() && Math.hypot(tx-after.x(),tz-after.z())<=.18 && Math.abs(ty-after.y())<.12 && Math.hypot(after.vx(),after.vz())<=.035){
                steps.add(new FusionSearch.Leg(new MotionPlan("fusion-clearance-connection",initial,frames,.20,.15),null));
                return new FusionSearch.Program(steps);
            }
        }
        throw new IllegalArgumentException("clearance connection has no simulated landing");
    }
    private boolean staticCell(BlockPos p,BlockState state){return FusionSafety.editableCell(world,client.player,p,state);}
    private BlockHitResult placementHit(BlockPos p){
        Vec3d eye=new Vec3d(agent.getX(),agent.getEyeY(),agent.getZ());
        for(Direction side:Direction.values()){
            BlockPos support=p.offset(side);Direction face=side.getOpposite();
            if(world.getBlockEntity(support)!=null)continue;
            Vec3d end=new Vec3d(support.getX()+.5+face.getOffsetX()*.49,
                    support.getY()+.5+face.getOffsetY()*.49,support.getZ()+.5+face.getOffsetZ()*.49);
            if(eye.squaredDistanceTo(end)>20.25)continue;
            BlockHitResult hit=world.rayTrace(new RayTraceContext(eye,end,RayTraceContext.ShapeType.OUTLINE,RayTraceContext.FluidHandling.NONE,agent));
            if(hit.getType()==HitResult.Type.BLOCK && hit.getBlockPos().offset(hit.getSide()).equals(p))return hit;
        }
        return null;
    }
    public FusionAgent apply(FusionSearch.Interaction action){
        if(action instanceof FusionSearch.Program program){
            FusionAgent next=copy();
            for(FusionSearch.Leg leg:program.steps()){
                if(leg.interaction()!=null)next=next.apply(leg.interaction());
                else for(MotionPlan.Frame frame:leg.motion().frames()){
                    MotionState state=next.step(frame.input());
                    if(state.positionError(frame.after())>1e-7 || state.velocityError(frame.after())>1e-7)throw new IllegalArgumentException("clearance replay mismatch");
                }
            }
            return next;
        }
        Edit edit=(Edit)action;
        if(edit.airbornePlacement()){
            FusionAgent next=applyPillarBlock(edit);
            for(MotionPlan.Frame frame:edit.hold().frames()){
                MotionState state=next.step(frame.input());
                if(state.positionError(frame.after())>1e-7 || state.velocityError(frame.after())>1e-7)
                    throw new IllegalArgumentException("pillar placement replay mismatch");
            }
            return next;
        }
        FusionAgent next=copy();
        FusionWorld.in(world,edits,()->{
            if(world.getBlockState(edit.pos())!=edit.before())throw new IllegalArgumentException("edit precondition changed");
            float[] look=look(agent,edit.hit().getPos());
            if(edit.hold()!=null){
                for(MotionPlan.Frame frame:edit.hold().frames()){
                    MotionState state=next.step(frame.input());
                    if(state.positionError(frame.after())>1e-7 || state.velocityError(frame.after())>1e-7)throw new IllegalArgumentException("aquatic edit replay mismatch");
                }
            }else for(int i=0;i<edit.ticks();i++)next.step(new MotionInput(false,false,false,false,false,false,false,look[0],look[1]));
            return null;
        });
        Map<BlockPos,BlockState> changes=new HashMap<>(edits);changes.put(edit.pos(),edit.after());
        Map<Item,Integer> used=new HashMap<>(spent);Map<Integer,Integer> damage=new HashMap<>(wear);
        if(edit.kind().equals("PLACE"))used.merge(edit.item(),1,Integer::sum);
        else if(edit.toolSlot()>=0 && agent.inventory.main.get(edit.toolSlot()).isDamageable())damage.merge(edit.toolSlot(),1,Integer::sum);
        return new FusionAgent(next,changes,used,damage);
    }
    public static float[] look(ClientPlayerEntity player,Vec3d point){
        double dx=point.x-player.getX(),dy=point.y-player.getEyeY(),dz=point.z-player.getZ();
        return new float[]{(float)Math.toDegrees(Math.atan2(-dx,dz)),(float)-Math.toDegrees(Math.atan2(dy,Math.hypot(dx,dz)))};
    }
}
