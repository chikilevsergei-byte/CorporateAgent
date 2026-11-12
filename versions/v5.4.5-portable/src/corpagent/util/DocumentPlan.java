package corpagent.util;

/** Deterministic built-in document routing; a selected tool is executable, not advice. */
public final class DocumentPlan {
    private DocumentPlan(){}
    public static IntentRouter.Intent intent(String message,String tools){
        String m=message==null?"":message.toLowerCase(),t=tools==null?"":tools.toLowerCase();IntentRouter.Intent base=IntentRouter.detect(m);
        if(base==IntentRouter.Intent.PRESENTATION||base==IntentRouter.Intent.EXCEL||base==IntentRouter.Intent.DATABASE||base==IntentRouter.Intent.PROTOCOL||base==IntentRouter.Intent.UPDATE_DB)return base;
        boolean create=m.matches("(?s).*(?:создай|создать|сделай|подготовь|сформируй|собери|выгрузи|экспортируй).*" );
        if(create&&(m.contains("паспорт")&&t.contains("паспорта")||m.contains("риск")&&t.contains("реестра рисков")))return IntentRouter.Intent.EXCEL;
        if(create&&(m.contains("статус")&&t.contains("статуса проекта в ppt")||m.contains("ук")&&t.contains("создание презентации")))return IntentRouter.Intent.PRESENTATION;
        return base;
    }
    public static String[] excelKeys(String message,String tools){
        String m=message==null?"":message.toLowerCase(),t=tools==null?"":tools.toLowerCase();
        int passport=m.indexOf("паспорт"),risk=m.indexOf("риск");
        if(passport>=0&&(risk<0||passport<risk))return new String[]{"паспорт"};
        if(risk>=0)return new String[]{"реестр рисков","риск"};
        if(t.contains("паспорт"))return new String[]{"паспорт"};
        if(t.contains("реестр")||t.contains("риск"))return new String[]{"реестр рисков","риск"};
        return new String[0];
    }
}
