package com.omestrem.media;
import android.app.Activity;import android.app.AlertDialog;import android.content.Intent;import android.graphics.Color;import android.os.Bundle;import android.view.Gravity;import android.widget.*;import org.json.JSONArray;import org.json.JSONObject;
/** Thin launcher: original RdsLiveActivity owns playback and all source controls. */
public final class LiveCatalogActivity extends Activity {
 private JSONArray catalog;
 private LinearLayout list;
 private String category="Toate";
 @Override public void onCreate(Bundle b){super.onCreate(b);Theme.applyPreferences(this);IptvOrgCatalog.init(this);catalog=IptvOrgCatalog.channelOrder(null,null);LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(0xFF0C111C);TextView title=new TextView(this);title.setText("OME LIVE • CODUL ORIGINAL");title.setTextSize(20);title.setTextColor(Color.WHITE);title.setPadding(20,28,20,20);root.addView(title);Button filter=new Button(this);filter.setText("Categorie / căutare");filter.setOnClickListener(v->chooseFilter());root.addView(filter);ScrollView scroll=new ScrollView(this);list=new LinearLayout(this);list.setOrientation(1);scroll.addView(list);root.addView(scroll);setContentView(root);render();}
 private void chooseFilter(){String[] cats={"Toate","generaliste","filme","sport","stiri","documentare","copii","muzica","diverse","religioase"};new AlertDialog.Builder(this).setTitle("Canale TV").setItems(cats,(d,i)->{category=cats[i];render();}).show();}
 private void render(){list.removeAllViews();for(int i=0;i<catalog.length();i++){JSONObject c=catalog.optJSONObject(i);if(c==null)continue;if(!"Toate".equals(category)&&!category.equalsIgnoreCase(c.optString("category")))continue;Button b=new Button(this);b.setAllCaps(false);b.setText(c.optString("name")+"   •   "+c.optString("category"));b.setOnClickListener(v->open(c));list.addView(b);}}
 private void open(JSONObject c){String page=c.optString("rdsPage"),tv=c.optString("tvZonePage"),name=c.optString("name");if(page.isEmpty()&&tv.isEmpty()){new AlertDialog.Builder(this).setMessage("Canal fără pagină de redare.").setPositiveButton("OK",null).show();return;}
  Intent intent=new Intent(this,RdsLiveActivity.class).putExtra("page",page.isEmpty()?tv:page).putExtra("tvZonePage",tv).putExtra("channelId",c.optString("id")).putExtra("title",name).putExtra("channelCategory",category).putExtra("channelZap",false);
  if(name.toLowerCase(java.util.Locale.ROOT).contains("antena 1")||name.equalsIgnoreCase("Antena1"))intent.putExtra("antenaPage","https://antena1.live/antena-1-live/").putExtra("initialAntena",true);
  if(!tv.isEmpty()&&page.isEmpty())intent.putExtra("initialTvZone",true);
  startActivity(intent);
 }
}
