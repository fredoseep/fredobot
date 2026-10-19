package com.fredoseep.motion;

import net.minecraft.client.input.Input;

/** Owns the movement input object so keyboard polling cannot overwrite a planned tick. */
public final class PlannedInput extends Input {
    private MotionInput frame;
    public void setFrame(MotionInput frame){this.frame=frame;}
    public void clear(){frame=null;tick(false);}
    public void copyHistory(Input source) {
        movementForward=source.movementForward;movementSideways=source.movementSideways;
        pressingForward=source.pressingForward;pressingBack=source.pressingBack;
        pressingLeft=source.pressingLeft;pressingRight=source.pressingRight;
        jumping=source.jumping;sneaking=source.sneaking;
    }
    @Override public void tick(boolean slowDown) {
        pressingForward=frame!=null && frame.forward();pressingBack=frame!=null && frame.back();
        pressingLeft=frame!=null && frame.left();pressingRight=frame!=null && frame.right();
        movementForward=(pressingForward?1:0)-(pressingBack?1:0);
        movementSideways=(pressingLeft?1:0)-(pressingRight?1:0);
        jumping=frame!=null && frame.jump();sneaking=frame!=null && frame.sneak();
        if(slowDown){movementForward*=.3F;movementSideways*=.3F;}
    }
}
