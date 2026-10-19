package com.fredoseep.algorithm.planning;

import net.minecraft.util.math.BlockPos;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/** Near-to-far 3D shells; each horizontal layer follows a square spiral without visiting a cell twice. */
public final class LayeredSpiralSearch {
    private LayeredSpiralSearch(){}
    public static List<BlockPos> find(BlockPos origin,int radius,int count,Predicate<BlockPos> target,
                                      Predicate<BlockPos> preferred){
        if(radius<0 || count<=0)return List.of();
        List<Candidate> nearest=new ArrayList<>();
        Comparator<Candidate> order=Comparator.comparingLong(Candidate::distance)
                .thenComparing(c->!c.preferred());
        visit(origin,radius,p->{
            long dx=(long)p.getX()-origin.getX(),dy=(long)p.getY()-origin.getY(),dz=(long)p.getZ()-origin.getZ();
            long shell=Math.max(Math.abs(dx),Math.max(Math.abs(dy),Math.abs(dz)));
            // Every unvisited cell is in this shell or a later shell. Do not scan the whole radius
            // once even that lower distance bound exceeds the furthest selected target.
            if(nearest.size()==count && shell*shell>nearest.get(count-1).distance())return false;
            long distance=dx*dx+dy*dy+dz*dz;
            if(nearest.size()==count && distance>nearest.get(count-1).distance())return true;
            if(target.test(p)){
                nearest.add(new Candidate(p.toImmutable(),distance,preferred.test(p)));
                nearest.sort(order);
                if(nearest.size()>count)nearest.remove(count);
            }
            return true;
        });
        List<BlockPos> result=new ArrayList<>();
        for(Candidate candidate:nearest)result.add(candidate.pos());
        return result;
    }
    private record Candidate(BlockPos pos,long distance,boolean preferred){}
    /** Feet-level first, then lower layers; distance/visibility only order targets within one layer. */
    public static List<BlockPos> findFeetFirst(BlockPos origin,int radius,int count,Predicate<BlockPos> target,Predicate<BlockPos> preferred){
        if(radius<0 || count<=0)return List.of();
        List<BlockPos> result=new ArrayList<>();
        for(int depth=0;depth<=radius && origin.getY()-depth>=0 && result.size()<count;depth++){
            int y=origin.getY()-depth;List<Candidate> layer=new ArrayList<>();
            for(int ring=0;ring<=radius;ring++){
                if(ring==0){BlockPos p=new BlockPos(origin.getX(),y,origin.getZ());if(target.test(p))layer.add(new Candidate(p,(long)depth*depth,preferred.test(p)));continue;}
                for(int x=-ring;x<ring;x++)addLayer(layer,origin,x,-depth,-ring,radius,target,preferred);
                for(int z=-ring;z<ring;z++)addLayer(layer,origin,ring,-depth,z,radius,target,preferred);
                for(int x=ring;x>-ring;x--)addLayer(layer,origin,x,-depth,ring,radius,target,preferred);
                for(int z=ring;z>-ring;z--)addLayer(layer,origin,-ring,-depth,z,radius,target,preferred);
            }
            layer.sort(Comparator.comparingLong(Candidate::distance).thenComparing(c->!c.preferred()));
            for(Candidate c:layer){if(result.size()==count)break;result.add(c.pos());}
        }
        return result;
    }
    private static void addLayer(List<Candidate> layer,BlockPos origin,int x,int y,int z,int radius,Predicate<BlockPos> target,Predicate<BlockPos> preferred){
        long distance=(long)x*x+(long)y*y+(long)z*z;if(distance>(long)radius*radius)return;
        BlockPos p=origin.add(x,y,z);if(target.test(p))layer.add(new Candidate(p,distance,preferred.test(p)));
    }
    public static void visit(BlockPos origin,int radius,Predicate<BlockPos> keepGoing){
        for(int shell=0;shell<=radius;shell++){
            for(int layer=0;layer<=shell*2;layer++){
                int dy=layer==0?0:(layer+1)/2*(layer%2==1?-1:1);
                if(origin.getY()+dy<0 || origin.getY()+dy>255)continue;
                for(int ring=0;ring<=shell;ring++){
                    if(Math.max(ring,Math.abs(dy))!=shell)continue;
                    if(ring==0){if(!emit(origin,0,dy,0,radius,keepGoing))return;continue;}
                    for(int x=-ring;x<ring;x++)if(!emit(origin,x,dy,-ring,radius,keepGoing))return;
                    for(int z=-ring;z<ring;z++)if(!emit(origin,ring,dy,z,radius,keepGoing))return;
                    for(int x=ring;x>-ring;x--)if(!emit(origin,x,dy,ring,radius,keepGoing))return;
                    for(int z=ring;z>-ring;z--)if(!emit(origin,-ring,dy,z,radius,keepGoing))return;
                }
            }
        }
    }
    private static boolean emit(BlockPos origin,int x,int y,int z,int radius,Predicate<BlockPos> visitor){
        if((long)x*x+(long)y*y+(long)z*z>(long)radius*radius)return true;
        return visitor.test(origin.add(x,y,z));
    }
}
