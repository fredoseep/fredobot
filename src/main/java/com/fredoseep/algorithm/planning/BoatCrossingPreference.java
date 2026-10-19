package com.fredoseep.algorithm.planning;

import java.util.function.IntPredicate;

public final class BoatCrossingPreference {
    private BoatCrossingPreference(){}
    public static int samples(double distance){return Math.max(1,Math.min(1024,(int)Math.ceil(distance*2)));}
    public static boolean use(double distance,boolean hasBoat,boolean riding,IntPredicate surfaceWater){
        if(riding)return true;
        if(!hasBoat || distance<8)return false;
        int samples=samples(distance);double run=0,step=distance/samples;boolean previous=false;
        for(int i=0;i<=samples;i++){
            boolean water=surfaceWater.test(i);run=water && previous?run+step:0;previous=water;
            if(run>=8)return true;
        }
        return false;
    }
}
