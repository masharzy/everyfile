package com.omnifiles.viewer;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Typeface;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.pdf.PdfRenderer;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Offline, storage-permission-free Android file viewer with honest fallbacks. */
public class MainActivity extends Activity {
    private static final int OPEN_REQUEST = 3401;
    private static final int BG = 0xFF0D1019, CARD = 0xFF191E2D, TEXT = 0xFFF6F7FD;
    private static final int MUTED = 0xFFA2ABC2, ACCENT = 0xFF818CF8, BORDER = 0xFF292F42;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout screen, body, header;
    private ScrollView scroll;
    private Uri currentUri;
    private String currentName = "", currentMime = "application/octet-stream";
    private File currentFile;
    private PdfRenderer pdf;
    private ParcelFileDescriptor pdfFd;
    private int pdfIndex;
    private ImageView pdfImage;
    private TextView pdfCounter;
    private MediaPlayer audio;
    private VideoView video;
    private Runnable audioUpdater;
    private WebView web;
    private ZipFile activeArchive;
    private boolean inViewer = false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(0);
        initLayout();
        showHome();
        consumeIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        consumeIntent(intent);
    }

    private void consumeIntent(Intent intent) {
        if (intent == null) return;
        Uri uri = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction())) uri = intent.getData();
        else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            if (Build.VERSION.SDK_INT >= 33) uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
            else uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        if (uri != null) openUri(uri, intent.getFlags());
    }

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + .5f); }
    private TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setIncludeFontPadding(true);
        return t;
    }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private android.graphics.drawable.GradientDrawable shape(int color, int r) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(color); g.setCornerRadius(dp(r)); return g;
    }
    private LinearLayout card() {
        LinearLayout c=column(); c.setBackground(shape(CARD,16)); c.setPadding(dp(16),dp(16),dp(16),dp(16)); return c;
    }
    private void add(ViewGroup p, View v) { p.addView(v); }
    private void gap(ViewGroup p, int h) { View v = new View(this); p.addView(v,new ViewGroup.LayoutParams(1,dp(h))); }
    private void padVertical(View v, int top, int bottom) { v.setPadding(v.getPaddingLeft(),dp(top),v.getPaddingRight(),dp(bottom)); }
    private void fillCard(LinearLayout c) { LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.bottomMargin=dp(12); body.addView(c,lp); }
    private Button button(String label, boolean primary, View.OnClickListener click) {
        Button b=new Button(this); b.setText(label); b.setTextColor(primary?Color.WHITE:TEXT);
        b.setTextSize(13); b.setAllCaps(false); b.setMinHeight(dp(48));
        b.setBackground(shape(primary?0xFF545BEA:0xFF30374A,13)); b.setOnClickListener(click); return b;
    }
    private void initLayout() {
        screen=column(); screen.setBackgroundColor(BG);
        setContentView(screen);
        header=row(); header.setPadding(dp(20),dp(14),dp(20),dp(12));
        screen.addView(header,new LinearLayout.LayoutParams(-1,-2));
        View separator=new View(this); separator.setBackgroundColor(BORDER); screen.addView(separator,new LinearLayout.LayoutParams(-1,dp(1)));
        scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setVerticalScrollBarEnabled(false);
        screen.addView(scroll,new LinearLayout.LayoutParams(-1,0,1f));
        body=column(); body.setPadding(dp(20),dp(22),dp(20),dp(30)); scroll.addView(body,new ScrollView.LayoutParams(-1,-2));
    }
    private void reset() {
        stopResources(); body.removeAllViews(); header.removeAllViews();
        scroll.scrollTo(0,0);
    }
    private void stopResources() {
        if (audioUpdater != null) { handler.removeCallbacks(audioUpdater); audioUpdater=null; }
        if (audio != null) { try { audio.stop(); } catch(Exception ignored) {} audio.release(); audio=null; }
        if (video != null) { try { video.stopPlayback(); } catch(Exception ignored) {} video=null; }
        if (web != null) { web.stopLoading(); web.destroy(); web=null; }
        if (activeArchive != null) { try { activeArchive.close(); } catch(Exception ignored) {} activeArchive=null; }
        if (pdf != null) { pdf.close(); pdf=null; }
        if (pdfFd != null) { try { pdfFd.close(); } catch(Exception ignored) {} pdfFd=null; }
    }
    private void buildHeader(String title, boolean back) {
        if (back) {
            TextView arrow=text("‹",34,TEXT,false);
            arrow.setGravity(Gravity.CENTER); header.addView(arrow,new LinearLayout.LayoutParams(dp(36),dp(42)));
            arrow.setOnClickListener(v->showHome());
        } else {
            TextView logo=text("▣",30,ACCENT,true);
            header.addView(logo,new LinearLayout.LayoutParams(dp(42),-2));
        }
        LinearLayout titles=column();
        add(titles,text(title,17,TEXT,true)); add(titles,text("UNIVERSAL FILE VIEWER",9,MUTED,true));
        header.addView(titles,new LinearLayout.LayoutParams(0,-2,1f));
        TextView right=text("+",28,ACCENT,false); right.setGravity(Gravity.CENTER);
        header.addView(right,new LinearLayout.LayoutParams(dp(43),dp(42)));
        right.setOnClickListener(v->pickFile());
    }
    private void pickFile() {
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(intent,OPEN_REQUEST); }
        catch(Exception e) { message("File picker not available"); }
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==OPEN_REQUEST && resultCode==RESULT_OK && data!=null && data.getData()!=null) {
            openUri(data.getData(), data.getFlags());
        }
    }
    private void message(String s) { Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }
    private void section(String s) { TextView t=text(s,15,TEXT,true); t.setPadding(0,dp(8),0,dp(14)); add(body,t); }
    private void showHome() {
        inViewer=false; reset(); buildHeader("Omni Files",false);
        add(body,text("Your files,\none viewer.",32,TEXT,true));
        gap(body,10);
        add(body,text("Preview popular formats offline. For specialized formats, use the app that supports them.",14,MUTED,false));
        gap(body,24);
        LinearLayout primary=card(); primary.setBackground(shape(0xFF262953,19));
        add(primary,text("📂  Choose a file",21,TEXT,true)); gap(primary,7);
        add(primary,text("Browse your phone, SD card or cloud documents",13,0xFFC8CAF3,false)); gap(primary,17);
        add(primary,button("Open file picker",true,v->pickFile())); fillCard(primary);
        gap(body,18);
        section("Recently opened");
        JSONArray rec=recent();
        if(rec.length()==0) {
            LinearLayout empty=card(); add(empty,text("No recent files yet",15,TEXT,true));
            gap(empty,5); add(empty,text("Files you open will appear here.",12,MUTED,false)); fillCard(empty);
        } else {
            for(int i=0;i<rec.length() && i<10;i++) {
                JSONObject item=rec.optJSONObject(i); if(item==null)continue;
                String name=item.optString("name","File"), uri=item.optString("uri", "");
                if(uri.isEmpty())continue;
                LinearLayout itemCard=card(); LinearLayout r=row();
                TextView glyph=text(iconFor(name),24,ACCENT,false);
                r.addView(glyph,new LinearLayout.LayoutParams(dp(44),-2));
                LinearLayout labels=column(); add(labels,text(name,14,TEXT,true));
                add(labels,text(ext(name).toUpperCase(Locale.ROOT)+"  ·  Tap to reopen",11,MUTED,false));
                r.addView(labels,new LinearLayout.LayoutParams(0,-2,1)); add(itemCard,r); fillCard(itemCard);
                itemCard.setOnClickListener(v->openUri(Uri.parse(uri),0));
            }
        }
        gap(body,17);
        section("Built-in previews");
        LinearLayout formats=card();
        add(formats,text("PDF  ·  Images  ·  GIF  ·  SVG",14,TEXT,true)); gap(formats,12);
        add(formats,text("Audio  ·  Video  ·  HTML  ·  EPUB",14,TEXT,true)); gap(formats,12);
        add(formats,text("DOCX  ·  XLSX  ·  PPTX  ·  ODF",14,TEXT,true)); gap(formats,12);
        add(formats,text("ZIP  ·  JSON  ·  CSV  ·  Code  ·  Fonts",14,TEXT,true)); gap(formats,12);
        add(formats,text("SQLite databases  ·  Unknown binary (hex)",13,MUTED,false)); fillCard(formats);
        gap(body,12);
        add(body,text("Private by design: no account, no upload and no storage permission. Files stay on this device.",12,MUTED,false));
    }
    private JSONArray recent() {
        try{return new JSONArray(getPreferences(MODE_PRIVATE).getString("recent","[]"));}
        catch(Exception e){return new JSONArray();}
    }
    private void saveRecent(String name,Uri uri) {
        if(uri==null) return;
        try {
            JSONArray old=recent(), next=new JSONArray();
            JSONObject item=new JSONObject(); item.put("name",name); item.put("uri",uri.toString()); next.put(item);
            for(int i=0;i<old.length() && next.length()<15;i++) {
                JSONObject o=old.optJSONObject(i);
                if(o!=null && !uri.toString().equals(o.optString("uri")))next.put(o);
            }
            getPreferences(MODE_PRIVATE).edit().putString("recent",next.toString()).apply();
        } catch(Exception ignored){}
    }
    private static String ext(String n) {
        int i=n.lastIndexOf('.'); return i<0?"FILE":n.substring(i+1).toLowerCase(Locale.ROOT);
    }
    private static String iconFor(String name) {
        String e=ext(name);
        if(e.equals("pdf"))return "📕";
        if(Arrays.asList("jpg","jpeg","png","webp","gif","heic","heif","bmp","svg","avif").contains(e))return "🏞";
        if(Arrays.asList("mp4","mov","mkv","3gp","avi","webm").contains(e))return "🎬";
        if(Arrays.asList("mp3","wav","ogg","flac","m4a","aac").contains(e))return "♫";
        if(Arrays.asList("zip","apk","jar","cbz","epub").contains(e))return "🗂";
        if(Arrays.asList("doc","docx","odt","rtf").contains(e))return "📘";
        if(Arrays.asList("xls","xlsx","ods","csv").contains(e))return "📊";
        if(Arrays.asList("ppt","pptx","odp").contains(e))return "📙";
        return "📄";
    }
    private void openUri(Uri uri, int flags) {
        if(uri==null)return;
        try {
            if((flags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)!=0)
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch(Exception ignored) {}
        String name=displayName(uri);
        String mime=getContentResolver().getType(uri);
        if(mime==null || mime.equals("application/octet-stream") || mime.equals("*/*"))mime=guessMime(name);
        final String mimeType=mime;
        currentUri=uri; currentName=name; currentMime=mimeType; currentFile=null;
        saveRecent(name,uri);
        String e=ext(name);
        if(isVideo(e,mimeType)) { showDocument(null,name,mimeType,uri); return; }
        if(isAudio(e,mimeType)) { showDocument(null,name,mimeType,uri); return; }
        reset(); inViewer=true; buildHeader("Opening file",true);
        add(body,text(name,20,TEXT,true)); gap(body,17);
        add(body,text("Preparing preview…",14,MUTED,false)); gap(body,18);
        ProgressBar spinner=new ProgressBar(this); body.addView(spinner,new LinearLayout.LayoutParams(dp(36),dp(36)));
        new Thread(()->{
            try {
                File cache=new File(getCacheDir(),"opened"); if(!cache.exists()&&!cache.mkdirs())throw new Exception("Cache unavailable");
                // Bound copies to prevent exhausting storage. Streamed media is handled separately.
                File out=File.createTempFile("preview_","."+e,cache);
                try (InputStream in=getContentResolver().openInputStream(uri); OutputStream os=new FileOutputStream(out)) {
                    if(in==null) throw new Exception("No permission to read file");
                    byte[] b=new byte[65536]; long count=0; int n;
                    while((n=in.read(b))!=-1) {
                        count+=n; if(count>180L*1024*1024)throw new Exception("File too large for internal preview (180 MB limit)");
                        os.write(b,0,n);
                    }
                } catch(Exception e1) { out.delete(); throw e1; }
                handler.post(()->showDocument(out,name,mimeType,uri));
            } catch(Exception exception) {
                handler.post(()->showUnsupported(name,uri,"Can't prepare preview: "+exception.getMessage()));
            }
        }).start();
    }
    private String displayName(Uri uri) {
        if("content".equals(uri.getScheme())) {
            try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)) {
                if(c!=null && c.moveToFirst())return c.getString(0);
            }catch(Exception ignored){}
        }
        String path=uri.getLastPathSegment(); return path==null?"File":path.replaceAll("[\\r\\n]", " ");
    }
    private String guessMime(String name) {
        String e=ext(name);
        String type=android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(e);
        return type==null?"application/octet-stream":type;
    }
    private boolean isVideo(String e,String mime) {
        return mime.startsWith("video/") || Arrays.asList("mp4","mkv","3gp","webm","mov","avi","m4v","ts").contains(e);
    }
    private boolean isAudio(String e,String mime) {
        return mime.startsWith("audio/") || Arrays.asList("mp3","wav","m4a","aac","flac","ogg","oga","opus","mid","midi","amr","wma").contains(e);
    }
    private boolean isImage(String e,String mime) {
        return mime.startsWith("image/") || Arrays.asList("png","jpg","jpeg","webp","gif","bmp","heic","heif","avif","tif","tiff").contains(e);
    }
    private boolean isText(String e,String mime) {
        if(mime.startsWith("text/") || mime.equals("application/json") || mime.equals("application/xml"))return true;
        return Arrays.asList("txt","log","md","markdown","json","jsonl","csv","tsv","xml","yaml","yml","ini","conf","cfg","properties","java","kt","js","jsx","ts","tsx","py","rb","php","sh","bat","ps1","go","rs","c","h","cpp","hpp","css","scss","sql","gradle","toml","env","gitignore","vcf","ics","srt","vtt","ass","tex","ipynb","asm","dart","swift").contains(e);
    }
    private void showDocument(File file,String name,String mime,Uri original) {
        reset(); inViewer=true; currentFile=file; currentUri=original; currentName=name; currentMime=mime;
        buildHeader("File preview",true);
        LinearLayout heading=card();
        add(heading,text(iconFor(name)+"  "+name,19,TEXT,true)); gap(heading,7);
        String size=file==null?"Streaming from document provider":formatSize(file.length());
        add(heading,text(ext(name).toUpperCase(Locale.ROOT)+"  ·  "+size,12,MUTED,false));
        gap(heading,14);
        LinearLayout actions=row();
        Button other=button("Open in app",false,v->externalOpen());
        Button share=button("Share",false,v->shareOriginal());
        actions.addView(other,new LinearLayout.LayoutParams(0,dp(45),1));
        View spacer=new View(this); actions.addView(spacer,new LinearLayout.LayoutParams(dp(10),1));
        actions.addView(share,new LinearLayout.LayoutParams(0,dp(45),1));
        other.setEnabled(original!=null); share.setEnabled(original!=null);
        add(heading,actions); fillCard(heading);
        try {
            String e=ext(name);
            if(isVideo(e,mime))showVideo(original);
            else if(isAudio(e,mime))showAudio(original);
            else if(e.equals("pdf"))showPdf(file);
            else if(e.equals("svg"))showMarkup(Extractor.readText(file,2_000_000),"image/svg+xml");
            else if(isImage(e,mime))showImage(file);
            else if(e.equals("htm")||e.equals("html")||e.equals("xhtml"))showMarkup(Extractor.readText(file,2_000_000),"text/html");
            else if(e.equals("epub"))showEpub(file);
            else if(e.equals("docx")||e.equals("xlsx")||e.equals("pptx")||e.equals("odt")||e.equals("ods")||e.equals("odp"))showOffice(file,e);
            else if(e.equals("zip")||e.equals("jar")||e.equals("cbz")||e.equals("apk"))showArchive(file);
            else if(e.equals("ttf")||e.equals("otf"))showFont(file);
            else if(e.equals("db")||e.equals("sqlite")||e.equals("sqlite3"))showDatabase(file);
            else if(isText(e,mime))showText(file,e);
            else showBinary(file,"No built-in decoder for this format. You can inspect bytes or open it with a specialized app.");
        }catch(Exception error) {
            showBinary(file,"Preview unavailable: "+error.getMessage());
        }
    }
    private static String formatSize(long bytes) {
        if(bytes<1024)return bytes+" B";
        if(bytes<1048576)return String.format(Locale.ROOT,"%.1f KB",bytes/1024.0);
        return String.format(Locale.ROOT,"%.1f MB",bytes/1048576.0);
    }
    private void showUnsupported(String name,Uri uri,String error) {
        reset(); inViewer=true; currentUri=uri; currentName=name; currentFile=null;
        buildHeader("File details",true);
        LinearLayout card=card(); add(card,text("Preview unavailable",22,TEXT,true)); gap(card,8);
        add(card,text(error,14,MUTED,false)); gap(card,16);
        add(card,button("Try another app",true,v->externalOpen())); fillCard(card);
    }
    private void externalOpen() {
        if(currentUri==null){message("This is an extracted preview. Reopen the original file in another app.");return;}
        try {
            Intent i=new Intent(Intent.ACTION_VIEW); i.setDataAndType(currentUri,currentMime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); i.setClipData(ClipData.newUri(getContentResolver(),currentName,currentUri));
            startActivity(Intent.createChooser(i,"Open with"));
        }catch(Exception e){message("No compatible external app found.");}
    }
    private void shareOriginal() {
        if(currentUri==null)return;
        try {
            Intent i=new Intent(Intent.ACTION_SEND); i.setType(currentMime);
            i.putExtra(Intent.EXTRA_STREAM,currentUri); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.setClipData(ClipData.newUri(getContentResolver(),currentName,currentUri));
            startActivity(Intent.createChooser(i,"Share file"));
        }catch(Exception e){message("Sharing unavailable.");}
    }
    private void showText(File file,String e)throws Exception {
        String data=Extractor.readText(file,2_000_000);
        if(e.equals("json")) {
            try {data=new JSONObject(data).toString(2);} catch(Exception ignored) {
                try{data=new JSONArray(data).toString(2);}catch(Exception ignored2){}
            }
        }
        if(data.isEmpty()) data="(Empty file)";
        section("Text preview");
        LinearLayout c=card(); TextView t=text(data,13,TEXT,false);
        t.setTextIsSelectable(true); t.setTypeface(Typeface.MONOSPACE); t.setLineSpacing(dp(3),1f);
        add(c,t); fillCard(c);
        if(file.length()>2_000_000)showNote("Preview limited to the first 2 MB. Open in a dedicated editor for full content.");
    }
    private void showNote(String msg) { TextView t=text(msg,12,MUTED,false); t.setPadding(0,dp(7),0,dp(14));add(body,t); }
    private void showImage(File file)throws Exception {
        section("Image preview");
        if(file==null)throw new Exception("Image unavailable");
        ImageView image=new ImageView(this); image.setAdjustViewBounds(true); image.setBackground(shape(CARD,12));
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if(Build.VERSION.SDK_INT>=28) {
            ImageDecoder.Source src=ImageDecoder.createSource(file);
            Drawable drawable=ImageDecoder.decodeDrawable(src,(decoder,info,s)-> {
                int w=info.getSize().getWidth(),h=info.getSize().getHeight();
                int max=Math.max(w,h);
                if(max>3500)decoder.setTargetSampleSize((int)Math.ceil(max/3500.0));
            });
            image.setImageDrawable(drawable);
            if(drawable instanceof AnimatedImageDrawable)((AnimatedImageDrawable)drawable).start();
        } else {
            BitmapFactory.Options options=new BitmapFactory.Options(); options.inJustDecodeBounds=true;
            BitmapFactory.decodeFile(file.getPath(),options);
            options.inSampleSize=Math.max(1, (int)Math.ceil(Math.max(options.outWidth,options.outHeight)/3500.0));
            options.inJustDecodeBounds=false;
            image.setImageBitmap(BitmapFactory.decodeFile(file.getPath(),options));
        }
        body.addView(image,new LinearLayout.LayoutParams(-1,-2));
        showNote("Supports formats decoded by your Android device. Animated GIFs require Android 9+.");
    }
    private void showMarkup(String markup,String mime) {
        section("Rendered preview");
        web=new WebView(this); WebSettings settings=web.getSettings();
        settings.setJavaScriptEnabled(false);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);
        settings.setBlockNetworkLoads(true); settings.setBuiltInZoomControls(true); settings.setDisplayZoomControls(false);
        web.setBackgroundColor(Color.WHITE);
        body.addView(web,new LinearLayout.LayoutParams(-1,dp(580)));
        web.loadDataWithBaseURL(null,markup,mime,"UTF-8",null);
        showNote("Offline preview; external images, fonts and scripts are intentionally blocked.");
    }
    private void showPdf(File file)throws Exception {
        if(file==null)throw new Exception("No local PDF");
        pdfFd=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);
        pdf=new PdfRenderer(pdfFd); if(pdf.getPageCount()<1)throw new Exception("PDF contains no pages");
        pdfIndex=0; section("PDF document");
        LinearLayout pager=row();
        Button previous=button("‹ Previous",false,v->{if(pdfIndex>0){pdfIndex--;renderPdf();}});
        pager.addView(previous,new LinearLayout.LayoutParams(0,dp(44),1));
        pdfCounter=text("",13,MUTED,true);pdfCounter.setGravity(Gravity.CENTER);
        pager.addView(pdfCounter,new LinearLayout.LayoutParams(0,dp(44),1));
        Button next=button("Next ›",false,v->{if(pdfIndex<pdf.getPageCount()-1){pdfIndex++;renderPdf();}});
        pager.addView(next,new LinearLayout.LayoutParams(0,dp(44),1));
        add(body,pager);gap(body,12);
        pdfImage=new ImageView(this); pdfImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        pdfImage.setAdjustViewBounds(true); pdfImage.setBackgroundColor(Color.WHITE);
        body.addView(pdfImage,new LinearLayout.LayoutParams(-1,-2));
        renderPdf(); showNote("Page-by-page preview. Encrypted/password-protected PDFs may require a dedicated PDF app.");
    }
    private void renderPdf() {
        try(PdfRenderer.Page page=pdf.openPage(pdfIndex)) {
            float density=getResources().getDisplayMetrics().density;
            float targetW=Math.min(2100,Math.max(850,getResources().getDisplayMetrics().widthPixels*1.5f));
            float scale=targetW/page.getWidth();
            int w=Math.max(1,(int)(page.getWidth()*scale));
            int h=Math.max(1,(int)(page.getHeight()*scale));
            if(h>3100){w=(int)(w*(3100.0/h)); h=3100;}
            Bitmap bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.WHITE);page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            pdfImage.setImageBitmap(bitmap);
            pdfCounter.setText((pdfIndex+1)+" / "+pdf.getPageCount());
        }catch(Exception e){message("Unable to render PDF page: "+e.getMessage());}
    }
    private void showVideo(Uri uri) {
        if(uri==null){showNote("Video source not available.");return;}
        section("Video player");
        video=new VideoView(this);video.setBackgroundColor(Color.BLACK);
        body.addView(video,new LinearLayout.LayoutParams(-1,dp(260)));
        MediaController controller=new MediaController(this);controller.setAnchorView(video);
        video.setMediaController(controller);video.setVideoURI(uri);
        video.setOnPreparedListener(mp->{video.start();controller.show(3000);});
        video.setOnErrorListener((mp,what,extra)->{message("Video codec not supported on this device.");return true;});
        showNote("Playback depends on codecs installed in Android. Some MKV/AVI/MOV files may need VLC.");
    }
    private void showAudio(Uri uri) {
        if(uri==null){showNote("Audio source not available.");return;}
        section("Audio player");
        LinearLayout c=card(); add(c,text("♫",55,ACCENT,true));gap(c,12);
        TextView state=text("Loading audio…",13,MUTED,false);add(c,state); gap(c,12);
        SeekBar seek=new SeekBar(this);add(c,seek);gap(c,12);
        Button toggle=button("Play / Pause",true,v->{
            if(audio==null)return;
            try{if(audio.isPlaying())audio.pause();else audio.start();}catch(Exception ignored){}
        });add(c,toggle);fillCard(c);
        try {
            audio=new MediaPlayer();audio.setDataSource(this,uri);
            audio.setOnPreparedListener(mp->{seek.setMax(mp.getDuration());state.setText("Ready to play");});
            audio.setOnErrorListener((mp,what,extra)->{state.setText("Unsupported audio codec");return true;});
            audio.prepareAsync();
            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
                @Override public void onProgressChanged(SeekBar b,int p,boolean fromUser){if(fromUser&&audio!=null)audio.seekTo(p);}
                @Override public void onStartTrackingTouch(SeekBar b){}
                @Override public void onStopTrackingTouch(SeekBar b){}
            });
            audioUpdater=new Runnable(){@Override public void run(){
                if(audio==null)return;
                try {
                    if(audio.isPlaying()){seek.setProgress(audio.getCurrentPosition());state.setText(formatTime(audio.getCurrentPosition())+" / "+formatTime(audio.getDuration()));}
                } catch(Exception ignored){}
                handler.postDelayed(this,500);
            }}; handler.post(audioUpdater);
        }catch(Exception e){state.setText("Unable to play: "+e.getMessage());}
    }
    private static String formatTime(int ms) {return String.format(Locale.ROOT,"%d:%02d",ms/60000,(ms/1000)%60);}
    private void showOffice(File file,String extension)throws Exception {
        section("Office document · text/data preview");
        String content=Extractor.extractOffice(file,extension);
        LinearLayout c=card();
        TextView t=text(content,14,TEXT,false);t.setTextIsSelectable(true);
        t.setLineSpacing(dp(4),1f); add(c,t);fillCard(c);
        showNote("This is a readable extraction, NOT an exact Microsoft Office layout. Tables, graphs, shapes, fonts and pagination can differ. Use Microsoft Office/LibreOffice for faithful rendering.");
    }
    private void showEpub(File file)throws Exception {
        section("EPUB ebook");
        String content=Extractor.extractEpub(file);
        LinearLayout c=card(); TextView t=text(content,15,TEXT,false);
        t.setTextIsSelectable(true);t.setLineSpacing(dp(6),1.2f);
        add(c,t);fillCard(c);
        showNote("Text-only EPUB preview, with a chapter/size limit. For full ebook styling use an EPUB reader.");
    }
    private void showArchive(File file)throws Exception {
        section("Archive contents");
        ZipFile zip=new ZipFile(file);
        activeArchive=zip;
        ArrayList<? extends ZipEntry> entries=Collections.list(zip.entries());
        entries.sort(Comparator.comparing(ZipEntry::getName,String.CASE_INSENSITIVE_ORDER));
        showNote(entries.size()+" entries. Tap a file to extract and preview (max 50 MB).");
        int shown=0;
        for(ZipEntry entry:entries) {
            if(entry.isDirectory())continue;
            if(++shown>250){showNote("Only first 250 entries shown.");break;}
            final String path=entry.getName();
            LinearLayout c=card();add(c,text(iconFor(path)+"  "+path,13,TEXT,true));
            add(c,text(formatSize(entry.getSize())+"  ·  Tap to preview",11,MUTED,false));
            fillCard(c);
            c.setOnClickListener(v->{
                try {
                    if(entry.getSize()>50*1024*1024)throw new Exception("Archive entry exceeds 50 MB");
                    File temp=File.createTempFile("archive_","."+ext(path),getCacheDir());
                    try(InputStream in=zip.getInputStream(entry);OutputStream out=new FileOutputStream(temp)) {
                        byte[] buf=new byte[32768];long copied=0;int n;
                        while((n=in.read(buf))!=-1){copied+=n;if(copied>50*1024*1024)throw new Exception("Extracted entry too large");out.write(buf,0,n);}
                    }catch(Exception e){temp.delete();throw e;}
                    showDocument(temp,path,guessMime(path),null);
                }catch(Exception e){message("Can't preview entry: "+e.getMessage());}
            });
        }
        // The OS will release the original archive when the viewer is closed.
    }
    private void showFont(File file) {
        section("Font preview");
        LinearLayout c=card();
        TextView sample=text("Aa Bb Cc Dd Ee\n1234567890\nThe quick brown fox jumps over the lazy dog.",23,TEXT,false);
        try { sample.setTypeface(Typeface.createFromFile(file)); }
        catch(Exception e){showNote("Font can't be decoded: "+e.getMessage());}
        add(c,sample);fillCard(c);
    }
    private void showDatabase(File file) throws Exception {
        section("SQLite database · read-only");
        String content=Extractor.inspectSqlite(file);
        LinearLayout c=card();TextView t=text(content,13,TEXT,false);
        t.setTypeface(Typeface.MONOSPACE); t.setTextIsSelectable(true);add(c,t);fillCard(c);
        showNote("First 10 rows per table; database is opened read-only and never modified.");
    }
    private void showBinary(File file,String info) {
        section("File details / binary preview");
        LinearLayout c=card();add(c,text(info,13,MUTED,false));gap(c,14);
        if(file!=null){
            try {
                String hex=Extractor.hexPreview(file,2048);
                TextView t=text(hex,11,TEXT,false); t.setTypeface(Typeface.MONOSPACE); t.setTextIsSelectable(true);
                add(c,t);
            }catch(Exception e){add(c,text("No readable raw bytes: "+e.getMessage(),12,MUTED,false));}
        }
        fillCard(c);
        showNote("Raw byte display does not mean the original file format is rendered. Proprietary or encrypted formats need specialized software.");
    }
    @Override public void onBackPressed() {
        if(inViewer)showHome(); else super.onBackPressed();
    }
    @Override protected void onDestroy() {stopResources();super.onDestroy();}
}
