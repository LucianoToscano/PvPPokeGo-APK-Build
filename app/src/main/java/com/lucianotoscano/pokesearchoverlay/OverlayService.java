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
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
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
    private static final int WIDTH_DP=270;
    private static final int BUBBLE_DP=46;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private WindowManager.LayoutParams params;
    private FrameLayout root;
    private View dragHandle;
    private WebView panel,website;
    private boolean minimized=false,shiny=true,compact=true,loaded=false,panelLoaded=false,stopped=false;
    private int height=139,requestGeneration=0;
    private String mode="iv",results="[]",status="Conectando...";
    private float downRawX,downRawY;
    private int initialX,initialY;
    private boolean dragged=false;
    private final Runnable periodic=new Runnable(){
        @Override public void run(){
            if(!stopped){refresh();handler.postDelayed(this,300000L);}
        }
    };
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private void js(String s){if(panelLoaded&&panel!=null)panel.evaluateJavascript(s,null);}
    private void update(){js("window.onNativeData("+results+","+JSONObject.quote(status)+","+JSONObject.quote(mode)+","+shiny+","+compact+")");}
    private void status(String value){status=value;update();}
    private void updateWindow(){
        if(wm!=null && params!=null && root!=null){
            try{wm.updateViewLayout(root,params);}catch(Exception ignored){}
        }
    }
    @Override public void onCreate(){
        super.onCreate();
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("pokesearch","Pokésearch Overlay",NotificationManager.IMPORTANCE_LOW));
        PendingIntent pi=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n=new Notification.Builder(this,"pokesearch")
                .setSmallIcon(android.R.drawable.ic_menu_search).setContentTitle("Pokésearch Overlay ativo")
                .setContentText("Abra para autenticar no PoGoCoordinates").setContentIntent(pi)
                .setOngoing(true).build();
        startForeground(73,n);
        wm=(WindowManager)getSystemService(WINDOW_SERVICE);
        root=new FrameLayout(this);
        root.setBackgroundColor(Color.TRANSPARENT);
        root.setClipChildren(true);

        // Hidden authenticated browser. It never receives user gestures; the window
        // itself occupies only the compact, visible overlay bounds.
        website=new WebView(this);
        website.getSettings().setJavaScriptEnabled(true);
        website.getSettings().setDomStorageEnabled(true);
        website.setClickable(false);
        website.setFocusable(false);
        website.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        website.setAlpha(0f);
        website.setOnTouchListener((v,event)->true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(website,true);
        root.addView(website,new FrameLayout.LayoutParams(dp(370),dp(680),Gravity.TOP|Gravity.LEFT));
        website.setWebViewClient(new WebViewClient(){
            @Override public void onPageFinished(WebView v,String url){
                if(url!=null&&url.startsWith("https://pogocoordinates.com/")){
                    loaded=true;
                    handler.postDelayed(()->{
                        if(!stopped)refresh();
                    },2500L);
                }
            }
        });

        panel=new WebView(this);
        panel.setBackgroundColor(Color.TRANSPARENT);
        panel.setLayerType(View.LAYER_TYPE_HARDWARE,null);
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

        // Native drag zone. Using raw screen coordinates prevents the feedback
        // jitter caused by moving a window during WebView touchmove events.
        dragHandle=new View(this);
        dragHandle.setBackgroundColor(Color.TRANSPARENT);
        dragHandle.setOnTouchListener((v,event)->{
            if(params==null)return true;
            switch(event.getActionMasked()){
                case MotionEvent.ACTION_DOWN:
                    downRawX=event.getRawX(); downRawY=event.getRawY();
                    initialX=params.x;initialY=params.y;dragged=false;return true;
                case MotionEvent.ACTION_MOVE:
                    int dx=Math.round(event.getRawX()-downRawX);
                    int dy=Math.round(event.getRawY()-downRawY);
                    if(Math.abs(dx)+Math.abs(dy)>dp(3))dragged=true;
                    if(dragged){
                        DisplayMetrics d=getResources().getDisplayMetrics();
                        int width=dp(minimized?BUBBLE_DP:WIDTH_DP);
                        int currentHeight=dp(minimized?BUBBLE_DP:height);
                        params.x=Math.max(0,Math.min(d.widthPixels-width,initialX+dx));
                        params.y=Math.max(0,Math.min(d.heightPixels-currentHeight,initialY+dy));
                        updateWindow();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if(minimized&&!dragged)minimize(false);
                    return true;
                case MotionEvent.ACTION_CANCEL:return true;
            }
            return true;
        });
        root.addView(dragHandle,new FrameLayout.LayoutParams(dp(23),dp(36),Gravity.TOP|Gravity.LEFT));

        params=new WindowManager.LayoutParams(dp(WIDTH_DP),dp(height),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity=Gravity.TOP|Gravity.LEFT;
        params.x=dp(8);params.y=dp(152);
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
    private void execute(String action,Consumer<JSONObject> callback){
        if(!loaded||website==null){
            status("Faça login no aplicativo.");return;
        }
        String code=script().replace("__ACTION__",JSONObject.quote(action))
                .replace("__MODE__",JSONObject.quote(mode)).replace("__SHINY__",shiny?"true":"false");
        website.evaluateJavascript(code,raw->{
            try{
                String decoded=new JSONArray("["+raw+"]").getString(0);
                JSONObject obj=new JSONObject(decoded);
                if(callback!=null)callback.accept(obj);
            }catch(Exception e){status("Não foi possível ler os dados do site.");}
        });
    }
    private void refresh(){
        if(!loaded){status("Aguardando login...");return;}
        final int generation=++requestGeneration;
        status("Pesquisando no site...");
        execute("search",response->{
            if(generation!=requestGeneration)return;
            if(!response.optBoolean("ok"))status("Filtro não confirmado; verifique no site.");
            handler.postDelayed(()->{if(!stopped&&generation==requestGeneration)collect(generation);},4500L);
        });
    }
    private void collect(int generation){
        execute("collect",response->{
            if(stopped||generation!=requestGeneration)return;
            JSONArray cards=response.optJSONArray("cards");
            int count=cards==null?0:Math.min(10,cards.length());
            results=cards==null?"[]":cards.toString();
            boolean uncertain=response.optBoolean("uncertain");
            if(uncertain)status("IV100: resultados diferentes foram ocultados");
            else if(count==0)status("Sem resultados · confira a busca no site");
            else status(count+" resultado"+(count==1?"":"s")+" · PoGoCoordinates");
        });
    }
    private void resize(int cssHeight){
        if(minimized||params==null)return;
        int target=Math.max(65,Math.min(315,cssHeight));
        if(Math.abs(target-height)<2)return;
        height=target;
        params.height=dp(height);
        updateWindow();
    }
    private void minimize(boolean yes){
        if(params==null)return;
        minimized=yes;
        params.width=dp(yes?BUBBLE_DP:WIDTH_DP);
        params.height=dp(yes?BUBBLE_DP:height);
        FrameLayout.LayoutParams h=new FrameLayout.LayoutParams(dp(yes?BUBBLE_DP:23),dp(yes?BUBBLE_DP:36),Gravity.TOP|Gravity.LEFT);
        dragHandle.setLayoutParams(h);
        updateWindow();
        js("window.setMinimized("+yes+")");
    }
    public class Bridge {
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
        @JavascriptInterface public void resize(int h){handler.post(()->OverlayService.this.resize(h));}
        @JavascriptInterface public void coords(int index){
            handler.post(()->execute("coords:"+index,response->status(
                response.optString("status",response.optBoolean("ok")?"Get coords acionado":"Get coords indisponível"))));
        }
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){return START_STICKY;}
    @Override public void onDestroy(){
        stopped=true;handler.removeCallbacksAndMessages(null);
        if(wm!=null&&root!=null){try{wm.removeView(root);}catch(Exception ignored){}}
        if(panel!=null){panel.removeJavascriptInterface("Native");panel.destroy();panel=null;}
        if(website!=null){website.destroy();website=null;}
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
