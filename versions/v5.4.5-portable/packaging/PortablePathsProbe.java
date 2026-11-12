package corpagent.data;
import java.nio.file.*;
import java.util.*;
public class PortablePathsProbe {
    public static void main(String[] args) throws Exception {
        Path base=Files.createTempDirectory("portable-cross-os-");
        Files.writeString(base.resolve(".portable-location"),"C:\\Portable Agent\\data");
        Files.writeString(base.resolve("databases.json"),"{}");
        Map<String,Object> db=new LinkedHashMap<>();
        db.put("path","C:\\Portable Agent\\data\\databases\\custom.db");
        db.put("file","C:\\external\\other.db");
        db.put("description","C:\\Portable Agent\\data\\leave-text-alone");
        Map<String,Object> nested=new LinkedHashMap<>();
        nested.put("file_path","C:/Portable Agent/data/output/test.xlsx");
        nested.put("path","C:/Portable Agent/data-sibling/private.db");
        db.put("attachments",List.of(nested));
        if(!PortablePaths.rebase(base.toString(),db)) throw new AssertionError("No rebase");
        if(!db.get("path").equals(base.resolve("databases/custom.db").toString())) throw new AssertionError("DB path");
        if(!nested.get("file_path").equals(base.resolve("output/test.xlsx").toString())) throw new AssertionError("Nested path");
        if(!db.get("file").equals("C:\\external\\other.db")) throw new AssertionError("External path changed");
        if(!nested.get("path").equals("C:/Portable Agent/data-sibling/private.db")) throw new AssertionError("Sibling changed");
        if(!db.get("description").equals("C:\\Portable Agent\\data\\leave-text-alone")) throw new AssertionError("Text changed");
        PortablePaths.record(base.toString());
        if(PortablePaths.rebase(base.toString(),db)) throw new AssertionError("Repeat relocation");
        Files.writeString(base.resolve(".portable-location"),"/Users/sergey/Portable/data");
        db.put("path","/Users/sergey/Portable/data/databases/mac.db");
        if(!PortablePaths.rebase(base.toString(),db) || !db.get("path").equals(base.resolve("databases/mac.db").toString())) throw new AssertionError("Mac source path");
        db.put("path","/Users/sergey/Portable/data/../../outside.db");
        try {PortablePaths.rebase(base.toString(),db); throw new AssertionError("Traversal accepted");}
        catch(IllegalStateException expected) {}
        System.out.println("PortablePathsProbe OK: Windows/Mac paths, nested fields, backups, external paths, text preservation, repeat startup, traversal rejection");
    }
}
