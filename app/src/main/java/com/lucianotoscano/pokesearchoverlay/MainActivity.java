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
        help.setText("POKÉSEARCH OVERLAY · ALPHA 0.10\nFaça login AQUI no PoGoCoordinates, mesmo que já tenha entrado pelo navegador.\nToque em INICIAR OVERLAY e arraste pela alça ⠿.");
        help.setTextColor(Color.WHITE);help.setTextSize(13);help.setPadding(16,16,16,12);
        root.addView(help);
        Button start=new Button(this);start.setText("INICIAR OVERLAY");root.addView(start);
        start.setOnClickListener(v->startOverlay());
        website=new WebView(this);
        website.getSettings().setJavaScriptEnabled(true);
        website.getSettings().setDomStorageEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(website,true);
        website.setWebViewClient(new WebViewClient());
        root.addView(website,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
        website.loadUrl("https://pogocoordinates.com/search");
    }
    private void startOverlay(){
        CookieManager.getInstance().flush();
        if(Build.VERSION.SDK_INT>=23&&!Settings.canDrawOverlays(this)){
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                  Uri.parse("package:"+getPackageName())),101);
            return;
        }
        Intent i=new Intent(this,OverlayService.class);
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
