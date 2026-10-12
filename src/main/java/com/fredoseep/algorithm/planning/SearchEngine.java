package com.fredoseep.algorithm.planning;

import java.util.*;
import java.util.function.*;

/** Minecraft-independent A*. State equality MUST include all future-relevant resources/world edits. */
public final class SearchEngine<S, A> {
    public interface ActionProvider<S, A> { Iterable<Edge<S, A>> expand(S state); }
    public static final class Edge<S, A> {
        public final S next;
        public final A action;
        public final double ticks, penalty;
        public Edge(S next, A action, double ticks, double penalty) {
            if (!Double.isFinite(ticks) || ticks <= 0 || !Double.isFinite(penalty) || penalty < 0)
                throw new IllegalArgumentException("Edges need positive finite time and nonnegative penalty");
            this.next = Objects.requireNonNull(next); this.action = action;
            this.ticks = ticks; this.penalty = penalty;
        }
    }
    public interface Profiler<S,A> {
        default void operation(String name,String provider,S state) {}
        default void expanded(S state) {}
        default void accepted(S state,A action,boolean newState,double score,double heuristic) {}
        default void providerTime(String provider,long nanoseconds,int edges) {}
        default void progress(Progress<S> progress) {}
        default void incumbent(Result<S,A> route) {}
        default void finished(Status status,Metrics metrics) {}
    }
    private static final class Counters {
        long generated,accepted,improved,rejected,stale,pruned;
        int peakOpen=1,peakKnown=1;
    }
    public static final class Metrics {
        public final int expanded,open,known,peakOpen,peakKnown;
        public final long generated,accepted,improved,rejected,stale,pruned;
        public final double frontierScore;
        Metrics(Counters c,int expanded,int open,int known,double frontierScore) {
            this.expanded=expanded;this.open=open;this.known=known;peakOpen=c.peakOpen;peakKnown=c.peakKnown;
            generated=c.generated;accepted=c.accepted;improved=c.improved;rejected=c.rejected;stale=c.stale;pruned=c.pruned;this.frontierScore=frontierScore;
        }
        public Map<String,Object> asMap() {
            Map<String,Object> m=new LinkedHashMap<>();m.put("expanded",expanded);m.put("open",open);m.put("known",known);
            m.put("peak_open",peakOpen);m.put("peak_known",peakKnown);m.put("generated",generated);m.put("accepted_new",accepted);
            m.put("improved_existing",improved);m.put("rejected_by_cost",rejected);m.put("stale_pops",stale);m.put("pruned_by_complete_route",pruned);m.put("frontier_f",frontierScore);return m;
        }
    }
    public interface Observer<S> { void update(Progress<S> progress); }
    public static final class Progress<S> {
        public final S current;
        public final List<S> currentPath, closestPath;
        public final int expanded, open, known;
        public final double score, heuristic;
        public final Metrics metrics;
        public final List<S> bestCompletePath;
        public final double bestCompleteScore;
        Progress(S current, List<S> currentPath, List<S> closestPath, int expanded, int open, int known, double score, double heuristic,Metrics metrics,List<S> bestCompletePath,double bestCompleteScore) {
            this.current=current; this.currentPath=List.copyOf(currentPath); this.closestPath=List.copyOf(closestPath);
            this.expanded=expanded; this.open=open; this.known=known; this.score=score; this.heuristic=heuristic;this.metrics=metrics;
            this.bestCompletePath=List.copyOf(bestCompletePath);this.bestCompleteScore=bestCompleteScore;
        }
    }
    public enum Status { COMPLETE, EXHAUSTED, LIMIT, CANCELLED }
    public static final class Result<S, A> {
        public final Status status;
        public final List<Edge<S, A>> steps;
        public final double ticks, penalty;
        public final int expanded;
        public final boolean optimal;
        public final String termination;
        Result(Status status, List<Edge<S, A>> steps, double ticks, double penalty, int expanded) {
            this(status,steps,ticks,penalty,expanded,status==Status.COMPLETE,status.name());
        }
        Result(Status status,List<Edge<S,A>> steps,double ticks,double penalty,int expanded,boolean optimal,String termination) {
            this.status = status; this.steps = Collections.unmodifiableList(steps);
            this.ticks = ticks; this.penalty = penalty; this.expanded = expanded;
            this.optimal=optimal;this.termination=termination;
        }
    }
    /** A deadline/state guard may return only a COMPLETE incumbent, never a closest prefix as COMPLETE. */
    public static final class Policy<S,A> {
        public final double weight;
        public final long budgetMillis;
        public final int maxKnown;
        public final Result<S,A> incumbent;
        public Policy(double weight,long budgetMillis,int maxKnown,Result<S,A> incumbent) {
            if(!Double.isFinite(weight)||weight<1||budgetMillis<0||maxKnown<0)
                throw new IllegalArgumentException("Invalid search policy");
            this.weight=weight;this.budgetMillis=budgetMillis;this.maxKnown=maxKnown;this.incumbent=incumbent;
        }
    }
    private static final class Label<S, A> {
        final S state; final Label<S, A> parent; final Edge<S, A> edge;
        final double ticks, penalty, h;
        final long sequence;
        Label(S state, Label<S, A> parent, Edge<S, A> edge, double ticks, double penalty, double h, long sequence) {
            this.state = state; this.parent = parent; this.edge = edge;
            this.ticks = ticks; this.penalty = penalty; this.h = h; this.sequence = sequence;
        }
        double g() { return ticks + penalty; }
    }
    private final List<ActionProvider<S, A>> providers = new ArrayList<>();
    public SearchEngine<S, A> register(ActionProvider<S, A> provider) { providers.add(provider); return this; }

    /** Default policy is exact A*. With admissible h, optimal=true proves minimal ticks+penalty.
     * COMPLETE means the steps reach the goal, including when a bounded policy returns an incumbent. */
    public Result<S, A> search(S start, Predicate<S> goal, ToDoubleFunction<S> heuristic,
                                ToDoubleFunction<S> progress, int maxExpanded, BooleanSupplier cancelled) {
        return search(start, goal, heuristic, progress, maxExpanded, cancelled, null);
    }
    public Result<S, A> search(S start, Predicate<S> goal, ToDoubleFunction<S> heuristic,
                                ToDoubleFunction<S> progress, int maxExpanded, BooleanSupplier cancelled, Observer<S> observer) {
        return search(start,goal,heuristic,progress,maxExpanded,cancelled,observer,null);
    }
    public Result<S,A> search(S start,Predicate<S> goal,ToDoubleFunction<S> heuristic,ToDoubleFunction<S> progress,
                             int maxExpanded,BooleanSupplier cancelled,Observer<S> observer,Profiler<S,A> profiler) {
        return search(start,goal,heuristic,progress,maxExpanded,cancelled,observer,profiler,new Policy<>(1,0,0,null));
    }
    public Result<S,A> search(S start,Predicate<S> goal,ToDoubleFunction<S> heuristic,ToDoubleFunction<S> progress,
                             int maxExpanded,BooleanSupplier cancelled,Observer<S> observer,Profiler<S,A> profiler,Policy<S,A> policy) {
        Result<S,A> incumbent=policy.incumbent;
        if(incumbent!=null && (incumbent.status!=Status.COMPLETE || !goal.test(incumbent.steps.isEmpty()?start:incumbent.steps.get(incumbent.steps.size()-1).next)))
            throw new IllegalArgumentException("Incumbent must reach the actual goal");
        long searchBegan=System.nanoTime();
        Counters counters=new Counters();
        Comparator<Label<S, A>> order = Comparator.<Label<S, A>>comparingDouble(n -> n.g() + policy.weight*n.h)
                .thenComparingDouble(n -> n.h).thenComparingLong(n -> n.sequence);
        PriorityQueue<Label<S, A>> open = new PriorityQueue<>(order);
        Map<S, Double> best = new HashMap<>();
        long sequence = 0;
        Label<S, A> root = new Label<>(start, null, null, 0, 0, checked(heuristic.applyAsDouble(start)), sequence++);
        Label<S, A> closest = root;
        double closestDistance = progress.applyAsDouble(start);
        open.add(root); best.put(start, 0.0);
        int expanded = 0;
        long nextReport = 0;
        if(profiler!=null)profiler.accepted(start,null,true,0,root.h);
        if(profiler!=null && incumbent!=null)profiler.incumbent(incumbent);
        while(!open.isEmpty()) {
            if(cancelled.getAsBoolean())return finish(Status.CANCELLED,root,expanded,open,best,counters,profiler);
            if(policy.budgetMillis>0 && (System.nanoTime()-searchBegan)/1_000_000L>=policy.budgetMillis)
                return bounded(incumbent,"TIME_BUDGET",closest,expanded,open,best,counters,profiler,false);
            op(profiler,"queue.poll","",null);
            Label<S,A> current=open.poll();
            op(profiler,"best.get/current","",current.state);
            if(current.g()>best.getOrDefault(current.state,Double.POSITIVE_INFINITY)){counters.stale++;continue;}
            // Use the UNWEIGHTED admissible bound for pruning. Ordering alone may be weighted.
            if(incumbent!=null && current.g()+current.h>=incumbent.ticks+incumbent.penalty) {
                counters.pruned++;
                if(policy.weight==1)return bounded(incumbent,"OPTIMAL_BOUND",closest,expanded,open,best,counters,profiler,true);
                continue;
            }
            long now=System.nanoTime();
            if((observer!=null || profiler!=null) && now>=nextReport) {
                op(profiler,"publish progress","",current.state);
                Progress<S> report=new Progress<>(current.state,preview(current),preview(closest),expanded,open.size(),best.size(),
                        current.g(),current.h,new Metrics(counters,expanded,open.size(),best.size(),current.g()+current.h),
                        completePreview(start,incumbent),incumbent==null?Double.POSITIVE_INFINITY:incumbent.ticks+incumbent.penalty);
                if(profiler!=null)profiler.progress(report);
                if(observer!=null)observer.update(report);
                nextReport=now+250_000_000L;
            }
            op(profiler,"goal.test","",current.state);
            if(goal.test(current.state)) {
                incumbent=result(Status.COMPLETE,current,expanded);
                if(profiler!=null)profiler.incumbent(incumbent);
                if(policy.weight==1)return bounded(incumbent,"OPTIMAL_GOAL",closest,expanded,open,best,counters,profiler,true);
                continue;
            }
            if(maxExpanded>0 && expanded>=maxExpanded)return bounded(incumbent,"EXPANSION_LIMIT",closest,expanded,open,best,counters,profiler,false);
            expanded++;if(profiler!=null)profiler.expanded(current.state);
            for(ActionProvider<S,A> provider:providers) {
                String name=profiler==null?"":provider.getClass().getSimpleName();
                op(profiler,"provider.generate",name,current.state);
                long began=profiler==null?0:System.nanoTime();
                Iterable<Edge<S,A>> edges=provider.expand(current.state);
                if(profiler!=null)profiler.providerTime(name+".generate",System.nanoTime()-began,edges instanceof Collection<?> c?c.size():0);
                began=profiler==null?0:System.nanoTime();int evaluated=0;
                for(Edge<S,A> edge:edges) {
                    if(cancelled.getAsBoolean())return finish(Status.CANCELLED,root,expanded,open,best,counters,profiler);
                    counters.generated++;evaluated++;
                    double ticks=current.ticks+edge.ticks,penalty=current.penalty+edge.penalty,g=ticks+penalty;
                    double h=checked(heuristic.applyAsDouble(edge.next));
                    if(incumbent!=null && g+h>=incumbent.ticks+incumbent.penalty){counters.pruned++;continue;}
                    op(profiler,"best.get/neighbor",name,edge.next);
                    Double previous=best.get(edge.next);
                    if(previous!=null && g>=previous){counters.rejected++;continue;}
                    if(previous==null && policy.maxKnown>0 && best.size()>=policy.maxKnown) {
                        if(goal.test(edge.next)) {
                            incumbent=result(Status.COMPLETE,new Label<>(edge.next,current,edge,ticks,penalty,h,sequence++),expanded);
                            if(profiler!=null)profiler.incumbent(incumbent);
                        }
                        return bounded(incumbent,"STATE_LIMIT",closest,expanded,open,best,counters,profiler,false);
                    }
                    op(profiler,"best.put",name,edge.next);best.put(edge.next,g);
                    if(previous==null)counters.accepted++;else counters.improved++;
                    op(profiler,"heuristic",name,edge.next);
                    Label<S,A> next=new Label<>(edge.next,current,edge,ticks,penalty,h,sequence++);
                    op(profiler,"queue.add",name,edge.next);open.add(next);
                    counters.peakOpen=Math.max(counters.peakOpen,open.size());counters.peakKnown=Math.max(counters.peakKnown,best.size());
                    if(profiler!=null)profiler.accepted(edge.next,edge.action,previous==null,g,next.h);
                    if(policy.weight>1 && goal.test(edge.next)) {
                        incumbent=result(Status.COMPLETE,next,expanded);
                        if(profiler!=null)profiler.incumbent(incumbent);
                    }
                    double distance=progress.applyAsDouble(edge.next);
                    if(distance<closestDistance || (distance==closestDistance && g<closest.g())){closestDistance=distance;closest=next;}
                }
                if(profiler!=null)profiler.providerTime(name+".relax",System.nanoTime()-began,evaluated);
            }
        }
        if(incumbent!=null)return bounded(incumbent,"OPTIMAL_EXHAUSTED",closest,expanded,open,best,counters,profiler,true);
        return finish(Status.EXHAUSTED,closest,expanded,open,best,counters,profiler);
    }
    private Result<S,A> bounded(Result<S,A> incumbent,String reason,Label<S,A> closest,int expanded,
                               PriorityQueue<Label<S,A>> open,Map<S,Double> best,Counters counters,Profiler<S,A> profiler,boolean optimal) {
        if(incumbent==null) {
            Result<S,A> partial=finish(Status.LIMIT,closest,expanded,open,best,counters,profiler);
            return new Result<>(partial.status,partial.steps,partial.ticks,partial.penalty,expanded,false,reason);
        }
        op(profiler,"return complete incumbent",reason,incumbent.steps.isEmpty()?closest.state:incumbent.steps.get(incumbent.steps.size()-1).next);
        if(profiler!=null)profiler.finished(Status.COMPLETE,new Metrics(counters,expanded,open.size(),best.size(),open.isEmpty()?Double.POSITIVE_INFINITY:open.peek().g()+open.peek().h));
        return new Result<>(Status.COMPLETE,incumbent.steps,incumbent.ticks,incumbent.penalty,expanded,optimal,reason);
    }
    private List<S> completePreview(S start,Result<S,A> route) {
        if(route==null)return List.of();
        List<S> path=new ArrayList<>();path.add(start);
        for(var edge:route.steps){if(path.size()>=256)break;path.add(edge.next);}
        return path;
    }
    private void op(Profiler<S,A> profiler,String name,String provider,S state){if(profiler!=null)profiler.operation(name,provider,state);}
    private Result<S,A> finish(Status status,Label<S,A> end,int expanded,PriorityQueue<Label<S,A>> open,Map<S,Double> best,
                              Counters counters,Profiler<S,A> profiler) {
        op(profiler,"reconstruct",status.name(),end.state);
        Result<S,A> result=result(status,end,expanded);
        if(profiler!=null)profiler.finished(status,new Metrics(counters,expanded,open.size(),best.size(),open.isEmpty()?Double.POSITIVE_INFINITY:open.peek().g()+open.peek().h));
        return result;
    }
    private List<S> preview(Label<S,A> end) {
        LinkedList<S> path = new LinkedList<>();
        for (Label<S,A> n=end; n != null && path.size() < 256; n=n.parent) path.addFirst(n.state);
        return path;
    }
    private static double checked(double h) {
        if (!Double.isFinite(h) || h < 0) throw new IllegalArgumentException("Invalid heuristic");
        return h;
    }
    private Result<S, A> result(Status status, Label<S, A> end, int expanded) {
        LinkedList<Edge<S, A>> steps = new LinkedList<>();
        for (Label<S, A> n = end; n.parent != null; n = n.parent) steps.addFirst(n.edge);
        return new Result<>(status, steps, end.ticks, end.penalty, expanded);
    }
}
