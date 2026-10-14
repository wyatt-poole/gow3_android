package ro.cobrabm.fexdroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** The startup graphics options of gow3.ini (scripts/graphics_options.py, gpu/shim/gow3_settings.cpp):
 *  the same keys the PC launcher and the in-game menu write. Applying them restarts the game, as the
 *  in-game menu's "Apply and restart" does. Also the key=value helpers for the app's own android.ini. */
final class SettingsDialog {
    private static final String[] RESOLUTIONS={"480p","720p","native"};
    private static final String[] RESOLUTION_NAMES={"480p (fastest)","720p","1080p (native)"};
    private static final String[] ENGINE_FPS={"60","120"};
    private static final String[] ENGINE_FPS_NAMES={"60 FPS (no patch)","120 FPS patch"};
    private static final String[] FPS_LIMITS={"30","60","0"};
    private static final String[] FPS_LIMIT_NAMES={"30 FPS","60 FPS","No limit"};
    private static final String[] FLAGS={"0","1"};
    private static final String[] OVERLAY_NAMES={"Off","On"};

    private final Activity activity;
    private final File ini;
    private final Runnable restart;

    SettingsDialog(Activity activity,File ini,Runnable restart) { this.activity=activity; this.ini=ini; this.restart=restart; }

    /** The last value of key in a key=value file, or null. */
    static String get(File file,String key) {
        String value=null;
        try {
            for (String line:Files.readAllLines(file.toPath(),StandardCharsets.UTF_8)) {
                if (line.trim().startsWith("#")) continue;
                int eq=line.indexOf('=');
                if (eq>0 && line.substring(0,eq).trim().equals(key)) value=line.substring(eq+1).trim();
            }
        } catch (Exception ignored) {}
        return value;
    }
    /** Rewrites key in place (or appends it), keeping every other line, as graphics_options.write_keys. */
    static boolean set(File file,String key,String value) {
        List<String> out=new ArrayList<>();
        boolean done=false;
        try {
            if (file.isFile()) for (String line:Files.readAllLines(file.toPath(),StandardCharsets.UTF_8)) {
                int eq=line.indexOf('=');
                if (!line.trim().startsWith("#") && eq>0 && line.substring(0,eq).trim().equals(key)) {
                    if (!done) out.add(key+"="+value);
                    done=true;
                } else out.add(line);
            }
            if (!done) out.add(key+"="+value);
            file.getParentFile().mkdirs();
            File temp=new File(file.getPath()+".tmp");
            Files.write(temp.toPath(),(String.join("\n",out)+"\n").getBytes(StandardCharsets.UTF_8));
            return temp.renameTo(file);
        } catch (Exception ex) {
            android.util.Log.e("GoW3","settings: "+ex);
            return false;
        }
    }
    private static int index(String[] values,String value,int fallback) {
        for (int i=0;i<values.length;++i) if (values[i].equals(value)) return i;
        return fallback;
    }
    /** The drawer's second line. */
    static String summary(File ini) {
        String resolution=RESOLUTION_NAMES[index(RESOLUTIONS,get(ini,"render_resolution"),1)].replaceAll(" \\(.*\\)","");
        String limit=get(ini,"fps_limit");
        return resolution+" · "+("0".equals(limit) ? "no FPS limit" : (limit==null ? "30" : limit)+" FPS limit")
            +("120".equals(get(ini,"engine_fps")) ? " · 120 FPS patch" : "");
    }

    private Spinner row(LinearLayout box,String label,String[] names,int selected) {
        TextView title=new TextView(activity); title.setText(label); title.setTextSize(14);
        title.setPadding(0,dp(10),0,dp(2));
        box.addView(title);
        Spinner spinner=new Spinner(activity);
        ArrayAdapter<String> adapter=new ArrayAdapter<>(activity,android.R.layout.simple_spinner_item,names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter); spinner.setSelection(selected);
        box.addView(spinner);
        return spinner;
    }
    private int dp(float n) { return Math.round(n*activity.getResources().getDisplayMetrics().density); }

    void show() {
        LinearLayout box=new LinearLayout(activity); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24),dp(8),dp(24),0);
        Spinner resolution=row(box,"Render resolution",RESOLUTION_NAMES,index(RESOLUTIONS,get(ini,"render_resolution"),1));
        Spinner engine=row(box,"Engine frame rate",ENGINE_FPS_NAMES,index(ENGINE_FPS,get(ini,"engine_fps"),0));
        Spinner limit=row(box,"Frame rate limit",FPS_LIMIT_NAMES,index(FPS_LIMITS,get(ini,"fps_limit"),0));
        Spinner overlay=row(box,"Performance overlay (FPS)",OVERLAY_NAMES,index(FLAGS,get(ini,"overlay_fps"),0));
        TextView note=new TextView(activity); note.setTextSize(12); note.setPadding(0,dp(12),0,0);
        note.setText("The picture always fills your screen at its own resolution; render resolution is how "
            +"detailed the game draws it. Phones are much slower than the PCs the patches were made for: 720p at "
            +"the game's own 60 FPS engine rate with a 30 FPS limit is the safe start. More options (cheats, "
            +"multipliers, image) are in the in-game menu.");
        box.addView(note);
        android.widget.ScrollView scroll=new android.widget.ScrollView(activity); scroll.addView(box);
        new AlertDialog.Builder(activity).setTitle("Graphics settings").setView(scroll)
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Apply and restart",(d,w) -> {
                boolean ok=set(ini,"render_resolution",RESOLUTIONS[resolution.getSelectedItemPosition()])
                    & set(ini,"engine_fps",ENGINE_FPS[engine.getSelectedItemPosition()])
                    & set(ini,"fps_limit",FPS_LIMITS[limit.getSelectedItemPosition()])
                    & set(ini,"overlay_fps",FLAGS[overlay.getSelectedItemPosition()])
                    & set(ini,"show_fps",FLAGS[overlay.getSelectedItemPosition()]);
                if (!ok) android.widget.Toast.makeText(activity,"Could not save the settings.",android.widget.Toast.LENGTH_LONG).show();
                else restart.run();
            }).show();
    }
}
