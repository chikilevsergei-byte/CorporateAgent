package corpagent.util;

import corpagent.llm.DeepSeekClient;
import java.util.*;

/** Requires a complete structured slide plan before creating an output file. */
public final class PresentationPlan {
    private PresentationPlan(){}
    public static int slideCount(String request){
        var matcher=java.util.regex.Pattern.compile("(?iu)(\\d{1,2}|один|два|две|три|четыре|пять|шесть|семь|восемь|девять|десять)\\s+(?:\\p{L}+\\s+){0,2}(?:слайд|slides)").matcher(request);
        if(!matcher.find())return 5;
        String count=matcher.group(1).toLowerCase(Locale.ROOT);
        return switch(count){case "один"->1;case "два","две"->2;case "три"->3;case "четыре"->4;case "пять"->5;case "шесть"->6;case "семь"->7;case "восемь"->8;case "девять"->9;case "десять"->10;default->Integer.parseInt(count);};
    }
    public static String generate(DeepSeekClient client,String request,String facts,String system){
        int count=slideCount(request);if(count<1||count>20)return "❌ Укажите от 1 до 20 слайдов.";
        String prompt="Создай JSON-план презентации по запросу. Верни только объект {\"slides\":[{\"title\":\"Заголовок\",\"body\":\"Текст\"}]}. В slides должно быть ровно "+count+" заполненных слайдов. Не сокращай число слайдов. Сохраняй точные идентификаторы и фактические числовые значения из запроса и данных. Не придумывай отсутствующие данные.\nЗапрос: "+request+"\n"+facts;
        for(int attempt=0;attempt<2;attempt++){
            Map<String,Object> message=Json.newObject();message.put("role","user");message.put("content",prompt);String text=client.callJson(List.of(message),system);
            if(text.startsWith("❌"))return text;
            try{int a=text.indexOf('{'),b=text.lastIndexOf('}');Map<String,Object> parsed=Json.parseObject(text.substring(a,b+1));List<Object> slides=Json.arr(parsed,"slides");if(slides==null||slides.size()!=count)continue;List<String> blocks=new ArrayList<>();for(Object slide:slides){var data=Json.asMap(slide);String title=Json.str(data,"title",""),body=Json.str(data,"body","");if(title.isBlank()||body.isBlank())throw new IllegalArgumentException("empty slide");blocks.add(title+"\n"+body);}return String.join("\n---SLIDE---\n",blocks);}catch(Exception ignored){}
        }
        return "❌ Модель не сформировала полный план из "+count+" слайдов. Неполная презентация не создана.";
    }
}
