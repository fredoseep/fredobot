package com.fredoseep.testing.combat;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Experimental flat-ground zombie policy. All estimates are heuristic, not a vanilla simulator. */
public final class PredictiveThreatPolicy {
    private PredictiveThreatPolicy() {}
    public static final double LATENCY_MARGIN=3, CONTACT_DISTANCE=2, AXE_CHARGE_TICKS=22.23;
    public static final int MIN_GAP=10;
    public record Threat(UUID id,double distance,double closingSpeed,float health,long sinceDamage) {}
    public record Decision(UUID id,String reason,double contactTicks,double remainingCooldown,double damageEstimate) {}
    public static double contactTicks(Threat t) {
        // Measured displacement is used by the caller; a receding mob may turn around shortly.
        double delay=t.closingSpeed()<-.005?4:0;
        double speed=t.closingSpeed()<-.005?.115:Math.max(.10,t.closingSpeed());
        return delay+Math.max(0,t.distance()-CONTACT_DISTANCE)/speed;
    }
    public static double damageEstimate(float cooldown) {
        double c=Math.max(0,Math.min(1,cooldown));
        // Conservative fit to measured unarmored adult-zombie iron-axe samples; not a damage API.
        return 8.85*(.1+.9*c*c);
    }
    /** Empirical iron-axe/zombie estimate used by these fixtures; not a universal mob damage formula. */
    public static boolean canFinishNormally(float health){return health>0 && health+.2<=damageEstimate(1);}
    public static boolean waitForNormalFinish(float health,float cooldown,double earliestContact){
        return canFinishNormally(health) && cooldown<.99 && earliestContact>Math.max(0,1-cooldown)*AXE_CHARGE_TICKS+7;
    }
    public static Decision choose(List<Threat> threats,float cooldown,long sinceAttack) {
        List<Threat> inRange=threats.stream().filter(t->t.health()>0 && t.distance()<=5 && t.sinceDamage()>=10).toList();
        if(inRange.isEmpty())return null;
        double remaining=Math.max(0,1-cooldown)*AXE_CHARGE_TICKS;
        boolean ready=cooldown>=.99;
        if(!ready && sinceAttack<MIN_GAP)return null;
        Threat urgent=inRange.stream().min(Comparator.comparingDouble(PredictiveThreatPolicy::contactTicks)
                .thenComparingDouble(Threat::distance)).orElseThrow();
        double estimate=damageEstimate(cooldown);
        Threat finish=inRange.stream().filter(t->t.health()+.2<=estimate)
                .filter(t->threats.stream().filter(o->!o.id().equals(t.id()) && o.health()>0)
                        .allMatch(o->contactTicks(o)>MIN_GAP+LATENCY_MARGIN+2))
                .min(Comparator.comparingDouble(Threat::health).thenComparingDouble(PredictiveThreatPolicy::contactTicks)).orElse(null);
        if(finish!=null)return new Decision(finish.id(),"SAFE_FINISH",contactTicks(finish),remaining,estimate);
        if(ready)return new Decision(urgent.id(),"CHARGED_THREAT",contactTicks(urgent),remaining,estimate);
        if(contactTicks(urgent)<=remaining+LATENCY_MARGIN+4)
            return new Decision(urgent.id(),"PREDICTED_CONTACT_BEFORE_READY",contactTicks(urgent),remaining,estimate);
        return null;
    }
}
