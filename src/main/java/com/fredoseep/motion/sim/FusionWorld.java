package com.fredoseep.motion.sim;

import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** Read-only, thread-local overlay. No chunk/setBlockState/save mutation is ever performed. */
public final class FusionWorld {
    private record View(World world,Map<BlockPos,BlockState> edits) {}
    private static final ThreadLocal<View> ACTIVE=new ThreadLocal<>();
    private FusionWorld(){}
    public static boolean active(){return ACTIVE.get()!=null;}
    public static BlockState override(World world,BlockPos pos){
        View view=ACTIVE.get();return view!=null && view.world()==world?view.edits().get(pos):null;
    }
    public static <T> T in(World world,Map<BlockPos,BlockState> edits,Supplier<T> body){
        View old=ACTIVE.get();ACTIVE.set(new View(world,edits));
        try{return body.get();}finally{if(old==null)ACTIVE.remove();else ACTIVE.set(old);}
    }
}
