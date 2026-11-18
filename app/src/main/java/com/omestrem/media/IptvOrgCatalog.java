package com.omestrem.media;
import android.content.Context;import org.json.JSONArray;import org.json.JSONObject;import java.io.InputStream;import java.nio.charset.StandardCharsets;
public final class IptvOrgCatalog {
 private static Context context;
 public static void init(Context c){context=c.getApplicationContext();}
 private static JSONArray load(String file){try(InputStream in=context.getAssets().open(file)){byte[] b=new byte[in.available()];int n=0,k;while(n<b.length &&(k=in.read(b,n,b.length-n))>0)n+=k;return new JSONArray(new String(b,0,n,StandardCharsets.UTF_8));}catch(Exception e){return new JSONArray();}}
 public static JSONArray channelOrder(String category,String query){JSONArray out=new JSONArray();for(String file:new String[]{"rdslive-channels.json","tvzone-channels.json"}){JSONArray arr=load(file);for(int i=0;i<arr.length();i++){JSONObject c=arr.optJSONObject(i);if(c==null)continue;String name=c.optString("name");if(category!=null&&!category.isEmpty()&&!"Toate".equalsIgnoreCase(category)&&!category.equalsIgnoreCase(c.optString("category")))continue;if(query!=null&&!query.isEmpty()&&!name.toLowerCase(java.util.Locale.ROOT).contains(query.toLowerCase(java.util.Locale.ROOT)))continue;out.put(c);}}return out;}
 public static JSONArray withSources(JSONArray arr){return arr==null?new JSONArray():arr;}
 public static JSONObject playable(JSONArray arr,int index){JSONObject c=arr==null?null:arr.optJSONObject(index);return c!=null&&c.optString("url").startsWith("https://")?c:null;}
}
