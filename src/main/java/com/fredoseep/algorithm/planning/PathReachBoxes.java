package com.fredoseep.algorithm.planning;

import com.fredoseep.algorithm.SimplePathfinder;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import java.util.List;

/** Shared by progress detection and dumps, so logged reach boxes match the executor. */
public final class PathReachBoxes {
    private PathReachBoxes() {}
    public static boolean plannedWater(SimplePathfinder.Node node) {
        return node.plannedAction!=null && (node.state==SimplePathfinder.MovementState.SWIMMING
                || node.state==SimplePathfinder.MovementState.DIVING);
    }
    public static boolean waterPositionReached(double x,double y,double z,SimplePathfinder.Node node) {
        double dx=x-node.pos.getX()-.5,dz=z-node.pos.getZ()-.5;
        double dy=y-node.pos.getY();
        return dx*dx+dz*dz<=.12 && dy>=-.25 && dy<=waterUpperLimit(node);
    }
    public static double waterUpperLimit(SimplePathfinder.Node node) {
        return node.state==SimplePathfinder.MovementState.SWIMMING?1.05:.25;
    }
    public static double finalHorizontalLimit(SimplePathfinder.Node node,boolean strict) {
        boolean water=node.state==SimplePathfinder.MovementState.SWIMMING || node.state==SimplePathfinder.MovementState.DIVING;
        return !strict && water?.12:.03;
    }
    public static Box player(PlayerEntity player, boolean strict) {
        if (!strict && player.hasVehicle()) return player.getVehicle().getBoundingBox().expand(.5,0,.5);
        if (!strict && (player.isSwimming() || player.isTouchingWater())) {
            Box body=player.getBoundingBox().expand(.2,.2,.2);
            var look=player.getRotationVec(1F).normalize().multiply(1.2);
            return body.union(body.offset(look.x,look.y,look.z));
        }
        return new Box(player.getX()-.3,player.getY(),player.getZ()-.3,
                player.getX()+.3,player.getY()+.5,player.getZ()+.3);
    }
    public static Box node(List<SimplePathfinder.Node> path,int index,boolean strict) {
        var node=path.get(index);double xz=0,down=0,up=0;
        if(plannedWater(node)) {
            return new Box(node.pos.getX()+.15,node.pos.getY()-.25,node.pos.getZ()+.15,
                    node.pos.getX()+.85,node.pos.getY()+waterUpperLimit(node),node.pos.getZ()+.85);
        }
        if (!strict) {
            switch (node.state) {
                case SWIMMING -> {xz=.35;down=.5;up=.5;}
                case DIVING -> {xz=.5;down=1;up=1;}
                case JUMPING_UP,JUMPING_AIR,FALLING -> {xz=.25;down=1;up=1.5;}
                default -> {}
            }
            if(node.state==SimplePathfinder.MovementState.WALKING && index+1<path.size()
                    && path.get(index+1).state==SimplePathfinder.MovementState.JUMPING_UP) xz=.35;
        }
        return new Box(node.pos.getX()+.25-xz,node.pos.getY()-down,node.pos.getZ()+.25-xz,
                node.pos.getX()+.75+xz,node.pos.getY()+1+up,node.pos.getZ()+.75+xz);
    }
}
