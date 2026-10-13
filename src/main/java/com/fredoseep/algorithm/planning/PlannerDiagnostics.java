package com.fredoseep.algorithm.planning;

import java.io.*;
import java.lang.management.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.zip.*;

/** Bounded asynchronous JSONL recorder. No Minecraft/live-world access, no synchronous disk writes by callers. */
public final class PlannerDiagnostics {
    public interface TerrainWriter { void write(Writer writer) throws Exception; }
    private final Path directory;
    private volatile Path archive;
    private final boolean keepNormal;
    private volatile boolean sessionError;
    private final Object exportLock=new Object();
    private final ArrayBlockingQueue<String> queue=new ArrayBlockingQueue<>(256);
    private final Object lifecycle=new Object();
    private volatile boolean finalized;
    private volatile long capturedCells,totalCells;
    private final AtomicBoolean closed=new AtomicBoolean();
    private final AtomicLong dropped=new AtomicLong();
    private final Consumer<Throwable> failureHandler;
    private final long started=System.nanoTime();
    private final Thread clientThread;
    private volatile Thread worker;
    private volatile String phase="REQUESTED",operation="not started",provider="",closeReason="";
    private volatile Object operationState;
    private volatile long operationStarted=started,lastProgress=started;
    private volatile Object searchReport=Map.of();
    private volatile Supplier<Object> runtimeContext=() -> Map.of();
    private volatile Function<Object,Object> describer=value -> value==null?null:value.getClass().getSimpleName();
    private long lastSample=started,lastCpu=-1,lastGcTime=gcTime(),lastGcCount=gcCount();
    private volatile long expanded;
    private long previousExpanded;
    private volatile String ioFailure;
    private volatile String stuckState;
    public PlannerDiagnostics(Path gameDirectory,long job,Thread clientThread,Consumer<Throwable> failureHandler) {
        this(gameDirectory,job,clientThread,failureHandler,true);
    }
    public PlannerDiagnostics(Path gameDirectory,long job,Thread clientThread,Consumer<Throwable> failureHandler,boolean keepNormal) {
        String name=LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))+"-job"+job+"-"+UUID.randomUUID().toString().substring(0,8);
        directory=gameDirectory.resolve("logs/fredobot-pathfinder").resolve(name);
        archive=directory.resolveSibling(name+".zip");
        this.clientThread=clientThread;this.failureHandler=failureHandler;this.keepNormal=keepNormal;
        DiagnosticRetention.ACTIVE.add(directory);
        Thread writer=new Thread(this::writeLoop,"fredobot-diagnostic-writer-"+job);writer.setDaemon(true);writer.start();
        event("session_start",Map.of("job",job,"java",System.getProperty("java.version"),"vm",System.getProperty("java.vm.name"),
                "processors",Runtime.getRuntime().availableProcessors(),"max_heap_bytes",Runtime.getRuntime().maxMemory(),"schema",1));
    }
    public Path directory(){return directory;}
    public Path archive(){return archive;}
    public boolean finalized(){return finalized;}
    public void capture(long captured,long total){if(captured!=capturedCells)lastProgress=System.nanoTime();capturedCells=captured;totalCells=total;}
    public void runtimeContext(Supplier<Object> context){runtimeContext=context;}
    public void describeStates(Function<Object,Object> describer){this.describer=describer;}
    public void attachWorker(Thread worker){this.worker=worker;}
    public void phase(String phase,String detail){if(phase.equals("NO_ROUTE"))sessionError=true;this.phase=phase;operation("phase: "+phase,"",null);event("phase",Map.of("phase",phase,"detail",detail));}
    public void operation(String operation,String provider,Object state){
        this.operationState=state;this.provider=provider;this.operation=operation;this.operationStarted=System.nanoTime();
    }
    public void progress(long expanded,Object report){this.expanded=expanded;searchReport=report;lastProgress=System.nanoTime();}
    public void event(String type,Object data){
        if(closed.get())return;
        String record=json(record(type,data));
        synchronized(lifecycle){if(closed.get())return;if(!queue.offer(record))dropped.incrementAndGet();}
    }
    private Map<String,Object> record(String type,Object data){
        Map<String,Object> record=new LinkedHashMap<>();record.put("elapsed_ms",(System.nanoTime()-started)/1_000_000);
        record.put("event",type);record.put("thread",Thread.currentThread().getName());record.put("data",data);return record;
    }
    /** Always sampled by the independent status thread, including during a hung provider/precompute operation. */
    public synchronized void sample(){
        if(closed.get())return;
        long now=System.nanoTime();double seconds=Math.max(.001,(now-lastSample)/1e9);
        Thread currentWorker=worker;long cpu=cpuTime(currentWorker),gc=gcTime(),collections=gcCount();
        Map<String,Object> data=new LinkedHashMap<>();
        data.put("runtime",runtimeContext.get());
        data.put("phase",phase);data.put("operation",operation);data.put("provider",provider);
        data.put("operation_age_ms",(now-operationStarted)/1_000_000);data.put("progress_age_ms",(now-lastProgress)/1_000_000);
        data.put("expanded_per_second",(expanded-previousExpanded)/seconds);
        data.put("worker_cpu_fraction",cpu<0 || lastCpu<0?null:(cpu-lastCpu)/(seconds*1e9));
        data.put("gc_ms_delta",gc-lastGcTime);data.put("gc_count_delta",collections-lastGcCount);
        Runtime runtime=Runtime.getRuntime();data.put("heap_used_bytes",runtime.totalMemory()-runtime.freeMemory());data.put("heap_max_bytes",runtime.maxMemory());
        data.put("captured_cells",capturedCells);data.put("total_cells",totalCells);
        data.put("dropped_events",dropped.get());data.put("io_failure",ioFailure);
        Object state=operationState;
        try{data.put("operation_state",describer.apply(state));}catch(RuntimeException error){data.put("state_description_error",error.toString());}
        data.put("search",searchReport);
        data.put("worker",threadInfo(currentWorker));data.put("client",threadInfo(clientThread));
        event("sample",data);
        lastSample=now;lastCpu=cpu;lastGcTime=gc;lastGcCount=collections;previousExpanded=expanded;
    }
    private static long cpuTime(Thread thread){
        try{ThreadMXBean bean=ManagementFactory.getThreadMXBean();return thread==null || !bean.isThreadCpuTimeSupported() || !bean.isThreadCpuTimeEnabled()?-1:bean.getThreadCpuTime(thread.getId());}
        catch(RuntimeException error){return -1;}
    }
    private static Object threadInfo(Thread thread){
        if(thread==null)return Map.of("state","NOT_STARTED");
        Map<String,Object> data=new LinkedHashMap<>();data.put("name",thread.getName());data.put("id",thread.getId());data.put("alive",thread.isAlive());
        try{
            ThreadInfo info=ManagementFactory.getThreadMXBean().getThreadInfo(thread.getId(),24);
            if(info==null){data.put("state","TERMINATED");return data;}
            data.put("state",info.getThreadState().toString());data.put("lock",info.getLockName());data.put("lock_owner",info.getLockOwnerName());
            data.put("blocked_count",info.getBlockedCount());data.put("waited_count",info.getWaitedCount());
            List<String> stack=new ArrayList<>();for(StackTraceElement element:info.getStackTrace())stack.add(element.toString());data.put("stack",stack);
        }catch(RuntimeException error){data.put("probe_error",error.toString());}
        return data;
    }
    private static long gcTime(){long n=0;for(var b:ManagementFactory.getGarbageCollectorMXBeans())n+=Math.max(0,b.getCollectionTime());return n;}
    private static long gcCount(){long n=0;for(var b:ManagementFactory.getGarbageCollectorMXBeans())n+=Math.max(0,b.getCollectionCount());return n;}
    public void error(Throwable error){sessionError=true;StringWriter stack=new StringWriter();error.printStackTrace(new PrintWriter(stack));event("error",Map.of("phase",phase,"operation",operation,"stack",stack.toString()));}
    /** One immutable client-captured state; writer emits a readable separate file before archiving. */
    public void stuckDump(Object state) {
        stuckState=json(state);sessionError=true;
        event("stuck_dump",Map.of("file","stuck.json","schema","fredobot-stuck-v1"));
    }
    /** Worker-only export; publishing the final filename atomically prevents incomplete snapshots entering the zip. */
    public void exportTerrain(TerrainWriter action) throws Exception {
        synchronized(exportLock){exportTerrainLocked(action);}
    }
    private void exportTerrainLocked(TerrainWriter action)throws Exception {
        if(closed.get())return;
        Path temporary=directory.resolve("terrain.json.gz.part"),target=directory.resolve("terrain.json.gz");
        event("terrain_export_start",Map.of("file",target.getFileName().toString()));
        try{
            Files.createDirectories(directory);
            try(Writer writer=new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(temporary)),StandardCharsets.UTF_8),65536)){action.write(writer);}
            if(Thread.currentThread().isInterrupted())throw new CancellationException();
            Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);
            event("terrain_export_done",Map.of("file",target.getFileName().toString(),"bytes",Files.size(target)));
        }catch(CancellationException error){throw error;}
        catch(Exception error){ioFailure=error.toString();event("terrain_export_error",Map.of("error",error.toString()));failureHandler.accept(error);}
    }
    public void close(String reason){synchronized(lifecycle){if(closed.get())return;closeReason=reason;
        if(reason.equals("failed"))sessionError=true;
        archive=directory.resolveSibling(directory.getFileName()+(sessionError?"-error.zip":"-normal.zip"));closed.set(true);}}
    private void writeLoop(){
        try{
            Files.createDirectories(directory);
            DiagnosticRetention.prune(directory.getParent(),keepNormal,50);
            try(BufferedWriter writer=Files.newBufferedWriter(directory.resolve("events.jsonl"),StandardCharsets.UTF_8)){
                while(!closed.get() || !queue.isEmpty()){
                    String line=queue.poll(500,TimeUnit.MILLISECONDS);
                    if(line!=null){writer.write(line);writer.newLine();int n=0;while(n++<64 && (line=queue.poll())!=null){writer.write(line);writer.newLine();}}
                    writer.flush();
                }
                writer.write(json(record("session_end",Map.of("reason",closeReason,"dropped_events",dropped.get()))));writer.newLine();
            }
            synchronized(exportLock) {
                if(stuckState!=null) Files.writeString(directory.resolve("stuck.json"),stuckState,StandardCharsets.UTF_8);
                if(sessionError || keepNormal)DiagnosticRetention.archive(directory,archive);
                DiagnosticRetention.removeSession(directory);
            }
            DiagnosticRetention.prune(directory.getParent(),keepNormal,50);
            finalized=true;
        }catch(Exception error){ioFailure=error.toString();failureHandler.accept(error);}
        finally{DiagnosticRetention.ACTIVE.remove(directory);}
    }
    /** Small dependency-free JSON encoder, also used by the snapshot exporter. */
    public static String json(Object value){StringBuilder out=new StringBuilder();append(out,value);return out.toString();}
    private static void append(StringBuilder out,Object value){
        if(value==null){out.append("null");return;}
        if(value instanceof Boolean){out.append(value);return;}
        if(value instanceof Number){double n=((Number)value).doubleValue();out.append(Double.isFinite(n)?value.toString():"null");return;}
        if(value instanceof Map<?,?> map){out.append('{');boolean first=true;for(var e:map.entrySet()){if(!first)out.append(',');first=false;append(out,String.valueOf(e.getKey()));out.append(':');append(out,e.getValue());}out.append('}');return;}
        if(value instanceof Iterable<?> list){out.append('[');boolean first=true;for(Object item:list){if(!first)out.append(',');first=false;append(out,item);}out.append(']');return;}
        if(value.getClass().isArray()){out.append('[');for(int i=0;i<java.lang.reflect.Array.getLength(value);i++){if(i>0)out.append(',');append(out,java.lang.reflect.Array.get(value,i));}out.append(']');return;}
        out.append('"');for(char c:String.valueOf(value).toCharArray())switch(c){
            case '"' -> out.append("\\\"");case '\\' -> out.append("\\\\");case '\n' -> out.append("\\n");case '\r' -> out.append("\\r");case '\t' -> out.append("\\t");
            default -> {if(c<32)out.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else out.append(c);}
        }out.append('"');
    }
}
