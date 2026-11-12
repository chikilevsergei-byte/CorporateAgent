package corpagent.util;

import corpagent.db.SqliteHelper;
import corpagent.llm.DeepSeekClient;
import java.util.*;
import java.sql.*;

/** Extracts a draft first, then writes a complete meeting/decision/task group atomically. */
public final class ProtocolWriter {
    private ProtocolWriter(){}
    public static Map<String,Object> extract(DeepSeekClient client,String source){
        String instruction="Извлеки факты из протокола. Верни только JSON-объект без Markdown: {\"meeting\":{\"title\":\"\",\"meeting_date\":\"YYYY-MM-DD или пусто\",\"participants\":\"\",\"summary\":\"\"},\"decisions\":[{\"decision\":\"\",\"owner\":\"\",\"due_date\":\"\"}],\"tasks\":[{\"title\":\"\",\"assignee\":\"\",\"due_date\":\"\",\"priority\":\"medium\"}]}. Каждое поручение — отдельный элемент tasks; не теряй ни одного поручения. Неизвестные сведения оставь пустыми. Сохраняй точные имена и идентификаторы из источника. Ничего не записывай и не придумывай.\nИсточник:\n"+source;
        for(int attempt=0;attempt<2;attempt++){
            Map<String,Object> message=Json.newObject();message.put("role","user");message.put("content",instruction+"\nТакже добавь массив questions с открытыми вопросами для обсуждения, если они есть; иначе пустой массив.");String text=client.callJson(List.of(message),"Ты извлекаешь структурированные факты из протокола встречи. Верни только JSON.");
            if(text.startsWith("❌"))throw new IllegalArgumentException(text);
            try{int a=text.indexOf('{'),b=text.lastIndexOf('}');if(a<0||b<a)continue;Map<String,Object> parsed=Json.parseObject(text.substring(a,b+1));if(Json.obj(parsed,"meeting")==null)continue;if(Json.arr(parsed,"tasks")==null||Json.arr(parsed,"decisions")==null)continue;if(Json.str(Json.obj(parsed,"meeting"),"title","").isBlank())continue;
                if(source.toLowerCase().contains("поручени")&&!source.toLowerCase().contains("без поручен")&&Json.arr(parsed,"tasks").isEmpty())continue;return parsed;
            }catch(Exception ignored){}
        }
        throw new IllegalArgumentException("Не удалось извлечь полный протокол. Запись в БД не выполнялась.");
    }
    public static String draft(Map<String,Object> data){Map<String,Object> meeting=Json.obj(data,"meeting");StringBuilder text=new StringBuilder("Встреча: "+Json.str(meeting,"title","")+"\nДата: "+Json.str(meeting,"meeting_date","")+"\nУчастники: "+Json.str(meeting,"participants","")+"\nРешения:\n");for(Object item:Json.arr(data,"decisions")){var decision=Json.asMap(item);text.append("- ").append(Json.str(decision,"decision",Json.str(decision,"text",""))).append('\n');}text.append("Поручения:\n");for(Object item:Json.arr(data,"tasks")){var task=Json.asMap(item);text.append("- ").append(Json.str(task,"title","")).append(" · ").append(Json.str(task,"assignee","")).append(" · ").append(Json.str(task,"due_date","")).append('\n');}if(Json.arr(data,"questions")!=null&&!Json.arr(data,"questions").isEmpty())text.append("Открытые вопросы:\n").append(String.join("\n",Json.arr(data,"questions").stream().map(String::valueOf).toList()));return text.toString();}
    public static long persist(String path,String projectId,Map<String,Object> data)throws Exception{
        try(Connection connection=SqliteHelper.open(path)){
            connection.setAutoCommit(false);
            try{
                Map<String,Object> meeting=Json.obj(data,"meeting");long id;
                try(var s=connection.prepareStatement("INSERT INTO meetings(project_id,title,meeting_date,participants,summary,created) VALUES(?,?,?,?,?,?)")){
                    s.setString(1,projectId==null?"1":projectId);s.setString(2,Json.str(meeting,"title",""));s.setString(3,Json.str(meeting,"meeting_date",""));s.setString(4,Json.str(meeting,"participants",""));String summary=Json.str(meeting,"summary","");if(Json.arr(data,"questions")!=null&&!Json.arr(data,"questions").isEmpty())summary+="\nОткрытые вопросы: "+String.join(" | ",Json.arr(data,"questions").stream().map(String::valueOf).toList());s.setString(5,summary);s.setString(6,Utils.nowIso());s.executeUpdate();
                }
                try(var s=connection.createStatement();var r=s.executeQuery("SELECT last_insert_rowid()")){r.next();id=r.getLong(1);}
                for(Object item:Json.arr(data,"decisions")){Map<String,Object> decision=Json.asMap(item);String value=Json.str(decision,"decision",Json.str(decision,"text",""));if(value.isBlank())throw new IllegalArgumentException("Пустое решение в протоколе");try(var s=connection.prepareStatement("INSERT INTO meeting_decisions(meeting_id,decision,owner,due_date) VALUES(?,?,?,?)")){s.setLong(1,id);s.setString(2,value);s.setString(3,Json.str(decision,"owner",""));s.setString(4,Json.str(decision,"due_date",""));s.executeUpdate();}}
                for(Object item:Json.arr(data,"tasks")){Map<String,Object> task=Json.asMap(item);String title=Json.str(task,"title","");if(title.isBlank())throw new IllegalArgumentException("Пустое поручение в протоколе");try(var s=connection.prepareStatement("INSERT INTO tasks(project_id,title,assignee,status,priority,due_date,linked_meeting,created) VALUES(?,?,?,'open',?,?,?,?)")){s.setString(1,projectId==null?"1":projectId);s.setString(2,title);s.setString(3,Json.str(task,"assignee",""));String priority=Json.str(task,"priority","medium");s.setString(4,List.of("low","medium","high").contains(priority)?priority:"medium");s.setString(5,Json.str(task,"due_date",""));s.setLong(6,id);s.setString(7,Utils.nowIso());s.executeUpdate();}}
                corpagent.util.TaskControl.check();connection.commit();return id;
            }catch(Exception e){connection.rollback();throw e;}
        }
    }
}
