package com.omestrem.media;
import android.net.Uri;
public final class TvZoneCatalog {
 public static boolean validPage(String url){try {Uri u=Uri.parse(url);return "https".equalsIgnoreCase(u.getScheme()) && ("www.tvzonehd.com".equalsIgnoreCase(u.getHost())||"tvzonehd.com".equalsIgnoreCase(u.getHost())) && u.getPath()!=null && !u.getPath().equals("/");}catch(Exception e){return false;}}
}
