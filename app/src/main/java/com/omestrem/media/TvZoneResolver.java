package com.omestrem.media;
import java.io.IOException;import java.util.regex.Matcher;import java.util.regex.Pattern;import okhttp3.OkHttpClient;import okhttp3.Request;import okhttp3.Response;
/** Compatibility adapter for direct HLS/DASH links; NOT the compiled original resolver. */
public final class TvZoneResolver {
 private static final String UA="Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36";
 public static String userAgent(){return UA;}
 public static String resolve(String url)throws IOException {
  if(!TvZoneCatalog.validPage(url))throw new IOException("Invalid TVZone page");
  OkHttpClient client=PreferredNetwork.client(10,18);
  try(Response res=client.newCall(new Request.Builder().url(url).header("User-Agent",UA).build()).execute()){
   if(!res.isSuccessful()||res.body()==null)throw new IOException("TVZone HTTP "+res.code());
   String html=res.body().string();
   Matcher matcher=Pattern.compile("https?://[^\\s<>]+?\\.(?:m3u8|mpd)(?:\\?[^\\s<>]*)?",Pattern.CASE_INSENSITIVE).matcher(html);
   if(matcher.find())return matcher.group().replace("&amp;","&");
  }
  throw new IOException("No directly published media URL. Original TVZone resolver source is required.");
 }
}
