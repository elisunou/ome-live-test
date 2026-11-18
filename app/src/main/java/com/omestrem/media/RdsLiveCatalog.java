package com.omestrem.media;
import android.net.Uri;import java.util.Locale;
public final class RdsLiveCatalog {
 public static boolean validPage(String url){try {Uri u=Uri.parse(url);return "https".equalsIgnoreCase(u.getScheme()) && "rdslive.net".equalsIgnoreCase(u.getHost()) && u.getPath()!=null && !u.getPath().equals("/");}catch(Exception e){return false;}}
 public static String displayName(String name){return name==null?"TV Live":name.trim();}
}
