package com.fredoseep.motion;

import java.util.List;
import java.util.function.IntPredicate;

/** Pure boundary policy. Never consumes or crosses a work/water/custom-path boundary. */
public final class GroundSegmentPolicy {
    private GroundSegmentPolicy(){}
    public static boolean pureAction(boolean groundMovement,boolean hasBreaks,boolean hasMaterial,boolean shoreline,boolean waterAction){
        return groundMovement && !hasBreaks && !hasMaterial && !shoreline && !waterAction;
    }
    public record Step(GroundRouteSearch.Cell cell,boolean pure) {}
    public record Ticket(long generation,Object path,int first,int last,Object endNode) {
        public boolean matches(long currentGeneration,Object currentPath,int index,Object currentEndNode){
            return generation==currentGeneration && path==currentPath && first==index && endNode==currentEndNode;
        }
    }
    public static int select(List<Step> path,int first,MotionState start){
        return select(path,first,start,24,32);
    }
    public static int select(List<Step> path,int first,MotionState start,double maxDistance,int maxNodes){
        return select(path,first,start,maxDistance,maxNodes,index->true);
    }
    /** Rejecting a distant landing must not discard the valid dry prefix before it. */
    public static int select(List<Step> path,int first,MotionState start,double maxDistance,int maxNodes,IntPredicate usable){
        if(first<0 || first>=path.size())return -1;
        int last=-1;
        for(int i=first;i<Math.min(path.size(),first+maxNodes);i++){
            Step step=path.get(i);if(!step.pure)break;
            var goal=step.cell.goal();double distance=Math.hypot(goal.x()-start.x(),goal.z()-start.z());
            if(distance>maxDistance || Math.abs(goal.y()-start.y())>8)break;
            if(distance>=1.25 || Math.abs(goal.y()-start.y())>=.75)last=i;
        }
        for(int i=last;i>=first;i--){
            var goal=path.get(i).cell.goal();
            if((Math.hypot(goal.x()-start.x(),goal.z()-start.z())>=1.25 || Math.abs(goal.y()-start.y())>=.75)
                    && usable.test(i))return i;
        }
        return -1;
    }
}
