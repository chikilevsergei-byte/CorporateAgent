package corpagent.files;

import corpagent.util.Utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Создание офисных файлов: PPTX (презентации) и XLSX (Excel).
 * Использует Apache POI (библиотеки в lib/).
 */
public class FileCreator {

    private final String outputFolder;

    public FileCreator(String outputFolder) {
        this.outputFolder = outputFolder;
        Utils.mkdirs(outputFolder);
    }

    /**
     * Создаёт презентацию PPTX из текста, разделённого на слайды маркером ---SLIDE---.
     * Первая строка каждого слайда — заголовок, остальные — текст.
     * Возвращает путь к созданному файлу.
     */
    public String createPptx(String content, String templatePath) {
        try {
            org.apache.poi.xslf.usermodel.XMLSlideShow prs;
            if (templatePath != null && Utils.exists(templatePath)) {
                InputStream in = new FileInputStream(templatePath);
                prs = new org.apache.poi.xslf.usermodel.XMLSlideShow(in);
                in.close();
            } else {
                prs = new org.apache.poi.xslf.usermodel.XMLSlideShow();
            }

            String[] slides = content.split("---SLIDE---");
            for (String slideContent : slides) {
                if (slideContent.trim().isEmpty()) {
                    continue;
                }
                org.apache.poi.xslf.usermodel.XSLFSlide slide = prs.createSlide();
                String[] lines = slideContent.trim().split("\n");
                if (lines.length > 0) {
                    org.apache.poi.xslf.usermodel.XSLFTextBox titleBox = slide.createTextBox();
                    titleBox.setAnchor(new java.awt.Rectangle(50, 40, 700, 60));
                    org.apache.poi.xslf.usermodel.XSLFTextParagraph tp = titleBox.addNewTextParagraph();
                    org.apache.poi.xslf.usermodel.XSLFTextRun run = tp.addNewTextRun();
                    run.setText(lines[0].trim());
                    run.setFontSize(28.0);
                    run.setBold(true);
                }
                if (lines.length > 1) {
                    StringBuilder body = new StringBuilder();
                    for (int i = 1; i < lines.length; i++) {
                        body.append(lines[i]).append("\n");
                    }
                    org.apache.poi.xslf.usermodel.XSLFTextBox bodyBox = slide.createTextBox();
                    bodyBox.setAnchor(new java.awt.Rectangle(50, 120, 700, 400));
                    org.apache.poi.xslf.usermodel.XSLFTextParagraph bp = bodyBox.addNewTextParagraph();
                    org.apache.poi.xslf.usermodel.XSLFTextRun br = bp.addNewTextRun();
                    br.setText(body.toString().trim());
                    br.setFontSize(16.0);
                }
            }

            String filename = outputFolder + java.io.File.separator + "presentation_" + Utils.timestamp() + ".pptx";
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

    /**
     * Создаёт Excel-файл XLSX из текста: строки разделены переносом, ячейки — ';'.
     * Возвращает путь к созданному файлу.
     */
    public String createExcel(String data, String templatePath) {
        try {
            org.apache.poi.xssf.usermodel.XSSFWorkbook wb;
            if (templatePath != null && Utils.exists(templatePath)) {
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
            String[] rows = data.trim().split("\n");
            for (int i = 0; i < rows.length; i++) {
                String[] cells = rows[i].split(";");
                org.apache.poi.ss.usermodel.Row row = ws.createRow(i);
                for (int j = 0; j < cells.length; j++) {
                    org.apache.poi.ss.usermodel.Cell cell = row.createCell(j);
                    cell.setCellValue(cells[j].trim());
                }
            }
            String filename = outputFolder + java.io.File.separator + "document_" + Utils.timestamp() + ".xlsx";
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
