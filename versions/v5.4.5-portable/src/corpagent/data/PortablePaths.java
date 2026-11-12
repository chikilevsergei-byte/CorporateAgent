package corpagent.data;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Rebase only persisted path fields inside the portable data directory. */
final class PortablePaths {
    private static final Set<String> KEYS=Set.of("path","file","file_path","filePath");
    @SafeVarargs
    static boolean rebase(String base, Map<String,Object>... maps) {
        Path directory=Path.of(base).toAbsolutePath().normalize();
        Path marker=directory.resolve(".portable-location");
        try {
            if(!Files.exists(marker)) return false;
            String old=Files.readString(marker,StandardCharsets.UTF_8).trim().replace('\\','/');
            if(old.equals(directory.toString().replace('\\','/'))) return false;
            if(old.isEmpty()) throw new java.io.IOException("Empty portable location marker");
            // Preserve the original JSON before changing any paths.
            Path backup=directory.resolve("backups/portable-relocation-"+UUID.randomUUID());
            Files.createDirectories(backup);
            for(String name:List.of("chats","skills","tools","databases","db_logs","projects","notes")) {
                Path file=directory.resolve(name+".json");
                if(Files.isRegularFile(file)) Files.copy(file,backup.resolve(name+".json"));
            }
            Files.copy(marker,backup.resolve(".portable-location"));
            boolean changed=false;
            for(Map<String,Object> map:maps) changed=walk(map,old,directory)||changed;
            return changed;
        } catch(java.io.IOException e) { throw new IllegalStateException("Cannot relocate portable data",e); }
    }
    @SuppressWarnings("unchecked")
    private static boolean walk(Object node,String old,Path base) {
        boolean changed=false;
        if(node instanceof Map<?,?>) {
            Map<String,Object> map=(Map<String,Object>)node;
            for(var entry:map.entrySet()) {
                Object value=entry.getValue();
                if(KEYS.contains(entry.getKey()) && value instanceof String) {
                    String path=((String)value).replace('\\','/');
                    if(path.startsWith(old+"/")) {
                        Path target=base.resolve(path.substring(old.length()+1)).normalize();
                        if(!target.startsWith(base)) throw new IllegalStateException("Invalid portable path");
                        entry.setValue(target.toString()); changed=true;
                    }
                } else changed=walk(value,old,base)||changed;
            }
        } else if(node instanceof List<?>) for(Object item:(List<?>)node) changed=walk(item,old,base)||changed;
        return changed;
    }
    static void record(String base) {
        Path directory=Path.of(base).toAbsolutePath().normalize();
        try {
            Files.createDirectories(directory);
            Path temp=Files.createTempFile(directory,"portable-location-",".tmp");
            try {
                Files.writeString(temp,directory.toString(),StandardCharsets.UTF_8);
                try { Files.move(temp,directory.resolve(".portable-location"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
                catch(AtomicMoveNotSupportedException e) { Files.move(temp,directory.resolve(".portable-location"),StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(temp); }
        } catch(java.io.IOException e) { throw new IllegalStateException("Cannot record portable data location",e); }
    }
}
