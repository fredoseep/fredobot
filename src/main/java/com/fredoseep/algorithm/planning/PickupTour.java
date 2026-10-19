package com.fredoseep.algorithm.planning;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Open Euclidean tour: visit every item, with a fixed start and no return leg. */
public final class PickupTour {
    public record Point(int id,double x,double y,double z) {}
    public static final int EXACT_LIMIT=14;
    private PickupTour(){}
    public static double distance(Point a,Point b){return Math.sqrt(Math.pow(a.x-b.x,2)+Math.pow(a.y-b.y,2)+Math.pow(a.z-b.z,2));}
    public static List<Point> order(Point start,List<Point> input){
        if(input.isEmpty())return List.of();
        List<Point> points=new ArrayList<>(input);points.sort(Comparator.comparingInt(Point::id));int n=points.size();
        double[][] d=new double[n][n];double[] first=new double[n];
        for(int i=0;i<n;i++){first[i]=distance(start,points.get(i));for(int j=0;j<n;j++)d[i][j]=distance(points.get(i),points.get(j));}
        int[] route=n<=EXACT_LIMIT?exact(first,d):improve(first,d);
        List<Point> result=new ArrayList<>();for(int i:route)result.add(points.get(i));return List.copyOf(result);
    }
    private static int[] exact(double[] first,double[][] d){
        int n=first.length,masks=1<<n;double[] cost=new double[masks*n];byte[] previous=new byte[masks*n];
        Arrays.fill(cost,Double.POSITIVE_INFINITY);Arrays.fill(previous,(byte)-1);
        for(int i=0;i<n;i++)cost[(1<<i)*n+i]=first[i];
        for(int mask=1;mask<masks;mask++)for(int end=0;end<n;end++)if((mask&(1<<end))!=0){
            double base=cost[mask*n+end];if(!Double.isFinite(base))continue;
            for(int next=0;next<n;next++)if((mask&(1<<next))==0){int key=(mask|1<<next)*n+next;double candidate=base+d[end][next];
                if(candidate<cost[key]){cost[key]=candidate;previous[key]=(byte)end;}}
        }
        int mask=masks-1,end=0;for(int i=1;i<n;i++)if(cost[mask*n+i]<cost[mask*n+end])end=i;
        int[] route=new int[n];for(int at=n-1;at>=0;at--){route[at]=end;int old=previous[mask*n+end];mask^=1<<end;end=old;}return route;
    }
    private static int[] improve(double[] first,double[][] d){
        int n=first.length;Integer[] starts=new Integer[n];for(int i=0;i<n;i++)starts[i]=i;Arrays.sort(starts,Comparator.comparingDouble(i->first[i]));
        int[] best=null;double bestCost=Double.POSITIVE_INFINITY;
        for(int seed=0;seed<Math.min(8,n);seed++){
            int[] route=new int[n];boolean[] used=new boolean[n];route[0]=starts[seed];used[route[0]]=true;
            for(int at=1;at<n;at++){int next=-1;for(int i=0;i<n;i++)if(!used[i] && (next<0 || d[route[at-1]][i]<d[route[at-1]][next]))next=i;route[at]=next;used[next]=true;}
            for(int pass=0;pass<8;pass++){
                boolean changed=false;
                for(int a=0;a<n-1;a++)for(int b=a+1;b<n;b++){
                    double old=(a==0?first[route[a]]:d[route[a-1]][route[a]])+(b==n-1?0:d[route[b]][route[b+1]]);
                    double replacement=(a==0?first[route[b]]:d[route[a-1]][route[b]])+(b==n-1?0:d[route[a]][route[b+1]]);
                    if(replacement+1e-8<old){for(int l=a,r=b;l<r;l++,r--){int swap=route[l];route[l]=route[r];route[r]=swap;}changed=true;}
                }
                if(!changed)break;
            }
            double cost=first[route[0]];for(int i=1;i<n;i++)cost+=d[route[i-1]][route[i]];
            if(cost<bestCost){bestCost=cost;best=route;}
        }
        return best;
    }
}
