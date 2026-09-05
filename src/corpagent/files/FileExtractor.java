package corpagent.files;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Извлечение текста из загруженных файлов.
 * Поддерживает: TXT, MD, CSV, HTML, DOCX, PDF, XLSX, PPTX.
 * Для DOCX/XLSX/PPTX используется Apache POI, для PDF — PDFBox (библиотеки в lib/).
 */
public class FileExtractor {

    private FileExtractor() {
    }

    /**
     * Извлекает текст из файла по расширению.
     * Возвращает текст или сообщение об ошибке/неподдерживаемом формате.
     */
    public static String extractText(File file) {
        String name = file.getName().toLowerCase();
        try {
            if (name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".csv") || name.endsWith(".html")) {
                byte[] bytes = Files.readAllBytes(file.toPath());
                return new String(bytes, StandardCharsets.UTF_8);
            } else if (name.endsWith(".docx")) {
                return extractDocx(file);
            } else if (name.endsWith(".pdf")) {
                return extractPdf(file);
            } else if (name.endsWith(".xlsx")) {
                return extractXlsx(file);
            } else if (name.endsWith(".pptx")) {
                return extractPptx(file);
            } else {
                return "Файл " + file.getName() + " загружен (формат не поддерживается для извлечения текста)";
            }
        } catch (Exception e) {
            return "Ошибка чтения файла " + file.getName() + ": " + e.getMessage();
        }
    }

    private static String extractDocx(File file) throws Exception {
        try {
            InputStream in = new FileInputStream(file);
            org.apache.poi.xwpf.usermodel.XWPFDocument doc = new org.apache.poi.xwpf.usermodel.XWPFDocument(in);
            StringBuilder sb = new StringBuilder();
            for (org.apache.poi.xwpf.usermodel.XWPFParagraph p : doc.getParagraphs()) {
                sb.append(p.getText()).append("\n");
            }
            in.close();
            return sb.toString();
        } catch (NoClassDefFoundError e) {
            return "Библиотека Apache POI не найдена для чтения DOCX. Положите poi-ooxml.jar в lib/";
        }
    }

    private static String extractPdf(File file) throws Exception {
        try {
            InputStream in = new FileInputStream(file);
            org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.pdmodel.PDDocument.load(in);
            org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
            String text = stripper.getText(doc);
            doc.close();
            in.close();
            return text;
        } catch (NoClassDefFoundError e) {
            return "Библиотека PDFBox не найдена для чтения PDF. Положите pdfbox.jar в lib/";
        }
    }

    private static String extractXlsx(File file) throws Exception {
        try {
            InputStream in = new FileInputStream(file);
            org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(in);
            StringBuilder sb = new StringBuilder();
            org.apache.poi.ss.usermodel.Sheet sheet = wb.getSheetAt(0);
            for (org.apache.poi.ss.usermodel.Row row : sheet) {
                StringBuilder line = new StringBuilder();
                for (org.apache.poi.ss.usermodel.Cell cell : row) {
                    if (line.length() > 0) {
                        line.append(";");
                    }
                    line.append(cell == null ? "" : cell.toString());
                }
                sb.append(line).append("\n");
            }
            wb.close();
            in.close();
            return sb.toString();
        } catch (NoClassDefFoundError e) {
            return "Библиотека Apache POI не найдена для чтения XLSX. Положите poi-ooxml.jar в lib/";
        }
    }

    private static String extractPptx(File file) throws Exception {
        try {
            InputStream in = new FileInputStream(file);
            org.apache.poi.xslf.usermodel.XMLSlideShow ppt = new org.apache.poi.xslf.usermodel.XMLSlideShow(in);
            StringBuilder sb = new StringBuilder();
            for (org.apache.poi.xslf.usermodel.XSLFSlide slide : ppt.getSlides()) {
                for (org.apache.poi.xslf.usermodel.XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof org.apache.poi.xslf.usermodel.XSLFTextShape) {
                        sb.append(((org.apache.poi.xslf.usermodel.XSLFTextShape) shape).getText()).append("\n");
                    }
                }
            }
            ppt.close();
            in.close();
            return sb.toString();
        } catch (NoClassDefFoundError e) {
            return "Библиотека Apache POI не найдена для чтения PPTX. Положите poi-ooxml.jar в lib/";
        }
    }
}
