package com.lucianotoscano.pokesearchoverlay;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class WatchAlerts {
    private static final String CHANNEL_ID="pokesearch_watch_sound_v1";
    public static final String PREF_LIST="watchList";
    public static final String PREF_ENABLED="watchAlertsEnabled";
    private WatchAlerts(){}
    public static String normalized(String text){
        if(text==null)return "";
        String s=Normalizer.normalize(text,Normalizer.Form.NFD).replaceAll("\\p{M}+","").toLowerCase(Locale.ROOT);
        return s.replaceAll("[^a-z0-9]+"," ").trim().replaceAll(" +"," ");
    }
    public static LinkedHashMap<String,String> names(String raw){
        LinkedHashMap<String,String> result=new LinkedHashMap<>();
        if(raw==null)return result;
        for(String part:raw.split("[,;\\n\\r]+")){
            String display=part.trim().replaceAll("\\s+"," ");
            if(display.isEmpty())continue;
            if(display.length()>70)display=display.substring(0,70).trim();
            String key=normalized(display);
            if(!key.isEmpty()&&!result.containsKey(key)&&result.size()<50)result.put(key,display);
        }
        return result;
    }
    public static String canonicalList(String raw){return String.join("\n",names(raw).values());}
    public static Set<String> watched(Context context){
        SharedPreferences p=context.getSharedPreferences("prefs",0);
        return new LinkedHashSet<>(names(p.getString(PREF_LIST,"")).keySet());
    }
    public static boolean matches(String name,Set<String> wanted){
        String n=normalized(name);
        if(n.isEmpty())return false;
        for(String key:wanted)if(n.equals(key)||n.startsWith(key+" "))return true;
        return false;
    }
    public static boolean isWatched(Context context,String name){
        return matches(name,watched(context));
    }
    public static boolean toggle(Context context,String name){
        SharedPreferences p=context.getSharedPreferences("prefs",0);
        LinkedHashMap<String,String> list=names(p.getString(PREF_LIST,""));
        String key=normalized(name);
        if(key.isEmpty())return false;
        boolean added;
        if(list.containsKey(key)){list.remove(key);added=false;}
        else if(list.size()<50){list.put(key,name.trim());added=true;}
        else return false;
        p.edit().putString(PREF_LIST,String.join("\n",list.values())).apply();
        return added;
    }
    public static boolean enabled(Context context){
        return context.getSharedPreferences("prefs",0).getBoolean(PREF_ENABLED,true);
    }
    public static boolean permissionGranted(Context context){
        return Build.VERSION.SDK_INT<33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED;
    }
    public static void ensureChannel(Context context){
        NotificationManager manager=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
        if(manager==null)return;
        NotificationChannel channel=new NotificationChannel(CHANNEL_ID,
            "PokeSearch · Favoritos",NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Aviso sonoro ao encontrar um favorito com IV100 ou ranking #1");
        channel.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build());
        manager.createNotificationChannel(channel);
    }
    private static boolean send(Context context,String title,String info,int id){
        if(!permissionGranted(context))return false;
        ensureChannel(context);
        NotificationManager manager=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
        if(manager==null)return false;
        Intent open=new Intent(context,MainActivity.class);
        PendingIntent intent=PendingIntent.getActivity(context,11,open,
            PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification note=new Notification.Builder(context,CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle(title).setContentText(info).setAutoCancel(true)
            .setStyle(new Notification.BigTextStyle().bigText(info)).setContentIntent(intent).build();
        try{manager.notify(id,note);return true;}catch(Exception ignored){return false;}
    }
    public static boolean test(Context context){
        return send(context,"🔔 Teste: Pokémon procurado",
            "Som de teste. Alertas reais exigem Pokémon cadastrado e IV100 ou ranking 1 confirmado.",11999);
    }
    public static boolean match(Context context,String pokemon,String info){
        return send(context,"🔔 Encontrado: "+pokemon,info,
            13000+(int)(System.currentTimeMillis()%10000L));
    }
}
