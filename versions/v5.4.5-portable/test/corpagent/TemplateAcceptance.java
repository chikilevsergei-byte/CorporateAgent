package corpagent;
import corpagent.files.FileCreator;
import corpagent.files.FileExtractor;
import java.nio.file.*;
import java.util.zip.ZipFile;

/** Uses copied real templates locally; generated files contain only synthetic test content. */
public final class TemplateAcceptance {
    public static void main(String[] args)throws Exception{
        Path root=Paths.get(args.length==0?System.getProperty("user.dir"):args[0]).toAbsolutePath();Path output=Files.createTempDirectory(root.resolve("build_test"),"templates-");
        FileCreator creator=new FileCreator(output.toString(),root.toString());
        String template=creator.getCorporatePresentationTemplate();if(template==null)throw new AssertionError("Presentation template not found");
        String pptx=creator.createPptx("# Synthetic acceptance\n## Test slide\nSynthetic template content",template);
        if(pptx==null||!Files.isRegularFile(Paths.get(pptx)))throw new AssertionError("PPTX not created");
        try(ZipFile z=new ZipFile(pptx)){if(z.getEntry("ppt/presentation.xml")==null)throw new AssertionError("PPTX invalid");}
        if(FileExtractor.extractText(Paths.get(pptx).toFile()).isEmpty())throw new AssertionError("PPTX extraction failed");
        String xlsxTemplate=creator.getCorporateExcelTemplate("паспорт","риск");if(xlsxTemplate==null)throw new AssertionError("Excel template not found");
        String xlsx=creator.createExcel("Name;Value\nSynthetic;123",xlsxTemplate,false);
        if(xlsx==null||!Files.isRegularFile(Paths.get(xlsx)))throw new AssertionError("XLSX not created");
        try(ZipFile z=new ZipFile(xlsx)){if(z.getEntry("xl/workbook.xml")==null)throw new AssertionError("XLSX invalid");}
        if(FileExtractor.extractText(Paths.get(xlsx).toFile()).isEmpty())throw new AssertionError("XLSX extraction failed");
        System.out.println("TemplateAcceptance OK: real PPTX/XLSX templates resolved, generated and extracted on embedded Java");
    }
}
