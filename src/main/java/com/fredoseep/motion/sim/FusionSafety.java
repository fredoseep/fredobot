package com.fredoseep.motion.sim;

import com.fredoseep.motion.LocalGroundSearch;
import com.fredoseep.algorithm.planning.PlannerSettings;
import com.fredoseep.motion.MotionEnvironmentPolicy;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.FallingBlock;
import net.minecraft.block.IceBlock;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.World;

public final class FusionSafety {
    private FusionSafety(){}
    /** Admission may wait for these entities, but the simulator never ignores them. */
    public static final class TransientCollision extends IllegalArgumentException {
        public TransientCollision(String detail){super(detail);}
    }
    public static void start(ClientPlayerEntity p){
        if(!p.isAlive() || p.hasVehicle() || p.isInLava() || p.isFallFlying() || p.isUsingItem()
                || p.isSpectator() || p.abilities.flying || p.hasNoGravity() || !p.getStatusEffects().isEmpty())
            throw new IllegalArgumentException("融合实验不支持当前玩家状态");
        area(p.world,p,p.getBoundingBox());
    }
    public static void goal(ClientPlayerEntity p,LocalGroundSearch.Goal g){
        BlockPos pos=new BlockPos(g.x(),g.y(),g.z());
        if(!p.world.getChunkManager().isChunkLoaded(pos.getX()>>4,pos.getZ()>>4))throw new IllegalArgumentException("目标区块未加载");
        if(p.world.getBlockState(pos).getFluidState().isIn(FluidTags.WATER)){area(p.world,p,new Box(pos));return;}
        Box body=new Box(g.x()-.3,g.y(),g.z()-.3,g.x()+.3,g.y()+1.8,g.z()+.3);
        area(p.world,p,body);
        BlockPos floor=new BlockPos(g.x(),g.y()-.001,g.z());
        BlockState support=p.world.getBlockState(floor);
        if(!Block.isShapeFullCube(support.getCollisionShape(p.world,floor))){
            if(!PlannerSettings.DEFAULT.allowWorldEdits || Math.abs(g.y()-Math.rint(g.y()))>1e-6
                    || !support.isAir() || !editableCell(p.world,p,floor,support))
                throw new IllegalArgumentException("目标支撑暂不支持规划放置 @ "+floor.toShortString());
            if(FusionMaterials.choices(p,p.world,floor).isEmpty())
                throw new IllegalArgumentException("目标缺少支撑且没有可用放置材料 @ "+floor.toShortString());
            // Eligibility only. The real floor remains air until a verified PLACE is confirmed.
        }
        if(p.world.doesNotCollide(p,body))return;
        if(!PlannerSettings.DEFAULT.allowWorldEdits)throw new IllegalArgumentException("目标站位存在碰撞且禁用地形编辑 @ "+pos.toShortString());
        // This is eligibility, not completion: actual physics still cannot occupy these
        // blocks until a BREAK action has been planned, replayed and confirmed in game.
        boolean obstacle=false;
        for(int x=(int)Math.floor(body.minX);x<=(int)Math.floor(body.maxX);x++)
            for(int y=(int)Math.floor(body.minY);y<=(int)Math.floor(body.maxY);y++)
                for(int z=(int)Math.floor(body.minZ);z<=(int)Math.floor(body.maxZ);z++){
                    BlockPos cell=new BlockPos(x,y,z);BlockState state=p.world.getBlockState(cell);
                    if(state.getCollisionShape(p.world,cell).isEmpty())continue;
                    obstacle=true;
                    if(!editableCell(p.world,p,cell,state) || !Block.isShapeFullCube(state.getCollisionShape(p.world,cell)))
                        throw new IllegalArgumentException("目标障碍暂不支持规划清理："+Registry.BLOCK.getId(state.getBlock())+" @ "+cell.toShortString());
                }
        if(!obstacle)throw new IllegalArgumentException("目标站位存在未建模碰撞 @ "+pos.toShortString());
    }
    public static boolean checkpoint(ClientPlayerEntity p){
        var velocity=p.getVelocity();
        if(Math.hypot(velocity.x,velocity.z)>.035 || Math.abs(velocity.y)>.1 || p.isClimbing())return false;
        if(!p.isTouchingWater() && !p.isSwimming())return p.isOnGround();
        return !p.isSwimming() && !p.isSubmergedInWater() && p.getAir()>=200;
    }
    /** An emergence checkpoint must be above its origin and have an actually open column. */
    public static boolean pitExit(ClientPlayerEntity player,double originY,double rimY){
        if(!Double.isFinite(rimY) || player.isTouchingWater() || player.isSwimming() || !checkpoint(player)
                || player.getY()<originY+1 || player.getY()<rimY-.15)return false;
        BlockPos feet=player.getBlockPos();
        int top=Math.min(254,Math.max((int)Math.ceil(originY)+8,(int)Math.ceil(player.getBoundingBox().maxY)+2));
        for(int y=(int)Math.ceil(player.getBoundingBox().maxY-1e-6);y<=top;y++){
            BlockPos pos=new BlockPos(feet.getX(),y,feet.getZ());
            if(!player.world.getChunkManager().isChunkLoaded(pos.getX()>>4,pos.getZ()>>4))return false;
            BlockState state=player.world.getBlockState(pos);
            if(!state.getFluidState().isEmpty() || !state.getCollisionShape(player.world,pos).isEmpty())return false;
        }
        return true;
    }
    /** Same conservative static-cell policy used by both goal eligibility and actual edit generation. */
    public static boolean editableCell(World w,ClientPlayerEntity p,BlockPos pos,BlockState state){
        if(!state.getFluidState().isEmpty() || state.getBlock() instanceof FallingBlock || state.getBlock() instanceof IceBlock
                || w.getBlockEntity(pos)!=null || state.getHardness(w,pos)<0)return false;
        for(Direction d:Direction.values()){
            BlockState other=w.getBlockState(pos.offset(d));
            if(!other.getFluidState().isEmpty() || other.getBlock() instanceof FallingBlock
                    || MotionEnvironmentPolicy.vine(Registry.BLOCK.getId(other.getBlock()).toString()))return false;
        }
        try{area(w,p,new Box(pos));return true;}catch(IllegalArgumentException unsupported){return false;}
    }
    /** Top-down excavation may remove stable sand/gravel, never the support of a falling column. */
    public static String miningRejection(World w,ClientPlayerEntity player,BlockPos pos,BlockState state){
        if(!state.getFluidState().isEmpty() || state.getBlock() instanceof IceBlock)return "FLUID_OR_ICE";
        if(w.getBlockEntity(pos)!=null || state.getHardness(w,pos)<0)return "PROTECTED_OR_UNBREAKABLE";
        for(Direction d:Direction.values()){
            BlockPos neighbour=pos.offset(d);BlockState other=w.getBlockState(neighbour);
            if(!other.getFluidState().isEmpty())return "ADJACENT_FLUID";
            if(MotionEnvironmentPolicy.vine(Registry.BLOCK.getId(other.getBlock()).toString()))return "ADJACENT_VINE";
            if(other.getBlock() instanceof FallingBlock){
                if(d==Direction.UP)return "FALLING_ABOVE";
                if(d!=Direction.DOWN && (FallingBlock.canFallThrough(w.getBlockState(neighbour.down())) || w.getBlockState(neighbour.down()).getCollisionShape(w,neighbour.down()).isEmpty()))
                    return "UNSUPPORTED_ADJACENT_GRAVITY";
            }
        }
        if(state.getBlock() instanceof FallingBlock && (FallingBlock.canFallThrough(w.getBlockState(pos.down())) || w.getBlockState(pos.down()).getCollisionShape(w,pos.down()).isEmpty()))
            return "UNSUPPORTED_GRAVITY";
        try{area(w,player,new Box(pos));return null;}catch(IllegalArgumentException unsupported){return "UNMODELLED_ENVIRONMENT";}
    }
    public static void area(World w,ClientPlayerEntity p,Box box){
        var entities=w.getEntities(p,box.expand(.2),e->e.isAlive() && !(e instanceof ItemEntity) && !(e instanceof ExperienceOrbEntity));
        if(!entities.isEmpty()){
            String detail="融合轨迹有实体碰撞："+entities.stream().limit(3)
                    .map(e->Registry.ENTITY_TYPE.getId(e.getType())+" @ "+e.getBlockPos().toShortString()).toList();
            if(entities.stream().allMatch(e->e instanceof FallingBlockEntity))throw new TransientCollision(detail);
            throw new IllegalArgumentException(detail);
        }
        staticArea(w,p,box);
    }
    /** Static terrain checks only, for detached combat prediction with separately modelled entities. */
    public static void staticArea(World w,ClientPlayerEntity p,Box box){
        for(int x=(int)Math.floor(box.minX-.01);x<=(int)Math.floor(box.maxX+.01);x++)
            for(int z=(int)Math.floor(box.minZ-.01);z<=(int)Math.floor(box.maxZ+.01);z++)
                for(int y=(int)Math.floor(box.minY-.08);y<=(int)Math.floor(box.maxY+.01);y++){
                    if(y<0 || y>254 || !w.getChunkManager().isChunkLoaded(x>>4,z>>4))throw new IllegalArgumentException("融合轨迹超出已加载范围");
                    BlockState s=w.getBlockState(new BlockPos(x,y,z));
                    if(s.getFluidState().isIn(FluidTags.LAVA))throw new IllegalArgumentException("融合轨迹接触岩浆");
                    if(s.getFluidState().isEmpty() && MotionEnvironmentPolicy.reject(Registry.BLOCK.getId(s.getBlock()).toString(),false,true))
                        throw new IllegalArgumentException("暂不支持的轨迹环境："+Registry.BLOCK.getId(s.getBlock())+" @ "+new BlockPos(x,y,z).toShortString());
                }
    }
}
