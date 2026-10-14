package com.fredoseep.utils.bt;

public final class TntTriggerPolicy {
    private TntTriggerPolicy() {}
    public enum Trigger { EXISTING_LIGHTER,CRAFT_LIGHTER,IRON_PLATE,GOLD_PLATE,BUTTON }
    public static Trigger choose(boolean lighter,int flint,int spareIron,boolean ironPlate,boolean goldPlate) {
        if(lighter)return Trigger.EXISTING_LIGHTER;
        if(flint>0 && spareIron>0)return Trigger.CRAFT_LIGHTER;
        if(ironPlate)return Trigger.IRON_PLATE;
        return goldPlate?Trigger.GOLD_PLATE:Trigger.BUTTON;
    }
}
