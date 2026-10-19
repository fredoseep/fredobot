package com.fredoseep.motion;

import java.util.ArrayList;
import java.util.List;

/** Local block samples only: no heightmap and no route computed ahead of Fusion. */
public final class FusionEscapeGeometry {
    public enum Kind {EMPTY,SOLID,GRAVITY,BLOCKED}
    public record Cell(int x,int y,int z) {}
    public interface View {Kind at(int x,int y,int z);}
    private FusionEscapeGeometry(){}
    private static boolean support(Kind k){return k==Kind.SOLID || k==Kind.GRAVITY;}
    /** Local steps stay within one level even if the final destination is much lower. */
    public static int[] standLevels(double sourceFeet,double goalFeet){
        int floor=(int)Math.ceil(sourceFeet-1e-4);
        int desired=Math.max(floor-1,Math.min((int)Math.floor(goalFeet),floor+1));
        return desired==floor?new int[]{floor}:new int[]{desired,floor};
    }
    /** Include the upright body before it drops into the destination column. */
    public static int passageTop(double sourceFeet,int standY){
        return Math.max(standY+1,(int)Math.ceil(sourceFeet+1.8-1e-6)-1);
    }
    /** A locally open column, bounded to eight blocks above the initial feet. */
    public static int surface(View view,int x,int z,double feet){
        int top=Math.min(252,(int)Math.ceil(feet)+8);
        boolean open=true;
        for(int y=top;y>=Math.max(0,(int)Math.floor(feet)-1);y--){
            Kind k=view.at(x,y,z);
            if(k!=Kind.EMPTY){
                if(open && y+2<=top && support(k))return y+1;
                return -1;
            }
        }
        return -1;
    }
    public static double surroundingSurface(View view,int x,int z,double feet){
        int best=Integer.MAX_VALUE;
        for(int r=1;r<=2;r++)for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}){
            int y=surface(view,x+d[0]*r,z+d[1]*r,feet);
            if(y>=feet+.5)best=Math.min(best,y);
        }
        return best==Integer.MAX_VALUE?Double.NaN:best;
    }
    /** Top-down body clearance; include any gravity blocks resting on the head cell. */
    public static List<Cell> clearance(View view,int x,int y,int z,int remaining){
        if(!support(view.at(x,y-1,z)))return null;
        int top=y+1;
        while(top<254 && view.at(x,top+1,z)==Kind.GRAVITY){
            if(top-y+1>=remaining)return null;
            top++;
        }
        List<Cell> cells=new ArrayList<>();
        for(int h=top;h>=y;h--){
            Kind k=view.at(x,h,z);
            if(k==Kind.EMPTY)continue;
            if(!support(k) || cells.size()>=remaining)return null;
            cells.add(new Cell(x,h,z));
        }
        return List.copyOf(cells);
    }
}
