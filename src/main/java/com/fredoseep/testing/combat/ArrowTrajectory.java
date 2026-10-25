package com.fredoseep.testing.combat;

/** Pure dry-air arrow kernel and moving-body slab intersection. No world/entity mutation. */
public final class ArrowTrajectory {
    private ArrowTrajectory(){}
    public record Point(double x,double y,double z){
        Point add(Point p){return new Point(x+p.x,y+p.y,z+p.z);}
        Point subtract(Point p){return new Point(x-p.x,y-p.y,z-p.z);}
    }
    public record Flight(Point position,Point velocity,boolean gravity){
        public Flight next(){return new Flight(position.add(velocity),new Point(velocity.x*.99,velocity.y*.99-(gravity?.05:0),velocity.z*.99),gravity);}
    }
    public record Body(double minX,double minY,double minZ,double maxX,double maxY,double maxZ){
        public Body expand(double n){return new Body(minX-n,minY-n,minZ-n,maxX+n,maxY+n,maxZ+n);}
    }
    /** Returns first intersecting fraction in [0,1], or +infinity. Also detects starts inside. */
    public static double intersection(Point from,Point to,Body b){
        double[] a={from.x,from.y,from.z},d={to.x-from.x,to.y-from.y,to.z-from.z};
        double[] lo={b.minX,b.minY,b.minZ},hi={b.maxX,b.maxY,b.maxZ};double enter=0,exit=1;
        for(int i=0;i<3;i++){
            if(Math.abs(d[i])<1e-12){if(a[i]<lo[i] || a[i]>hi[i])return Double.POSITIVE_INFINITY;}
            else {double u=(lo[i]-a[i])/d[i],v=(hi[i]-a[i])/d[i];enter=Math.max(enter,Math.min(u,v));exit=Math.min(exit,Math.max(u,v));if(enter>exit)return Double.POSITIVE_INFINITY;}
        }
        return enter;
    }
    /** Relative motion, with conservative envelope for any body dimension changes. */
    public static double movingHit(Point from,Point to,Body before,Body after,double margin){
        Point delta=new Point((after.minX+after.maxX-before.minX-before.maxX)*.5,
                after.minY-before.minY,(after.minZ+after.maxZ-before.minZ-before.maxZ)*.5);
        Body envelope=new Body(Math.min(before.minX,after.minX-delta.x),Math.min(before.minY,after.minY-delta.y),Math.min(before.minZ,after.minZ-delta.z),
                Math.max(before.maxX,after.maxX-delta.x),Math.max(before.maxY,after.maxY-delta.y),Math.max(before.maxZ,after.maxZ-delta.z));
        return intersection(from,to.subtract(delta),envelope.expand(margin));
    }
}
