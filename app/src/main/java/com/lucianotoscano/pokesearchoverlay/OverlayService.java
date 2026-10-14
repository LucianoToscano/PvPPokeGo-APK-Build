package com.lucianotoscano.pokesearchoverlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.function.Consumer;

public class OverlayService extends Service {
    private static final int DOCK_DP=52, BUBBLE_DP=45, DETAIL_DP=226;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private WindowManager.LayoutParams params,detailParams;
    private FrameLayout root,detailRoot;
    private View grip;
    private WebView panel,detailPanel,website;
    private boolean minimized=false,shiny=true,loaded=false,panelLoaded=false,detailLoaded=false,stopped=false;
    private int height=190,detailHeight=225,requestGeneration=0,selected=-1;
    private String mode="iv",results="[]",status="Conectando...";
    private float startRawX,startRawY;
    private int startX,startY;
    private boolean dragged=false;
    private final Runnable periodic=new Runnable(){
        @Override public void run(){
            if(!stopped){refresh();handler.postDelayed(this,300000L);}
        }
    };
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private DisplayMetrics metrics(){
        DisplayMetrics m=new DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(m);
        return m;
    }
    private int screenWidthDp(){DisplayMetrics m=metrics();return Math.round(m.widthPixels/m.density);}
    private int screenHeightDp(){DisplayMetrics m=metrics();return Math.round(m.heightPixels/m.density);}
    private void js(WebView web,boolean ready,String code){
        if(web!=null&&ready)web.evaluateJavascript(code,null);
    }
    private int count(){
        try{return new JSONArray(results).length();}catch(Exception e){return 0;}
    }
    private int rowDp(){
        int n=Math.max(1,count());
        int available=screenHeightDp()-160;
        return Math.max(25,Math.min(37,available/n));
    }
    private void update(){
        js(panel,panelLoaded,"window.onNativeData("+results+","+JSONObject.quote(status)+","+
                JSONObject.quote(mode)+","+shiny+","+rowDp()+")");
        if(selected>=0)showDetails(selected);
    }
    private void status(String s){status=s;update();}
    private void updateWindow(){
        if(wm!=null&&root!=null&&params!=null){
            try{wm.updateViewLayout(root,params);}catch(Exception ignored){}
        }
    }
    private void moveDetail(){
        if(detailRoot==null||detailParams==null||params==null)return;
        int w=screenWidthDp(),h=screenHeightDp(),gap=5;
        int right=params.x/dp(1)+DOCK_DP+gap;
        int left=params.x/dp(1)-DETAIL_DP-gap;
        int preferred=right+DETAIL_DP<=w-4?right:left;
        detailParams.x=dp(Math.max(0,Math.min(w-DETAIL_DP,preferred)));
        int dockY=params.y/dp(1);
        detailParams.y=dp(Math.max(0,Math.min(h-detailHeight-45,dockY+18)));
        try{wm.updateViewLayout(detailRoot,detailParams);}catch(Exception ignored){}
    }
    private void newWebsite(){
        website=new WebView(this);
        website.getSettings().setJavaScriptEnabled(true);
        website.getSettings().setDomStorageEnabled(true);
        website.setAlpha(0);
        website.setFocusable(false);
        website.setClickable(false);
        website.setOnTouchListener((v,e)->true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(website,true);
        root.addView(website,new FrameLayout.LayoutParams(dp(390),dp(690),Gravity.TOP|Gravity.LEFT));
        website.setWebViewClient(new WebViewClient(){
            @Override public void onPageFinished(WebView view,String url){
                if(url!=null&&url.startsWith("https://pogocoordinates.com/")){
                    loaded=true;
                    handler.postDelayed(()->{if(!stopped)refresh();},2500);
                }
            }
        });
    }
    @Override public void onCreate(){
        super.onCreate();
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("pokesearch","Pokésearch Overlay",
                NotificationManager.IMPORTANCE_LOW));
        PendingIntent pi=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification note=new Notification.Builder(this,"pokesearch")
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setContentTitle("Pokésearch Overlay ativo")
                .setContentText("Toque para abrir o PoGoCoordinates")
                .setContentIntent(pi).setOngoing(true).build();
        startForeground(73,note);
        wm=(WindowManager)getSystemService(WINDOW_SERVICE);
        root=new FrameLayout(this);
        root.setBackgroundColor(Color.TRANSPARENT);
        root.setClipChildren(true);
        newWebsite();
        panel=new WebView(this);
        panel.setBackgroundColor(Color.TRANSPARENT);
        panel.getSettings().setJavaScriptEnabled(true);
        panel.addJavascriptInterface(new Bridge(),"Native");
        root.addView(panel,new FrameLayout.LayoutParams(-1,-1));
        panel.setWebViewClient(new WebViewClient(){
            @Override public void onPageFinished(WebView v,String url){
                if(url!=null&&url.startsWith("file:///android_asset/")){
                    panelLoaded=true;update();
                }
            }
        });
        grip=new View(this);
        grip.setBackgroundColor(Color.TRANSPARENT);
        grip.setOnTouchListener((v,event)->{
            if(params==null)return true;
            switch(event.getActionMasked()){
                case MotionEvent.ACTION_DOWN:
                    startRawX=event.getRawX();startRawY=event.getRawY();
                    startX=params.x;startY=params.y;dragged=false;return true;
                case MotionEvent.ACTION_MOVE:
                    int dx=Math.round(event.getRawX()-startRawX);
                    int dy=Math.round(event.getRawY()-startRawY);
                    if(Math.abs(dx)+Math.abs(dy)>dp(4))dragged=true;
                    if(dragged){
                        DisplayMetrics d=metrics();
                        int winWidth=dp(minimized?BUBBLE_DP:DOCK_DP);
                        int winHeight=dp(minimized?BUBBLE_DP:height);
                        params.x=Math.max(0,Math.min(d.widthPixels-winWidth,startX+dx));
                        params.y=Math.max(0,Math.min(d.heightPixels-winHeight,startY+dy));
                        updateWindow();moveDetail();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if(minimized&&!dragged)minimize(false);
                    return true;
                case MotionEvent.ACTION_CANCEL:return true;
            }
            return true;
        });
        root.addView(grip,new FrameLayout.LayoutParams(dp(DOCK_DP),dp(12),Gravity.TOP|Gravity.LEFT));

        params=new WindowManager.LayoutParams(dp(DOCK_DP),dp(height),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity=Gravity.TOP|Gravity.LEFT;
        params.x=dp(Math.max(0,screenWidthDp()-DOCK_DP-7));params.y=dp(120);
        try{wm.addView(root,params);}catch(Exception e){stopSelf();return;}

        detailRoot=new FrameLayout(this);
        detailRoot.setBackgroundColor(Color.TRANSPARENT);
        detailPanel=new WebView(this);
        detailPanel.setBackgroundColor(Color.TRANSPARENT);
        detailPanel.getSettings().setJavaScriptEnabled(true);
        detailPanel.addJavascriptInterface(new Bridge(),"Native");
        detailRoot.addView(detailPanel,new FrameLayout.LayoutParams(-1,-1));
        detailPanel.setWebViewClient(new WebViewClient(){
            @Override public void onPageFinished(WebView v,String url){
                if(url!=null&&url.startsWith("file:///android_asset/")){
                    detailLoaded=true;
                    if(selected>=0)showDetails(selected);
                }
            }
        });
        detailParams=new WindowManager.LayoutParams(dp(DETAIL_DP),dp(detailHeight),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        detailParams.gravity=Gravity.TOP|Gravity.LEFT;
        detailParams.x=dp(15);detailParams.y=dp(135);
        detailRoot.setVisibility(View.GONE);
        try{wm.addView(detailRoot,detailParams);}catch(Exception e){detailRoot=null;}
        panel.loadUrl("file:///android_asset/dock.html");
        if(detailRoot!=null)detailPanel.loadUrl("file:///android_asset/detail.html");
        website.loadUrl("https://pogocoordinates.com/search");
        handler.postDelayed(periodic,300000L);
    }
    private String script(){
        try(InputStream in=getAssets().open("probe.js")){
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            byte[] buf=new byte[4096];int n;
            while((n=in.read(buf))!=-1)out.write(buf,0,n);
            return out.toString("UTF-8");
        }catch(Exception e){return "null";}
    }
    private void execute(String action,Consumer<JSONObject> callback){
        if(!loaded||website==null){status("Abra o app e faça login.");return;}
        String code=script().replace("__ACTION__",JSONObject.quote(action))
                .replace("__MODE__",JSONObject.quote(mode))
                .replace("__SHINY__",shiny?"true":"false");
        website.evaluateJavascript(code,raw->{
            try{
                String decoded=new JSONArray("["+raw+"]").getString(0);
                JSONObject r=new JSONObject(decoded);
                if(callback!=null)callback.accept(r);
            }catch(Exception e){status("Falha ao ler resultados no site.");}
        });
    }
    private void refresh(){
        if(!loaded){status("Aguardando login...");return;}
        final int generation=++requestGeneration;
        status("Atualizando...");
        execute("search",response->{
            if(generation!=requestGeneration)return;
            if(!response.optBoolean("ok"))status("Tentando consultar os filtros do site...");
            handler.postDelayed(()->{
                if(!stopped&&generation==requestGeneration)collect(generation);
            },4600L);
        });
    }
    private void collect(int generation){
        execute("collect",r->{
            if(stopped||generation!=requestGeneration)return;
            JSONArray items=r.optJSONArray("cards");
            results=items==null?"[]":items.toString();
            int n=count();
            if(selected>=n)hideDetails();
            if(r.optBoolean("uncertain"))status("IV não confirmado");
            else if(n==0)status("Sem resultados");
            else status(n+" Pokémon");
        });
    }
    private void resize(int h){
        if(minimized||params==null)return;
        int target=Math.max(78,Math.min(screenHeightDp()-95,h));
        if(Math.abs(target-height)<2)return;
        height=target;params.height=dp(height);updateWindow();moveDetail();
    }
    private void minimize(boolean yes){
        if(params==null)return;
        minimized=yes;
        if(yes)hideDetails();
        params.width=dp(yes?BUBBLE_DP:DOCK_DP);
        params.height=dp(yes?BUBBLE_DP:height);
        grip.setLayoutParams(new FrameLayout.LayoutParams(
                dp(yes?BUBBLE_DP:DOCK_DP),dp(yes?BUBBLE_DP:12),Gravity.TOP|Gravity.LEFT));
        js(panel,panelLoaded,"window.setMinimized("+yes+")");
        updateWindow();
    }
    private void showDetails(int index){
        if(detailRoot==null||detailPanel==null||!detailLoaded||minimized)return;
        try{
            JSONArray a=new JSONArray(results);
            if(index<0||index>=a.length()){hideDetails();return;}
            selected=index;
            JSONObject obj=a.getJSONObject(index);
            js(detailPanel,true,"window.showDetails("+obj.toString()+","+JSONObject.quote(mode)+")");
            detailRoot.setVisibility(View.VISIBLE);
            moveDetail();
            js(panel,panelLoaded,"window.highlight("+index+")");
        }catch(Exception e){hideDetails();}
    }
    private void hideDetails(){
        selected=-1;
        if(detailRoot!=null)detailRoot.setVisibility(View.GONE);
        js(panel,panelLoaded,"window.highlight(-1)");
    }
    private void resizeDetail(int heightDp){
        detailHeight=Math.max(90,Math.min(310,heightDp));
        if(detailParams!=null&&detailRoot!=null){
            detailParams.height=dp(detailHeight);moveDetail();
        }
    }
    private String extractCoordinates(String input){
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("(-?\\d{1,2}(?:\\.\\d+)?)\\s*[,;]\\s*(-?\\d{1,3}(?:\\.\\d+)?)").matcher(input);
        if(m.find()){
            try{
                double lat=Double.parseDouble(m.group(1)),lon=Double.parseDouble(m.group(2));
                if(Math.abs(lat)<=90&&Math.abs(lon)<=180)return m.group(1)+","+m.group(2);
            }catch(Exception ignored){}
        }
        return "";
    }
    private void deliverCoords(String coordinates,String message){
        js(detailPanel,detailLoaded,"window.coordsResult("+JSONObject.quote(coordinates)+","+JSONObject.quote(message)+")");
        status(message);
    }
    public class Bridge{
        @JavascriptInterface public void select(String s){handler.post(()->{
            if(!s.equals("iv")&&!s.equals("gl"))return;
            mode=s;getSharedPreferences("prefs",0).edit().putString("mode",s).apply();
            results="[]";hideDetails();update();refresh();
        });}
        @JavascriptInterface public void shiny(){handler.post(()->{
            shiny=!shiny;getSharedPreferences("prefs",0).edit().putBoolean("shiny",shiny).apply();
            results="[]";hideDetails();update();refresh();
        });}
        @JavascriptInterface public void refresh(){handler.post(OverlayService.this::refresh);}
        @JavascriptInterface public void minimize(){handler.post(()->OverlayService.this.minimize(true));}
        @JavascriptInterface public void restore(){handler.post(()->OverlayService.this.minimize(false));}
        @JavascriptInterface public void close(){handler.post(()->stopSelf());}
        @JavascriptInterface public void resize(int size){handler.post(()->OverlayService.this.resize(size));}
        @JavascriptInterface public void resizeDetail(int size){handler.post(()->OverlayService.this.resizeDetail(size));}
        @JavascriptInterface public void toggleDetail(int index){handler.post(()->{
            if(selected==index)hideDetails();else showDetails(index);
        });}
        @JavascriptInterface public void hideDetail(){handler.post(OverlayService.this::hideDetails);}
        @JavascriptInterface public void coords(int sourceIndex){handler.post(()->{
            final ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            final String before=clipboard!=null&&clipboard.hasPrimaryClip()&&clipboard.getPrimaryClip()!=null
                    &&clipboard.getPrimaryClip().getItemCount()>0?
                    String.valueOf(clipboard.getPrimaryClip().getItemAt(0).coerceToText(OverlayService.this)):"";
            execute("coords:"+sourceIndex,r->{
                if(!r.optBoolean("ok")){deliverCoords("",r.optString("status","Get coords indisponível"));return;}
                handler.postDelayed(()->{
                    String coord="";
                    try{
                        ClipData clip=clipboard==null?null:clipboard.getPrimaryClip();
                        if(clip!=null&&clip.getItemCount()>0){
                            String after=String.valueOf(clip.getItemAt(0).coerceToText(OverlayService.this)).trim();
                            if(!after.equals(before))coord=extractCoordinates(after);
                        }
                    }catch(Exception ignored){}
                    if(!coord.isEmpty()){deliverCoords(coord,"Coordenadas copiadas");return;}
                    execute("readCoords",found->deliverCoords(found.optString("coords",""),
                            found.optBoolean("ok")?"Coordenadas encontradas":
                            "O site não disponibilizou coordenadas. Abra o app para conferir."));
                },850L);
            });
        });}

    }
    @Override public int onStartCommand(Intent i,int flags,int id){
        mode=getSharedPreferences("prefs",0).getString("mode",mode);
        shiny=getSharedPreferences("prefs",0).getBoolean("shiny",shiny);
        update();
        return START_STICKY;
    }
    @Override public void onDestroy(){
        stopped=true;handler.removeCallbacksAndMessages(null);
        if(wm!=null&&root!=null){try{wm.removeView(root);}catch(Exception ignored){}}
        if(wm!=null&&detailRoot!=null){try{wm.removeView(detailRoot);}catch(Exception ignored){}}
        if(panel!=null){panel.removeJavascriptInterface("Native");panel.destroy();panel=null;}
        if(detailPanel!=null){detailPanel.removeJavascriptInterface("Native");detailPanel.destroy();detailPanel=null;}
        if(website!=null){website.destroy();website=null;}
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
