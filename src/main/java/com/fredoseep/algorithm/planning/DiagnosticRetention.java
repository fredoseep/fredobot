package com.fredoseep.algorithm.planning;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.zip.*;

/** Manages only recognisable FredoBot session files; active captures are never removed. */
public final class DiagnosticRetention {
    private DiagnosticRetention() {}
    static final Set<Path> ACTIVE=ConcurrentHashMap.newKeySet();
    private static final Pattern STEM=Pattern.compile("\\d{8}-\\d{6}-\\d{3}-job\\d+-[0-9a-f]{8}");
    private static final Object LOCK=new Object();
    public static void prune(Path root,boolean keepNormal,int limit)throws IOException {
        synchronized(LOCK) {
            if(!Files.isDirectory(root))return;
            // Migrate older zip+directory pairs. Incomplete previous-run sessions count
            // as errors; do not lose useful crash diagnostics just because they lack an end.
            try(var entries=Files.list(root)) {
                for(Path p:entries.toList()) {
                    String stem=p.getFileName().toString();
                    if(!STEM.matcher(stem).matches() || !Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS) || ACTIVE.contains(p))continue;
                    Path events=p.resolve("events.jsonl");
                    String trace=Files.isRegularFile(events)?new String(Files.readAllBytes(events),java.nio.charset.StandardCharsets.UTF_8):"";
                    boolean error=isError(trace) || !trace.contains("\"event\":\"session_end\"");
                    Path old=root.resolve(stem+".zip"),target=root.resolve(stem+(error?"-error.zip":"-normal.zip"));
                    if(error || keepNormal) {
                        if(Files.isRegularFile(old))Files.move(old,target,StandardCopyOption.REPLACE_EXISTING);
                        else if(!Files.exists(target))archive(p,target);
                    } else {Files.deleteIfExists(old);Files.deleteIfExists(target);}
                    removeSession(p);
                }
            }
            // Legacy standalone archives are classified using their trace, not the name.
            try(var entries=Files.list(root)) {
                for(Path p:entries.toList()) {
                    String name=p.getFileName().toString();
                    if(!name.endsWith(".zip") || !STEM.matcher(name.substring(0,name.length()-4)).matches() || !Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))continue;
                    String stem=name.substring(0,name.length()-4);if(ACTIVE.contains(root.resolve(stem)))continue;
                    boolean error;
                    try(ZipFile zip=new ZipFile(p.toFile())) {
                        var e=zip.getEntry("events.jsonl");
                        String trace=e==null?"":new String(zip.getInputStream(e).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                        error=isError(trace) || !trace.contains("\"event\":\"session_end\"");
                    }catch(IOException malformed){error=true;}
                    if(error || keepNormal)Files.move(p,root.resolve(stem+(error?"-error.zip":"-normal.zip")),StandardCopyOption.REPLACE_EXISTING);
                    else Files.delete(p);
                }
            }
            trim(root,"-error.zip",limit);
            trim(root,"-normal.zip",keepNormal?limit:0);
        }
    }
    private static boolean isError(String trace) {
        return trace.contains("\"event\":\"error\"") || trace.contains("\"phase\":\"NO_ROUTE\"")
                || trace.contains("\"reason\":\"failed\"");
    }
    private static void trim(Path root,String suffix,int limit)throws IOException {
        try(var entries=Files.list(root)) {
            List<Path> files=entries.filter(p -> Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> {String n=p.getFileName().toString();return n.endsWith(suffix) && STEM.matcher(n.substring(0,n.length()-suffix.length())).matches();})
                    .sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
            for(int i=0;i<files.size()-limit;i++)Files.deleteIfExists(files.get(i));
        }
    }
    static void archive(Path directory,Path archive)throws IOException {
        Path temporary=archive.resolveSibling(archive.getFileName()+".part");
        try {
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(temporary))) {
            for(String name:List.of("events.jsonl","terrain.json.gz")) {
                Path file=directory.resolve(name);if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))continue;
                zip.putNextEntry(new ZipEntry(name));Files.copy(file,zip);zip.closeEntry();
            }
        }
        Files.move(temporary,archive,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(temporary);}
    }
    static void removeSession(Path directory)throws IOException {
        for(String name:List.of("events.jsonl","terrain.json.gz","terrain.json.gz.part"))Files.deleteIfExists(directory.resolve(name));
        // Unknown user files are preserved, including their containing directory.
        try(var entries=Files.list(directory)){if(entries.findAny().isEmpty())Files.deleteIfExists(directory);}
    }
}
