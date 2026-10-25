package com.fredoseep.testing.combat;

import java.util.ArrayList;
import java.util.List;

/** Reproducible fixtures; block verification uses the same explicit geometry as construction. */
public final class CombatTerrainLayout {
    private CombatTerrainLayout() {}
    public enum Kind { FLAT, CORNER, CORRIDOR, STEP }
    public static Kind forName(String name){
        if(name.startsWith("corner_"))return Kind.CORNER;
        if(name.startsWith("corridor_"))return Kind.CORRIDOR;
        if(name.startsWith("step_"))return Kind.STEP;
        return Kind.FLAT;
    }
    public static double playerY(Kind kind){return kind==Kind.STEP?5:4;}
    public static List<String> commands(Kind kind){
        List<String> out=new ArrayList<>();out.add("/fill -12 4 -12 12 9 12 minecraft:air");
        if(kind==Kind.CORNER){out.add("/fill -2 4 -2 -2 6 9 minecraft:stone");out.add("/fill -2 4 -2 9 6 -2 minecraft:stone");}
        if(kind==Kind.CORRIDOR){out.add("/fill -2 4 -10 -2 6 10 minecraft:stone");out.add("/fill 2 4 -10 2 6 10 minecraft:stone");}
        if(kind==Kind.STEP)out.add("/fill -1 4 -1 1 4 1 minecraft:stone");
        return List.copyOf(out);
    }
    public static boolean stoneAt(Kind kind,int x,int y,int z){
        if(y<4 || y>6)return false;
        return switch(kind){
            case CORNER -> (x==-2 && z>=-2 && z<=9) || (z==-2 && x>=-2 && x<=9);
            case CORRIDOR -> (x==-2 || x==2) && z>=-10 && z<=10;
            case STEP -> y==4 && x>=-1 && x<=1 && z>=-1 && z<=1;
            default -> false;
        };
    }
}
