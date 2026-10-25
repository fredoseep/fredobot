package com.fredoseep.testing.combat;

import com.fredoseep.mixin.MotionClientAccess;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;

/** Optional ordinary jumps for the experiment. Attack decisions and emergency fallback stay separate. */
public final class PursuitCriticalInput {
    private Input saved;
    private PulseInput owned;
    private boolean autoJump;
    private long started=-1000;
    private int attempts;
    private boolean waiting;
    private static final class PulseInput extends Input {
        boolean pulse;
        @Override public void tick(boolean slowDown){
            movementForward=movementSideways=0;
            pressingForward=pressingBack=pressingLeft=pressingRight=sneaking=false;
            jumping=pulse;pulse=false;
        }
    }
    public static String choose(boolean waiting,long age,boolean ground,float fall,float cooldown,double distance,double contact){
        if(waiting && age<20 && !ground && fall<=.05 && contact>5)return "WAIT_FALL";
        if(waiting && age<3 && ground && contact>5)return "WAIT_FALL";
        if(!waiting && ground && age>=20 && cooldown>=.70 && distance<=5 && distance-.115*8>=2.8 && contact>12)return "JUMP";
        return "ATTACK_POLICY";
    }
    public String update(MinecraftClient client,long tick,boolean ground,float fall,float cooldown,double distance,double contact){
        return update(client,tick,ground,fall,cooldown,distance,contact,true);
    }
    public String update(MinecraftClient client,long tick,boolean ground,float fall,float cooldown,double distance,double contact,boolean usefulJump){
        if(owned==null){
            saved=client.player.input;owned=new PulseInput();client.player.input=owned;
            autoJump=client.options.autoJump;client.options.autoJump=false;attempts=0;waiting=false;started=-1000;
        }
        client.options.keySprint.setPressed(false);client.player.setSprinting(false);
        ((MotionClientAccess)(Object)client.player).fredobot$setSprintWindow(0);
        return updateWithPulse(tick,ground,fall,cooldown,distance,contact,usefulJump,()->owned.pulse=true);
    }
    /** Shared-input mode: the movement owner emits the pulse; this controller never replaces its Input. */
    public String updateWithPulse(long tick,boolean ground,float fall,float cooldown,double distance,double contact,
                                  boolean safeJump,Runnable pulse){
        long age=tick-started;
        if(waiting && (age>=20 || (age>=3 && ground) || fall>.05 || contact<=5))waiting=false;
        String action=choose(waiting,age,ground,fall,cooldown,distance,contact);
        if(action.equals("JUMP")){
            if(!safeJump)return "ATTACK_POLICY";
            pulse.run();waiting=true;started=tick;attempts++;
        }
        return action;
    }
    public boolean wouldJump(long tick,boolean ground,float fall,float cooldown,double distance,double contact){
        long age=tick-started;
        boolean stillWaiting=waiting && !(age>=20 || (age>=3 && ground) || fall>.05 || contact<=5);
        return choose(stillWaiting,age,ground,fall,cooldown,distance,contact).equals("JUMP");
    }
    public void reset(MinecraftClient client){stop(client);attempts=0;started=-1000;}
    public int attempts(){return attempts;}
    public void stop(MinecraftClient client){
        if(owned!=null){owned.pulse=false;owned.tick(false);
            if(client.player!=null && client.player.input==owned)client.player.input=saved;
            client.options.autoJump=autoJump;
        }
        saved=null;owned=null;waiting=false;
    }
}
