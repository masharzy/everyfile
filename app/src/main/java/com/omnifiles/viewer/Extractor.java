package com.omnifiles.viewer;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.text.Html;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

/** Self-contained, intentionally bounded parsers. Never executes imported content. */
public final class Extractor {
    private Extractor() {}
    private static final int MAX_OUT = 190_000;
    private static final int MAX_XML = 8_000_000;

    public static String readText(File file,int maxBytes) throws Exception {
        try(InputStream in=new FileInputStream(file)) {
            byte[] buf=readLimited(in,maxBytes);
            if(buf.length>=3 && (buf[0]&255)==239 && (buf[1]&255)==187 && (buf[2]&255)==191)
                return new String(buf,3,buf.length-3,StandardCharsets.UTF_8);
            if(buf.length>=2 && (buf[0]&255)==255 && (buf[1]&255)==254)
                return new String(buf,2,buf.length-2,StandardCharsets.UTF_16LE);
            if(buf.length>=2 && (buf[0]&255)==254 && (buf[1]&255)==255)
                return new String(buf,2,buf.length-2,StandardCharsets.UTF_16BE);
            return new String(buf,StandardCharsets.UTF_8);
        }
    }
    private static byte[] readLimited(InputStream in,int max) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream(Math.min(max,32768));
        byte[] buf=new byte[32768];int n;
        while(out.size()<max && (n=in.read(buf,0,Math.min(buf.length,max-out.size())))!=-1)
            out.write(buf,0,n);
        return out.toByteArray();
    }
    private static byte[] readEntry(ZipFile zip,String path) throws Exception {
        ZipEntry entry=zip.getEntry(path);
        if(entry==null)throw new Exception("Archive entry not found: "+path);
        if(entry.getSize()>MAX_XML)throw new Exception("XML document too large");
        try(InputStream in=zip.getInputStream(entry)) {return readLimited(in,MAX_XML);}
    }
    private static Document parse(byte[] bytes) throws Exception {
        DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        try{factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);}catch(Exception ignored){}
        return factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(bytes));
    }
    private static NodeList descendants(Node element,String local) {
        if(element instanceof Element)return ((Element)element).getElementsByTagNameNS("*",local);
        return ((Document)element).getElementsByTagNameNS("*",local);
    }
    private static String texts(Node element,String local) {
        NodeList list=descendants(element,local);
        StringBuilder b=new StringBuilder();
        for(int i=0;i<list.getLength();i++)b.append(list.item(i).getTextContent());
        return b.toString();
    }
    private static String paragraphs(Document doc) {
        StringBuilder result=new StringBuilder();
        NodeList ps=descendants(doc,"p");
        for(int i=0;i<ps.getLength() && result.length()<MAX_OUT;i++) {
            String s=texts(ps.item(i),"t").trim();
            if(s.isEmpty())s=ps.item(i).getTextContent().trim();
            if(!s.isEmpty())result.append(s).append("\n\n");
        }
        return result.toString().trim();
    }
    public static String extractOffice(File file,String ext)throws Exception {
        try(ZipFile zip=new ZipFile(file)) {
            switch(ext) {
                case "docx": {
                    Document d=parse(readEntry(zip,"word/document.xml"));
                    String s=paragraphs(d);
                    return limited(s.isEmpty()?"No text paragraphs found.":s);
                }
                case "xlsx":return extractSheet(zip);
                case "pptx":return extractSlides(zip);
                case "odt":case "ods":case "odp": {
                    Document d=parse(readEntry(zip,"content.xml"));
                    StringBuilder b=new StringBuilder();
                    NodeList ps=descendants(d,"p");
                    for(int i=0;i<ps.getLength() && b.length()<MAX_OUT;i++) {
                        String line=ps.item(i).getTextContent().trim();
                        if(!line.isEmpty())b.append(line).append("\n");
                    }
                    return limited(b.length()==0?"No readable paragraphs found.":b.toString());
                }
                default:throw new Exception("Unsupported Office file");
            }
        }
    }
    private static String extractSlides(ZipFile zip)throws Exception {
        ArrayList<String> names=new ArrayList<>();
        for(ZipEntry e:Collections.list(zip.entries())) {
            if(e.getName().matches("ppt/slides/slide[0-9]+\\.xml")) names.add(e.getName());
        }
        names.sort(Comparator.comparingInt(Extractor::slideNumber));
        StringBuilder result=new StringBuilder(); int index=0;
        for(String name:names) {
            if(index>=80||result.length()>MAX_OUT)break;
            index++; result.append("SLIDE ").append(index).append("\n");
            Document d=parse(readEntry(zip,name));
            NodeList ps=descendants(d,"p");
            for(int i=0;i<ps.getLength();i++) {
                String line=texts(ps.item(i),"t").trim();
                if(!line.isEmpty())result.append("• ").append(line).append("\n");
            }
            result.append("\n");
        }
        return limited(result.length()==0?"No slide text found.":result.toString());
    }
    private static int slideNumber(String path) {
        try{return Integer.parseInt(path.replaceAll("[^0-9]",""));}catch(Exception e){return Integer.MAX_VALUE;}
    }
    private static String extractSheet(ZipFile zip)throws Exception {
        ArrayList<String> strings=new ArrayList<>();
        if(zip.getEntry("xl/sharedStrings.xml")!=null) {
            Document shared=parse(readEntry(zip,"xl/sharedStrings.xml"));
            NodeList si=descendants(shared,"si");
            for(int i=0;i<si.getLength() && i<100_000;i++)strings.add(texts(si.item(i),"t"));
        }
        ArrayList<String> sheets=new ArrayList<>();
        for(ZipEntry e:Collections.list(zip.entries())) {
            if(e.getName().matches("xl/worksheets/sheet[0-9]+\\.xml"))sheets.add(e.getName());
        }
        sheets.sort(Comparator.comparingInt(Extractor::slideNumber));
        StringBuilder out=new StringBuilder();int count=0;
        for(String sheet:sheets) {
            if(++count>15 || out.length()>MAX_OUT)break;
            out.append("SHEET ").append(count).append("\n");
            Document d=parse(readEntry(zip,sheet));
            NodeList rows=descendants(d,"row");
            for(int r=0;r<rows.getLength() && r<300 && out.length()<MAX_OUT;r++) {
                Node row=rows.item(r);NodeList cells=descendants(row,"c");
                ArrayList<String> values=new ArrayList<>();
                int next=0;
                for(int j=0;j<cells.getLength() && j<60;j++) {
                    Element c=(Element)cells.item(j);
                    int column=columnIndex(c.getAttribute("r"));
                    if(column>=30)continue;
                    while(next<column && next<30){values.add("");next++;}
                    String type=c.getAttribute("t");String raw=firstContent(c,"v");
                    if("s".equals(type)) {
                        try{int idx=Integer.parseInt(raw);raw=idx>=0&&idx<strings.size()?strings.get(idx):raw;}
                        catch(Exception ignored){}
                    } else if("inlineStr".equals(type))raw=texts(c,"t");
                    if("b".equals(type))raw="1".equals(raw)?"TRUE":"FALSE";
                    values.add(raw.replaceAll("[\\r\\n\\t]+"," "));next++;
                }
                while(!values.isEmpty() && values.get(values.size()-1).isEmpty())values.remove(values.size()-1);
                if(!values.isEmpty())out.append(String.join("  |  ",values)).append("\n");
            }
            out.append("\n");
        }
        return limited(out.length()==0?"No cell data found.":out.toString());
    }
    private static int columnIndex(String address) {
        int col=0;
        for(char c:address.toUpperCase(Locale.ROOT).toCharArray()) {
            if(c<'A'||c>'Z')break;
            col=col*26+c-'A'+1;
        }
        return col>0?col-1:0;
    }
    private static String firstContent(Node e,String local) {
        NodeList found=descendants(e,local);
        return found.getLength()>0?found.item(0).getTextContent():"";
    }
    public static String extractEpub(File file)throws Exception {
        try(ZipFile zip=new ZipFile(file)) {
            ArrayList<String> items=new ArrayList<>();
            for(ZipEntry entry:Collections.list(zip.entries())) {
                String p=entry.getName().toLowerCase(Locale.ROOT);
                if(!entry.isDirectory() && (p.endsWith(".xhtml")||p.endsWith(".html")||p.endsWith(".htm")))items.add(entry.getName());
            }
            // This is a text preview, not a full EPUB layout engine.
            items.sort(String.CASE_INSENSITIVE_ORDER);
            StringBuilder out=new StringBuilder(); int chapter=0;
            for(String name:items) {
                if(chapter>=35||out.length()>MAX_OUT)break;
                String html=new String(readEntry(zip,name),StandardCharsets.UTF_8);
                html=html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>","");
                String converted=Html.fromHtml(html,Html.FROM_HTML_MODE_LEGACY).toString().trim();
                if(converted.isEmpty())continue;
                out.append("CHAPTER ").append(++chapter).append("\n\n")
                        .append(converted).append("\n\n");
            }
            return limited(out.length()==0?"No readable ebook chapters found.":out.toString());
        }
    }
    private static String limited(String s) {
        return s.length()>MAX_OUT?s.substring(0,MAX_OUT)+"\n\n[Preview truncated]":s;
    }
    public static String inspectSqlite(File file)throws Exception {
        StringBuilder out=new StringBuilder();
        SQLiteDatabase db=SQLiteDatabase.openDatabase(file.getAbsolutePath(),null,SQLiteDatabase.OPEN_READONLY);
        try {
            ArrayList<String> names=new ArrayList<>();
            try(Cursor meta=db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name LIMIT 12",null)) {
                while(meta.moveToNext())names.add(meta.getString(0));
            }
            if(names.isEmpty())return "No user tables found.";
            for(String name:names) {
                out.append("TABLE: ").append(name).append("\n");
                String quoted="\""+name.replace("\"","\"\"")+"\"";
                try(Cursor cursor=db.rawQuery("SELECT * FROM "+quoted+" LIMIT 10",null)) {
                    out.append(String.join(" | ",cursor.getColumnNames())).append("\n");
                    while(cursor.moveToNext() && out.length()<MAX_OUT) {
                        StringBuilder line=new StringBuilder();
                        for(int j=0;j<cursor.getColumnCount();j++) {
                            if(j>0)line.append(" | ");
                            String value=cursor.isNull(j)?"NULL":cursor.getType(j)==Cursor.FIELD_TYPE_BLOB?"[BLOB]":cursor.getString(j);
                            line.append(value==null?"NULL":value.replaceAll("[\\r\\n]+"," "));
                        }
                        out.append(line).append("\n");
                    }
                }catch(Exception error){out.append("Could not read table: ").append(error.getMessage()).append("\n");}
                out.append("\n");
            }
        }finally{db.close();}
        return limited(out.toString());
    }
    public static String hexPreview(File file,int max)throws Exception {
        byte[] bytes;
        try(InputStream input=new FileInputStream(file)){bytes=readLimited(input,max);}
        if(bytes.length==0)return "Empty file";
        StringBuilder result=new StringBuilder();
        for(int i=0;i<bytes.length;i+=16) {
            result.append(String.format(Locale.ROOT,"%08X  ",i));
            for(int j=0;j<16;j++) {
                result.append(i+j<bytes.length?String.format(Locale.ROOT,"%02X ",bytes[i+j]&255):"   ");
                if(j==7)result.append(' ');
            }
            result.append(" |");
            for(int j=i;j<i+16&&j<bytes.length;j++) {
                int c=bytes[j]&255;result.append(c>=32&&c<=126?(char)c:'.');
            }
            result.append("|\n");
        }
        return result.toString();
    }
}
