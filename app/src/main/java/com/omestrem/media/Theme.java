package com.omestrem.media;
import android.app.Activity;import android.graphics.Color;import android.graphics.drawable.GradientDrawable;
public final class Theme {
 public static final int ACCENT=0xFFE33855, PANEL=0xFF111925, PANEL_2=0xFF202C3D, TEXT=Color.WHITE;
 public static void applyPreferences(Activity a){ a.getWindow().setStatusBarColor(0xFF080A12);a.getWindow().setNavigationBarColor(0xFF080A12); }
 public static GradientDrawable box(int color,int radius,Activity a){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(radius*a.getResources().getDisplayMetrics().density);return d;}
}
