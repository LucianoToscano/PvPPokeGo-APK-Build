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
    private static final int BUBBLE_DP=45, DETAIL_DP=226;
    private int spriteSize=45;
    private int dockDp(){return spriteSize+8;}
    private int menuDp(){return dockDp()+282;}
    private final Handler handler=new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private WindowManager.LayoutParams params,detailParams;
    private FrameLayout root,detailRoot;
    private View grip;
    private WebView panel,detailPanel,website;
    private boolean menuExpanded=false,menuLeft=false,minimized=false,shiny=true,loaded=false,panelLoaded=false,detailLoaded=false,stopped=false;
    private int height=72,detailHeight=225,requestGeneration=0,selected=-1,intervalMinutes=5;
    private boolean searchInProgress=false;
    private boolean showTimers=false,markBorders=true,alerts=false;
    private String sort="despawn";
    private long lastAlertAt=0L;
    private final java.util.Set<String> knownSpawns=new java.util.HashSet<>();
    private long armedDespawnMillis=0L;
    private final Runnable expiryRefresh=new Runnable(){
        @Override public void run(){
            armedDespawnMillis=0L;
            if(stopped||!loaded)return;
            if(searchInProgress){
                handler.postDelayed(this,2000L);
                return;
            }
            try{
                JSONArray old=new JSONArray(results),active=new JSONArray();
                long now=System.currentTimeMillis();
                for(int i=0;i<old.length();i++){
                    JSONObject card=old.optJSONObject(i);
                    if(card==null)continue;
                    long expiry=card.optLong("expiresAtMs",Long.MAX_VALUE);
                    if(expiry>now)active.put(card);
                }
                if(active.length()!=old.length()){
                    results=active.toString();hideDetails();update();
                }
            }catch(Exception ignored){}
            refresh(false);
        }
    };
    private void armNextDespawn(JSONArray cards){
        handler.removeCallbacks(expiryRefresh);
        armedDespawnMillis=0L;
        long now=System.currentTimeMillis(),next=Long.MAX_VALUE;
        if(cards==null)return;
        for(int i=0;i<cards.length();i++){
            JSONObject card=cards.optJSONObject(i);
            if(card==null)continue;
            String timer=card.optString("despawn","");
            String[] parts=timer.split(":");
            if(parts.length!=2)continue;
            try{
                long seconds=Long.parseLong(parts[0])*60L+Long.parseLong(parts[1]);
                if(seconds<=0||seconds>86400)continue;
                long readAt=card.optLong("sampledAt",now);
                long deadline=readAt+seconds*1000L;
                if(deadline>now&&deadline<next)next=deadline;
            }catch(NumberFormatException ignored){}
        }
        if(next==Long.MAX_VALUE)return;
        armedDespawnMillis=next;
        handler.postDelayed(expiryRefresh,Math.max(250L,next-now+150L));
    }
    private String mode="iv",results="[]",status="Conectando...";
    private float startRawX,startRawY;
    private int startX,startY;
    private boolean dragged=false;
    private final Runnable periodic=new Runnable(){
        @Override public void run(){
            if(!stopped){refresh();handler.postDelayed(this,intervalMinutes*60000L);}
        }
    };
    private void schedulePeriodic(){
        handler.removeCallbacks(periodic);
        if(!stopped)handler.postDelayed(periodic,intervalMinutes*60000L);
    }
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
        return Math.max(28,Math.min(spriteSize+1,available/n));
    }
    private void update(){
        js(panel,panelLoaded,"window.onNativeData("+results+","+JSONObject.quote(status)+","+
                JSONObject.quote(mode)+","+shiny+","+rowDp()+","+intervalMinutes+","+spriteSize+","+showTimers+","+markBorders+","+WatchAlerts.enabled(this)+")");
        if(selected>=0)showDetails(selected);
    }
    private void status(String s){
        status=s;
        getSharedPreferences("prefs",0).edit().putString("lastStatus",s).apply();
        update();
    }
    private void diagnosticError(String s){
        getSharedPreferences("prefs",0).edit().putString("lastError",s).apply();
        status(s);
    }
    private void updateWindow(){
        if(wm!=null&&root!=null&&params!=null){
            try{wm.updateViewLayout(root,params);}catch(Exception ignored){}
        }
    }
    private void moveDetail(){
        if(detailRoot==null||detailParams==null||params==null)return;
        int w=screenWidthDp(),h=screenHeightDp(),gap=5;
        int dockPos=params.x/dp(1)+(menuExpanded&&menuLeft?menuDp()-dockDp():0);
        int right=dockPos+dockDp()+gap;
        int left=dockPos-DETAIL_DP-gap;
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
        website.getSettings().setDatabaseEnabled(true);
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
                        int winWidth=dp(minimized?BUBBLE_DP:menuExpanded?menuDp():dockDp());
                        int winHeight=dp(minimized?BUBBLE_DP:height);
                        params.x=Math.max(0,Math.min(d.widthPixels-winWidth,startX+dx));
                        params.y=Math.max(0,Math.min(d.heightPixels-winHeight,startY+dy));
                        updateWindow();moveDetail();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    // The same gear is the drag handle and menu toggle.
                    if(!dragged){
                        if(minimized)minimize(false);
                        else menu(!menuExpanded);
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:return true;
            }
            return true;
        });
        root.addView(grip,new FrameLayout.LayoutParams(dp(dockDp()),dp(23),Gravity.TOP|Gravity.LEFT));

        params=new WindowManager.LayoutParams(dp(dockDp()),dp(height),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity=Gravity.TOP|Gravity.LEFT;
        params.x=dp(Math.max(0,screenWidthDp()-dockDp()-7));params.y=dp(120);
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
        schedulePeriodic();
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
            }catch(Exception e){
                JSONObject failure=new JSONObject();
                try{failure.put("ok",false);failure.put("reason","Resposta inválida do navegador interno");}catch(Exception ignored){}
                if(callback!=null)callback.accept(failure);
                else diagnosticError("Falha ao ler resultados no site.");
            }
        });
    }
    private void refresh(){refresh(false);}
    private void refresh(boolean manual){
        if(stopped)return;
        if(searchInProgress&&!manual)return;
        if(!loaded||website==null){
            diagnosticError("Sessão do site ainda não carregada; abra o aplicativo para conferir o login.");
            return;
        }
        handler.removeCallbacks(expiryRefresh);
        armedDespawnMillis=0L;
        searchInProgress=true;
        final int generation=++requestGeneration;
        status(manual?"Busca manual iniciada...":"Atualizando...");
        execute("session",session->{
            if(stopped||generation!=requestGeneration)return;
            final String auth=session.optString("auth","não confirmado");
            getSharedPreferences("prefs",0).edit().putString("sessionStatus",auth).apply();
            if(auth.equals("login necessário")){
                searchInProgress=false;
                diagnosticError("Login necessário. Abra o Pokésearch e autentique-se.");
                return;
            }
            execute("prepareSearch",prepared->{
                if(stopped||generation!=requestGeneration)return;
                if(!prepared.optBoolean("ok")){
                    searchInProgress=false;
                    diagnosticError("Não foi possível selecionar a aba de busca.");
                    return;
                }
                handler.postDelayed(()->execute("configureSearch",configured->{
                    if(stopped||generation!=requestGeneration)return;
                    if(!configured.optBoolean("ok")){
                        searchInProgress=false;
                        diagnosticError("Falha ao configurar os filtros.");
                        return;
                    }
                    handler.postDelayed(()->execute("submitSearch",submitted->{
                        if(stopped||generation!=requestGeneration)return;
                        if(!submitted.optBoolean("ok")){
                            searchInProgress=false;
                            diagnosticError(submitted.optString("reason","Falha ao pressionar Search."));
                            return;
                        }
                        status(manual?"Busca manual em andamento...":"Buscando...");
                        pollSearch(generation,0,manual);
                    }),650L);
                }),600L);
            });
        });
    }
    private void pollSearch(int generation,int attempt,boolean manual){
        handler.postDelayed(()->execute("collect",r->{
            if(stopped||generation!=requestGeneration)return;
            if(!r.optBoolean("ok")){
                searchInProgress=false;
                diagnosticError("Falha na leitura dos resultados do site.");
                return;
            }
            int found=r.optInt("found",0);
            boolean loading=r.optBoolean("loading",false);
            boolean empty=r.optBoolean("explicitEmpty",false);
            if((loading||(!empty&&found==0))&&attempt<8){
                pollSearch(generation,attempt+1,manual);
                return;
            }
            if(loading||(!empty&&found==0&&attempt>=8)){
                searchInProgress=false;
                diagnosticError("Busca não confirmada no site (tempo limite). Resultados anteriores mantidos.");
                return;
            }
            applyCollectedResults(r);
        }), attempt==0?3000L:1300L);
    }
    private long expiration(JSONObject item){
        if(item==null)return Long.MAX_VALUE;
        String[] p=item.optString("despawn","").split(":");
        if(p.length!=2)return Long.MAX_VALUE;
        try{
            long secs=Long.parseLong(p[0])*60+Long.parseLong(p[1]);
            if(secs<=0||secs>86400)return Long.MAX_VALUE;
            return item.optLong("sampledAt",System.currentTimeMillis())+secs*1000L;
        }catch(Exception e){return Long.MAX_VALUE;}
    }
    private String spawnKey(JSONObject c){
        return c.optString("name","")+"|"+c.optString("cp","")+"|"+
            c.optString("level","")+"|"+c.optString("location","");
    }
    private JSONArray sortItems(JSONArray source){
        java.util.ArrayList<JSONObject> a=new java.util.ArrayList<>();
        if(source!=null)for(int i=0;i<source.length();i++){
            JSONObject c=source.optJSONObject(i);if(c!=null)a.add(c);
        }
        java.util.Comparator<JSONObject> comparator;
        switch(sort){
            case "rank":
                comparator=(a1,a2)->Integer.compare(rankForSort(a1),rankForSort(a2));break;
            case "iv":
                comparator=(a1,a2)->Double.compare(a2.optDouble("ivPercent",0),a1.optDouble("ivPercent",0));break;
            case "shiny":
                comparator=(a1,a2)->Boolean.compare(a2.optBoolean("shiny"),a1.optBoolean("shiny"));break;
            default:
                comparator=(a1,a2)->Long.compare(expiration(a1),expiration(a2));
        }
        a.sort(comparator);
        JSONArray sorted=new JSONArray();
        for(JSONObject c:a)sorted.put(c);
        return sorted;
    }
    private int rankForSort(JSONObject card){
        try{return Integer.parseInt(card.optString("rank","999999"));}
        catch(Exception ignored){return 999999;}
    }
    private void recordNewResults(JSONArray cards){
        android.content.SharedPreferences preferences=getSharedPreferences("prefs",0);
        JSONArray history;
        try{history=new JSONArray(preferences.getString("history","[]"));}
        catch(Exception ignored){history=new JSONArray();}
        java.util.Set<String> current=new java.util.HashSet<>();
        String firstNew="";
        String firstWatched="";
        String watchDetails="";
        int watchedCount=0;
        java.util.Set<String> wanted=WatchAlerts.watched(this);
        long now=System.currentTimeMillis();
        for(int i=0;i<cards.length();i++){
            JSONObject c=cards.optJSONObject(i);
            if(c==null)continue;
            String key=spawnKey(c);
            current.add(key);
            if(knownSpawns.contains(key))continue;
            if(firstNew.isEmpty())firstNew=c.optString("name","Pokémon");
            if(WatchAlerts.enabled(this)&&WatchAlerts.matches(c.optString("name",""),wanted)){
                watchedCount++;
                if(firstWatched.isEmpty()){
                    firstWatched=c.optString("name","Pokémon");
                    watchDetails="Busca "+mode.toUpperCase(java.util.Locale.ROOT)+
                        " · PC "+c.optString("cp","?")+
                        (c.optString("location","").isEmpty()?"":" · "+c.optString("location",""));
                }
            }
            JSONObject entry=new JSONObject();
            try{
                entry.put("name",c.optString("name","Pokémon"));
                entry.put("cp",c.optString("cp",""));
                entry.put("mode",mode.toUpperCase(java.util.Locale.ROOT));
                entry.put("rank",c.optString("rank",""));
                entry.put("at",now);
                history.put(entry);
            }catch(Exception ignored){}
        }
        knownSpawns.clear();knownSpawns.addAll(current);
        JSONArray limited=new JSONArray();
        for(int i=Math.max(0,history.length()-40);i<history.length();i++)limited.put(history.opt(i));
        preferences.edit().putString("history",limited.toString()).apply();
        if(watchedCount>0&&!firstWatched.isEmpty()){
            if(watchedCount>1)watchDetails+=" · +"+(watchedCount-1)+" Pokémon da lista";
            boolean sent=WatchAlerts.match(this,firstWatched,watchDetails);
            if(!sent&&!WatchAlerts.permissionGranted(this))
                preferences.edit().putString("lastWatchAlertError",
                    "Permissão de notificações não concedida").apply();
        }
        if(alerts&&!firstNew.isEmpty()&&now-lastAlertAt>60000L){
            lastAlertAt=now;
            NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            if(nm!=null){
                nm.createNotificationChannel(new NotificationChannel("pokesearch_alerts",
                    "Novos Pokémon encontrados",NotificationManager.IMPORTANCE_DEFAULT));
                Notification note=new Notification.Builder(this,"pokesearch_alerts")
                    .setSmallIcon(android.R.drawable.ic_menu_search)
                    .setContentTitle("Novo Pokémon: "+firstNew)
                    .setContentText("Encontrado na busca "+mode.toUpperCase(java.util.Locale.ROOT))
                    .setAutoCancel(true).build();
                try{nm.notify(74,note);}catch(Exception ignored){}
            }
        }
    }
    private void applyCollectedResults(JSONObject r){
        JSONArray items=r.optJSONArray("cards");
        if(items==null)items=new JSONArray();
        long now=System.currentTimeMillis();
        for(int i=0;i<items.length();i++){
            JSONObject card=items.optJSONObject(i);
            if(card==null)continue;
            try{
                card.put("sampledAt",now);
                long end=expiration(card);
                if(end!=Long.MAX_VALUE)card.put("expiresAtMs",end);
            }catch(Exception ignored){}
        }
        JSONArray sorted=sortItems(items);
        recordNewResults(sorted);
        results=sorted.toString();
        searchInProgress=false;
        armNextDespawn(sorted);
        if(selected>=0)hideDetails();
        getSharedPreferences("prefs",0).edit()
            .putLong("lastSearchAt",now).putString("lastError","Nenhum").apply();
        if(r.optBoolean("uncertain"))status("Alguns IVs não puderam ser confirmados.");
        else if(sorted.length()==0)status("Sem resultados confirmados");
        else status(sorted.length()+" Pokémon atualizados");
    }
    private void resize(int h){
        if(minimized||params==null)return;
        int target=Math.max(46,Math.min(screenHeightDp()-95,h));
        if(Math.abs(target-height)<2)return;
        height=target;params.height=dp(height);updateWindow();moveDetail();
    }
    private void menu(boolean show){
        if(params==null||minimized)return;
        if(show==menuExpanded)return;
        if(show){
            int screen=metrics().widthPixels;
            int extra=dp(menuDp()-dockDp());
            menuLeft=params.x+dp(menuDp())>screen;
            if(menuLeft)params.x=Math.max(0,params.x-extra);
            params.width=dp(menuDp());
        }else{
            if(menuLeft)params.x=Math.min(metrics().widthPixels-dp(dockDp()),params.x+dp(menuDp()-dockDp()));
            params.width=dp(dockDp());
        }
        menuExpanded=show;
        FrameLayout.LayoutParams g=new FrameLayout.LayoutParams(dp(dockDp()),dp(27),Gravity.TOP|Gravity.LEFT);
        g.leftMargin=show&&menuLeft?dp(menuDp()-dockDp()):0;
        grip.setLayoutParams(g);
        js(panel,panelLoaded,"window.syncMenu("+show+","+JSONObject.quote(show&&menuLeft?"left":"right")+")");
        updateWindow();moveDetail();
    }
    private void minimize(boolean yes){
        if(params==null)return;
        if(yes&&menuExpanded)menu(false);
        minimized=yes;
        if(yes)hideDetails();
        params.width=dp(yes?BUBBLE_DP:dockDp());
        params.height=dp(yes?BUBBLE_DP:height);
        grip.setLayoutParams(new FrameLayout.LayoutParams(
                dp(yes?BUBBLE_DP:dockDp()),dp(yes?BUBBLE_DP:27),Gravity.TOP|Gravity.LEFT));
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
            js(detailPanel,true,"window.showDetails("+obj.toString()+","+JSONObject.quote(mode)+","+WatchAlerts.isWatched(this,obj.optString("name",""))+")");
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
    private void pollCoordinates(JSONObject target,int attempt,boolean autoCopy){
        JSONObject query=new JSONObject();
        try{
            query.put("name",target.optString("name",""));
            query.put("cp",target.optString("cp",""));
        }catch(Exception ignored){}
        execute("readCoords:"+query.toString(),result->{
            String coords=result.optString("coords","");
            if(result.optBoolean("ok")&&!coords.isEmpty()){
                try{
                    JSONArray existing=new JSONArray(results);
                    for(int i=0;i<existing.length();i++){
                        JSONObject card=existing.getJSONObject(i);
                        if(card.optInt("sourceIndex",-1)==target.optInt("sourceIndex",-2)){
                            card.put("coords",coords);
                            break;
                        }
                    }
                    results=existing.toString();
                }catch(Exception ignored){}
                if(autoCopy)copyCoordinatesInternal(coords);
                deliverCoords(coords,autoCopy?"Coordenadas copiadas":"Coordenadas obtidas");return;
            }
            if(attempt<8){
                handler.postDelayed(()->pollCoordinates(target,attempt+1,autoCopy),450L);
            }else{
                deliverCoords("","O site não retornou coordenadas. Confira no navegador interno.");
            }
        });
    }
    private void copyCoordinatesInternal(String value){
        String coords=extractCoordinates(value);
        ClipboardManager cb=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        if(cb!=null&&!coords.isEmpty())cb.setPrimaryClip(ClipData.newPlainText("Pokémon coordinates",coords));
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
        @JavascriptInterface public void toggleWatch(String pokemon){handler.post(()->{
            boolean watched=WatchAlerts.toggle(OverlayService.this,pokemon);
            js(detailPanel,detailLoaded,"window.watchFeedback("+watched+")");
        });}
        @JavascriptInterface public void copyCoords(String coords){handler.post(()->{
            String valid=extractCoordinates(coords);
            if(valid.isEmpty()){js(detailPanel,detailLoaded,"window.copyFeedback("+JSONObject.quote("Coordenadas inválidas")+")");return;}
            ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            if(clipboard!=null){clipboard.setPrimaryClip(ClipData.newPlainText("Coordenadas Pokémon",valid));js(detailPanel,detailLoaded,"window.copyFeedback("+JSONObject.quote("✓ Copiado!")+ ")");}
        });}

        @JavascriptInterface public void select(String s){handler.post(()->{
            if(!s.equals("iv")&&!s.equals("gl")&&!s.equals("ul"))return;
            mode=s;getSharedPreferences("prefs",0).edit().putString("mode",s).apply();
            results="[]";hideDetails();update();refresh();
        });}
        @JavascriptInterface public void menu(boolean open){handler.post(()->OverlayService.this.menu(open));}
        @JavascriptInterface public void cycleMode(){handler.post(()->{
            mode=mode.equals("iv")?"gl":mode.equals("gl")?"ul":"iv";
            getSharedPreferences("prefs",0).edit().putString("mode",mode).apply();
            results="[]";hideDetails();update();refresh();
        });}
        @JavascriptInterface public void changeSpriteSize(int direction){handler.post(()->{
            if(direction!=1&&direction!=-1)return;
            int[] sizes={32,40,45,50,60}; int at=2;
            for(int j=0;j<sizes.length;j++)if(sizes[j]==spriteSize)at=j;
            int size=sizes[Math.max(0,Math.min(sizes.length-1,at+direction))];
            if(size==spriteSize)return;
            boolean keepMenu=menuExpanded;
            if(keepMenu)menu(false);
            spriteSize=size;
            getSharedPreferences("prefs",0).edit().putInt("spriteSize",size).apply();
            if(params!=null){
                params.width=dp(minimized?BUBBLE_DP:dockDp());
                params.x=Math.max(0,Math.min(metrics().widthPixels-params.width,params.x));
                grip.setLayoutParams(new FrameLayout.LayoutParams(
                    dp(minimized?BUBBLE_DP:dockDp()),dp(minimized?BUBBLE_DP:23),
                    Gravity.TOP|Gravity.LEFT));
                updateWindow();
            }
            update();
            if(keepMenu)menu(true);
            android.widget.Toast.makeText(OverlayService.this,"Sprites: "+size+" × "+size,
                android.widget.Toast.LENGTH_SHORT).show();
        });}
        @JavascriptInterface public void toggleTimers(){handler.post(()->{
            showTimers=!showTimers;
            getSharedPreferences("prefs",0).edit().putBoolean("showTimers",showTimers).apply();
            update();
        });}
        @JavascriptInterface public void toggleWatchAlerts(){handler.post(()->{
            boolean enabled=!WatchAlerts.enabled(OverlayService.this);
            getSharedPreferences("prefs",0).edit()
                .putBoolean(WatchAlerts.PREF_ENABLED,enabled).apply();
            update();
            android.widget.Toast.makeText(OverlayService.this,
                enabled?"Alertas de procurados ativados":"Alertas de procurados pausados",
                android.widget.Toast.LENGTH_SHORT).show();
        });}
        @JavascriptInterface public void openWatchList(){handler.post(()->{
            Intent i=new Intent(OverlayService.this,MainActivity.class);
            i.setAction("com.lucianotoscano.pokesearchoverlay.EDIT_WATCH_LIST");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
            try{startActivity(i);}catch(Exception e){
                android.widget.Toast.makeText(OverlayService.this,"Abra a lista no aplicativo",
                    android.widget.Toast.LENGTH_SHORT).show();
            }
        });}
        @JavascriptInterface public void cycleInterval(){handler.post(()->{
            int[] options={1,3,5,10,15,20};
            int next=0;
            for(int j=0;j<options.length;j++){
                if(options[j]==intervalMinutes){next=(j+1)%options.length;break;}
            }
            intervalMinutes=options[next];
            getSharedPreferences("prefs",0).edit().putInt("intervalMinutes",intervalMinutes).putBoolean("intervalExplicit",true).apply();
            schedulePeriodic();update();
        });}
        @JavascriptInterface public void shiny(){handler.post(()->{
            shiny=!shiny;getSharedPreferences("prefs",0).edit().putBoolean("shiny",shiny).apply();
            results="[]";hideDetails();update();refresh();
        });}
        @JavascriptInterface public void refresh(){handler.post(()->OverlayService.this.refresh(true));}
        @JavascriptInterface public void minimize(){handler.post(()->OverlayService.this.minimize(true));}
        @JavascriptInterface public void restore(){handler.post(()->OverlayService.this.minimize(false));}
        @JavascriptInterface public void close(){handler.post(()->stopSelf());}
        @JavascriptInterface public void resize(int size){handler.post(()->OverlayService.this.resize(size));}
        @JavascriptInterface public void resizeDetail(int size){handler.post(()->OverlayService.this.resizeDetail(size));}
        @JavascriptInterface public void toggleDetail(int index){handler.post(()->{
            if(selected==index)hideDetails();else showDetails(index);
        });}
        @JavascriptInterface public void hideDetail(){handler.post(OverlayService.this::hideDetails);}
        @JavascriptInterface public void longPressCoords(int i){handler.post(()->{
            try{
                JSONArray a=new JSONArray(results);
                if(i>=0&&i<a.length())requestCoordinates(a.getJSONObject(i).optInt("sourceIndex",-1),true);
            }catch(Exception ignored){}
        });}
        @JavascriptInterface public void coords(int sourceIndex){handler.post(()->requestCoordinates(sourceIndex,false));}
    }
    private void requestCoordinates(int sourceIndex,boolean autoCopy){
        JSONObject chosen=null;
        try{
            JSONArray a=new JSONArray(results);
            for(int i=0;i<a.length();i++){
                JSONObject item=a.getJSONObject(i);
                if(item.optInt("sourceIndex",-1)==sourceIndex){chosen=item;break;}
            }
        }catch(Exception ignored){}
        if(chosen==null){deliverCoords("","Pokémon indisponível");return;}
        JSONObject target=chosen;
        execute("coords:"+sourceIndex,response->{
            if(!response.optBoolean("ok")){
                deliverCoords("",response.optString("status","Get coords indisponível"));return;
            }
            pollCoordinates(target,0,autoCopy);
        });
    }
    @Override public int onStartCommand(Intent i,int flags,int id){
        if(i!=null&&"com.lucianotoscano.pokesearchoverlay.REFRESH_LOGIN".equals(i.getAction())){
            ++requestGeneration;searchInProgress=false;handler.removeCallbacks(expiryRefresh);
            results="[]";hideDetails();loaded=false;
            knownSpawns.clear();
            status("Atualizando sessão após login...");
            CookieManager.getInstance().flush();
            if(website!=null)website.loadUrl("https://pogocoordinates.com/search");
        }
        // v0.20 saved 10 as the implicit default; only keep a rate the user explicitly chose.
        android.content.SharedPreferences pref=getSharedPreferences("prefs",0);
        if(!pref.getBoolean("intervalExplicit",false)){
            intervalMinutes=5;
            pref.edit().putInt("intervalMinutes",5).apply();
        }else{
            intervalMinutes=pref.getInt("intervalMinutes",5);
        }
        if(intervalMinutes!=1&&intervalMinutes!=3&&intervalMinutes!=5&&
           intervalMinutes!=10&&intervalMinutes!=15&&intervalMinutes!=20)intervalMinutes=5;
        schedulePeriodic();
        spriteSize=pref.getInt("spriteSize",45);
        if(spriteSize!=32&&spriteSize!=40&&spriteSize!=45&&spriteSize!=50&&spriteSize!=60)spriteSize=45;
        showTimers=pref.getBoolean("showTimers",false);
        markBorders=pref.getBoolean("markBorders",true);
        alerts=pref.getBoolean("alerts",false);
        sort=pref.getString("sort","despawn");
        if(params!=null&&!menuExpanded){
            params.width=dp(minimized?BUBBLE_DP:dockDp());
            params.x=Math.min(params.x,metrics().widthPixels-params.width);
            grip.setLayoutParams(new FrameLayout.LayoutParams(
                dp(minimized?BUBBLE_DP:dockDp()),dp(minimized?BUBBLE_DP:23),Gravity.TOP|Gravity.LEFT));
            updateWindow();
        }
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
