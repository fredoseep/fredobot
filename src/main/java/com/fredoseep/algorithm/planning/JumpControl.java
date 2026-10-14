package com.fredoseep.algorithm.planning;

/** Velocity-aware landing control; deliberately does not alter the player's velocity directly. */
public final class JumpControl {
    private JumpControl() {}
    public record Input(boolean forward,boolean back,boolean jump,boolean sprint) {}
    public static Input approach(double distance,double closingSpeed,double yawError) {
        boolean aligned=Math.abs(yawError)<=8;
        boolean brake=aligned && closingSpeed>.06 && distance<=Math.max(.245,closingSpeed*2);
        return new Input(aligned && !brake && distance>.18,brake,false,false);
    }
    public static Input step(double distance,double closingSpeed,boolean grounded,boolean landingHeight,boolean launched,boolean gap) {
        boolean jump=grounded && !launched;
        boolean brake=!grounded && landingHeight && closingSpeed>.05 && distance<=Math.max(.22,closingSpeed*4);
        boolean forward=!brake && distance>.12;
        return new Input(forward,brake,jump,gap && jump && distance>1.2);
    }
    /** Per-node lifecycle: a key request is not proof of take-off. */
    public static final class Attempt {
        private boolean requested,airborne,turning;
        private int stable,pending,cooldown,failures,requests,age,missedTakeoffs,lowLandings;
        private double lastError=Double.NaN,closing,lateral;
        private String phase="WAIT_GROUND";
        public boolean failed(){return failures>=3;}
        public boolean turningProgress(){return turning && age<=45;}
        public java.util.Map<String,Object> diagnostic(){return java.util.Map.ofEntries(
                java.util.Map.entry("phase",phase),java.util.Map.entry("requests",requests),
                java.util.Map.entry("airborne_confirmed",airborne),java.util.Map.entry("failures",failures),
                java.util.Map.entry("missed_takeoffs",missedTakeoffs),java.util.Map.entry("low_landings",lowLandings),
                java.util.Map.entry("stable_ticks",stable),java.util.Map.entry("pending_ticks",pending),
                java.util.Map.entry("node_ticks",age),java.util.Map.entry("yaw_error",Double.isFinite(lastError)?lastError:0),
                java.util.Map.entry("closing_speed",closing),java.util.Map.entry("lateral_speed",lateral));}
        public Input tick(double distance,double closingSpeed,double lateralSpeed,boolean grounded,
                boolean landingHeight,double yawError,boolean gap) {
            age++;closing=closingSpeed;lateral=Math.abs(lateralSpeed);
            double error=Math.abs(yawError);
            turning=!requested && grounded && error>8 && Double.isFinite(lastError) && error<lastError-.5;
            lastError=error;
            if(failed()){phase="FAILED";return new Input(false,false,false,false);}
            if(!grounded) {
                if(requested){airborne=true;phase="AIRBORNE";}
                else {phase="WAIT_GROUND";stable=0;}
                Input landing=step(distance,closingSpeed,false,landingHeight,true,gap);
                return new Input(requested && landing.forward(),landing.back(),false,false);
            }
            if(landingHeight) {
                phase="LANDED";turning=false;
                return new Input(error<=8 && distance>.12,false,false,false);
            }
            if(requested) {
                if(airborne || ++pending>=4) {
                    if(airborne)lowLandings++;else missedTakeoffs++;
                    failures++;requested=false;airborne=false;stable=0;cooldown=2;phase="RETRY_RELEASE";
                    if(failed()){phase="FAILED";return new Input(false,false,false,false);}
                } else {
                    phase="WAIT_TAKEOFF";return new Input(error<=8,false,false,false);
                }
            }
            if(cooldown>0){cooldown--;return new Input(false,false,false,false);}
            if(error>8 || lateral>.04 || Math.abs(closingSpeed)>.14) {
                stable=0;phase="ALIGN_BRAKE";
                return new Input(false,error<=8 && closingSpeed>.14,false,false);
            }
            if(++stable<2){phase="STABILIZE";return new Input(false,false,false,false);}
            requested=true;pending=0;requests++;phase="REQUEST_TAKEOFF";
            return new Input(true,false,true,gap && distance>1.2);
        }
    }
}
