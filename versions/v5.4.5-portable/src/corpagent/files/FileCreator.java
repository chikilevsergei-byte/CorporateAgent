package corpagent.files;

import corpagent.util.Utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.awt.Color;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFSlideLayout;
import org.apache.poi.xslf.usermodel.XSLFSlideMaster;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;

/**
 * Создание офисных файлов: PPTX (презентации) и XLSX (Excel). Использует Apache POI.
 * <p>Фирменные презентации (инструмент Супер Ленты) строятся на корпоративных макетах
 * шаблона «Шаблон презентаций Супер Лента.pptx». Слайды-примеры шаблона удаляются.
 * Текст чистится от markdown-мусора и оформляется по корпоративным правилам: шрифт
 * Verdana, сдержанная палитра (зелёный акцент, тёмно-серый текст).</p>
 */
public class FileCreator {

    private final String outputFolder;
    private final String baseDir;

    public FileCreator(String outputFolder) {
        this(outputFolder, null);
    }

    /** @param baseDir базовая папка приложения (для поиска корп. шаблонов в {base}/Шаблоны/); может быть null */
    public FileCreator(String outputFolder, String baseDir) {
        this.outputFolder = outputFolder;
        this.baseDir = baseDir;
        Utils.mkdirs(outputFolder);
    }
    public String getPresentationTemplate(String message,String tools){
        String m=message==null?"":message.toLowerCase(),t=tools==null?"":tools.toLowerCase();
        if(m.contains("без шаблон"))return null;
        boolean status=t.contains("статуса проекта в ppt")||m.contains("шаблон статуса")||m.contains("шаблон_статус")||m.matches("(?s).*(?:создай|подготовь|сформируй|сделай)\\s+(?:презентацию\\s+)?статус.*");
        String key=m.contains("гипер")?"гипер лента":m.contains("групп")?"группа лента":status?"статус проекта":m.contains("супер")||m.contains("шаблон")||m.contains("корпоратив")||m.contains("лент")||t.contains("шаблоне")?"презентаций супер лента":null;
        if(key==null||baseDir==null)return null;
        File[] files=new File(baseDir,"Шаблоны").listFiles();if(files!=null)for(File f:files)if(f.isFile()&&!f.getName().startsWith("~$")&&f.getName().toLowerCase().endsWith(".pptx")&&f.getName().toLowerCase().contains(key))return f.getAbsolutePath();
        return null;
    }

    /** Возвращает корп. шаблон презентаций Супер Ленты из {base}/Шаблоны (или null). */
    public String getCorporatePresentationTemplate() {
        if (baseDir == null || baseDir.trim().isEmpty()) {
            return null;
        }
        File dir = new File(baseDir, "Шаблоны");
        if (!dir.isDirectory()) {
            return null;
        }
        File[] files = dir.listFiles(new java.io.FileFilter() {
            public boolean accept(File f) {
                return f.isFile() && f.getName().toLowerCase().endsWith(".pptx");
            }
        });
        if (files == null) {
            return null;
        }
        for (File f : files) {
            if (f.getName().toLowerCase().contains("шаблон презентаций супер лента")) {
                return f.getAbsolutePath();
            }
        }
        return null;
    }

    /**
     * Возвращает корп. Excel-шаблон из {base}/Шаблоны по виду документа (C2.2).
     * Ищется .xlsx (временные файлы «~$…» игнорируются), в имени которого встречается
     * один из ключевых фрагментов {@code keys}. Ключи проверяются в порядке приоритета.
     *
     * @param keys фрагменты имени (любой регистр), например {@code {"паспорт"}}
     * @return абсолютный путь к шаблону или null, если шаблон не найден
     */
    public String getCorporateExcelTemplate(String... keys) {
        if (baseDir == null || baseDir.trim().isEmpty() || keys == null || keys.length == 0) {
            return null;
        }
        File dir = new File(baseDir, "Шаблоны");
        if (!dir.isDirectory()) {
            return null;
        }
        File[] files = dir.listFiles(new java.io.FileFilter() {
            public boolean accept(File f) {
                String n = f.getName().toLowerCase();
                return f.isFile() && n.endsWith(".xlsx") && !n.startsWith("~$");
            }
        });
        if (files == null) {
            return null;
        }
        for (String key : keys) {
            if (key == null || key.trim().isEmpty()) {
                continue;
            }
            String k = key.toLowerCase();
            for (File f : files) {
                if (f.getName().toLowerCase().contains(k)) {
                    return f.getAbsolutePath();
                }
            }
        }
        return null;
    }

    /**
     * Создаёт презентацию PPTX из текста, разделённого на слайды маркером ---SLIDE---.
     * Первая строка каждого слайда — заголовок, остальные — текст.
     *
     * @return путь к файлу или строка «ОШИБКА ...» при сбое
     */
    public String createPptx(String content, String templatePath) {
        try {
            boolean corporate = templatePath != null && !templatePath.trim().isEmpty()
                    && Utils.exists(templatePath);
            if(corporate){
                List<String[]> blocks=splitSlides(content);if(blocks.isEmpty())return "ОШИБКА: Нет содержимого слайдов";
                String filename=outputFolder+File.separator+"presentation_"+Utils.timestamp()+"_"+java.util.UUID.randomUUID()+".pptx";
                CorporatePptx.write(java.nio.file.Path.of(templatePath),java.nio.file.Path.of(filename),blocks);
                return filename;
            }
            XMLSlideShow prs;
            if (corporate) {
                InputStream in = new FileInputStream(templatePath);
                prs = new XMLSlideShow(in);
                in.close();
            } else {
                prs = new XMLSlideShow();
            }

            // Если используем корпоративный шаблон — убираем его слайды-примеры,
            // оставляя мастер и макеты, чтобы итоговый файл содержал только наш контент.
            if (corporate) {
                while (!prs.getSlides().isEmpty()) {
                    prs.removeSlide(0);
                }
            }

            List<String[]> blocks = splitSlides(content);
            if (blocks.isEmpty()) {
                blocks.add(new String[]{"Презентация"});
            }
            for (int i = 0; i < blocks.size(); i++) {
                boolean isTitle = corporate && i == 0;
                if (corporate) {
                    buildCorporateSlide(prs, blocks.get(i), isTitle);
                } else {
                    buildPlainSlide(prs, blocks.get(i));
                }
            }

            String filename = outputFolder + File.separator + "presentation_"
                    + Utils.timestamp() + "_" + java.util.UUID.randomUUID().toString() + ".pptx";
            FileOutputStream out = new FileOutputStream(filename);
            prs.write(out);
            out.close();
            prs.close();
            return filename;
        } catch (NoClassDefFoundError e) {
            return "ОШИБКА: Библиотека Apache POI не найдена для создания PPTX. Положите poi-ooxml.jar в lib/";
        } catch (Exception e) {
            return "ОШИБКА создания PPTX: " + e.getMessage();
        }
    }

    // =====================================================================
    // Разбор текста
    // =====================================================================

    /** Делит текст на слайды; внутри каждого убирает markdown-мусор и пустые строки. */
    private List<String[]> splitSlides(String content) {
        List<String[]> out = new ArrayList<String[]>();
        if (content == null) {
            return out;
        }
        for (String block : content.split("---SLIDE---")) {
            List<String> lines = new ArrayList<String>();
            for (String ln : block.split("\n")) {
                String c = stripMarkdown(ln);
                if (!c.isEmpty()) {
                    lines.add(c);
                }
            }
            if (!lines.isEmpty()) {
                out.add(lines.toArray(new String[0]));
            }
        }
        return out;
    }

    /** Убирает типичный markdown-мусор из строки (пустую строку превращает в ""). */
    private static String stripMarkdown(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw;
        if (s.matches("^\\s*(?:---|===|\\*\\*\\*|___)\\s*$")) { // горизонтальная черта
            return "";
        }
        if (s.trim().startsWith("```")) { // закрытие/открытие кодового блока
            return "";
        }
        s = s.replaceAll("^#{1,6}\\s+", "");         // ## заголовок
        s = s.replaceAll("\\*\\*(.+?)\\*\\*", "$1"); // жирный
        s = s.replaceAll("__([^_]+)__", "$1");       // жирный (alt)
        s = s.replaceAll("`([^`]*)`", "$1");         // код
        s = s.trim();
        return s;
    }

    // =====================================================================
    // Фирменные константы оформления (Супер Лента)
    // =====================================================================

    private static final String FONT_V   = "Verdana";
    private static final Color C_DK      = new Color(0x24, 0x2B, 0x33); // почти чёрный
    private static final Color C_BODY    = new Color(0x2E, 0x35, 0x3C); // тёмный текст
    private static final Color C_GRAY    = new Color(0x5A, 0x64, 0x6E); // серый-шифер
    private static final Color C_GREEN   = new Color(0x00, 0xA1, 0x44); // акцент (зелёный)

    // =====================================================================
    // Корпоративная сборка (шаблон Супер Ленты)
    // =====================================================================

    private void buildCorporateSlide(XMLSlideShow prs, String[] lines, boolean isTitle) {
        String heading = lines[0];
        List<String> body = toBodyList(lines);
        XSLFSlideLayout lay = chooseCorporateLayout(prs, heading, isTitle);
        XSLFSlide slide = (lay != null) ? prs.createSlide(lay) : prs.createSlide();
        for(var placeholder:slide.getPlaceholders())placeholder.clearText();

        if (isTitle) {
            // Титульный слайд: крупный центр + зелёный акцентный сегмент.
            addText(slide, heading, new Rectangle(110, 230, 620, 96),
                    C_DK, true, 34.0, C_GRAY, "надзаголовок-дата/тема", false);
            if (!body.isEmpty()) {
                addSubtitle(slide, body, new Rectangle(112, 344, 560, 44));
            }
        } else {
            addText(slide, heading, new Rectangle(70, 46, 620, 44),
                    C_DK, true, 22.0, C_GRAY, "", false);
            addBody(slide, body, true, false, false);
        }
    }

    // Выбирает корпоративный макет по роли слайда.
    private XSLFSlideLayout chooseCorporateLayout(XMLSlideShow prs, String heading, boolean isTitle) {
        try {
            List<XSLFSlideMaster> masters = prs.getSlideMasters();
            if (masters.isEmpty()) {
                return null;
            }
            XSLFSlideLayout[] lays = masters.get(0).getSlideLayouts();
            String h = heading.toLowerCase();

            XSLFSlideLayout closing = find(lays, "завершающий");
            if (closing != null && (h.startsWith("спасибо") || h.startsWith("вопросы")
                    || h.startsWith("завершен") || h.startsWith("контакты"))) {
                return closing;
            }
            if (isTitle) {
                XSLFSlideLayout t = find(lays, "титул с логоготипом");
                if (t != null) {
                    return t;
                }
                t = findFirstOf(lays, new String[]{"титул", "облож", "титул с логоготип"});
                if (t != null) {
                    return t;
                }
            }
            XSLFSlideLayout c = find(lays, "контентный слайд_1");
            if (c == null) {
                c = findFirstOf(lays, new String[]{"контентный"});
            }
            return c;
        } catch (Throwable e) {
            return null;
        }
    }

    private static XSLFSlideLayout find(XSLFSlideLayout[] lays, String key) {
        if (lays == null) {
            return null;
        }
        for (XSLFSlideLayout l : lays) {
            String nm = l.getName();
            if (nm != null && nm.toLowerCase().contains(key)) {
                return l;
            }
        }
        return null;
    }

    private static XSLFSlideLayout findFirstOf(XSLFSlideLayout[] lays, String[] keys) {
        for (String k : keys) {
            XSLFSlideLayout l = find(lays, k);
            if (l != null) {
                return l;
            }
        }
        return null;
    }

    // =====================================================================
    // Обычная (некорпоративная) сборка
    // =====================================================================

    private void buildPlainSlide(XMLSlideShow prs, String[] lines) {
        XSLFSlide slide = prs.createSlide();
        String heading = lines[0];
        List<String> body = toBodyList(lines);
        addText(slide, heading, new Rectangle(50, 40, 700, 50), C_DK, true, 26.0,
                new Color(0x1F, 0x3A, 0x63), "", false);
        if (!body.isEmpty()) {
            addBody(slide, body, false, true, true);
            // здесь обычный (учебный) режим: тело рисуем только один раз
        }
    }

    // =====================================================================
    // Низкоуровневые помощники
    // =====================================================================

    private List<String> toBodyList(String[] lines) {
        List<String> out = new ArrayList<String>();
        for (int i = 1; i < lines.length; i++) {
            out.add(lines[i]);
        }
        return out;
    }

    /** Добавляет текстовую строку (с произвольным размером/цветом). */
    private XSLFTextBox addText(XSLFSlide slide, String text, Rectangle anchor,
                                Color fg, boolean bold, double size, Color line,
                                String kicker, boolean bullet) {
        XSLFTextBox tb = slide.createTextBox();
        tb.setAnchor(anchor);
        XSLFTextParagraph p = tb.addNewTextParagraph();
        XSLFTextRun r = p.addNewTextRun();
        r.setText(text);
        r.setFontFamily(FONT_V);
        r.setFontColor(fg);
        r.setBold(bold);
        r.setFontSize(size);
        return tb;
    }

    /** Добавляет подзаголовок (титульный слайд). */
    private void addSubtitle(XSLFSlide slide, List<String> body, Rectangle anchor) {
        XSLFTextBox tb = slide.createTextBox();
        tb.setAnchor(anchor);
        XSLFTextParagraph p = tb.addNewTextParagraph();
        XSLFTextRun r = p.addNewTextRun();
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String line : body) {
            if (!first) {
                sb.append('\n');
            }
            sb.append(line);
            first = false;
        }
        r.setText(sb.toString());
        r.setFontFamily(FONT_V);
        r.setFontColor(C_GRAY);
        r.setFontSize(15.0);
    }

    /**
     * Добавляет один/несколько абзацев тела. При {@code bullet} каждый абзац снабжается
     * зелёным маркером (корпоративный стиль списка); иначе — обычный многострочный текст.
     * Параметр guard служит для корректной отрисовки одного режима без дублей.
     */
    private void addBody(XSLFSlide slide, List<String> body, boolean bullet,
                         boolean multi, boolean guard) {
        if (body.isEmpty()) {
            return;
        }
        XSLFTextBox tb = slide.createTextBox();
        tb.setAnchor(bullet ? new Rectangle(78, 132, 600, 340)
                            : new Rectangle(60, 120, 620, 200));
        boolean cameBullet = bullet; // режим этой вставки
        for (String line : body) {
            XSLFTextParagraph p = tb.addNewTextParagraph();
            if (cameBullet) {
                XSLFTextRun dot = p.addNewTextRun();
                dot.setText("•  ");
                dot.setFontColor(C_GREEN);
                dot.setBold(true);
                dot.setFontFamily(FONT_V);
                dot.setFontSize(15.0);
            }
            XSLFTextRun r = p.addNewTextRun();
            r.setText(line);
            r.setFontColor(C_BODY);
            r.setFontFamily(FONT_V);
            r.setFontSize(15.0);
        }
    }

    /**
     * Создаёт Excel-файл XLSX из текста: строки разделены переносом, ячейки — ';'.
     * Эквивалент {@code createExcel(data, templatePath, true)}.
     *
     * @return путь к файлу или строка «ОШИБКА ...» при сбое
     */
    public String createExcel(String data, String templatePath) {
        return createExcel(data, templatePath, true);
    }

    /**
     * Создаёт Excel-файл XLSX из текста: строки разделены переносом, ячейки — ';' (C2.2).
     * <p>Если задан {@code templatePath}, книга открывается по корп. шаблону (сохраняются его
     * стили и структура); при {@code appendAfterTemplate=true} данные дописываются под
     * последней заполненной строкой шаблона, иначе — с первой строки (перезапись).</p>
     *
     * @return путь к файлу или строка «ОШИБКА ...» при сбое
     */
    public String createExcel(String data, String templatePath, boolean appendAfterTemplate) {
        try {
            boolean hasTemplate = templatePath != null && !templatePath.trim().isEmpty()
                    && Utils.exists(templatePath);
            org.apache.poi.xssf.usermodel.XSSFWorkbook wb;
            if (hasTemplate) {
                InputStream in = new FileInputStream(templatePath);
                wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(in);
                in.close();
            } else {
                wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
            }
            org.apache.poi.ss.usermodel.Sheet ws = wb.getNumberOfSheets() == 0 ? null : wb.getSheetAt(0);
            if (ws == null) {
                ws = wb.createSheet("Лист1");
            }
            int startRow = 0;
            boolean filledForm=hasTemplate&&appendAfterTemplate&&fillStructuredTemplate(wb,ws,data,templatePath);
            if (hasTemplate && appendAfterTemplate) {
                // Дописываем данные под последней непустой строкой шаблона.
                startRow = ws.getLastRowNum() + 1;
            }
            if (data != null&&!filledForm) {
                String[] rows = data.split("\n");
                int r = 0;
                for (String rawRow : rows) {
                    if (rawRow.trim().isEmpty()) {
                        continue;
                    }
                    String[] cells = rawRow.split(";", -1);
                    org.apache.poi.ss.usermodel.Row row = ws.createRow(startRow + r);
                    for (int j = 0; j < cells.length; j++) {
                        org.apache.poi.ss.usermodel.Cell cell = row.createCell(j);
                        cell.setCellValue(cells[j].trim());
                    }
                    r++;
                }
            }
            String filename = outputFolder + File.separator + "document_"
                    + Utils.timestamp() + "_" + java.util.UUID.randomUUID().toString() + ".xlsx";
            // Imported templates may contain dangling device-specific printer settings.
            for(int i=0;i<wb.getNumberOfSheets();i++){
                var sheet=wb.getSheetAt(i);if(sheet.getCTWorksheet().isSetPageSetup()&&sheet.getCTWorksheet().getPageSetup().isSetId())sheet.getCTWorksheet().getPageSetup().unsetId();
                java.util.List<String> printerIds=new java.util.ArrayList<>();for(var relationship:sheet.getPackagePart().getRelationshipsByType("http://schemas.openxmlformats.org/officeDocument/2006/relationships/printerSettings"))printerIds.add(relationship.getId());for(String id:printerIds)sheet.getPackagePart().removeRelationship(id);
            }
            FileOutputStream out = new FileOutputStream(filename);
            wb.write(out);
            out.close();
            wb.close();
            return filename;
        } catch (NoClassDefFoundError e) {
            return "ОШИБКА: Библиотека Apache POI не найдена для создания XLSX. Положите poi-ooxml.jar в lib/";
        } catch (Exception e) {
            return "ОШИБКА создания XLSX: " + e.getMessage();
        }
    }

    public String getOutputFolder() {
        return outputFolder;
    }

    private static boolean fillStructuredTemplate(org.apache.poi.xssf.usermodel.XSSFWorkbook workbook,org.apache.poi.ss.usermodel.Sheet sheet,String data,String template){
        if(data==null)return false;String lower=template.toLowerCase();java.util.List<java.util.List<String>> rows=delimitedRows(data);
        if(lower.contains("паспорт")){
            java.util.Map<String,String> fields=new java.util.LinkedHashMap<String,String>();for(var row:rows)if(row.size()>=2)fields.put(row.get(0).trim().toLowerCase(),String.join(";",row.subList(1,row.size())).trim());
            java.util.Set<String> known=java.util.Set.of("project_name","reasons","strategy","product","financial_kpi","nonfinancial_kpi","budget","dates","team");
            if(!fields.containsKey("project_name"))fields.put("project_name","Нет данных");
            java.util.List<java.util.List<String>> extra=new java.util.ArrayList<>();for(var row:rows)if(!row.isEmpty()&&!known.contains(row.get(0).trim().toLowerCase()))extra.add(row);
            String[][] positions={{"project_name","A1"},{"reasons","A3"},{"strategy","A5"},{"product","A7"},{"financial_kpi","A10"},{"nonfinancial_kpi","A12"},{"budget","A14"},{"dates","C3"},{"team","C5"}};
            for(String[] pair:positions){String value=fields.getOrDefault(pair[0],"Нет данных");if(pair[0].equals("project_name"))value="Паспорт проекта «"+value+"»";setFormCell(sheet,pair[1],value);}
            for(int i=workbook.getNumberOfSheets()-1;i>=0;i--)if(workbook.getSheetName(i).toLowerCase().contains("пример"))workbook.removeSheetAt(i);
            if(!extra.isEmpty()){var source=workbook.createSheet("Исходные данные");for(int i=0;i<extra.size();i++){var target=source.createRow(i);for(int j=0;j<extra.get(i).size();j++)target.createCell(j).setCellValue(extra.get(i).get(j));}}
            return true;
        }
        if(lower.contains("риск")&&!rows.isEmpty()&&rows.get(0).stream().anyMatch(c->riskHeader(c).equals("title"))){
            setFormCell(sheet,"A1","Реестр рисков");
            String[] keys={"id","date","title","description","category","probability","impact","priority","owner","status","mitigation","due_date"};int[] columns={0,1,7,9,10,11,12,13,14,15,16,17};
            java.util.Map<String,Integer> header=new java.util.HashMap<String,Integer>();for(int i=0;i<rows.get(0).size();i++)header.put(riskHeader(rows.get(0).get(i)),i);
            org.apache.poi.ss.usermodel.CellStyle[] styles=new org.apache.poi.ss.usermodel.CellStyle[18];var sample=sheet.getRow(18);if(sample!=null)for(int i=0;i<18;i++)if(sample.getCell(i)!=null)styles[i]=sample.getCell(i).getCellStyle();
            for(int i=sheet.getNumMergedRegions()-1;i>=0;i--)if(sheet.getMergedRegion(i).getFirstRow()>=18)sheet.removeMergedRegion(i);
            for(var row:sheet)if(row.getRowNum()>=18)for(var cell:row)cell.setBlank();
            for(int index=1;index<rows.size();index++){var values=rows.get(index);if(values.stream().allMatch(String::isBlank))continue;int rowIndex=17+index;var row=sheet.getRow(rowIndex);if(row==null)row=sheet.createRow(rowIndex);row.setHeightInPoints(54);
                for(int k=0;k<keys.length;k++){Integer field=header.get(keys[k]);String value=field!=null&&field<values.size()?values.get(field):"";var cell=row.getCell(columns[k]);if(cell==null)cell=row.createCell(columns[k]);cell.setCellValue(value);if(styles[columns[k]]!=null)cell.setCellStyle(styles[columns[k]]);}
                sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(rowIndex,rowIndex,1,6));sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(rowIndex,rowIndex,7,8));
            }
            return true;
        }
        return false;
    }
    private static String riskHeader(String value){String v=value.trim().toLowerCase();return switch(v){case "risk","риск","название риска","наименование риска"->"title";case "описание"->"description";case "вероятность"->"probability";case "влияние"->"impact";case "приоритет"->"priority";case "ответственный","владелец"->"owner";case "статус"->"status";case "меры","митигация","план реагирования"->"mitigation";case "срок"->"due_date";default->v;};}
    private static void setFormCell(org.apache.poi.ss.usermodel.Sheet sheet,String address,String value){var ref=new org.apache.poi.ss.util.CellReference(address);var row=sheet.getRow(ref.getRow());if(row==null)row=sheet.createRow(ref.getRow());var cell=row.getCell(ref.getCol());if(cell==null)cell=row.createCell(ref.getCol());cell.setCellValue(value);}
    private static java.util.List<java.util.List<String>> delimitedRows(String text){
        java.util.List<java.util.List<String>> rows=new java.util.ArrayList<>();java.util.List<String> row=new java.util.ArrayList<>();StringBuilder cell=new StringBuilder();boolean quoted=false;
        for(int i=0;i<text.length();i++){char c=text.charAt(i);if(c=='"'){if(quoted&&i+1<text.length()&&text.charAt(i+1)=='"'){cell.append('"');i++;}else quoted=!quoted;}else if(!quoted&&(c==';'||c=='\n')){row.add(cell.toString().trim());cell.setLength(0);if(c=='\n'){if(row.stream().anyMatch(v->!v.isBlank()))rows.add(row);row=new java.util.ArrayList<>();}}else if(c!='\r')cell.append(c);}
        row.add(cell.toString().trim());if(row.stream().anyMatch(v->!v.isBlank()))rows.add(row);return rows;
    }
}
