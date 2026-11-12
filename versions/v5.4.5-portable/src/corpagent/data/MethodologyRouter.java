package corpagent.data;

import corpagent.util.Json;
import corpagent.db.SqliteHelper;
import java.util.*;

/** Resolves reference sources independently from the writable project database. */
public final class MethodologyRouter {
    private MethodologyRouter(){}
    public static List<String> sources(String message,DataStore store){
        String m=message==null?"":message.toLowerCase(Locale.ROOT);List<String> names=new ArrayList<>();
        boolean it=java.util.regex.Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])(?:it|ит)(?![\\p{L}\\p{N}])").matcher(m).find()&&m.contains("методолог")||m.contains("itil")||m.contains("scrum")||m.contains("agile");
        boolean pm=m.contains("lpg")||m.matches("(?s).*(?:проектн[а-яё]*\\s+управлен|управлен[а-яё]*\\s+проект).*" )||(m.contains("методолог")&&m.contains("проект")&&!it);
        if(it&&store.getDatabase("Методология ИТ проектов")!=null)names.add("Методология ИТ проектов");
        if(pm&&store.getDatabase("Методология LPG")!=null)names.add("Методология LPG");
        return names;
    }
    public static boolean empty(DataStore store,String name){
        Map<String,Object> info=store.getDatabase(name);if(info==null||!Json.bool(info,"enabled",true))return true;
        String path=Json.str(info,"path","");if(!new java.io.File(path).isFile())return true;
        for(String table:SqliteHelper.getTables(path)){var rows=SqliteHelper.executeQuery(path,"SELECT 1 FROM "+corpagent.util.Utils.quoteIdent(table)+" LIMIT 1");if(rows.success&&!rows.rows.isEmpty())return false;}
        return true;
    }
}
