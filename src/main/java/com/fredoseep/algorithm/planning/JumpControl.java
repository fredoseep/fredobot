package com.fredoseep.algorithm.planning;

/** Velocity-aware landing control; deliberately does not alter the player's velocity directly. */
public final class JumpControl {
    private JumpControl() {}
    public record Input(boolean forward,boolean back,boolean jump,boolean sprint) {}
    public static Input step(double distance,double closingSpeed,boolean grounded,boolean landingHeight,boolean launched,boolean gap) {
        boolean jump=grounded && !launched;
        boolean brake=!grounded && landingHeight && closingSpeed>.05 && distance<=Math.max(.22,closingSpeed*4);
        boolean forward=!brake && distance>.12;
        return new Input(forward,brake,jump,gap && jump && distance>1.2);
    }
}
