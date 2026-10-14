package ro.cobrabm.fexdroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;
import java.io.File;
import java.io.IOException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Installs the game from its PKG files (the runtime's pkg_install.py, one PKG per run): the base game
 *  first, then its update (01.02) into the same destination. Picks the PKG (a real path: multi-GB files are
 *  never copied), then asks where to install it (the tool makes the CUSAxxxxx folder inside), runs the tool
 *  under a progress dialog (bytes, rate, time left; Cancel destroys the process) and reports the result. When the game folder is ready the
 *  activity starts it. */
final class GameInstaller {
    /** The runtime's installer (gow3/pkg_install.py, run by the rootfs's Python). */
    static final String SCRIPT="pkg_install.py";
    static final int REQUEST_PKG=7, REQUEST_DEST=8;
    static final String DEFAULT_DEST="/storage/emulated/0/GoW3";
    /** A PKG whose path cannot be read is copied only when it is smaller than this. */
    static final long COPY_LIMIT=1L<<30;

    interface Host {
        /** Runs a runtime Python script, passing each output line; false when the script is missing. */
        boolean scriptStream(String script,java.util.function.Consumer<String> lines,AtomicBoolean cancel,String... args) throws IOException;
        /** The file path of a picked document, or null (then its content is copied). */
        File pathOf(Uri document);
        /** The folder path of a picked tree, or null. */
        File folderOf(Uri tree);
        boolean hasStorage();
        void pick(Intent intent,int code);
        void toast(String message);
        boolean destroyed();
        /** The current game folder, or null. */
        String gameDir();
        boolean gameRunning();
        /** Stops the running game; blocks (called off the UI thread). */
        void stopGame();
        /** The folder has the game ready: make it the game folder and start (or restart) the game. */
        void useGame(File dir);
        /** The flow ended without a ready game; `stopped`: the installer had stopped the running game. */
        void installEnded(boolean stopped);
    }

    private final Activity activity;
    private final Host host;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancel=new AtomicBoolean();
    private volatile boolean closed=true;
    private PkgProgressDialog progress;
    private String dest=DEFAULT_DEST;
    private File pending; private String pendingName=""; private boolean pendingTemp, destChosen, stopped, busy;

    GameInstaller(Activity activity,Host host) { this.activity=activity; this.host=host; }

    private boolean live() { return !closed && !host.destroyed() && !activity.isFinishing(); }
    private void background(Runnable r,String name) { Thread t=new Thread(r,name); t.setDaemon(true); t.start(); }
    private void end() { if (!closed) { closed=true; host.installEnded(stopped); } }
    private void alert(String title,String message,boolean pickAgain) {
        AlertDialog.Builder b=new AlertDialog.Builder(activity).setTitle(title).setMessage(message).setCancelable(false);
        if (pickAgain) b.setPositiveButton("Pick again",(d,w) -> pickPkg()).setNegativeButton("Cancel",(d,w) -> end());
        else b.setPositiveButton("OK",(d,w) -> end());
        b.show();
    }

    /** Picks the PKG; where to install it is asked next. */
    void start() {
        if (busy) return;
        closed=false; cancel.set(false); dest=DEFAULT_DEST; destChosen=false; stopped=false; pending=null;
        if (!host.hasStorage()) { host.toast("Installing needs storage access."); end(); return; }
        pickPkg();
    }
    /** The install flow is open (from start() until it ends). */
    boolean active() { return !closed; }
    void close() {
        closed=true; cancel.set(true);
        if (progress!=null) progress.dismiss();
    }

    private void pickDest() {
        try { host.pick(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),REQUEST_DEST); }
        catch (Exception ex) { host.toast("No folder picker: "+ex); discard(); end(); }
    }
    private void pickPkg() {
        try { host.pick(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),REQUEST_PKG); }
        catch (Exception ex) { host.toast("No file picker: "+ex); end(); }
    }

    /** Picker results: the PKG, the destination folder. */
    void onResult(int code,int result,Intent data) {
        if (closed) return;
        boolean ok=result==Activity.RESULT_OK && data!=null && data.getData()!=null;
        if (code==REQUEST_PKG) { if (ok) resolve(data.getData()); else end(); }
        else if (code==REQUEST_DEST) {
            if (!ok) { askDestination(); return; }
            File dir=host.folderOf(data.getData());
            if (dir==null) { host.toast("That folder cannot be used: choose one in the phone's storage or on the SD card."); askDestination(); return; }
            dest=dir.getPath(); destChosen=true;
            confirmStop();
        }
    }

    /** The PKG as a readable file: its own path, or a copy in the cache when it is small. */
    private void resolve(Uri uri) {
        File own=host.pathOf(uri);
        if (own!=null && own.isFile() && own.canRead()) { picked(own,false,own.getName()); return; }
        String name="game.pkg"; long size=-1;
        try (android.database.Cursor c=activity.getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME,android.provider.OpenableColumns.SIZE},null,null,null)) {
            if (c!=null && c.moveToFirst()) {
                if (c.getString(0)!=null) name=c.getString(0);
                if (!c.isNull(1)) size=c.getLong(1);
            }
        } catch (Exception ignored) {}
        final String label=name; final long total=size;
        if (total<0 || total>COPY_LIMIT) {
            alert("Move the PKG first","The app cannot read this file where it is, and it is too large to copy. Move the PKG into the "
                +"phone's storage (for example the Download folder), then pick it again from there.",true);
            return;
        }
        File cache=activity.getCacheDir();
        if (total+(64L<<20)>new StatFs(cache.getPath()).getAvailableBytes()) {
            alert("Not enough space","There is not enough free space to copy "+label+". Move the PKG into the phone's storage "
                +"(for example the Download folder), then pick it again from there.",true);
            return;
        }
        busy=true; cancel.set(false);
        progress=new PkgProgressDialog(activity,"Install from PKG","Reading "+label+"…",cancel);
        background(() -> {
            File tmp=null; boolean cancelled=false; String failure=null;
            try {
                tmp=File.createTempFile("game-",".pkg",cache);
                try (InputStream in=activity.getContentResolver().openInputStream(uri)) {
                    if (in==null) throw new IOException("Cannot open "+label);
                    copy(in,tmp,total,(d,t) -> main.post(() -> { if (live() && !cancel.get()) progress.fraction("Reading "+label,d,t); }));
                }
            } catch (CancellationException e) { cancelled=true;
            } catch (Exception e) { failure=e.getMessage()!=null ? e.getMessage() : e.toString(); }
            File copy=tmp; String problem=failure; boolean stop=cancelled;
            if ((stop || problem!=null) && copy!=null) copy.delete();
            main.post(() -> {
                progress.dismiss(); busy=false;
                if (!live()) { if (copy!=null) copy.delete(); return; }
                if (stop) { if (copy!=null) copy.delete(); end(); }
                else if (problem!=null) alert("Could not read the PKG",problem,false);
                else picked(copy,true,label);
            });
        },"Game-copy");
    }

    private void picked(File pkg,boolean temporary,String name) {
        if (!isPkg(pkg)) {
            if (temporary) pkg.delete();
            alert("Not a PKG","That file is not a PS4 .pkg package.",true); return;
        }
        pending=pkg; pendingTemp=temporary;
        if (destChosen) confirmStop(); else { pendingName=name; askDestination(); }
    }

    /** The PKG is picked: where to install it (the update goes where the base game went, without asking). */
    private void askDestination() {
        if (pending==null) { end(); return; }
        new AlertDialog.Builder(activity).setTitle("Install "+pendingName).setCancelable(false)
            .setMessage("Choose the folder to install into. A folder named after the game's serial (for example "
                +"CUSA01623) is created inside it and becomes the game folder.\n\nYou need free space about the size of the PKG.")
            .setNegativeButton("Cancel",(d,w) -> { discard(); end(); })
            .setPositiveButton("Choose folder…",(d,w) -> pickDest()).show();
    }
    private void discard() { if (pending!=null && pendingTemp) pending.delete(); pending=null; }

    /** The running game blocks a destination that holds its folder: ask, then stop it. */
    private void confirmStop() {
        if (holdsGame())
            new AlertDialog.Builder(activity).setTitle("Stop the game?").setCancelable(false)
                .setMessage("The game will close while installing.")
                .setNegativeButton("Cancel",(d,w) -> { discard(); end(); })
                .setPositiveButton("Continue",(d,w) -> run()).show();
        else run();
    }

    /** The game is running from a folder inside the destination. */
    private boolean holdsGame() {
        String current=host.gameDir();
        return host.gameRunning() && current!=null && (current.equals(dest) || current.startsWith(dest.endsWith("/") ? dest : dest+"/"));
    }
    private void run() {
        final File pkg=pending; final boolean temporary=pendingTemp; pending=null;
        busy=true; cancel.set(false);
        progress=new PkgProgressDialog(activity,"Install game","Starting…",cancel);
        background(() -> {
            JSONObject result=null; String failure=null; boolean cancelled=false;
            try {
                if (holdsGame()) { stopped=true; host.stopGame(); }
                PkgProgress track=new PkgProgress();
                String[] answer={null}; StringBuilder other=new StringBuilder();
                boolean known=host.scriptStream(SCRIPT,line -> {
                    line=line.trim();
                    long now=SystemClock.elapsedRealtime();
                    if (track.feed(line,now)) {
                        String label=track.label, text=track.detail(now); int permille=track.permille();
                        main.post(() -> { if (live() && !cancel.get()) progress.pkg(label,"Installing the game…",permille,text); });
                    } else if (line.startsWith("{")) answer[0]=line;
                    else if (!line.isEmpty() && other.length()<400) other.append(line).append('\n');
                },cancel,"--dest",dest,"--pkg",pkg.getAbsolutePath());
                if (!known) throw new IOException("Update the app's runtime to install from PKG");
                if (cancel.get()) throw new CancellationException();
                if (answer[0]==null) {
                    if (other.indexOf("usage:")>=0) throw new IOException("Update the app's runtime to install from PKG");
                    throw new IOException("The PKG could not be installed"+(other.length()>0 ? ": "+other.toString().trim() : "."));
                }
                result=new JSONObject(answer[0]);
            } catch (CancellationException e) { cancelled=true;
            } catch (Exception e) {
                failure=e.getMessage()!=null ? e.getMessage() : e.toString();
                android.util.Log.e("GoW3","game install: "+e);
            } finally { if (temporary) pkg.delete(); }
            JSONObject o=result; String problem=failure; boolean stop=cancelled;
            main.post(() -> {
                progress.dismiss(); busy=false;
                if (!live()) return;
                if (stop) { host.toast("Install cancelled."); end(); }
                else if (problem!=null) alert("Not installed",problem,false);
                else if (!o.optBoolean("ok")) alert("Not installed",o.isNull("problem") ? "The PKG could not be installed." : o.optString("problem"),false);
                else finished(o);
            });
        },"Game-install");
    }

    private void finished(JSONObject o) {
        String where=o.optString("gameDir");
        String next=o.isNull("next") ? "" : o.optString("next");
        if (!o.optBoolean("ready") || where.isEmpty()) { alert("Not installed","The PKG could not be installed.",false); return; }
        if (next.isEmpty()) { closed=true; host.useGame(new File(where)); return; }
        // An older version (the base game, 01.00) runs, without the patches made for 01.02.
        String version=o.isNull("appVer") ? "" : o.optString("appVer");
        new AlertDialog.Builder(activity).setTitle("Game installed")
            .setMessage("God of War III"+(version.isEmpty() ? "" : " version "+version)+" is installed in\n"+where
                +".\n\n"+next+". You can play now and install the update later.")
            .setCancelable(false)
            .setNegativeButton("Play now",(d,w) -> { closed=true; host.useGame(new File(where)); })
            .setPositiveButton("Pick update PKG…",(d,w) -> pickPkg()).show();
    }

    /** A PS4 package by its first bytes (0x7F "CNT"). */
    static boolean isPkg(File file) {
        byte[] head=new byte[4];
        try (FileInputStream in=new FileInputStream(file)) {
            int n=0, r;
            while (n<head.length && (r=in.read(head,n,head.length-n))>0) n+=r;
            return n==4 && head[0]==0x7F && head[1]=='C' && head[2]=='N' && head[3]=='T';
        } catch (IOException e) { return false; }
    }
    interface Progress { void update(long done,long total); }
    /** Copies a stream to a file (a picked PKG without a readable path). Total -1: unknown. */
    private void copy(InputStream in,File out,long total,Progress progress) throws IOException {
        byte[] buffer=new byte[64<<10];
        long done=0, last=0;
        try (OutputStream o=new FileOutputStream(out)) {
            for (int n; (n=in.read(buffer))>0;) {
                if (cancel.get()) throw new CancellationException();
                o.write(buffer,0,n); done+=n;
                long now=System.nanoTime();
                if (now-last>100_000_000L) { last=now; progress.update(done,total); }
            }
        }
        progress.update(done,total);
    }
}
