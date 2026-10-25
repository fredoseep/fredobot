package com.fredoseep.testing.combat;

import java.util.Map;
import java.util.List;
import java.util.LinkedHashMap;

/** Test-only horizontal input servo. Writes input strength, never position or velocity.
 * Acceleration estimates assume this experiment's unbuffed walking player on flat ground. */
public final class ContactFollowServo {
    public record Command(double x,double z) {}
    private final boolean impulseAware;
    private long tick=-1,attackTick=-100;
    private int impulseFrames,attackSignals;
    private boolean intercepting;
    public ContactFollowServo(){this(false);}
    public ContactFollowServo(boolean impulseAware){this.impulseAware=impulseAware;}
    /** Marks a sent attack, not a confirmed hit. Live observations still determine movement. */
    public void attackSent(long serverTick){attackTick=serverTick;attackSignals++;}

    private int observedAge,commandAge,frames,brakingFrames,reversals;
    private double targetX,targetZ,targetVX,targetVZ,errorSum,maxError,lastX,lastZ;
    private Command last=new Command(0,0);
    public boolean observe(long serverTick,int clientAge,double x,double z){
        if(serverTick<=tick)return false;
        if(tick>=0){double dt=serverTick-tick;targetVX=(x-targetX)/dt;targetVZ=(z-targetZ)/dt;
            double speed=Math.hypot(targetVX,targetVZ);if(speed>.6){targetVX*=.6/speed;targetVZ*=.6/speed;}}
        targetX=x;targetZ=z;tick=serverTick;observedAge=clientAge;return true;
    }
    public int validUntilAge(){return observedAge+4;}
    public Command command(int age,double px,double pz,double vx,double vz,boolean grounded){
        commandAge=age;
        if(tick<0 || age>validUntilAge()){last=new Command(0,0);return last;}
        double lead=Math.min(3,Math.max(0,age-observedAge)+1);
        double ex=targetX+targetVX*lead-px,ez=targetZ+targetVZ*lead-pz;
        double desiredX=targetVX+.25*ex,desiredZ=targetVZ+.25*ez;
        intercepting=false;
        if(impulseAware && tick>=attackTick && tick-attackTick<=16){
            // A pulled airborne mob is approaching, not choosing a persistent walking velocity.
            // Aim at a short decaying-flight interception point; do not flee its incoming velocity.
            double separation=Math.hypot(targetX-px,targetZ-pz);
            double closing=(targetX-px)*targetVX+(targetZ-pz)*targetVZ;
            if(separation>.35 && closing<-.01){
                double flightLead=1+(.91)*(lead-1);
                ex=targetX+targetVX*flightLead-px;ez=targetZ+targetVZ*flightLead-pz;
                desiredX=.55*ex;desiredZ=.55*ez;intercepting=true;impulseFrames++;
            }
        }
        double speed=Math.hypot(desiredX,desiredZ);if(speed>.215){desiredX*=.215/speed;desiredZ*=.215/speed;}
        // Client velocity is the carried velocity before the next movement input accelerates it.
        double acceleration=grounded?.098:.02;
        double x=(desiredX-vx)/acceleration,z=(desiredZ-vz)/acceleration;
        double input=Math.hypot(x,z);if(input>1){x/=input;z/=input;}
        if(x*vx+z*vz<-.001)brakingFrames++;
        if(x*lastX+z*lastZ<-.1)reversals++;
        lastX=x;lastZ=z;last=new Command(x,z);frames++;
        double error=Math.hypot(ex,ez);errorSum+=error;maxError=Math.max(maxError,error);return last;
    }
    public Map<String,Object> summary(){
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("sourceTick",tick);out.put("commandAge",commandAge);out.put("frames",frames);
        out.put("targetVelocity",List.of(targetVX,targetVZ));out.put("lastInput",List.of(last.x(),last.z()));
        out.put("meanPredictedError",frames==0?0:errorSum/frames);out.put("maxPredictedError",maxError);
        out.put("brakingFrames",brakingFrames);out.put("inputReversals",reversals);
        out.put("impulse",Map.of("enabled",impulseAware,"attackSignals",attackSignals,"interceptFrames",impulseFrames,"intercepting",intercepting));
        return out;
    }
}
