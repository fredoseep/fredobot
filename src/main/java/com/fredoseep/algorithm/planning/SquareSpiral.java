package com.fredoseep.algorithm.planning;

import java.util.ArrayList;
import java.util.List;

/** Clockwise square rings, strictly inside before outside. */
public final class SquareSpiral {
    public record Offset(int x,int z) {}
    private SquareSpiral(){}
    public static List<Offset> offsets(int radius){
        List<Offset> result=new ArrayList<>();result.add(new Offset(0,0));
        for(int r=1;r<=radius;r++){
            for(int z=-r;z<r;z++)result.add(new Offset(r,z));
            for(int x=r;x>-r;x--)result.add(new Offset(x,r));
            for(int z=r;z>-r;z--)result.add(new Offset(-r,z));
            for(int x=-r;x<r;x++)result.add(new Offset(x,-r));
        }
        return List.copyOf(result);
    }
}
