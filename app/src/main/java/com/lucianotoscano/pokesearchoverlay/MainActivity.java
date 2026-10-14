package com.lucianotoscano.pokesearchoverlay;

import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.graphics.Color;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private WebView website;
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.rgb(12,12,16));
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(17,18,22));
        TextView help=new TextView(this);
        help.setText("POKÉSEARCH OVERLAY · ALPHA 0.23\nFaça login AQUI no PoGoCoordinates, mesmo que já tenha entrado pelo navegador.\nToque em INICIAR OVERLAY. Toque na engrenagem para abrir ou fechar o menu; segure e arraste a engrenagem para mover.");
        help.setTextColor(Color.WHITE);help.setTextSize(13);help.setPadding(16,16,16,12);
        root.addView(help);
        Button start=new Button(this);start.setText("INICIAR OVERLAY");root.addView(start);
        start.setOnClickListener(v->startOverlay());
        website=new WebView(this);
        website.getSettings().setJavaScriptEnabled(true);
        website.getSettings().setDomStorageEnabled(true);
        website.getSettings().setDatabaseEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(website,true);
        website.setWebViewClient(new WebViewClient(){
            @Override public void onPageFinished(WebView view,String url){
                if(url!=null&&url.startsWith("https://pogocoordinates.com/")){
                    view.evaluateJavascript(readAsset("presets.js"),null);
                }
            }
        });
        root.addView(website,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
        website.loadUrl("https://pogocoordinates.com/search");
    }
    private String readAsset(String path){
        try(InputStream in=getAssets().open(path)){
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            byte[] b=new byte[4096];int n;
            while((n=in.read(b))!=-1)out.write(b,0,n);
            return out.toString("UTF-8");
        }catch(Exception e){return "void 0";}
    }
    private void startOverlay(){
        CookieManager.getInstance().flush();
        if(Build.VERSION.SDK_INT>=23&&!Settings.canDrawOverlays(this)){
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                  Uri.parse("package:"+getPackageName())),101);
            return;
        }
        Intent i=new Intent(this,OverlayService.class);
        i.setAction("com.lucianotoscano.pokesearchoverlay.REFRESH_LOGIN");
        if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);
        moveTaskToBack(true);
    }
    @Override protected void onActivityResult(int req,int result,Intent data){
        super.onActivityResult(req,result,data);
        if(req==101&&Settings.canDrawOverlays(this))startOverlay();
    }
    @Override public void onBackPressed(){
        if(website!=null&&website.canGoBack())website.goBack();else super.onBackPressed();
    }
    @Override protected void onDestroy(){
        if(website!=null){website.stopLoading();website.destroy();website=null;}
        super.onDestroy();
    }
}
