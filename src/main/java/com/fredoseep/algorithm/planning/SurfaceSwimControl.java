package com.fredoseep.algorithm.planning;

/** Surface crossing: immerse, release sneak to start sprint-swimming, then balance breathing. */
public final class SurfaceSwimControl {
    public enum Phase { IDLE,IMMERSING,STARTING,CRUISING,FLOATING }
    public record Keys(boolean sneak,boolean jump,boolean sprint,float pitch) {}
    private Phase phase=Phase.IDLE;
    private int immersionTicks,swimmingTicks,startupTicks;
    public Phase phase(){return phase;}
    public void reset(){phase=Phase.IDLE;immersionTicks=0;swimmingTicks=0;startupTicks=0;}
    public boolean starting(){return (phase==Phase.IMMERSING || phase==Phase.STARTING) && startupTicks<=60;}
    public Keys tick(boolean touchingWater,boolean deep,boolean swimming,boolean submerged,
                     boolean canSprint,boolean longCrossing,double riseError,double velocityY) {
        if(!touchingWater){reset();return new Keys(false,false,canSprint,0);}
        if(swimming && canSprint) {
            phase=Phase.CRUISING;immersionTicks=0;startupTicks=0;swimmingTicks++;
            boolean rise=riseError>.25 || (swimmingTicks>25 && submerged && velocityY<.03);
            return new Keys(false,rise,true,rise?-10:-1);
        }
        swimmingTicks=0;
        boolean starting=phase==Phase.IMMERSING || phase==Phase.STARTING;
        if((riseError>.25 && !starting) || !deep || !canSprint || (!longCrossing && !starting)) {
            phase=Phase.FLOATING;immersionTicks=0;
            return new Keys(false,riseError>.25 || submerged,canSprint,riseError>.25?-60:-1);
        }
        if(++startupTicks>60) {
            phase=Phase.FLOATING;return new Keys(false,submerged,true,-1);
        }
        if(!submerged && immersionTicks<40) {
            phase=Phase.IMMERSING;immersionTicks++;
            return new Keys(true,false,true,10);
        }
        phase=Phase.STARTING;
        // Sneak must be released to let sprint activate the swimming pose.
        return new Keys(false,false,true,10);
    }
}
