package corpagent;
import corpagent.data.DataStore;
import corpagent.util.Json;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Repairs interrupted first-run presets and preserves existing SQLite state. */
public final class PresetRecoveryTest {
    public static void main(String[] args)throws Exception{
        Path base=Files.createTempDirectory("ca54-preset-recovery-");
        DataStore first=new DataStore(base.toString());
        if(first.getDatabases().size()!=2)throw new AssertionError("Expected built-in databases");
        Map<String,Object> db=Json.asMap(first.getDatabases().values().iterator().next());Path path=Paths.get(Json.str(db,"path"));
        if(!Files.exists(path)||Files.size(path)==0)throw new AssertionError("Preset not created");
        Files.move(path,base.resolve("preserved-first-preset.db"));
        new DataStore(base.toString());
        if(!Files.exists(path)||corpagent.db.SqliteHelper.getTables(path.toString()).isEmpty())throw new AssertionError("Missing preset was not rebuilt");
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+path);Statement s=c.createStatement()){s.execute("CREATE TABLE preserve_me(value TEXT)");s.execute("INSERT INTO preserve_me VALUES('synthetic-preserved')");}
        new DataStore(base.toString());
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+path);Statement s=c.createStatement();ResultSet rs=s.executeQuery("SELECT value FROM preserve_me")){if(!rs.next()||!"synthetic-preserved".equals(rs.getString(1)))throw new AssertionError("Existing database overwritten");}
        if(!Files.exists(base.resolve("preserved-first-preset.db")))throw new AssertionError("Original test database removed");
        System.out.println("PresetRecoveryTest OK (5 checks)");
    }
}
