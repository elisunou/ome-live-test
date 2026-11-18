package com.omestrem.media;
import android.net.Uri;import org.json.JSONObject;
/** Authorized session descriptor published by the provider's own page. Never invents tokens. */
public final class RdsLiveStream {
 public final String url,token,mode;public final long expiresAt;
 private RdsLiveStream(String url,String token,String mode,long expiry){this.url=url;this.token=token;this.mode=mode;this.expiresAt=expiry;}
 public static RdsLiveStream from(JSONObject json){if(json==null)return null;String url=json.optString("url");Uri uri=Uri.parse(url);if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null)return null;long expiry=json.optLong("expiresAt",0);if(expiry==0)expiry=System.currentTimeMillis()+60000;return new RdsLiveStream(url,json.optString("token"),json.optString("mode"),expiry);}
 public boolean sameMedia(RdsLiveStream other){if(other==null)return false;Uri a=Uri.parse(url),b=Uri.parse(other.url);return a.getHost().equalsIgnoreCase(b.getHost())&&a.getPath().equals(b.getPath());}
 public Uri resolve(Uri requested){
   // Preserve the provider-granted request. This compatibility layer does not guess
   // how private RDSLive signing works; an existing token parameter may be renewed.
   if(requested==null)return Uri.parse(url);
   if(token.isEmpty()||"direct".equalsIgnoreCase(mode))return requested;
   Uri current=Uri.parse(url);
   if(!current.getHost().equalsIgnoreCase(requested.getHost()))return requested;
   if(requested.getQueryParameterNames().contains("token"))return requested.buildUpon().clearQuery().encodedQuery(replaceToken(requested,token)).build();
   return requested;
 }
 private static String replaceToken(Uri uri,String token){StringBuilder b=new StringBuilder();for(String key:uri.getQueryParameterNames()){for(String val:uri.getQueryParameters(key)){if(b.length()>0)b.append('&');b.append(Uri.encode(key)).append('=').append(Uri.encode("token".equals(key)?token:val));}}return b.toString();}
}
