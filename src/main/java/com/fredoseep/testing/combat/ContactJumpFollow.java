package com.fredoseep.testing.combat;

import java.util.Map;

/** Test-only, one ordinary jump-input pulse per eligible pull attack. */
public final class ContactJumpFollow {
    public enum Mode { NONE, IMMEDIATE, CONFIRMED }
    private final Mode mode;
    private int deadline=-1,requests,pulses,takeoffs;
    private long attackTick=-1,lastTick=-1;
    private float healthBefore;
    private boolean pending,confirmed,wasGrounded=true,waitingTakeoff;
    public ContactJumpFollow(Mode mode){this.mode=mode;}
    public void attackSent(long tick,int age,float health){
        if(mode==Mode.NONE)return;
        attackTick=tick;deadline=age+12;healthBefore=health;pending=true;confirmed=false;requests++;
    }
    public void observe(long tick,float health,double heightDifference,double horizontalDistance){
        if(tick<=lastTick)return;lastTick=tick;
        if(pending && tick>attackTick && health<healthBefore-.1f && heightDifference>.2 && horizontalDistance<1.3)confirmed=true;
    }
    public boolean input(int age,boolean grounded,boolean fresh,boolean headroom){
        if(waitingTakeoff && wasGrounded && !grounded){takeoffs++;waitingTakeoff=false;}
        wasGrounded=grounded;
        if(age>deadline){pending=false;waitingTakeoff=false;}
        if(!pending || !fresh || !grounded || !headroom || mode==Mode.CONFIRMED && !confirmed)return false;
        pending=false;pulses++;waitingTakeoff=true;return true;
    }
    public Map<String,Object> summary(){return Map.of("mode",mode.name(),"requests",requests,"pulses",pulses,"takeoffs",takeoffs,"pending",pending);}
}
