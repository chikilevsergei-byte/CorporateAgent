package corpagent.files;

import corpagent.db.SqliteHelper;
import java.util.*;

/** Exports factual database values; missing business fields never become invented budgets/goals. */
public final class ProjectDocumentData {
    private ProjectDocumentData(){}
    private static List<Map<String,String>> rows(String path,String table){
        List<Map<String,String>> out=new ArrayList<>();if(!SqliteHelper.getTables(path).contains(table))return out;
        var result=SqliteHelper.executeQuery(path,"SELECT * FROM "+corpagent.util.Utils.quoteIdent(table));if(!result.success)return out;
        for(var row:result.rows){Map<String,String> item=new LinkedHashMap<>();for(int i=0;i<result.columns.size();i++)item.put(result.columns.get(i),row.get(i)==null?"":String.valueOf(row.get(i)));out.add(item);}return out;
    }
    private static String combine(List<Map<String,String>> rows,String... columns){List<String> text=new ArrayList<>();for(var row:rows){List<String> values=new ArrayList<>();for(String col:columns){String v=row.getOrDefault(col,"");if(!v.isBlank())values.add(col+": "+v);}if(!values.isEmpty())text.add(String.join(", ",values));}return text.isEmpty()?"Нет данных":String.join(" | ",text);}
    private static String csv(String value){return '"'+value.replace("\"","\"\"").replace('\n',' ').replace('\r',' ')+'"';}
    public static String passport(String path){
        List<Map<String,String>> projects=rows(path,"project");if(projects.isEmpty())return null;Map<String,String> p=projects.get(0),fields=new LinkedHashMap<>();
        fields.put("project_name",p.getOrDefault("name","Нет данных"));fields.put("reasons",combine(rows(path,"problem_statement"),"situation","complication","question","answer"));fields.put("strategy",p.getOrDefault("strategy","Нет данных"));fields.put("product",p.getOrDefault("description","Нет данных"));
        fields.put("financial_kpi","Нет данных");fields.put("nonfinancial_kpi",combine(rows(path,"goal_smart"),"goal","specific","measurable","achievable","relevant","time_bound"));fields.put("budget",p.getOrDefault("budget","Нет данных"));fields.put("dates",combine(projects,"start_date","end_date"));fields.put("team",combine(rows(path,"team_member"),"name","role","responsibility"));
        StringBuilder text=new StringBuilder();for(var entry:fields.entrySet())text.append(entry.getKey()).append(';').append(csv(entry.getValue().isBlank()?"Нет данных":entry.getValue())).append('\n');return text.toString();
    }
    public static String risks(String path){
        if(!SqliteHelper.getTables(path).contains("risks"))return null;String[] keys={"id","date","title","description","category","probability","impact","priority","owner","status","mitigation","due_date"};StringBuilder text=new StringBuilder(String.join(";",keys)+"\n");
        for(var risk:rows(path,"risks")){for(int i=0;i<keys.length;i++){if(i>0)text.append(';');String key=keys[i];text.append(csv(risk.getOrDefault(key,key.equals("date")?risk.getOrDefault("updated",""):"")));}text.append('\n');}return text.toString();
    }
    public static String literalTable(String message,String files){
        if(files!=null&&!files.isBlank())return null;
        int line=message.indexOf('\n');if(line<0||!message.substring(line+1).contains(";"))return null;
        String header=message.substring(0,line);int colon=header.lastIndexOf(':');if(colon>=0)header=header.substring(colon+1).trim();if(!header.contains(";"))return null;return header+message.substring(line);
    }
}
