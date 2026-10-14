package com.lucianotoscano.pokesearchoverlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public class OverlayService extends Service {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private WindowManager.LayoutParams params;
    private FrameLayout root;
    private WebView panel,website;
    private boolean minimized=false,shiny=true,compact=true,loaded=false,panelLoaded=false,stopped=false;
    private int height=195;
    private String mode="iv", results="[]", status="Conectando ao PoGoCoordinates...";
    private final Runnable periodic=new Runnable(){@Override public void run(){
        if(!stopped){refresh();handler.postDelayed(this,300000L);}
    }};
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private void js(String expr){if(panelLoaded && panel!=null)panel.evaluateJavascript(expr,null);}
    private void update(){js("window.onNativeData("+results+","+JSONObject.quote(status)+","+JSONObject.quote(mode)+","+shiny+","+compact+")");}
    private void status(String value){status=value;update();}
    @Override public void onCreate(){
        super.onCreate();
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("pokesearch","Pokésearch Overlay",NotificationManager.IMPORTANCE_LOW));
        PendingIntent pi=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification=new Notification.Builder(this,"pokesearch")
            .setSmallIcon(android.R.drawable.ic_menu_search).setContentTitle("Pokésearch Overlay ativo")
            .setContentText("Toque para abrir o site").setContentIntent(pi).setOngoing(true).build();
        startForeground(73,notification);
        wm=(WindowManager)getSystemService(WINDOW_SERVICE);
        root=new FrameLayout(this);
        root.setBackgroundColor(Color.TRANSPARENT);
        website=new WebView(this);
        website.getSettings().setJavaScriptEnabled(true);
        website.getSettings().setDomStorageEnabled(true);
        website.setAlpha(0f);
        root.addView(website,new FrameLayout.LayoutParams(dp(390),dp(720),Gravity.BOTTOM|Gravity.RIGHT));
        website.setWebViewClient(new WebViewClient(){
            @Override public void onPageFinished(WebView view,String url){
                if(url.startsWith("https://pogocoordinates.com/")){
                    loaded=true;status("Site conectado. Buscando...");
                    handler.postDelayed(()->refresh(),3000L);
                }
            }
        });
        panel=new WebView(this);
        panel.setBackgroundColor(Color.TRANSPARENT);
        panel.getSettings().setJavaScriptEnabled(true);
        panel.addJavascriptInterface(new Bridge(),"Native");
        root.addView(panel,new FrameLayout.LayoutParams(-1,-1));
        panel.setWebViewClient(new WebViewClient(){
            @Override public void onPageFinished(WebView view,String url){
                if(url.startsWith("file:///android_asset/")){panelLoaded=true;update();}
            }
        });
        params=new WindowManager.LayoutParams(dp(318),dp(height),WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT);
        params.gravity=Gravity.TOP|Gravity.LEFT;
        params.x=dp(10);params.y=dp(160);
        try{wm.addView(root,params);}
        catch(Exception e){stopSelf();return;}
        panel.loadUrl("file:///android_asset/overlay.html");
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
    private void execute(String action,java.util.function.Consumer<JSONObject> callback){
        if(!loaded || website==null){status("Abra o aplicativo e faça login no site.");return;}
        String script=script().replace("__ACTION__",JSONObject.quote(action))
                .replace("__MODE__",JSONObject.quote(mode)).replace("__SHINY__",shiny?"true":"false");
        website.evaluateJavascript(script,raw->{
            try{
                String decoded=new JSONArray("["+raw+"]").getString(0);
                JSONObject response=new JSONObject(decoded);
                if(callback!=null)callback.accept(response);
            }catch(Exception e){status("Não foi possível ler a página. Confira o login.");}
        });
    }
    private void refresh(){
        if(!loaded){status("Aguardando a página...");return;}
        status("Buscando...");
        execute("search",response->{
            if(!response.optBoolean("ok"))status("Confira filtros e login no site.");
            handler.postDelayed(()->collect(),4200L);
        });
    }
    private void collect(){
        execute("collect",response->{
            JSONArray cards=response.optJSONArray("cards");
            if(cards!=null&&cards.length()>0){
                results=cards.toString();status("Atualizado · "+cards.length()+" resultados");
            }else{
                results="[]";status("Nenhum resultado encontrado; confira os filtros no site.");
            }
        });
    }
    private void resize(int cssHeight){
        if(minimized||params==null)return;
        int target=Math.max(78,Math.min(480,cssHeight));
        if(target==height)return;
        height=target;params.height=dp(height);
        try{wm.updateViewLayout(root,params);}catch(Exception ignored){}
    }
    private void minimize(boolean yes){
        minimized=yes;
        params.width=dp(yes?56:318);params.height=dp(yes?56:height);
        try{wm.updateViewLayout(root,params);}catch(Exception ignored){}
        js("window.setMinimized("+yes+")");
    }
    private void move(int dx,int dy){
        if(params==null)return;
        DisplayMetrics d=getResources().getDisplayMetrics();
        params.x=Math.max(0,Math.min(d.widthPixels-dp(minimized?56:318),params.x+dp(dx)));
        params.y=Math.max(0,Math.min(d.heightPixels-dp(56),params.y+dp(dy)));
        try{wm.updateViewLayout(root,params);}catch(Exception ignored){}
    }
    public class Bridge{
        @JavascriptInterface public void select(String value){handler.post(()->{
            if(!value.equals("iv")&&!value.equals("gl"))return;
            mode=value;results="[]";update();refresh();
        });}
        @JavascriptInterface public void shiny(){handler.post(()->{
            shiny=!shiny;results="[]";update();refresh();
        });}
        @JavascriptInterface public void refresh(){handler.post(OverlayService.this::refresh);}
        @JavascriptInterface public void minimize(){handler.post(()->OverlayService.this.minimize(true));}
        @JavascriptInterface public void restore(){handler.post(()->OverlayService.this.minimize(false));}
        @JavascriptInterface public void close(){handler.post(()->stopSelf());}
        @JavascriptInterface public void compact(){handler.post(()->{compact=!compact;update();});}
        @JavascriptInterface public void move(int dx,int dy){handler.post(()->OverlayService.this.move(dx,dy));}
        @JavascriptInterface public void resize(int val){handler.post(()->OverlayService.this.resize(val));}
        @JavascriptInterface public void coords(int index){handler.post(()->execute("coords:"+index,
            response->status(response.optString("status","Get coords enviado ao site"))));}
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){return START_STICKY;}
    @Override public void onDestroy(){
        stopped=true;handler.removeCallbacksAndMessages(null);
        if(wm!=null&&root!=null){try{wm.removeView(root);}catch(Exception ignored){}}
        if(panel!=null){panel.removeJavascriptInterface("Native");panel.destroy();panel=null;}
        if(website!=null){website.destroy();website=null;}
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
