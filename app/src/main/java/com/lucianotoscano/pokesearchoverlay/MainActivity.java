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
        bell.setImageResource(R.drawable.ic_bell_outline);
        watchRow.addView(bell,new LinearLayout.LayoutParams(d(27),d(27)));
        LinearLayout watchTexts=new LinearLayout(this);
        watchTexts.setOrientation(LinearLayout.VERTICAL);
        watchTexts.setPadding(d(9),0,0,0);
        TextView watchTitle=new TextView(this);
        watchTitle.setText("Pokémon procurados");
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
                if(url!=null&&url.startsWith("https://pogocoordinates.com/"))
                    view.evaluateJavascript(readAsset("presets.js"),null);
            }
        });
        root.addView(website,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
        website.loadUrl("https://pogocoordinates.com/search");
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
        watchSummary.setText(size==0?"Nenhum selecionado · toque para adicionar":
            size+" monitorado"+(size==1?"":"s")+" · toque para editar");
    }
    private void openPicker(){
        PokemonPicker.show(this,this::refreshWatchCount);
    }
    @Override protected void onResume(){
        super.onResume();
        refreshWatchCount();
    }
    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);
        setIntent(intent);
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
            .setTitle("⚙ Configurações e diagnóstico")
            .setView(view)
            .setPositiveButton("Concluído",null)
            .show();
    }
    private ScrollView buildPreferences(){
        SharedPreferences prefs=getSharedPreferences("prefs",0);
        ScrollView sc=new ScrollView(this);
        sc.setFillViewport(false);
        LinearLayout body=new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(d(14),d(8),d(14),d(12));
        sc.addView(body);
        Button picker=new Button(this);
        picker.setText("🔔 Procurados · buscar na Pokédex");
        picker.setAllCaps(false);
        body.addView(picker);
        picker.setOnClickListener(v->openPicker());
        Button textMode=new Button(this);
        textMode.setText("Editar nomes manualmente");
        textMode.setAllCaps(false);
        body.addView(textMode);
        textMode.setOnClickListener(v->showWatchDialog());
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
        return sc;
    }
    private void buildWatchButton(LinearLayout root){
        Button watch=new Button(this);
        watch.setText("🔔 Pokémon procurados · alerta sonoro");
        root.addView(watch);
        watch.setOnClickListener(v->showWatchDialog());
    }
    private boolean checkNotificationPermission(){
        if(Build.VERSION.SDK_INT>=33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},83);
            Toast.makeText(this,"Autorize notificações e tente novamente",Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }
    private void showWatchDialog(){
        SharedPreferences p=getSharedPreferences("prefs",0);
        LinearLayout content=new LinearLayout(this);
        content.setPadding(d(18),d(8),d(18),d(6));
        content.setOrientation(LinearLayout.VERTICAL);
        TextView help=new TextView(this);
        help.setText("Cadastre até 50 Pokémon por nome, separados por vírgulas ou uma por linha. Ex.: Frigibax, Gible, Axew. Avisamos somente quando a nova ocorrência atender IV100, GL#1 ou UL#1, com dados confirmados no filtro ativo.");
        help.setTextColor(Color.WHITE);content.addView(help);
        EditText edit=new EditText(this);
        edit.setText(p.getString(WatchAlerts.PREF_LIST,""));
        edit.setHint("Frigibax, Gible, Axew...");
        edit.setTextColor(Color.WHITE);edit.setHintTextColor(Color.LTGRAY);
        edit.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        edit.setMinLines(3);edit.setMaxLines(6);content.addView(edit);
        CheckBox enabled=new CheckBox(this);
        enabled.setText("Ativar notificações sonoras de procurados");
        enabled.setTextColor(Color.WHITE);
        enabled.setChecked(p.getBoolean(WatchAlerts.PREF_ENABLED,true));
        content.addView(enabled);
        TextView note=new TextView(this);
        note.setText("O som depende da permissão de notificações e das configurações do canal no Android. Alertas gerais são independentes.");
        note.setTextColor(Color.LTGRAY);content.addView(note);
        ScrollView sc=new ScrollView(this);sc.addView(content);
        AlertDialog dialog=new AlertDialog.Builder(this)
            .setTitle("🔔 Minha lista de procurados")
            .setView(sc)
            .setPositiveButton("Salvar",(w,which)->{
                String list=WatchAlerts.canonicalList(edit.getText().toString());
                p.edit().putString(WatchAlerts.PREF_LIST,list)
                    .putBoolean(WatchAlerts.PREF_ENABLED,enabled.isChecked()).apply();
                Toast.makeText(this,"Lista salva: "+WatchAlerts.names(list).size()+" Pokémon",Toast.LENGTH_SHORT).show();
                if(enabled.isChecked()&&!list.isEmpty())checkNotificationPermission();
            })
            .setNegativeButton("Cancelar",null)
            .setNeutralButton("🔊 Testar som",null)
            .create();
        dialog.setOnShowListener(w->dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{
            if(checkNotificationPermission()){
                boolean sent=WatchAlerts.test(this);
                Toast.makeText(this,sent?"Notificação de teste enviada":"Não foi possível enviar o alerta",Toast.LENGTH_SHORT).show();
            }
        }));
        dialog.show();
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
