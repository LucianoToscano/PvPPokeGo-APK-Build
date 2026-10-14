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
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.ScrollView;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Date;
import java.text.SimpleDateFormat;
import java.util.Locale;
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
        help.setText("POKÉSEARCH OVERLAY · ALPHA 0.25\nFaça login AQUI no PoGoCoordinates, mesmo que já tenha entrado pelo navegador.\nToque em INICIAR OVERLAY. Toque na engrenagem para abrir ou fechar o menu; segure e arraste a engrenagem para mover.");
        help.setTextColor(Color.WHITE);help.setTextSize(13);help.setPadding(16,16,16,12);
        root.addView(help);
        Button start=new Button(this);start.setText("INICIAR OVERLAY");root.addView(start);
        start.setOnClickListener(v->startOverlay());
        buildPreferences(root);
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
    private int d(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private void buildPreferences(LinearLayout parent){
        SharedPreferences prefs=getSharedPreferences("prefs",0);
        Button toggle=new Button(this);
        toggle.setText("⚙ Preferências, histórico e diagnóstico");
        parent.addView(toggle);
        ScrollView sc=new ScrollView(this);
        sc.setFillViewport(false);
        LinearLayout body=new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);body.setPadding(d(12),0,d(12),d(6));
        sc.addView(body);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,0);
        parent.addView(sc,lp);
        toggle.setOnClickListener(v->{
            boolean expand=sc.getLayoutParams().height==0;
            LinearLayout.LayoutParams pp=(LinearLayout.LayoutParams)sc.getLayoutParams();
            pp.height=expand?d(232):0;sc.setLayoutParams(pp);
        });
        android.widget.TextView subtitle=new android.widget.TextView(this);
        subtitle.setText("Tamanho do Pokémon · alterações aplicadas ao iniciar o overlay");
        subtitle.setTextColor(Color.LTGRAY);body.addView(subtitle);
        Spinner sizes=new Spinner(this);
        String[] sizeLabels={"32 × 32","40 × 40","50 × 50","60 × 60"};
        sizes.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,sizeLabels));
        int current=prefs.getInt("spriteSize",50);
        sizes.setSelection(current==32?0:current==40?1:current==60?3:2);
        sizes.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(android.widget.AdapterView<?> p,android.view.View v,int pos,long id){
                prefs.edit().putInt("spriteSize",new int[]{32,40,50,60}[pos]).apply();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p){}
        });
        body.addView(sizes);
        android.widget.TextView orderTitle=new android.widget.TextView(this);
        orderTitle.setText("Ordenação dos resultados");orderTitle.setTextColor(Color.LTGRAY);body.addView(orderTitle);
        Spinner order=new Spinner(this);
        String[] choices={"Despawn mais próximo","Melhor ranking PvP","Maior IV","Shiny primeiro"};
        String[] values={"despawn","rank","iv","shiny"};
        order.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,choices));
        String val=prefs.getString("sort","despawn");
        int pos=0;for(int i=0;i<values.length;i++)if(values[i].equals(val))pos=i;
        order.setSelection(pos);
        order.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(android.widget.AdapterView<?> p,android.view.View v,int index,long id){
                prefs.edit().putString("sort",values[index]).apply();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p){}
        });
        body.addView(order);
        settingCheckbox(body,prefs,"showTimers","Mostrar tempo individual de despawn",false);
        settingCheckbox(body,prefs,"markBorders","Bordas discretas por tipo de filtro",true);
        settingCheckbox(body,prefs,"alerts","Alertar novos Pokémon (notificação)",false);
        Button history=new Button(this);history.setText("Ver histórico recente");
        body.addView(history);
        history.setOnClickListener(v->{
            JSONArray data;
            try{data=new JSONArray(prefs.getString("history","[]"));}catch(Exception e){data=new JSONArray();}
            StringBuilder msg=new StringBuilder();
            SimpleDateFormat df=new SimpleDateFormat("dd/MM HH:mm",Locale.getDefault());
            for(int i=data.length()-1;i>=0;i--){
                JSONObject item=data.optJSONObject(i);if(item==null)continue;
                msg.append(df.format(new Date(item.optLong("at",0)))).append(" · ")
                  .append(item.optString("name","Pokémon")).append(" · ")
                  .append(item.optString("mode","")).append(" · CP ")
                  .append(item.optString("cp","?")).append("\n");
            }
            new AlertDialog.Builder(this).setTitle("Últimos resultados")
                .setMessage(msg.length()==0?"Nenhum resultado registrado.":msg.toString())
                .setPositiveButton("Fechar",null)
                .setNegativeButton("Limpar histórico",(dialog,w)->prefs.edit().remove("history").apply())
                .show();
        });
        Button diagnostics=new Button(this);diagnostics.setText("Diagnóstico da última busca");
        body.addView(diagnostics);
        diagnostics.setOnClickListener(v->{
            long when=prefs.getLong("lastSearchAt",0);
            String time=when>0?new SimpleDateFormat("dd/MM HH:mm:ss",Locale.getDefault()).format(new Date(when)):"Ainda não realizada";
            String message="Status: "+prefs.getString("lastStatus","Aguardando primeira pesquisa")
                +"\nSessão: "+prefs.getString("sessionStatus","Desconhecida")
                +"\nÚltima busca: "+time
                +"\nÚltimo erro: "+prefs.getString("lastError","Nenhum registrado");
            new AlertDialog.Builder(this).setTitle("Diagnóstico do Pokésearch")
                .setMessage(message).setPositiveButton("OK",null).show();
        });
    }
    private void settingCheckbox(LinearLayout root,SharedPreferences prefs,String key,String label,boolean def){
        CheckBox check=new CheckBox(this);check.setText(label);check.setTextColor(Color.WHITE);
        check.setChecked(prefs.getBoolean(key,def));
        check.setOnCheckedChangeListener((button,enabled)->prefs.edit().putBoolean(key,enabled).apply());
        root.addView(check);
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
