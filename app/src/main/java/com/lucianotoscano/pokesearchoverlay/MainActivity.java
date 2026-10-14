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
import android.widget.EditText;
import android.text.InputType;
import android.content.pm.PackageManager;
import android.Manifest;
import android.widget.Toast;
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
import android.widget.ImageButton;
import android.widget.ImageView;
import android.view.Gravity;
import android.graphics.Typeface;

public class MainActivity extends Activity {
    private WebView website;
    private TextView watchSummary;
    private boolean shinyLoginRequested=false;
    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.rgb(11,13,18));
        getWindow().setNavigationBarColor(Color.rgb(11,13,18));
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(11,13,18));
        root.setPadding(d(12),d(3),d(12),0);
        LinearLayout header=new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);
        TextView brand=new TextView(this);
        brand.setText("PokeSearch");
        brand.setTextColor(Color.WHITE);
        brand.setTextSize(22);
        brand.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        brand.setPadding(d(7),0,0,0);
        header.addView(brand,new LinearLayout.LayoutParams(0,d(51),1));
        ImageButton heart=makeIcon(R.drawable.ic_heart_outline,"Contribuir com um café via Pix");
        heart.setOnClickListener(v->PixSupport.show(this));
        header.addView(heart,new LinearLayout.LayoutParams(d(45),d(45)));
        ImageButton gear=makeIcon(R.drawable.ic_settings_outline,"Preferências, histórico e diagnóstico");
        gear.setOnClickListener(v->showPreferencesDialog());
        header.addView(gear,new LinearLayout.LayoutParams(d(45),d(45)));
        root.addView(header);
        Button start=new Button(this);
        start.setText("Ativar overlay");
        start.setTextColor(Color.WHITE);
        start.setTextSize(15);
        start.setAllCaps(false);
        start.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        start.setBackground(round(Color.rgb(213,45,79),12));
        LinearLayout.LayoutParams startParams=new LinearLayout.LayoutParams(-1,d(49));
        startParams.setMargins(d(2),d(3),d(2),d(9));
        root.addView(start,startParams);
        start.setOnClickListener(v->startOverlay());
        LinearLayout watchRow=new LinearLayout(this);
        watchRow.setOrientation(LinearLayout.HORIZONTAL);
        watchRow.setGravity(Gravity.CENTER_VERTICAL);
        watchRow.setPadding(d(11),d(5),d(10),d(5));
        watchRow.setBackground(round(Color.rgb(24,28,36),11));
        ImageView bell=new ImageView(this);
        bell.setImageResource(R.drawable.ic_star_outline);
        watchRow.addView(bell,new LinearLayout.LayoutParams(d(27),d(27)));
        LinearLayout watchTexts=new LinearLayout(this);
        watchTexts.setOrientation(LinearLayout.VERTICAL);
        watchTexts.setPadding(d(9),0,0,0);
        TextView watchTitle=new TextView(this);
        watchTitle.setText("Pokémon favoritos");
        watchTitle.setTextColor(Color.WHITE);
        watchTitle.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        watchTitle.setTextSize(13);
        watchTexts.addView(watchTitle);
        watchSummary=new TextView(this);
        watchSummary.setTextSize(11);
        watchSummary.setTextColor(Color.rgb(159,169,183));
        watchTexts.addView(watchSummary);
        watchRow.addView(watchTexts,new LinearLayout.LayoutParams(0,-2,1));
        TextView arrow=new TextView(this);
        arrow.setText("›");
        arrow.setTextColor(Color.rgb(175,184,195));
        arrow.setTextSize(23);
        watchRow.addView(arrow);
        LinearLayout.LayoutParams rowParams=new LinearLayout.LayoutParams(-1,d(60));
        rowParams.setMargins(d(2),0,d(2),d(10));
        root.addView(watchRow,rowParams);
        watchRow.setOnClickListener(v->openPicker());
        refreshWatchCount();
        website=new WebView(this);
        website.getSettings().setJavaScriptEnabled(true);
        website.getSettings().setDomStorageEnabled(true);
        website.getSettings().setDatabaseEnabled(true);
        website.setBackgroundColor(Color.rgb(12,14,20));
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(website,true);
        website.setWebViewClient(new WebViewClient(){
            @Override public void onPageFinished(WebView view,String url){
                if(url!=null&&url.startsWith("https://pogocoordinates.com/")){
                    view.evaluateJavascript(readAsset("presets.js"),null);
                    if(shinyLoginRequested)runRequestedShinyLogin();
                }
            }
        });
        root.addView(website,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
        website.loadUrl("https://pogocoordinates.com/search");
        handleShinyLoginIntent(getIntent());
        if("com.lucianotoscano.pokesearchoverlay.EDIT_WATCH_LIST".equals(getIntent().getAction()))
            root.post(this::openPicker);
        if("com.lucianotoscano.pokesearchoverlay.OPEN_SETTINGS".equals(getIntent().getAction()))
            root.post(this::showPreferencesDialog);
        if("com.lucianotoscano.pokesearchoverlay.OPEN_PIX".equals(getIntent().getAction()))
            root.post(()->PixSupport.show(this));
    }
    private ImageButton makeIcon(int drawable,String description){
        ImageButton button=new ImageButton(this);
        button.setImageResource(drawable);
        button.setContentDescription(description);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(d(10),d(10),d(10),d(10));
        button.setBackgroundColor(Color.TRANSPARENT);
        return button;
    }
    private android.graphics.drawable.GradientDrawable round(int color,int radius){
        android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();
        bg.setColor(color);bg.setCornerRadius(d(radius));return bg;
    }
    private void refreshWatchCount(){
        if(watchSummary==null)return;
        int size=WatchAlerts.watched(this).size();
        watchSummary.setText(size==0?"Nenhum favorito · toque para escolher":
            size+" favorito"+(size==1?"":"s")+" · toque para editar");
    }
    private void openPicker(){
        PokemonPicker.show(this,this::refreshWatchCount);
    }
    @Override protected void onResume(){
        super.onResume();
        refreshWatchCount();
    }
    private void handleShinyLoginIntent(Intent intent){
        if(intent==null||!"com.lucianotoscano.pokesearchoverlay.OPEN_SHINY_LOGIN".equals(intent.getAction()))return;
        shinyLoginRequested=true;
        if(website!=null){
            String current=website.getUrl();
            if(current!=null&&current.startsWith("https://pogocoordinates.com/search"))
                runRequestedShinyLogin();
            else if(current!=null&&!current.startsWith("https://pogocoordinates.com/"))
                website.loadUrl("https://pogocoordinates.com/search");
        }
    }
    private void runRequestedShinyLogin(){
        if(!shinyLoginRequested||website==null)return;
        shinyLoginRequested=false;
        website.postDelayed(()->{
            if(website!=null&&website.getUrl()!=null&&website.getUrl().startsWith("https://pogocoordinates.com/"))
                website.evaluateJavascript(readAsset("shiny_login.js"),null);
        },900);
    }
    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);
        setIntent(intent);
        handleShinyLoginIntent(intent);
        if("com.lucianotoscano.pokesearchoverlay.EDIT_WATCH_LIST".equals(intent.getAction()))
            getWindow().getDecorView().post(this::openPicker);
        if("com.lucianotoscano.pokesearchoverlay.OPEN_SETTINGS".equals(intent.getAction()))
            getWindow().getDecorView().post(this::showPreferencesDialog);
        if("com.lucianotoscano.pokesearchoverlay.OPEN_PIX".equals(intent.getAction()))
            getWindow().getDecorView().post(()->PixSupport.show(this));
    }
    private int d(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private void showPreferencesDialog(){
        ScrollView view=buildPreferences();
        new AlertDialog.Builder(this)
            .setTitle("⚙ Preferências")
            .setView(view)
            .setPositiveButton("Concluído",null)
            .show();
    }
    private ScrollView buildPreferences(){
        SharedPreferences prefs=getSharedPreferences("prefs",0);
        ScrollView sc=new ScrollView(this);
        LinearLayout body=new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(d(14),d(10),d(14),d(14));
        sc.addView(body);
        android.widget.TextView subtitle=new android.widget.TextView(this);
        subtitle.setText("Tamanho do Pokémon · altere também por P / M / G no overlay");
        subtitle.setTextColor(Color.LTGRAY);body.addView(subtitle);
        Spinner sizes=new Spinner(this);
        String[] sizeLabels={"P · 35 × 35","M · 45 × 45","G · 55 × 55"};
        sizes.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,sizeLabels));
        int current=prefs.getInt("spriteSize",45);
        sizes.setSelection(current==35?0:current==55?2:1);
        sizes.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(android.widget.AdapterView<?> p,android.view.View v,int pos,long id){
                prefs.edit().putInt("spriteSize",new int[]{35,45,55}[pos]).apply();
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
        return sc;
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
