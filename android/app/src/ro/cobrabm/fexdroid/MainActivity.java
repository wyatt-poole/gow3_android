package ro.cobrabm.fexdroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** God of War III Remastered on Android. Keeps the game surface alive beneath its sliding drawer.
 *  Adapted from Bloodborne-Android's player (github.com/ducvd89/Bloodborne-Android); the Java package
 *  stays ro.cobrabm.fexdroid because the display and input bridges (libfxdisplay, libfxio) bind to it. */
public final class MainActivity extends Activity {
    /** The app's files (/data/data/<package>/files), its Linux rootfs, the port (gow3/: run.py, scripts,
     *  binaries) and the player's data (gow3-data/: gow3.ini, saves, prepared game image). */
    private String FILES, ROOT, BASE, DATA;
    /** The game (e.g. CUSA01623, with eboot.bin) in shared storage, chosen by the player. */
    private String gameDir;
    private static final String PREFS="gow3";
    private static final int REQUEST_FOLDER=1, REQUEST_STORAGE=2, REQUEST_DRIVER=3, REQUEST_SAVES=9; // 7-8: GameInstaller
    /** X11 keycodes (evdev + 8) of the keys the in-game menu (gpu/shim/gow3_overlay.cpp) reads. */
    private static final int X_INSERT=118, X_ESCAPE=9, X_RETURN=36, X_UP=111, X_DOWN=116, X_LEFT=113, X_RIGHT=114,
        X_TAB=23, X_CONTROL=37;
    /** The game's X display (Xvfb) and the game window on it: 1280x720 or 1920x1080 (android.ini
     *  display_output: auto, the default, matches the screen; 720p; 1080p). Read when the app starts. */
    private int X_WIDTH=1280, X_HEIGHT=720;
    private SurfaceHolder pendingHolder;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private FrameLayout screen;
    private SurfaceView surface;
    private View scrim, handle;
    private LinearLayout drawer;
    private LinearLayout drawerItems;
    private TextView status;
    private View resume;
    private LinearLayout graphicsItem, menuItemRow, driverItem, controllerItem, folderItem;
    private TouchControllerOverlay controllerOverlay;
    private PadBridge pad;
    private AudioBridge audio;
    private volatile Process xvfb, game;
    private volatile int shmid=-1;
    private volatile boolean destroyed;
    private DriverManager drivers;
    private volatile boolean customDriver;
    private DriverDownloadDialog driverDownload;
    private GameInstaller installer;
    private AppUpdater updater;
    private LinearLayout updateItem;
    private volatile long gameStartedAt;
    private boolean drawerOpen, displayOnly, startingX, hadError, swipeConsumed;
    /** The game's own menu (cheats, multipliers, overlay) is open: touches and the gamepad drive it. */
    private boolean gameMenu;
    private float touchX, touchY;
    private boolean edgeGesture;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        FILES="/data/data/"+getPackageName()+"/files";
        ROOT=FILES+"/rootfs"; BASE=FILES+"/gow3"; DATA=FILES+"/gow3-data";
        new File(DATA).mkdirs();
        drivers=new DriverManager(new File(FILES));
        installer=new GameInstaller(this,installerHost());
        updater=new AppUpdater(this,new AppUpdater.Host() {
            @Override public void stopGame() { MainActivity.this.stopGame(); }
            @Override public void restartGame() { if (!destroyed && shmid>=0) MainActivity.this.restartGame(); }
            @Override public void detail(String text) { setDetail(updateItem,text); }
            @Override public void closed() { if (!destroyed) immersive(); }
            @Override public boolean destroyed() { return destroyed; }
        });
        worker.execute(updater::cleanup); // downloads an earlier run left
        gameDir=getSharedPreferences(PREFS,MODE_PRIVATE).getString("game_dir",null);
        String output=setting("display_output");
        if ("1080p".equals(output) || (!"720p".equals(output) && screenFits1080p())) { X_WIDTH=1920; X_HEIGHT=1080; }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT>=28) {
            WindowManager.LayoutParams attrs=getWindow().getAttributes();
            attrs.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attrs);
        }
        displayOnly="x".equals(getIntent().getStringExtra("action"));
        pad=new PadBridge(new File(DATA,"android-pad.state"));
        audio=new AudioBridge(new File(DATA,"audio.fifo")); audio.start();
        buildScreen();
        immersive();
    }

    /** Auto display output: 1080p when the largest 16:9 area of the screen is at least 1080 pixels tall. */
    private boolean screenFits1080p() {
        android.util.DisplayMetrics m=new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(m);
        int w=Math.max(m.widthPixels,m.heightPixels), h=Math.min(m.widthPixels,m.heightPixels);
        return Math.min(h,w*9/16)>=1080;
    }
    private int dp(float n) { return Math.round(n*getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size, int color) {
        TextView t=new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); return t;
    }
    private FrameLayout.LayoutParams frame(int width,int height,int gravity) {
        return new FrameLayout.LayoutParams(width,height,gravity);
    }
    private void fitSurface(int width,int height) {
        if (width<=0 || height<=0) return;
        int w=width, h=height;
        if ((long)w*9>(long)h*16) w=h*16/9; else h=w*9/16;
        FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)surface.getLayoutParams();
        if (p.width==w && p.height==h) return;
        p.width=w; p.height=h;
        // Not during the layout pass that reported the size.
        surface.post(() -> surface.setLayoutParams(p));
    }

    private void buildScreen() {
        screen=new FrameLayout(this); screen.setBackgroundColor(Color.BLACK);
        surface=new SurfaceView(this);
        screen.addView(surface,frame(-1,-1,Gravity.CENTER));
        // The game is 16:9: the largest 16:9 area of the screen, black bars around it.
        screen.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> fitSurface(r-l,b-t));
        surface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) {
                if (shmid>=0) attach(h);
                else if (!hasGame()) { pendingHolder=h; if (!installer.active()) askGameFolder(false); }
                else if (!startingX) { startingX=true; worker.execute(() -> startX(h)); }
            }
            @Override public void surfaceChanged(SurfaceHolder h,int format,int w,int height) {}
            @Override public void surfaceDestroyed(SurfaceHolder h) { DisplayBridge.stop(); }
        });
        // The game has no mouse; the in-game menu does: touches become the X11 pointer while it is open.
        surface.setOnTouchListener((v,e) -> gameMenu && pointer(v,e));
        status=text("Starting God of War III…",18,0xffd6a64a); status.setGravity(Gravity.CENTER);
        screen.addView(status,frame(-1,-1,Gravity.CENTER));
        status.setClickable(false);

        controllerOverlay=new TouchControllerOverlay(this,pad);
        controllerOverlay.setVisibility(controllerEnabled() ? View.VISIBLE : View.GONE);
        screen.addView(controllerOverlay,frame(-1,-1,Gravity.CENTER));

        handle=new View(this);
        GradientDrawable grip=new GradientDrawable(); grip.setColor(0x80d6a64a); grip.setCornerRadius(dp(4));
        handle.setBackground(grip); handle.setContentDescription("Open the app menu");
        screen.addView(handle,frame(dp(4),dp(44),Gravity.LEFT|Gravity.CENTER_VERTICAL));
        handle.setOnClickListener(v -> openDrawer());

        scrim=new View(this); scrim.setBackgroundColor(0x99000000);
        scrim.setVisibility(View.GONE); scrim.setOnClickListener(v -> closeDrawer());
        screen.addView(scrim,frame(-1,-1,Gravity.FILL));

        drawer=new LinearLayout(this); drawer.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable panel=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0xff241614,0xff120c0b});
        drawer.setBackground(panel);
        drawer.setElevation(dp(16)); drawer.setVisibility(View.GONE);
        drawerItems=new LinearLayout(this); drawerItems.setOrientation(LinearLayout.VERTICAL);
        drawerItems.setPadding(dp(14),dp(20),dp(14),dp(16));
        android.widget.ScrollView drawerScroll=new android.widget.ScrollView(this);
        drawerScroll.setFillViewport(false);
        drawerScroll.addView(drawerItems);
        LinearLayout body=new LinearLayout(this);
        body.addView(drawerScroll,new LinearLayout.LayoutParams(0,-1,1));
        View edge=new View(this); edge.setBackgroundColor(0x55d6a64a);
        body.addView(edge,new LinearLayout.LayoutParams(dp(1),-1));
        drawer.addView(body,new LinearLayout.LayoutParams(-1,0,1));

        TextView title=text("GOD OF WAR III",24,0xffeee0c8); title.setTypeface(Typeface.SERIF,Typeface.BOLD);
        title.setLetterSpacing(0.12f); title.setPadding(dp(10),0,0,0);
        drawerItems.addView(title,new LinearLayout.LayoutParams(-1,-2));
        TextView hint=text("Swipe left or press B to return",12,0xff8e9097);
        hint.setPadding(dp(10),dp(2),0,dp(6));
        drawerItems.addView(hint,new LinearLayout.LayoutParams(-1,-2));

        section("Game");
        resume=menuItem("ic_play","Resume game",null,() -> closeDrawer());
        menuItemRow=menuItem("ic_cheats","In-game menu","Cheats, multipliers, overlay, image",() -> {
            closeDrawer(); toggleGameMenu();
        });
        menuItem("ic_refresh","Restart game","Unsaved progress is lost",() -> new AlertDialog.Builder(this)
            .setTitle("Restart God of War III?").setMessage("Any unsaved progress will be lost.")
            .setNegativeButton("Cancel",null).setPositiveButton("Restart",(dialog,which) -> restartGame()).show());

        section("Graphics");
        graphicsItem=menuItem("ic_tune","Graphics settings","",() -> {
            closeDrawer();
            new SettingsDialog(this,new File(DATA,"gow3.ini"),new File(DATA,"android.ini"),this::restartGame,this::restartApp).show();
        });
        driverItem=menuItem("ic_memory","GPU driver","",() -> { closeDrawer(); showDrivers(); });

        section("Controls");
        controllerItem=menuItem("ic_gamepad","On-screen controller","",() -> {
            boolean enabled=!controllerEnabled();
            getSharedPreferences(PREFS,MODE_PRIVATE).edit().putBoolean("controller_overlay",enabled).apply();
            controllerOverlay.release();
            closeDrawer();
        });
        menuItem("ic_edit","Edit controller layout","Move, resize or reset the buttons",() -> {
            getSharedPreferences(PREFS,MODE_PRIVATE).edit().putBoolean("controller_overlay",true).apply();
            closeDrawer();
            controllerOverlay.edit(null);
        });

        section("Storage");
        folderItem=menuItem("ic_folder","Game folder","",() -> { closeDrawer(); askGameFolder(true); });
        menuItem("ic_extension","Install from PKG","Base game, then its 01.02 update",() -> { closeDrawer(); installer.start(); });
        menuItem("ic_download","Back up saves","Copy them to the Download folder",() -> { closeDrawer(); backupSaves(); });
        menuItem("ic_restore","Restore saves","From a backup folder",() -> { closeDrawer(); askRestoreSaves(); });
        menuItem("ic_exit","Quit game",null,() -> new AlertDialog.Builder(this)
            .setTitle("Quit God of War III?").setMessage("Any unsaved progress will be lost.")
            .setNegativeButton("Cancel",null).setPositiveButton("Quit",(dialog,which) -> finish()).show());

        section("App");
        updateItem=menuItem("ic_refresh","Check for updates","Version "+updater.version(),() -> updater.check());

        String version="";
        try { version=" "+getPackageManager().getPackageInfo(getPackageName(),0).versionName; }
        catch (Exception ignored) {}
        TextView about=text("God of War III for Android"+version,12,0xff8e9097);
        about.setPadding(dp(24),dp(8),dp(14),dp(14));
        drawer.addView(about,new LinearLayout.LayoutParams(-1,-2));
        screen.addView(drawer,frame(dp(320),-1,Gravity.LEFT));
        setContentView(screen);
    }

    /** A new display size needs a new X server: the app starts again (its processes end with it). */
    private void restartApp() {
        destroyed=true; DisplayBridge.stop(); stopGame(); if (xvfb!=null) xvfb.destroyForcibly();
        Intent start=getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (start!=null) {
            start.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
            android.app.PendingIntent later=android.app.PendingIntent.getActivity(this,0,start,
                android.app.PendingIntent.FLAG_CANCEL_CURRENT|android.app.PendingIntent.FLAG_IMMUTABLE);
            ((android.app.AlarmManager)getSystemService(ALARM_SERVICE))
                .set(android.app.AlarmManager.RTC,System.currentTimeMillis()+500,later);
        }
        android.os.Process.killProcess(android.os.Process.myPid());
    }
    private void restartGame() {
        closeDrawer(); setGameMenu(false); status.setText("Restarting God of War III…"); status.setVisibility(View.VISIBLE);
        worker.execute(() -> { stopGame(); startGame(); });
    }

    private void section(String name) {
        TextView t=text(name.toUpperCase(java.util.Locale.ROOT),11,0xffd6a64a);
        t.setLetterSpacing(0.15f); t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(10),dp(16),0,dp(6));
        drawerItems.addView(t,new LinearLayout.LayoutParams(-1,-2));
    }

    /** A menu row: icon, title and an optional second line (null: none; "" filled by refreshDrawer). */
    private LinearLayout menuItem(String icon,String title,String detail,Runnable action) {
        LinearLayout row=new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(10),dp(8),dp(10),dp(8));
        row.setFocusable(true); row.setClickable(true); row.setOnClickListener(v -> action.run());
        android.graphics.drawable.StateListDrawable states=new android.graphics.drawable.StateListDrawable();
        GradientDrawable lit=new GradientDrawable(); lit.setColor(0x33d6a64a); lit.setCornerRadius(dp(10));
        lit.setStroke(dp(1),0x99d6a64a);
        GradientDrawable pressed=new GradientDrawable(); pressed.setColor(0x44d6a64a); pressed.setCornerRadius(dp(10));
        states.addState(new int[]{android.R.attr.state_pressed},pressed);
        states.addState(new int[]{android.R.attr.state_focused},lit);
        states.addState(new int[]{},new android.graphics.drawable.ColorDrawable(0));
        row.setBackground(states);

        android.widget.ImageView image=new android.widget.ImageView(this);
        int id=getResources().getIdentifier(icon,"drawable",getPackageName());
        if (id!=0) image.setImageResource(id);
        LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(dp(24),dp(24)); ip.rightMargin=dp(14);
        row.addView(image,ip);

        LinearLayout texts=new LinearLayout(this); texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(title,15,0xffeee0c8));
        if (detail!=null) {
            TextView second=text(detail,12,0xff8e9097); second.setTag("detail");
            texts.addView(second);
        }
        row.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.bottomMargin=dp(2);
        row.setMinimumHeight(dp(48));
        drawerItems.addView(row,lp); return row;
    }
    private static void setDetail(LinearLayout row,String value) {
        TextView detail=row==null ? null : (TextView)row.findViewWithTag("detail");
        if (detail!=null) detail.setText(value);
    }
    /** The rows' second lines: the current settings, read when the drawer opens. */
    private void refreshDrawer() {
        setDetail(graphicsItem,SettingsDialog.summary(new File(DATA,"gow3.ini"),new File(DATA,"android.ini")));
        DriverManager.Driver driver=drivers.find(setting("gpu_driver"));
        setDetail(driverItem,driver==null ? "Built-in Turnip" : driver.title()+" · automatic choice off");
        setDetail(controllerItem,controllerEnabled() ? "On" : "Off");
        setDetail(folderItem,gameDir==null ? "Not chosen" : gameDir);
    }

    private void openDrawer() {
        if (drawerOpen) return;
        drawerOpen=true; controllerOverlay.release(); pad.reset(); XInput.button(1,false);
        refreshDrawer();
        controllerOverlay.setVisibility(View.GONE);
        handle.setVisibility(View.GONE);
        scrim.animate().cancel(); drawer.animate().cancel();
        scrim.setAlpha(0); scrim.setVisibility(View.VISIBLE); scrim.animate().alpha(1).setDuration(200).start();
        drawer.setTranslationX(-dp(320)); drawer.setVisibility(View.VISIBLE);
        drawer.animate().translationX(0).setDuration(220).start(); resume.requestFocus();
    }
    private void closeDrawer() {
        if (!drawerOpen) return;
        drawerOpen=false; pad.reset();
        controllerOverlay.setVisibility(controllerEnabled() && !gameMenu ? View.VISIBLE : View.GONE);
        scrim.animate().cancel(); drawer.animate().cancel();
        scrim.animate().alpha(0).setDuration(180).withEndAction(() -> scrim.setVisibility(View.GONE)).start();
        drawer.animate().translationX(-dp(320)).setDuration(200)
            .withEndAction(() -> { drawer.setVisibility(View.GONE); handle.setVisibility(View.VISIBLE); surface.requestFocus(); }).start();
        immersive();
    }

    // ---- The game's own menu (Insert on a keyboard): cheats, multipliers, overlay ----
    private void tap(int keycode) { XInput.key(keycode,true); XInput.key(keycode,false); }
    private void toggleGameMenu() {
        if (shmid<0) { toast("The game has not started yet."); return; }
        tap(X_INSERT);
        setGameMenu(!gameMenu);
    }
    private void setGameMenu(boolean open) {
        gameMenu=open; pad.reset(); controllerOverlay.release();
        controllerOverlay.setVisibility(controllerEnabled() && !open ? View.VISIBLE : View.GONE);
        if (open) toast("Tap to use the menu. Back closes it.");
    }
    /** A touch on the game picture as the X11 pointer (the in-game menu's mouse). */
    private boolean pointer(View v,MotionEvent e) {
        if (v.getWidth()<=0 || v.getHeight()<=0) return true;
        int x=Math.round(Math.max(0,Math.min(1,e.getX()/v.getWidth()))*(X_WIDTH-1));
        int y=Math.round(Math.max(0,Math.min(1,e.getY()/v.getHeight()))*(X_HEIGHT-1));
        XInput.moveTo(x,y);
        int action=e.getActionMasked();
        if (action==MotionEvent.ACTION_DOWN) XInput.button(1,true);
        else if (action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL) XInput.button(1,false);
        return true;
    }
    /** The gamepad in the in-game menu: the keys it navigates with (ImGui keyboard navigation). */
    private boolean menuKey(KeyEvent e) {
        int x;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP: x=X_UP; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: x=X_DOWN; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: x=X_LEFT; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: x=X_RIGHT; break;
            case KeyEvent.KEYCODE_BUTTON_A: case KeyEvent.KEYCODE_DPAD_CENTER: x=X_RETURN; break;
            case KeyEvent.KEYCODE_BUTTON_B: x=X_ESCAPE; break;
            case KeyEvent.KEYCODE_BUTTON_L1: case KeyEvent.KEYCODE_BUTTON_R1:
                // The next or previous tab: Ctrl+Tab (Shift is not sent; R1 and L1 both go forward).
                if (e.getAction()==KeyEvent.ACTION_DOWN) { XInput.key(X_CONTROL,true); tap(X_TAB); XInput.key(X_CONTROL,false); }
                return true;
            default: return true; // nothing reaches the game while its menu is open
        }
        XInput.key(x,e.getAction()==KeyEvent.ACTION_DOWN);
        if (x==X_ESCAPE && e.getAction()==KeyEvent.ACTION_UP) setGameMenu(false); // Escape closes the menu
        return true;
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        if (Build.VERSION.SDK_INT>=30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c=getWindow().getInsetsController();
            if (c!=null) {
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(WindowInsets.Type.systemBars());
            }
        }
    }
    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) immersive(); else if (pad!=null) pad.reset();
    }
    @Override public void onBackPressed() {
        if (drawerOpen) closeDrawer();
        else if (gameMenu) toggleGameMenu();
        else openDrawer();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getActionMasked()==MotionEvent.ACTION_DOWN) {
            swipeConsumed=false;
            touchX=e.getX(); touchY=e.getY();
            edgeGesture=!drawerOpen && touchX<dp(28);
        }
        if (swipeConsumed) return true;
        if (e.getActionMasked()==MotionEvent.ACTION_MOVE) {
            float dx=e.getX()-touchX, dy=e.getY()-touchY;
            if (edgeGesture && dx>dp(40) && Math.abs(dy)<dp(90)) {
                openDrawer(); edgeGesture=false; swipeConsumed=true; return true;
            }
            if (drawerOpen && dx< -dp(70) && Math.abs(dy)<dp(90)) {
                MotionEvent cancel=MotionEvent.obtain(e); cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(cancel); cancel.recycle();
                closeDrawer(); swipeConsumed=true; return true;
            }
        }
        if (edgeGesture) {
            if (e.getActionMasked()==MotionEvent.ACTION_UP) { edgeGesture=false; openDrawer(); }
            return true;
        }
        return super.dispatchTouchEvent(e);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code=e.getKeyCode();
        if (code==KeyEvent.KEYCODE_BACK || code==KeyEvent.KEYCODE_BUTTON_MODE) {
            if (e.getAction()==KeyEvent.ACTION_UP) onBackPressed(); return true;
        }
        if (drawerOpen) {
            if (code==KeyEvent.KEYCODE_BUTTON_B) {
                if (e.getAction()==KeyEvent.ACTION_UP) closeDrawer(); return true;
            }
            if (code==KeyEvent.KEYCODE_BUTTON_A) {
                if (e.getAction()==KeyEvent.ACTION_UP && getCurrentFocus()!=null) getCurrentFocus().performClick();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        if (gameMenu && e.getSource()!=0 && (e.isFromSource(android.view.InputDevice.SOURCE_GAMEPAD)
                || e.isFromSource(android.view.InputDevice.SOURCE_DPAD))) return menuKey(e);
        // R3 with L2 held opens the game's menu, as on the PC (L3+R3 is a game action).
        if (code==KeyEvent.KEYCODE_BUTTON_THUMBR && e.getAction()==KeyEvent.ACTION_DOWN && pad.held("l2")) {
            pad.reset(); toggleGameMenu(); return true;
        }
        if (pad.key(e)) return true;
        // A keyboard: its keys go to the game's X11 window (the PC's keyboard layout, Insert: the menu).
        if (shmid>=0 && e.getScanCode()>0 && e.getScanCode()+8<=255) {
            XInput.key(e.getScanCode()+8,e.getAction()==KeyEvent.ACTION_DOWN); return true;
        }
        return super.dispatchKeyEvent(e);
    }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if ((drawerOpen || gameMenu) && e.isFromSource(android.view.InputDevice.SOURCE_JOYSTICK)) return true;
        return pad.motion(e) || super.dispatchGenericMotionEvent(e);
    }
    private boolean controllerEnabled() {
        return getSharedPreferences(PREFS,MODE_PRIVATE).getBoolean("controller_overlay",true);
    }
    @Override protected void onPause() {
        if (controllerOverlay!=null) controllerOverlay.release();
        if (pad!=null) pad.reset();
        if (audio!=null) audio.pause();
        super.onPause();
    }
    @Override protected void onResume() {
        super.onResume(); if (audio!=null) audio.resume();
        if (updater!=null) updater.resumed(); // back from the "install unknown apps" setting
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // UI checks over ADB, using the same handlers as the actual controls.
        String action=intent.getStringExtra("ui_action");
        if ("open-menu".equals(action)) openDrawer();
        if ("close-menu".equals(action)) closeDrawer();
        if ("game-menu".equals(action)) toggleGameMenu();
    }

    private static void stopScript(Process p) {
        if (!p.isAlive()) return;
        p.destroy();
        try { if (!p.waitFor(5,java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly(); }
        catch (InterruptedException e) { p.destroyForcibly(); Thread.currentThread().interrupt(); }
    }
    private Process process(String... args) throws java.io.IOException {
        ProcessBuilder b=new ProcessBuilder(args).redirectErrorStream(true);
        Map<String,String> env=b.environment(); env.clear();
        env.put("PATH",ROOT+"/usr/bin:"+ROOT+"/bin:/system/bin");
        env.put("HOME",FILES+"/home/gow3"); env.put("LANG","C.UTF-8");
        env.put("TMPDIR",ROOT+"/tmp"); env.put("XDG_RUNTIME_DIR",ROOT+"/tmp");
        env.put("FXD_FILES",FILES); env.put("FXD_ROOT",ROOT); env.put("GOW3_ANDROID_INPUT","1");
        // The rootfs's libraries by name (its loader's built-in paths are those of its original package).
        env.put("LD_LIBRARY_PATH",ROOT+"/usr/lib/aarch64-linux-gnu:"+ROOT+"/usr/lib");
        // Its libc emulates SysV shared memory through fxshmd (started below): the socket by name.
        env.put("FXD_SHM_SOCKET",ROOT+"/tmp/.fxshm/sock");
        if (gameDir!=null) env.put("GOW3_GAME_DIR",gameDir);
        if (args[0].endsWith("/Xvfb")) env.put("LD_PRELOAD",ROOT+"/usr/lib/fexdroid/libfxpath.so");
        env.put("LIBGL_DRIVERS_PATH",ROOT+"/usr/lib/aarch64-linux-gnu/dri");
        return b.start();
    }
    private void startX(SurfaceHolder holder) {
        try {
            RuntimeInstaller.ensure(this, new File(FILES), message -> main.post(() -> status.setText(message)));
            if (!new File(ROOT,"usr/bin/Xvfb").canExecute() || !new File(BASE,"run-game.sh").isFile())
                throw new java.io.IOException("The God of War III runtime is missing. Reinstall the app.");
            new File(FILES,"home/gow3").mkdirs();
            try (PrintWriter passwd=new PrintWriter(new File(ROOT,"etc/passwd"));
                 PrintWriter group=new PrintWriter(new File(ROOT,"etc/group"))) {
                int uid=android.os.Process.myUid();
                passwd.println("root:x:0:0:root:/root:/bin/sh");
                passwd.println("user:x:"+uid+":"+uid+":GoW3:"+FILES+"/home/gow3:"+ROOT+"/bin/sh");
                group.println("root:x:0:"); group.println("user:x:"+uid+":");
            }
            new File(ROOT,"tmp/.X0-lock").delete(); new File(ROOT,"tmp/.X11-unix/X0").delete();
            // The shared memory daemon (Xvfb's frame, the display bridge): forks itself and stays.
            Process shmd=process(ROOT+"/usr/libexec/fxshmd","--dir",ROOT+"/tmp/.fxshm");
            shmd.waitFor();
            xvfb=process(ROOT+"/usr/bin/Xvfb",":0","-screen","0",X_WIDTH+"x"+X_HEIGHT+"x24",
                "-shmem","-ac","-nolisten","tcp","-pn");
            Pattern ready=Pattern.compile("screen 0 shmid (\\d+)");
            BufferedReader r=new BufferedReader(new InputStreamReader(xvfb.getInputStream()));
            String line;
            while ((line=r.readLine())!=null && !destroyed) {
                android.util.Log.i("GoW3-X11",line);
                Matcher m=ready.matcher(line);
                if (m.find()) {
                    shmid=Integer.parseInt(m.group(1));
                    main.post(() -> attach(holder));
                    break;
                }
            }
            if (shmid<0) throw new java.io.IOException("The game display could not start.");
            Thread drain=new Thread(() -> { try (BufferedReader reader=r) { while (reader.readLine()!=null) {} }
                catch (Exception ignored) {} },"X11-log");
            drain.setDaemon(true); drain.start();
        } catch (Exception ex) { error(ex.toString()); }
    }
    private void attach(SurfaceHolder holder) {
        if (destroyed || !holder.getSurface().isValid()) return;
        String result=DisplayBridge.start(holder.getSurface(),ROOT+"/tmp/.fxshm/sock",shmid,60,ROOT+"/tmp/fxpresent.sock");
        android.util.Log.i("GoW3",result);
        if (!result.startsWith("bridge running:")) { error(result); return; }
        XInput.connect(0);
        if (!displayOnly && game==null) worker.execute(() -> startGame());
        pollFrames();
    }

    // ---- Game folder: the extracted game (CUSA01623 and others, with eboot.bin) in shared storage ----
    /** God of War III Remastered's releases (scripts/patches.py GAMES). */
    private static final String[] SERIALS={"CUSA01623","CUSA01715","CUSA01720"};
    private static boolean isGame(File dir) { return dir!=null && new File(dir,"eboot.bin").isFile(); }
    private boolean hasGame() {
        if (Build.VERSION.SDK_INT>=23 && checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                !=android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
        return gameDir!=null && isGame(new File(gameDir));
    }
    /** Explains, asks for storage access when needed, then opens the folder picker (or the PKG installer). */
    private void askGameFolder(boolean change) {
        if (Build.VERSION.SDK_INT>=23 && checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                !=android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE},REQUEST_STORAGE);
            return;
        }
        status.setText(change ? "Choose the God of War III game folder." :
            "Choose your God of War III game folder (e.g. CUSA01623, the folder with eboot.bin), or install from PKG.");
        status.setVisibility(View.VISIBLE);
        new AlertDialog.Builder(this).setTitle("God of War III game folder")
            .setMessage("Select your own extracted dump of God of War III Remastered (e.g. CUSA01623, which contains "
                +"eboot.bin, sce_module and sce_sys) or the folder that holds it.\n\nOr install it from your PKG files: "
                +"the base game first, then the 01.02 update."+(gameDir!=null ? "\n\nCurrent: "+gameDir : ""))
            .setPositiveButton("Choose folder",(d,w) -> {
                Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                try { startActivityForResult(pick,REQUEST_FOLDER); }
                catch (Exception ex) { error("No folder picker: "+ex); }
            })
            .setNeutralButton("Install from PKG…",(d,w) -> installer.start())
            .setNegativeButton(change ? "Cancel" : "Quit",(d,w) -> { if (!change) finish(); })
            .setCancelable(change).show();
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(code,permissions,results);
        if (code!=REQUEST_STORAGE) return;
        if (results.length>0 && results[0]==android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (hasGame()) startPending(); else askGameFolder(false);
        } else error("God of War III needs storage access to read the game folder.");
    }
    @Override protected void onActivityResult(int code,int result,Intent data) {
        super.onActivityResult(code,result,data);
        if (code==REQUEST_DRIVER) {
            if (result==RESULT_OK && data!=null && data.getData()!=null) installDriver(data.getData());
            return;
        }
        if (code==REQUEST_SAVES) {
            if (result==RESULT_OK && data!=null && data.getData()!=null) restoreSaves(folderOf(data.getData()));
            else immersive();
            return;
        }
        if (code==GameInstaller.REQUEST_PKG || code==GameInstaller.REQUEST_DEST) { installer.onResult(code,result,data); return; }
        if (code!=REQUEST_FOLDER) return;
        if (result!=RESULT_OK || data==null || data.getData()==null) { if (!hasGame()) askGameFolder(false); return; }
        File dir=folderOf(data.getData());
        if (dir!=null && !isGame(dir))
            for (String serial : SERIALS) if (isGame(new File(dir,serial))) { dir=new File(dir,serial); break; }
        if (!isGame(dir)) {
            new AlertDialog.Builder(this).setTitle("Not a God of War III folder")
                .setMessage((dir==null ? "That location" : dir.getPath())+" has no eboot.bin.")
                .setPositiveButton("Choose again",(d,w) -> askGameFolder(gameDir!=null)).show();
            return;
        }
        useGame(dir);
    }
    /** A game folder chosen or installed from PKGs: the game starts (again) from it. */
    private void useGame(File dir) {
        boolean changed=gameDir!=null && !gameDir.equals(dir.getPath());
        gameDir=dir.getPath();
        getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString("game_dir",gameDir).apply();
        status.setText("Starting God of War III…"); status.setVisibility(View.VISIBLE);
        if (shmid>=0 && (changed || game==null)) restartGame(); else startPending();
    }

    // ---- Save data backup and restore (SaveBackup): gow3-data/user/savedata ----
    private File savesDir() { return SaveBackup.saves(new File(DATA)); }
    private boolean gameRunning() { Process p=game; return p!=null && p.isAlive(); }
    private boolean saveStorage() {
        if (Build.VERSION.SDK_INT<23 || checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                ==android.content.pm.PackageManager.PERMISSION_GRANTED) return true;
        toast("Allow storage access first (it is asked when the game starts)."); return false;
    }
    private static final String STOP_NOTE="\n\nThe game is stopped for this and starts again afterwards; unsaved progress is lost.";
    private void backupSaves() {
        if (!saveStorage()) return;
        if (!SaveBackup.hasSaves(savesDir())) {
            new AlertDialog.Builder(this).setTitle("No saves yet").setMessage("There is no save data to back up. Play and save in the game first.")
                .setPositiveButton("OK",null).setOnDismissListener(d -> immersive()).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Back up saves?")
            .setMessage("Copies your saves to a new GoW3-saves-… folder in your Download folder.")
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Back up",(d,w) -> saveJob("Backing up saves…","Backup failed",false,() -> {
                File dest=SaveBackup.backup(savesDir(),downloads(),new java.util.Date());
                return "Saves copied to\n"+dest.getPath();
            })).setOnDismissListener(d -> immersive()).show();
    }
    private File downloads() { return android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS); }
    private void askRestoreSaves() {
        if (!saveStorage()) return;
        new AlertDialog.Builder(this).setTitle("Restore saves")
            .setMessage("Choose a backup folder (GoW3-saves-… in Download), a savedata folder, or the PC port's user/savedata "
                +"folder. It replaces your current saves; those are backed up to Download first.")
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Choose folder",(d,w) -> {
                try { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),REQUEST_SAVES); }
                catch (Exception ex) { toast("No folder picker: "+ex); }
            }).setOnDismissListener(d -> immersive()).show();
    }
    private void restoreSaves(File picked) {
        File source=SaveBackup.find(picked);
        if (source==null) {
            new AlertDialog.Builder(this).setTitle("Not a saves folder")
                .setMessage((picked==null ? "That location" : picked.getPath())+" has no save data (expected a savedata folder, "
                    +"or a folder with one, holding user id / title id folders such as 1000/CUSA01623).")
                .setPositiveButton("OK",null).setOnDismissListener(d -> immersive()).show();
            return;
        }
        try { if (source.getCanonicalFile().equals(savesDir().getCanonicalFile())) { toast("That is the current save data."); immersive(); return; } }
        catch (java.io.IOException ignored) {}
        new AlertDialog.Builder(this).setTitle("Replace your saves?")
            .setMessage("This replaces your current saves with those in\n"+source.getPath()+"\n\nYour current saves are backed up to the "
                +"Download folder first."+(gameRunning() ? STOP_NOTE : ""))
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Restore",(d,w) -> saveJob("Restoring saves…","Restore failed",true,() -> {
                String note="";
                if (SaveBackup.hasSaves(savesDir()))
                    note="\n\nYour previous saves are in\n"+SaveBackup.backup(savesDir(),downloads(),new java.util.Date()).getPath();
                SaveBackup.restore(savesDir(),source);
                return "Saves restored."+note;
            })).setOnDismissListener(d -> immersive()).show();
    }
    private void saveJob(String progress,String failure,boolean stop,java.util.concurrent.Callable<String> job) {
        boolean wasRunning=stop && gameRunning();
        AlertDialog busy=new AlertDialog.Builder(this).setTitle(progress).setMessage("Please wait.").setCancelable(false).show();
        worker.execute(() -> {
            if (wasRunning) stopGame();
            String message; boolean ok=true;
            try { message=job.call(); }
            catch (Exception ex) { ok=false; message=ex.getMessage()==null ? ex.toString() : ex.getMessage(); android.util.Log.e("GoW3","saves: "+ex); }
            String text=message; boolean success=ok;
            main.post(() -> {
                busy.dismiss();
                if (destroyed) return;
                new AlertDialog.Builder(this).setTitle(success ? "Done" : failure).setMessage(text)
                    .setPositiveButton("OK",null).setOnDismissListener(d -> immersive()).show();
                if (wasRunning && shmid>=0) restartGame();
            });
        });
    }
    /** A storage folder of the picker (tree document "primary:Path" or "XXXX-XXXX:Path") as a path. */
    static File folderOf(android.net.Uri tree) {
        try {
            String id=android.provider.DocumentsContract.getTreeDocumentId(tree);
            int colon=id.indexOf(':');
            String volume=colon<0 ? id : id.substring(0,colon), path=colon<0 ? "" : id.substring(colon+1);
            return DocPath.volume(android.os.Environment.getExternalStorageDirectory(),volume,path);
        } catch (Exception ex) { return null; }
    }
    /** The file path of a picked document, or null for other providers (the caller copies the content). */
    private File pathOf(android.net.Uri document) {
        try {
            if ("file".equals(document.getScheme())) return new File(document.getPath());
            if (!android.provider.DocumentsContract.isDocumentUri(this,document)) return null;
            return DocPath.document(document.getAuthority(),android.provider.DocumentsContract.getDocumentId(document),
                android.os.Environment.getExternalStorageDirectory());
        } catch (Exception ex) { return null; }
    }
    private GameInstaller.Host installerHost() {
        return new GameInstaller.Host() {
            @Override public boolean scriptStream(String script,java.util.function.Consumer<String> lines,AtomicBoolean cancel,String... args) throws java.io.IOException {
                if (!new File(BASE,script).isFile()) return false;
                String[] command=new String[args.length+2];
                command[0]=ROOT+"/usr/bin/python3.13"; command[1]=BASE+"/"+script;
                System.arraycopy(args,0,command,2,args.length);
                Process p=pythonProcess(command);
                // Cancel (or the app closing) stops the process, which ends the read below.
                Thread watch=new Thread(() -> {
                    while (p.isAlive()) {
                        if (cancel.get() || destroyed) { stopScript(p); return; }
                        try { Thread.sleep(100); } catch (InterruptedException e) { return; }
                    }
                },"Install-cancel");
                watch.setDaemon(true); watch.start();
                try (BufferedReader r=new BufferedReader(new InputStreamReader(p.getInputStream(),"UTF-8"))) {
                    for (String line; (line=r.readLine())!=null;) lines.accept(line);
                    p.waitFor();
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { stopScript(p); watch.interrupt(); }
                return true;
            }
            @Override public File pathOf(android.net.Uri document) { return MainActivity.this.pathOf(document); }
            @Override public File folderOf(android.net.Uri tree) { return MainActivity.folderOf(tree); }
            @Override public boolean hasStorage() {
                return Build.VERSION.SDK_INT<23 || (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    ==android.content.pm.PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    ==android.content.pm.PackageManager.PERMISSION_GRANTED);
            }
            @Override public void pick(Intent intent,int code) { startActivityForResult(intent,code); }
            @Override public void toast(String message) { MainActivity.this.toast(message); }
            @Override public boolean destroyed() { return destroyed; }
            @Override public String gameDir() { return gameDir; }
            @Override public boolean gameRunning() { return MainActivity.this.gameRunning(); }
            @Override public void stopGame() { MainActivity.this.stopGame(); }
            @Override public void useGame(File dir) { MainActivity.this.useGame(dir); }
            @Override public void installEnded(boolean stopped) {
                if (destroyed) return;
                immersive();
                if (!hasGame()) askGameFolder(false);
                else if (stopped && shmid>=0) restartGame(); // the install stopped the game and did not finish
            }
        };
    }
    /** The rootfs's Python, as run-game.sh starts it. */
    private Process pythonProcess(String... command) throws java.io.IOException {
        ProcessBuilder b=new ProcessBuilder(command).redirectErrorStream(false);
        b.redirectError(ProcessBuilder.Redirect.appendTo(new File(DATA,"install.log")));
        Map<String,String> env=b.environment(); env.clear();
        env.put("PATH",ROOT+"/usr/bin:"+ROOT+"/bin:/system/bin");
        env.put("HOME",FILES+"/home/gow3"); env.put("LANG","C.UTF-8"); env.put("PYTHONHOME",ROOT+"/usr");
        env.put("PYTHONUNBUFFERED","1"); env.put("TMPDIR",ROOT+"/tmp");
        env.put("LD_LIBRARY_PATH",ROOT+"/usr/lib/aarch64-linux-gnu:"+ROOT+"/usr/lib:"+BASE+"/lib");
        return b.start();
    }
    private void startPending() {
        SurfaceHolder h=pendingHolder!=null ? pendingHolder : surface.getHolder();
        pendingHolder=null;
        if (h.getSurface()!=null && h.getSurface().isValid() && !startingX && shmid<0) {
            startingX=true; worker.execute(() -> startX(h));
        }
    }

    private void pollFrames() {
        if (destroyed) return;
        Process running=game;
        // A driver from a zip without this app's Mesa patches presents through X11, which the bridge
        // shows without counting it: the start text goes once that game has run for a while.
        if (!hadError && (DisplayBridge.directFrames()>0 || customDriver && running!=null && running.isAlive()
                && android.os.SystemClock.uptimeMillis()-gameStartedAt>20000)) status.setVisibility(View.GONE);
        main.postDelayed(this::pollFrames,1000);
    }

    // ---- GPU driver: the bundled Turnip or one installed from a zip (DriverManager, run-game.sh) ----
    private void showDrivers() {
        java.util.List<DriverManager.Driver> list=drivers.list();
        DriverManager.Driver current=drivers.find(setting("gpu_driver"));
        String[] names=new String[list.size()+1];
        names[0]="Built-in Turnip (automatic by GPU)";
        int checked=0;
        for (int i=0;i<list.size();++i) {
            names[i+1]=list.get(i).title();
            if (current!=null && current.id.equals(list.get(i).id)) checked=i+1;
        }
        int[] choice={checked};
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("GPU driver")
            .setSingleChoiceItems(names,checked,(d,which) -> {
                choice[0]=which;
                ((AlertDialog)d).getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(which>0);
            })
            .setPositiveButton("Use",(d,w) -> useDriver(choice[0]==0 ? null : list.get(choice[0]-1)))
            .setNeutralButton("Add driver…",(d,w) -> askAddDriver())
            .setNegativeButton("Delete",(d,w) -> { if (choice[0]>0) deleteDriver(list.get(choice[0]-1)); })
            .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(choice[0]>0));
        dialog.setOnDismissListener(d -> immersive());
        dialog.show();
    }
    private void askAddDriver() {
        new AlertDialog.Builder(this).setTitle("Add a GPU driver")
            .setItems(new String[]{"Download a Turnip build","Install a zip from this device"},(d,which) -> {
                if (which==0) showDriverDownloads(); else askDriverZip();
            })
            .setNegativeButton("Cancel",null).setOnDismissListener(d -> immersive()).show();
    }
    private void showDriverDownloads() {
        if (driverDownload!=null) driverDownload.close();
        driverDownload=new DriverDownloadDialog(this,drivers,new File(FILES),new DriverDownloadDialog.Host() {
            @Override public String gpuVariant() { return MainActivity.this.gpuVariant(); }
            @Override public void use(DriverManager.Driver driver) { useDriver(driver); }
            @Override public void closed() { if (!destroyed) immersive(); }
            @Override public boolean destroyed() { return destroyed; }
        });
        driverDownload.show();
    }
    /** The bundled Turnip that suits this GPU, as run-game.sh chooses it from kgsl-chip-id's output
     *  (0x44... is Adreno 8xx): "gen8", "main", or null when the GPU cannot be read. Blocks. */
    private String gpuVariant() {
        try {
            Process p=process(BASE+"/bin/kgsl-chip-id");
            try {
                if (!p.waitFor(5,java.util.concurrent.TimeUnit.SECONDS)) return null;
                String line=new BufferedReader(new InputStreamReader(p.getInputStream())).readLine();
                if (line==null || !line.trim().startsWith("0x")) return null;
                return line.trim().startsWith("0x44") ? "gen8" : "main";
            } finally { p.destroyForcibly(); }
        } catch (Exception ex) {
            android.util.Log.w("GoW3","GPU detection: "+ex);
            return null;
        }
    }
    private void askDriverZip() {
        new AlertDialog.Builder(this).setTitle("Install a GPU driver")
            .setMessage("Choose a zip with a Linux (glibc) build of Turnip, such as the Turnip builds for "
                +"Winlator or Mobox. Turnip for Android (adrenotools zips) cannot load here.\n\n"
                +"Drivers without this app's Mesa patches show the picture through X11, which is slower. A driver you "
                +"install turns off the automatic choice of Turnip by GPU. If the game stops, choose Built-in Turnip again.")
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Choose zip",(d,w) -> {
                Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                    .putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/zip","application/x-zip-compressed",
                        "application/octet-stream"});
                try { startActivityForResult(pick,REQUEST_DRIVER); }
                catch (Exception ex) { toast("No file picker: "+ex); }
            }).show();
    }
    private void installDriver(android.net.Uri zip) {
        String zipName="driver.zip";
        try (android.database.Cursor c=getContentResolver().query(zip,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)) {
            if (c!=null && c.moveToFirst() && c.getString(0)!=null) zipName=c.getString(0);
        } catch (Exception ignored) {}
        final String name=zipName;
        AlertDialog progress=new AlertDialog.Builder(this).setTitle("GPU driver")
            .setMessage("Installing "+name+"…").setCancelable(false).show();
        Thread install=new Thread(() -> {
            DriverManager.Driver installed=null; String failure=null;
            try (java.io.InputStream in=getContentResolver().openInputStream(zip)) {
                if (in==null) throw new java.io.IOException("Cannot open "+name);
                installed=drivers.install(in,name);
            } catch (Exception ex) {
                failure=ex.getMessage()!=null ? ex.getMessage() : ex.toString();
                android.util.Log.e("GoW3","driver install: "+ex);
            }
            DriverManager.Driver result=installed; String problem=failure;
            main.post(() -> {
                progress.dismiss();
                if (destroyed) return;
                if (result==null) new AlertDialog.Builder(this).setTitle("Driver not installed").setMessage(problem)
                    .setPositiveButton("OK",null).setOnDismissListener(d -> immersive()).show();
                else new AlertDialog.Builder(this).setTitle("Driver installed")
                    .setMessage(result.title()+" is installed. Use it now?")
                    .setNegativeButton("Later",null).setPositiveButton("Use",(d,w) -> useDriver(result))
                    .setOnDismissListener(d -> immersive()).show();
            });
        },"Driver-install");
        install.setDaemon(true); install.start();
    }
    private void useDriver(DriverManager.Driver driver) {
        String id=driver==null ? "" : driver.id, before=setting("gpu_driver");
        if (id.equals(before==null ? "" : before)) return;
        if (!SettingsDialog.set(new File(DATA,"android.ini"),"gpu_driver",id)) {
            toast("Could not save the GPU driver setting."); return;
        }
        askRestart(driver==null ? "the built-in Turnip" : driver.title());
    }
    private void deleteDriver(DriverManager.Driver driver) {
        new AlertDialog.Builder(this).setTitle("Delete "+driver.title()+"?")
            .setNegativeButton("Cancel",(d,w) -> showDrivers())
            .setPositiveButton("Delete",(d,w) -> {
                boolean active=driver.id.equals(setting("gpu_driver"));
                if (active) SettingsDialog.set(new File(DATA,"android.ini"),"gpu_driver","");
                try { drivers.delete(driver.id); }
                catch (Exception ex) { toast("Could not delete the driver: "+ex.getMessage()); return; }
                if (active) askRestart("the built-in Turnip"); else showDrivers();
            }).show();
    }
    private void toast(String message) { android.widget.Toast.makeText(this,message,android.widget.Toast.LENGTH_LONG).show(); }
    private void askRestart(String driver) {
        if (shmid<0 || displayOnly) return; // the game has not started: it starts with this driver
        new AlertDialog.Builder(this).setTitle("Restart God of War III?")
            .setMessage("The game uses "+driver+" after a restart. Any unsaved progress will be lost.")
            .setNegativeButton("Later",null).setPositiveButton("Restart",(d,w) -> restartGame())
            .setOnDismissListener(d -> immersive()).show();
    }
    /** The last value of an app setting (android.ini, read by run-game.sh; gow3.ini is the game's own
     *  file, which it rewrites with its keys only), or null. */
    private String setting(String key) { return SettingsDialog.get(new File(DATA,"android.ini"),key); }

    private void startGame() {
        if (destroyed) return;
        hadError=false;
        customDriver=drivers.find(setting("gpu_driver"))!=null;
        gameStartedAt=android.os.SystemClock.uptimeMillis();
        try {
            new File(DATA,"logs").mkdirs();
            Process launched=process(ROOT+"/bin/sh",BASE+"/run-game.sh","game"); game=launched;
            Thread log=new Thread(() -> {
                try (BufferedReader r=new BufferedReader(new InputStreamReader(launched.getInputStream()));
                     PrintWriter out=new PrintWriter(new File(DATA,"logs/android-game.log"))) {
                    String line; while ((line=r.readLine())!=null) { out.println(line); out.flush(); }
                    int code=launched.waitFor();
                    if (game==launched) { game=null; error("Game stopped ("+code+"). Open the menu to restart.\n"
                        +"Log: "+DATA+"/logs/android-game.log"); }
                } catch (Exception ex) {
                    // A restart kills this process on purpose: only the current game's errors show.
                    if (!destroyed && game==launched) error(ex.toString());
                }
            },"GoW3-log"); log.setDaemon(true); log.start();
        } catch (Exception ex) { error(ex.toString()); }
    }
    private void error(String message) {
        hadError=true;
        android.util.Log.e("GoW3",message);
        main.post(() -> { if (!destroyed) { status.setText(message); status.setVisibility(View.VISIBLE); } });
    }
    private void stopGame() {
        Process p=game; game=null;
        if (p!=null) { p.destroyForcibly(); try { p.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
    }
    @Override protected void onDestroy() {
        destroyed=true; if (driverDownload!=null) driverDownload.close(); if (installer!=null) installer.close(); if (updater!=null) updater.close(); pad.reset(); audio.stop(); main.removeCallbacksAndMessages(null);
        DisplayBridge.stop(); stopGame(); if (xvfb!=null) xvfb.destroyForcibly();
        worker.shutdownNow(); super.onDestroy();
    }
}
