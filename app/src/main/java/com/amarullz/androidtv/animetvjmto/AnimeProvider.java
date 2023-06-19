package com.amarullz.androidtv.animetvjmto;

import android.annotation.SuppressLint;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.VectorDrawable;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.AsyncTask;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.tvprovider.media.tv.Channel;
import androidx.tvprovider.media.tv.ChannelLogoUtils;
import androidx.tvprovider.media.tv.PreviewProgram;
import androidx.tvprovider.media.tv.TvContractCompat;

import org.chromium.net.CronetEngine;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;

public class AnimeProvider {
    private static final String _TAG="ATVLOG-CHANNEL";

    private static final String[] CHANNELS_PROJECTION = {
            TvContractCompat.Channels._ID,
            TvContract.Channels.COLUMN_DISPLAY_NAME,
            TvContractCompat.Channels.COLUMN_BROWSABLE
    };
    private static final String[] PROGRAM_PROJECTION = {
            TvContractCompat.Programs._ID,
            TvContract.Programs.COLUMN_CHANNEL_ID,
            TvContract.Programs.COLUMN_TITLE
    };

    private static final String[] PLAYNEXT_PROJECTION = {
            TvContractCompat.WatchNextPrograms._ID,
            TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
            TvContract.WatchNextPrograms.COLUMN_TITLE
    };
    public interface RecentCallback {
        void onFinish(String result);
    }
    private final Context ctx;
    private final CronetEngine cronet;
    private long CHANNEL_ID=0;

    public HttpURLConnection initQuic(String url, String method) throws IOException {
        return AnimeApi.initCronetQuic(cronet,url,method);
    }

    public AnimeProvider(Context c){
        ctx=c;
        cronet = AnimeApi.buildCronet(c);

        try {
            CHANNEL_ID = initChannel();

            loadRecent(new RecentCallback() {
                @Override
                public void onFinish(String result) {
                    try {
                        JSONArray ja = new JSONArray(result);
                        if (ja.length() > 0) {
                            clearPrograms();
                            for (int i = 0; i < ja.length(); i++) {
                                try {
                                    JSONObject o = ja.getJSONObject(i);
                                    String uri = o.getString("url");
                                    String title = o.getString("title");
                                    String poster = o.getString("poster");
                                    String tip = o.getString("tip");
                                    String ep = o.getString("ep");
                                    String tp = o.getString("type");
                                    String desc = tp;
                                    if (!ep.equals("")) {
                                        desc += " Episode " + ep;
                                    }
                                    desc = desc.trim();
                                    addProgram(title, desc, poster, uri, tip);
                                } catch (Exception ignored) {
                                }
                            }
                        }
                    } catch (JSONException ignored) {
                    }
                    Log.d(_TAG, "RES = " + result);
                }
            });
        }catch (Exception ex){
            Log.d(_TAG, ex.toString());
        }
    }

    public void loadRecent(RecentCallback cb){
        AsyncTask.execute(() ->loadRecentExec(cb));
    }

    /* Get latest updated sub */
    private void loadRecentExec(RecentCallback cb){
        try {
            HttpURLConnection conn = initQuic(
                    "https://9anime.to/ajax/home/widget/updated-sub",
                    "GET"
            );
            ByteArrayOutputStream buffer = AnimeApi.getBody(conn, null);
            JSONObject jo=new JSONObject(buffer.toString());
            if (jo.has("result")){
                JSONArray r=new JSONArray("[]");
                String res=jo.getString("result");
                Document doc = Jsoup.parse(res);
                Elements els=doc.getElementsByClass("item");
                for (int i=0;i<els.size();i++){
                    try {
                        Element el = els.get(i);
                        Element tt = el.firstElementChild();
                        Element link = el.lastElementChild().firstElementChild();
                        Element img = tt.getElementsByTag("img").get(0);
                        Elements sb = tt.getElementsByClass("sub");
                        Elements rg = tt.getElementsByClass("right");
                        JSONObject d = new JSONObject("{}");
                        d.put("url", link.attr("href"));
                        d.put("title", link.text().trim());
                        d.put("poster", img.attr("src"));
                        d.put("ep", (sb.size() > 0)?sb.get(0).text().trim():"");
                        d.put("type", (rg.size() > 0)?rg.get(0).text().trim():"");
                        d.put("tip", tt.attr("data-tip"));
                        r.put(d);
                    }catch(Exception ignored){}
                }
                if (r.length()>0) {
                    cb.onFinish(r.toString());
                    return;
                }
            }
        }catch(Exception ignored){}
        cb.onFinish("");
    }

    /* Drawable to bitmap */
    @NonNull
    public static Bitmap convertToBitmap(Context context, int resourceId) {
        Drawable drawable = context.getDrawable(resourceId);
        if (drawable instanceof VectorDrawable) {
            Bitmap bitmap =
                    Bitmap.createBitmap(
                            drawable.getIntrinsicWidth(),
                            drawable.getIntrinsicHeight(),
                            Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            drawable.draw(canvas);
            return bitmap;
        }

        return BitmapFactory.decodeResource(context.getResources(), resourceId);
    }

    /* Create Channel */
    private long initChannel() {
        Cursor cursor =
                ctx.getContentResolver()
                        .query(
                                TvContractCompat.Channels.CONTENT_URI,
                                CHANNELS_PROJECTION,
                                null,
                                null,
                                null);
        if (cursor != null && cursor.moveToFirst()) {
            do {
                Channel channel = Channel.fromCursor(cursor);
                Log.d(_TAG,"Existing channel = "+channel.getDisplayName());
                return channel.getId();
            } while (cursor.moveToNext());
        }
        Intent myIntent = new Intent(ctx, MainActivity.class);
        myIntent.setPackage(ctx.getPackageName());
        Uri intentUri= Uri.parse(myIntent.toUri(Intent.URI_ANDROID_APP_SCHEME));
        Channel ch = new Channel.Builder().setType(TvContractCompat.Channels.TYPE_PREVIEW)
                .setDisplayName("AnimeTV")
                .setInternalProviderId("AnimeTV")
                .setAppLinkIntentUri(intentUri)
                .build();
        Uri uri = ctx.getContentResolver().insert(TvContract.Channels.CONTENT_URI, ch.toContentValues());
        Log.d(_TAG,"Created New Channel = "+uri);
        long channelId = ContentUris.parseId(uri);
        Log.d(_TAG, "channel id " + channelId);
        Bitmap bitmap = convertToBitmap(ctx, R.drawable.splash);
        ChannelLogoUtils.storeChannelLogo(ctx, channelId, bitmap);
        return channelId;
    }

    @SuppressLint("RestrictedApi")
    private void clearPrograms() {
        Cursor cursor =
                ctx.getContentResolver()
                        .query(
                                TvContractCompat.PreviewPrograms.CONTENT_URI,
                                PROGRAM_PROJECTION,
                                null,
                                null,
                                null);
        if (cursor != null && cursor.moveToFirst()) {
            int c=0;
            do {
                PreviewProgram prog = PreviewProgram.fromCursor(cursor);
                if (prog.getChannelId()==CHANNEL_ID) {
                    ctx.getContentResolver()
                            .delete(
                                    TvContractCompat.buildPreviewProgramUri(prog.getId()),
                                    null,
                                    null);
                    c++;
                }
            } while (cursor.moveToNext());
            Log.d(_TAG, "Cleanup " + c+" Programs");
        }
    }

    @SuppressLint("RestrictedApi")
    private Uri addProgram(String title, String desc, String image, String uri, String tip) {
        PreviewProgram.Builder builder = new PreviewProgram.Builder();
        Intent myIntent = new Intent(ctx, MainActivity.class);
        myIntent.setPackage(ctx.getPackageName());
        myIntent.putExtra("viewurl", uri);
        myIntent.putExtra("viewtip", tip);
        Uri intentUri= Uri.parse(myIntent.toUri(Intent.URI_ANDROID_APP_SCHEME));
        builder.setChannelId(CHANNEL_ID)
                .setType(TvContractCompat.PreviewProgramColumns.TYPE_TV_EPISODE)
                .setTitle(title)
                .setDescription(desc)
                .setPosterArtUri(Uri.parse(image))
                .setIntentUri(intentUri);
        PreviewProgram previewProgram = builder.build();
        Uri programUri = ctx.getContentResolver().insert(
                                TvContractCompat.PreviewPrograms.CONTENT_URI,
                                previewProgram.toContentValues());
        return programUri;
    }
}