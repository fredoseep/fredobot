package com.fredoseep.motion;

/** Pure tick lifecycle. Wall time, paused client callbacks and duplicate HEAD hooks never advance it. */
public final class MotionSequence {
    public enum Phase {READY,EXECUTING,SUSPENDED,COMPLETE,FAILED,CANCELLED}
    public record Deviation(String boundary,int frame,MotionState expected,MotionState actual,
                            double positionError,double velocityError) {}
    private final MotionPlan plan;
    private Phase phase=Phase.READY;
    private int index;
    private boolean pending;
    private String reason="";
    private Deviation deviation;
    public MotionSequence(MotionPlan plan){this.plan=java.util.Objects.requireNonNull(plan);}
    public MotionPlan plan(){return plan;}
    public Phase phase(){return phase;}
    public int index(){return index;}
    public boolean ownsControl(){return phase==Phase.READY || phase==Phase.EXECUTING || phase==Phase.SUSPENDED;}
    public String reason(){return reason;}
    public Deviation deviation(){return deviation;}
    public MotionInput beforeTick(MotionState actual) {
        if(!ownsControl() || pending)return null;
        MotionState expected=index==0?plan.initial():plan.frames().get(index-1).after();
        if(!matches("before",expected,actual))return null;
        phase=Phase.EXECUTING;pending=true;
        return plan.frames().get(index).input();
    }
    public void afterTick(MotionState actual) {
        if(!pending || !ownsControl())return;
        pending=false;
        if(!matches("after",plan.frames().get(index).after(),actual))return;
        if(++index==plan.frames().size())phase=Phase.COMPLETE;
    }
    public void suspend(){if(ownsControl()){pending=false;phase=Phase.SUSPENDED;}}
    public void cancel(String reason) {
        if(ownsControl()){this.reason=reason;phase=Phase.CANCELLED;pending=false;}
    }
    private boolean matches(String boundary,MotionState expected,MotionState actual) {
        double pos=expected.positionError(actual),vel=expected.velocityError(actual);
        if(pos<=plan.positionTolerance() && vel<=plan.velocityTolerance() && expected.grounded()==actual.grounded())return true;
        deviation=new Deviation(boundary,index,expected,actual,pos,vel);
        reason="STATE_DIVERGED";phase=Phase.FAILED;pending=false;return false;
    }
}
