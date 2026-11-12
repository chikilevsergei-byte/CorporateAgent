package corpagent.data;

import corpagent.db.SqliteHelper;
import java.util.*;
import java.util.regex.Pattern;

/** Local Unicode-aware relevance ranking with source locations. No LLM/network calls. */
public final class ReferenceSearch {
    private ReferenceSearch() {}
    public static boolean available(String path) { return SqliteHelper.getTables(path).contains("source_chunks"); }
    private static final Set<String> STOP=Set.of("методология","методологии","методологию","проект","проекта","проектов","проектами","управления","управлению","какие","какой","какая","которые","ответь","расскажи","пожалуйста","нужно","можно","согласно","должен","должны","такое","сделать","общих","вопрос","использовать","есть");
    private static Set<String> terms(String query) {
        Set<String> result=new LinkedHashSet<>();var m=Pattern.compile("[\\p{L}\\p{N}]{3,}").matcher(query.toLowerCase(Locale.ROOT).replace('ё','е'));
        while(m.find()){String t=m.group();if(STOP.contains(t))continue; if(t.matches("[а-я]+")&&t.length()>5)t=t.substring(0,t.length()-2);result.add(t);}
        return result;
    }
    private record Chunk(String file,String section,int start,int end,String text,String notice) {}
    private record Hit(Chunk chunk,double score) {}
    public static String search(String path,String query) {
        Set<String> tokens=terms(query);if(tokens.isEmpty())return "";
        List<Chunk> chunks=new ArrayList<>();Map<String,Integer> frequencies=new HashMap<>();
        try(var db=SqliteHelper.open(path);var st=db.createStatement();var rows=st.executeQuery("SELECT d.filename,c.section,c.line_start,c.line_end,c.content,d.notice FROM source_chunks c JOIN source_documents d ON d.id=c.document_id ORDER BY c.id")){
            while(rows.next()){Chunk c=new Chunk(rows.getString(1),rows.getString(2),rows.getInt(3),rows.getInt(4),rows.getString(5),rows.getString(6));chunks.add(c);String lower=(c.section+" "+c.text).toLowerCase(Locale.ROOT).replace('ё','е');for(String token:tokens)if(lower.contains(token))frequencies.merge(token,1,Integer::sum);}
        }catch(Exception e){throw new IllegalStateException("Не удалось прочитать источники методологии",e);}
        List<Hit> hits=new ArrayList<>();
        for(Chunk c:chunks){String text=c.text.toLowerCase(Locale.ROOT).replace('ё','е'),heading=c.section.toLowerCase(Locale.ROOT).replace('ё','е');double score=0;int matched=0;
            for(String token:tokens){if(!text.contains(token)&&!heading.contains(token))continue;matched++;double weight=Math.log(1.0+chunks.size()/(double)frequencies.getOrDefault(token,1));score+=weight*(heading.contains(token)?2:1);}
            if(matched>0)hits.add(new Hit(c,score*(1+0.2*(matched-1))));
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());StringBuilder result=new StringBuilder();int count=0;
        for(Hit hit:hits){Chunk c=hit.chunk;String entry="\nФайл: "+c.file+"; раздел: "+c.section+"; строки "+c.start+"–"+c.end+"\nОграничения источника: "+c.notice+"\n"+c.text;
            if(result.length()+entry.length()>13500)continue;result.append(entry);if(++count>=5)break;}
        return result.toString();
    }
}
