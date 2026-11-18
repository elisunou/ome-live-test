package com.omestrem.media;
import java.util.concurrent.TimeUnit;import okhttp3.OkHttpClient;
public final class PreferredNetwork {
 public static OkHttpClient client(int connectSeconds,int readSeconds){return new OkHttpClient.Builder().connectTimeout(connectSeconds,TimeUnit.SECONDS).readTimeout(readSeconds,TimeUnit.SECONDS).followRedirects(true).build();}
}
