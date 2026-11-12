package corpagent.files;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.*;

/** Preserve the original Office package and branding; only add generated slides. */
final class CorporatePptx {
    private static final String P="http://schemas.openxmlformats.org/presentationml/2006/main", A="http://schemas.openxmlformats.org/drawingml/2006/main", R="http://schemas.openxmlformats.org/officeDocument/2006/relationships", REL="http://schemas.openxmlformats.org/package/2006/relationships";
    private CorporatePptx() {}
    private static Document parse(byte[] bytes)throws Exception{
        var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }
    private static byte[] xml(Document doc)throws Exception{
        var factory=TransformerFactory.newInstance();factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET,"");var out=new ByteArrayOutputStream();factory.newTransformer().transform(new DOMSource(doc),new StreamResult(out));return out.toByteArray();
    }
    private static Element element(Document d,Element parent,String ns,String name){Element e=d.createElementNS(ns,name);parent.appendChild(e);return e;}
    private static String escape(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    private static String shape(int id,String text,long x,long y,long width,long height,int font,boolean bold){
        StringBuilder paragraphs=new StringBuilder();for(String line:text.split("\\n"))paragraphs.append("<a:p><a:pPr marL=\"0\" indent=\"0\"/><a:r><a:rPr lang=\"ru-RU\" sz=\"").append(font).append("\" b=\"").append(bold?1:0).append("\"><a:solidFill><a:srgbClr val=\"242B33\"/></a:solidFill><a:latin typeface=\"Verdana\"/><a:cs typeface=\"Verdana\"/></a:rPr><a:t>").append(escape(line)).append("</a:t></a:r><a:endParaRPr lang=\"ru-RU\"/></a:p>");
        return "<p:sp><p:nvSpPr><p:cNvPr id=\""+id+"\" name=\"Generated text "+id+"\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x=\""+x+"\" y=\""+y+"\"/><a:ext cx=\""+width+"\" cy=\""+height+"\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/><a:ln><a:noFill/></a:ln></p:spPr><p:txBody><a:bodyPr wrap=\"square\" lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\"><a:normAutofit/></a:bodyPr><a:lstStyle/>"+paragraphs+"</p:txBody></p:sp>";
    }
    static void write(Path template,Path target,List<String[]> blocks)throws Exception{
        Map<String,byte[]> parts=new LinkedHashMap<>();try(var zip=new ZipFile(template.toFile())){var entries=zip.entries();while(entries.hasMoreElements()){var entry=entries.nextElement();if(!entry.isDirectory())parts.put(entry.getName(),zip.getInputStream(entry).readAllBytes());}}
        Document presentation=parse(parts.get("ppt/presentation.xml")),rels=parse(parts.get("ppt/_rels/presentation.xml.rels")),types=parse(parts.get("[Content_Types].xml"));
        Element root=presentation.getDocumentElement(),list=(Element)presentation.getElementsByTagNameNS(P,"sldIdLst").item(0);
        if(list==null){list=presentation.createElementNS(P,"p:sldIdLst");Node size=presentation.getElementsByTagNameNS(P,"sldSz").item(0);root.insertBefore(list,size);}while(list.hasChildNodes())list.removeChild(list.getFirstChild());
        // Section/custom-show entries refer to example slides, not to generated content.
        for(String name:List.of("custShowLst","extLst")){var nodes=presentation.getElementsByTagNameNS(P,name);while(nodes.getLength()>0){Node n=nodes.item(0);n.getParentNode().removeChild(n);}}
        Element size=(Element)presentation.getElementsByTagNameNS(P,"sldSz").item(0);long w=Long.parseLong(size.getAttribute("cx")),h=Long.parseLong(size.getAttribute("cy"));
        String fallback=null,content=null,title=null;
        for(var entry:parts.entrySet())if(entry.getKey().matches("ppt/slideLayouts/slideLayout\\d+\\.xml")){
            Document layout=parse(entry.getValue());Element c=(Element)layout.getElementsByTagNameNS(P,"cSld").item(0);String name=c==null?"":c.getAttribute("name").toLowerCase(Locale.ROOT);
            if(fallback==null)fallback=entry.getKey();if(name.contains("контентный слайд_1"))content=entry.getKey();if(name.contains("титул светлый"))title=entry.getKey();
        }
        if(content==null){for(var entry:parts.entrySet())if(entry.getKey().matches("ppt/slideLayouts/slideLayout\\d+\\.xml")){String name=new String(entry.getValue(),StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);if(name.contains("контент")||name.contains("title and content")){content=entry.getKey();break;}}}
        if(content==null)content=fallback;if(title==null)title=content;if(content==null)throw new IOException("В шаблоне нет макетов слайдов");
        for(int i=0;i<blocks.size();i++){
            String part="ppt/slides/generated_"+i+".xml",id="rIdGenerated_"+i;while(parts.containsKey(part)){part=part.replace(".xml","x.xml");}while(rels.getElementById(id)!=null)id+="x";
            Element sid=element(presentation,list,P,"p:sldId");sid.setAttribute("id",Integer.toString(256+i));sid.setAttributeNS(R,"r:id",id);
            Element relationship=element(rels,rels.getDocumentElement(),REL,"Relationship");relationship.setAttribute("Id",id);relationship.setAttribute("Type",R+"/slide");relationship.setAttribute("Target",part.substring(4));
            Element type=element(types,types.getDocumentElement(),"http://schemas.openxmlformats.org/package/2006/content-types","Override");type.setAttribute("PartName","/"+part);type.setAttribute("ContentType","application/vnd.openxmlformats-officedocument.presentationml.slide+xml");
            String[] lines=blocks.get(i);String body=String.join("\n",Arrays.copyOfRange(lines,1,lines.length));boolean cover=i==0;
            String slide="<?xml version=\"1.0\" encoding=\"UTF-8\"?><p:sld xmlns:p=\""+P+"\" xmlns:a=\""+A+"\" xmlns:r=\""+R+"\" showMasterSp=\"1\"><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>"+shape(2,lines[0],w*7/100,h*(cover?29:9)/100,w*78/100,h*18/100,cover?3200:2600,true)+shape(3,body,w*7/100,h*(cover?50:29)/100,w*78/100,h*(cover?33:53)/100,1800,false)+"</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>";
            parts.put(part,slide.getBytes(StandardCharsets.UTF_8));String layout=cover?title:content;String rel="<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\""+REL+"\"><Relationship Id=\"rIdLayout\" Type=\""+R+"/slideLayout\" Target=\"../slideLayouts/"+layout.substring(layout.lastIndexOf('/')+1)+"\"/></Relationships>";parts.put("ppt/slides/_rels/"+part.substring(part.lastIndexOf('/')+1)+".rels",rel.getBytes(StandardCharsets.UTF_8));
        }
        parts.put("ppt/presentation.xml",xml(presentation));parts.put("ppt/_rels/presentation.xml.rels",xml(rels));parts.put("[Content_Types].xml",xml(types));
        if(parts.containsKey("docProps/app.xml")){Document props=parse(parts.get("docProps/app.xml"));var slides=props.getElementsByTagNameNS("*","Slides");if(slides.getLength()>0)slides.item(0).setTextContent(Integer.toString(blocks.size()));parts.put("docProps/app.xml",xml(props));}
        try(var zip=new ZipOutputStream(Files.newOutputStream(target))){for(var entry:parts.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}}
    }
}
