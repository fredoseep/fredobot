package com.fredoseep.motion;

import java.util.function.Consumer;

/** One goal, one primary attempt, and at most one fallback. Stale completions cannot restart work. */
public final class GroundFirstRouting {
    public enum Result { COMPLETE,FAILED,CANCELLED }
    public enum Phase { NEW,PRIMARY,FALLBACK,DONE,CANCELLED }
    @FunctionalInterface public interface Primary { boolean begin(Consumer<Result> completed); }
    private final Primary primary;
    private final Runnable complete,cancelled;
    private final Consumer<String> fallback;
    private Phase phase=Phase.NEW;
    public GroundFirstRouting(Primary primary,Runnable complete,Consumer<String> fallback,Runnable cancelled){
        this.primary=primary;this.complete=complete;this.fallback=fallback;this.cancelled=cancelled;
    }
    public Phase phase(){return phase;}
    public void start(boolean eligible,String unsupportedReason){
        if(phase!=Phase.NEW)return;
        if(!eligible){useFallback(unsupportedReason);return;}
        phase=Phase.PRIMARY;
        if(!primary.begin(this::finished) && phase==Phase.PRIMARY)useFallback("PRIMARY_BEGIN_REJECTED");
    }
    private void finished(Result result){
        if(phase!=Phase.PRIMARY)return;
        switch(result){
            case COMPLETE -> {phase=Phase.DONE;complete.run();}
            case FAILED -> useFallback("PRIMARY_FAILED");
            case CANCELLED -> cancel();
        }
    }
    private void useFallback(String reason){
        if(phase!=Phase.NEW && phase!=Phase.PRIMARY)return;
        phase=Phase.FALLBACK;fallback.accept(reason);
    }
    public void cancel(){
        if(phase!=Phase.NEW && phase!=Phase.PRIMARY)return;
        phase=Phase.CANCELLED;cancelled.run();
    }
}
