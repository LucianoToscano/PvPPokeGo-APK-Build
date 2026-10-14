package com.lucianotoscano.pokesearchoverlay;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.util.Base64;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public final class PixSupport {
    // Verified against the QR shared by the app owner, including its CRC-16/CCITT.
    public static final String PAYLOAD="00020126580014br.gov.bcb.pix01369417c350-2b62-47a2-97f7-10ebd5a0e04d5204000053039865802BR5924LUCIANO CARECHA SALDANHA6009Sao Paulo62230519daqr155656798608654630446FA";
    private static final String QR_PNG="iVBORw0KGgoAAAANSUhEUgAAADkAAAA5AQAAAACkY74oAAAB30lEQVR42o3RT0iTcRzH8c/v60+eZzyQgy22hX8eqYM1/yRkPEINCap1WKGFHUapmYcg0h2i7FBP0UFXVAoRUQfRBgpeOhRGWB2CkllkA5ek8pijJv3RB7f2bO7Z06Vjh96n1/3NLAAArhL+9g/ojkuXrQYRJLy5x7/OrIHMay1SQJNByESkZhEgUGKl9A5ARVeayw5qGii7zzPRk7GDSrS7YttnA2QcHks0NSogRNyZ4VMKKBfk93sxBF7c5fQlT9tBxU8/VE6W+0FZR9FOV3USlB/8cTHeJIPE9a2TsSWArZ9Ip1KeehvxAa9wbLYH3Pq+cSsQHgUV4t1vR6J9QK510V7DK1SW964e3R4O2mDyza8c9gqV8ufOln45ngTllgVjftEPsuSy/vRHDZxudo+YK26Q2FX7vlUaBRXObxv8XaKCpX2vc4X9QZ3pkec/Z8adj0kKMG+n0A6y2kPjLKaAjCfvnHueqSBxarl3eGEC3PqVGBtq3NHBzANxn7hrQ0fqxVoo6nKpJHwKnvRfV8AMQdOqwuU6Zaeq6eHLPtCmqtgDxzc3uF5Z39Lv2wISovFDjygJMi9Md9bOySAIq3U3JBksdYYdadCnl5CZtxnCQJ1K2d17PbdDCth/zP0Dz8Ww7U1+oqAAAAAASUVORK5CYII=";
    private PixSupport(){}
    private static int dp(Activity a,int px){return (int)(a.getResources().getDisplayMetrics().density*px+0.5f);}
    public static void show(Activity a){
        LinearLayout root=new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(a,18),dp(a,8),dp(a,18),dp(a,10));
        TextView intro=new TextView(a);
        intro.setText("Gostou do Pokésearch? A contribuição é totalmente voluntária, de qualquer valor.");
        intro.setTextColor(Color.WHITE);intro.setTextSize(14);
        root.addView(intro);
        byte[] data=Base64.decode(QR_PNG,Base64.DEFAULT);
        Bitmap image=BitmapFactory.decodeByteArray(data,0,data.length);
        if(image!=null){
            ImageView qr=new ImageView(a);
            qr.setImageBitmap(image);
            qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams qrLayout=new LinearLayout.LayoutParams(dp(a,245),dp(a,245));
            qrLayout.gravity=Gravity.CENTER_HORIZONTAL;qrLayout.topMargin=dp(a,8);
            root.addView(qr,qrLayout);
        }
        TextView receiver=new TextView(a);
        receiver.setText("Destinatário do Pix:\nLUCIANO CARECHA SALDANHA\n\nConfira o nome antes de confirmar no banco.");
        receiver.setTextColor(Color.LTGRAY);receiver.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(receiver);
        new AlertDialog.Builder(a).setTitle("❤️ Contribuir via Pix")
            .setView(root)
            .setPositiveButton("⧉ Copiar Pix",(dialog,which)->{
                ClipboardManager clipboard=(ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE);
                if(clipboard!=null){
                    clipboard.setPrimaryClip(ClipData.newPlainText("Pix · Pokésearch Overlay",PAYLOAD));
                    Toast.makeText(a,"Código Pix copiado. Escolha o valor no seu banco.",Toast.LENGTH_LONG).show();
                }
            })
            .setNegativeButton("Fechar",null).show();
    }
}
