package com.fredoseep.utils.player;

import com.fredoseep.excutor.BotEngine;
import com.fredoseep.logging.GlobalStateLogger;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RayTraceContext;

import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Select an interaction stance in paused render slices, independently of the path backend. */
public final class WorkbenchApproach {
    private static Request active;
    private static final int RADIUS=4, WIDTH=RADIUS*2+1, TOTAL=WIDTH*WIDTH*WIDTH;
    public static final class Request {
        private final ClientWorld world;
        private final ClientPlayerEntity player;
        private final BlockPos table;
        private final Set<BlockPos> excluded;
        private final BooleanSupplier current;
        private final GameMenuScreen pause=new GameMenuScreen(false);
        private final long started=System.currentTimeMillis();
        private long pausedAt;
        private int cursor;
        private double best=Double.POSITIVE_INFINITY;
        private BlockPos position;
        private String status="SELECTING";
        private Request(MinecraftClient c,BlockPos table,Set<BlockPos> excluded,BooleanSupplier current){
            world=c.world;player=c.player;this.table=table.toImmutable();
            this.excluded=Set.copyOf(excluded);this.current=current;
        }
        public boolean finished(){return !status.equals("SELECTING");}
        public boolean cancelled(){return status.equals("CANCELLED");}
        public BlockPos position(){return position;}
    }
    private WorkbenchApproach(){}
    public static Request begin(MinecraftClient c,BlockPos table,Set<BlockPos> excluded,BooleanSupplier current){
        if(active!=null || c.world==null || c.player==null || c.interactionManager==null
                || c.getServer()==null || c.currentScreen!=null || !current.getAsBoolean())return null;
        active=new Request(c,table,excluded,current);
        Vec3d point=Vec3d.ofCenter(table);face(c.player,point);
        c.openScreen(active.pause);
        log("workbench_stance_started",active);
        return active;
    }
    /** Called by the existing render pump, including while the game is paused. */
    public static void pump(MinecraftClient c){
        Request r=active;if(r==null)return;
        if(c.world!=r.world || c.player!=r.player || !r.player.isAlive()
                || !BotEngine.getInstance().isRunning() || !r.current.getAsBoolean()
                || c.currentScreen!=r.pause){finish(c,r,"CANCELLED");return;}
        if(System.currentTimeMillis()-r.started>10_000){finish(c,r,"TIMEOUT");return;}
        if(!c.isPaused())return;
        if(r.pausedAt==0){r.pausedAt=System.currentTimeMillis();return;}
        if(System.currentTimeMillis()-r.pausedAt<150)return;
        if(r.world.getBlockState(r.table).getBlock()!=Blocks.CRAFTING_TABLE){finish(c,r,"TABLE_MISSING");return;}
        long deadline=System.nanoTime()+2_000_000;
        do {
            int n=r.cursor++;
            BlockPos p=r.table.add(n%WIDTH-RADIUS,n/(WIDTH*WIDTH)-RADIUS,n/WIDTH%WIDTH-RADIUS);
            if(!r.excluded.contains(p) && standable(c,p)){
                Vec3d feet=new Vec3d(p.getX()+.5,p.getY(),p.getZ()+.5);
                double score=r.player.squaredDistanceTo(feet);
                if(score<r.best && interaction(c,r.table,feet.add(0,r.player.getStandingEyeHeight(),0))!=null){
                    r.best=score;r.position=p;
                }
            }
        }while(r.cursor<TOTAL && System.nanoTime()<deadline);
        if(r.cursor==TOTAL)finish(c,r,r.position==null?"NO_STANCE":"READY");
    }
    public static void cancel(){if(active!=null)finish(MinecraftClient.getInstance(),active,"CANCELLED");}
    private static void finish(MinecraftClient c,Request r,String status){
        r.status=status;if(!status.equals("READY"))r.position=null;
        active=null;
        if(c.currentScreen==r.pause)c.openScreen(null);
        log("workbench_stance_"+status.toLowerCase(java.util.Locale.ROOT),r);
    }
    private static void log(String event,Request r){
        GlobalStateLogger.logCustomEvent(event,Map.of("table",r.table.toShortString(),
                "stance",r.position==null?"none":r.position.toShortString(),"checked",r.cursor,
                "elapsed_ms",System.currentTimeMillis()-r.started));
    }
    public static boolean standable(MinecraftClient c,BlockPos p){
        if(c.world==null || c.player==null || p.getY()<1 || p.getY()>253)return false;
        if(!c.world.getChunkManager().isChunkLoaded(p.getX()>>4,p.getZ()>>4))return false;
        BlockPos floor=p.down();
        if(c.world.getBlockState(floor).getBlock()==Blocks.CRAFTING_TABLE
                || !Block.isShapeFullCube(c.world.getBlockState(floor).getCollisionShape(c.world,floor)))return false;
        for(BlockPos cell:new BlockPos[]{floor,p,p.up()}){
            Block block=c.world.getBlockState(cell).getBlock();
            if(!c.world.getFluidState(cell).isEmpty() || block==Blocks.MAGMA_BLOCK || block==Blocks.CACTUS
                    || block==Blocks.FIRE || block==Blocks.SOUL_FIRE || block==Blocks.CAMPFIRE
                    || block==Blocks.SOUL_CAMPFIRE || block==Blocks.SWEET_BERRY_BUSH)return false;
        }
        Box body=new Box(p.getX()+.2,p.getY(),p.getZ()+.2,
                p.getX()+.8,p.getY()+1.8,p.getZ()+.8);
        // Use collision-shape boxes directly: isSpaceEmpty is unavailable in this mapping.
        // Include neighbouring cells and the floor for shapes extending beyond their cell.
        for(int x=-1;x<=1;x++)for(int y=-1;y<=2;y++)for(int z=-1;z<=1;z++){
            BlockPos cell=p.add(x,y,z);
            if(!c.world.getChunkManager().isChunkLoaded(cell.getX()>>4,cell.getZ()>>4))return false;
            for(Box shape:c.world.getBlockState(cell).getCollisionShape(c.world,cell).getBoundingBoxes())
                if(body.intersects(shape.offset(cell.getX(),cell.getY(),cell.getZ())))return false;
        }
        return true;
    }
    /** Actual first-hit ray and eye reach, also used after movement to reject stale stances. */
    public static BlockHitResult interaction(MinecraftClient c,BlockPos table,Vec3d eye){
        if(c.world==null || c.player==null || c.interactionManager==null
                || c.world.getBlockState(table).getBlock()!=Blocks.CRAFTING_TABLE)return null;
        double reach=Math.max(0,c.interactionManager.getReachDistance()-.3);
        double[] samples={.5,.2,.8};
        for(int axis=0;axis<3;axis++)for(double face:new double[]{.01,.99})
            for(double a:samples)for(double b:samples){
                Vec3d point=new Vec3d(table.getX()+(axis==0?face:a),
                        table.getY()+(axis==1?face:(axis==0?a:b)),table.getZ()+(axis==2?face:b));
                if(eye.squaredDistanceTo(point)>reach*reach)continue;
                BlockHitResult hit=c.world.rayTrace(new RayTraceContext(eye,point,RayTraceContext.ShapeType.OUTLINE,
                        RayTraceContext.FluidHandling.NONE,c.player));
                if(hit.getType()==HitResult.Type.BLOCK && hit.getBlockPos().equals(table)
                        && eye.squaredDistanceTo(hit.getPos())<=reach*reach)return hit;
            }
        return null;
    }
    public static void face(ClientPlayerEntity player,Vec3d point){
        double dx=point.x-player.getX(),dz=point.z-player.getZ();
        player.yaw=(float)Math.toDegrees(Math.atan2(-dx,dz));
        player.pitch=(float)-Math.toDegrees(Math.atan2(point.y-player.getEyeY(),Math.hypot(dx,dz)));
    }
}
