package com.omestrem.media;
import android.net.Uri;
public final class AntenaLiveCatalog {
 public static boolean validPage(String url){try{Uri u=Uri.parse(url);return "https".equalsIgnoreCase(u.getScheme()) && "antena1.live".equalsIgnoreCase(u.getHost()) && u.getPath()!=null && !u.getPath().equals("/");}catch(Exception e){return false;}}
}
