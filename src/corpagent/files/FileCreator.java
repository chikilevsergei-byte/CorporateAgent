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
     * Создаёт презентацию PPTX из текста, разделённого на слайды маркером ---SLIDE---.
     * Первая строка каждого слайда — заголовок, остальные — текст.
     *
     * @return путь к файлу или строка «ОШИБКА ...» при сбое
     */
    public String createPptx(String content, String templatePath) {
        try {
            boolean corporate = templatePath != null && !templatePath.trim().isEmpty()
                    && Utils.exists(templatePath);
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
                    + Utils.timestamp() + ".pptx";
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
     *
     * @return путь к файлу или строка «ОШИБКА ...» при сбое
     */
    public String createExcel(String data, String templatePath) {
        try {
            org.apache.poi.xssf.usermodel.XSSFWorkbook wb;
            if (templatePath != null && !templatePath.trim().isEmpty()
                    && Utils.exists(templatePath)) {
                InputStream in = new FileInputStream(templatePath);
                wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(in);
                in.close();
            } else {
                wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
            }
            org.apache.poi.ss.usermodel.Sheet ws = wb.getSheetAt(0);
            if (ws == null) {
                ws = wb.createSheet("Лист1");
            }
            if (data != null) {
                String[] rows = data.split("\n");
                for (int i = 0; i < rows.length; i++) {
                    String[] cells = rows[i].split(";");
                    org.apache.poi.ss.usermodel.Row row = ws.createRow(i);
                    for (int j = 0; j < cells.length; j++) {
                        org.apache.poi.ss.usermodel.Cell cell = row.createCell(j);
                        cell.setCellValue(cells[j].trim());
                    }
                }
            }
            String filename = outputFolder + File.separator + "document_"
                    + Utils.timestamp() + ".xlsx";
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
}
