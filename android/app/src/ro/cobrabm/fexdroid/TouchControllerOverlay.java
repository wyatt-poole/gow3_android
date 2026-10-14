package ro.cobrabm.fexdroid;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Typeface;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Translucent PlayStation-style controls with a persistent, draggable layout. A touch outside every
 *  control turns the camera: a right stick anchored where the finger landed (no mouse input). */
final class TouchControllerOverlay extends View {
    private static final String[] NAMES={
        "l2","l1","touchpad","options","r1","r2",
        "up","left","right","down","triangle","circle","cross","square",
        "leftstick","l3","r3"
    };
    private static final float[][] DEFAULT={
        {87,105},{213,105},{640,105},{773,105},{1067,105},{1193,105},
        {160,395},{90,465},{230,465},{160,535},
        {1120,405},{1190,475},{1120,545},{1050,475},
        {320,612},{420,614},{860,614}
    };
    /** Camera touch: the drag (layout pixels) from where it landed that tilts the right stick fully. */
    private static final float CAMERA_RANGE=64;
    /** A control's size in the editor: a factor of its default size. */
    private static final float MIN_SIZE=0.6f, MAX_SIZE=2f, SIZE_STEP=1.1f;
    private final PadBridge pad;
    private final SharedPreferences preferences;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<String,PointF> centers=new LinkedHashMap<>();
    private final Map<String,Float> sizes=new HashMap<>();
    private final SparseArray<String> controls=new SparseArray<>();
    private final SparseArray<PointF> positions=new SparseArray<>();
    private int cameraPointer=-1;
    private final PointF cameraAnchor=new PointF();
    private final Set<String> pressed=new LinkedHashSet<>();
    private boolean editing;
    // Editor: the finger dragging `dragged`, a second finger pinching it, and the control −/+ resize.
    private int editPointer=-1, pinchPointer=-1;
    private float pinchDistance, pinchSize;
    private String dragged, selected;
    private Runnable onEditDone;

    TouchControllerOverlay(Context context,PadBridge pad) {
        super(context); this.pad=pad;
        preferences=context.getSharedPreferences("gow3",Context.MODE_PRIVATE);
        for (int i=0;i<NAMES.length;i++) {
            String name=NAMES[i];
            centers.put(name,new PointF(preferences.getFloat("overlay_x_"+name,DEFAULT[i][0]),
                                        preferences.getFloat("overlay_y_"+name,DEFAULT[i][1])));
            sizes.put(name,clampSize(preferences.getFloat("overlay_s_"+name,1f)));
        }
        setContentDescription("On-screen PlayStation controller");
    }
    private PointF center(String name) { return centers.get(name); }
    private float size(String name) { Float s=sizes.get(name); return s==null ? 1f : s; }
    private static float clampSize(float s) { return Math.max(MIN_SIZE,Math.min(MAX_SIZE,s)); }
    // The 1280x720 layout drawn at one scale in the centred 16:9 area, as the game (MainActivity):
    // round on any screen shape. Controls may be dragged into the bars around it.
    private float scale() { return Math.max(1e-3f,Math.min(getWidth()/1280f,getHeight()/720f)); }
    private float offsetX() { return (getWidth()-1280f*scale())/2f; }
    private float offsetY() { return (getHeight()-720f*scale())/2f; }
    private float layoutX(MotionEvent e,int index) { return (e.getX(index)-offsetX())/scale(); }
    private float layoutY(MotionEvent e,int index) { return (e.getY(index)-offsetY())/scale(); }
    private static float distance(float x,float y,PointF p) { return (float)Math.hypot(x-p.x,y-p.y); }
    private static boolean stick(String name) { return name.equals("leftstick"); }
    private static boolean rectangle(String name) {
        return name.equals("l1") || name.equals("l2") || name.equals("r1") ||
               name.equals("r2") || name.equals("touchpad") || name.equals("options");
    }
    private PointF dpadCenter() {
        PointF u=center("up"),l=center("left"),r=center("right"),d=center("down");
        return new PointF((u.x+l.x+r.x+d.x)/4f,(u.y+l.y+r.y+d.y)/4f);
    }
    /** The d-pad's size: the mean of its four buttons'. */
    private float dpadSize() { return (size("up")+size("left")+size("right")+size("down"))/4f; }
    private String hit(float x,float y) {
        PointF dpad=dpadCenter(); float k=dpadSize();
        if (!editing && distance(x,y,dpad)<112*k && distance(center("up").x,center("up").y,dpad)<115*k &&
            distance(center("left").x,center("left").y,dpad)<115*k &&
            distance(center("right").x,center("right").y,dpad)<115*k &&
            distance(center("down").x,center("down").y,dpad)<115*k) return "dpad";
        String winner=null; float best=Float.MAX_VALUE;
        for (String name:NAMES) {
            PointF p=center(name); float s=size(name);
            float dx=Math.abs(x-p.x),dy=Math.abs(y-p.y);
            boolean inside=rectangle(name) ? dx<(name.equals("touchpad") ? 72 : 62)*s && dy<38*s
                    : distance(x,y,p)<(stick(name) ? 70 : name.equals("l3") || name.equals("r3") ? 39 : 48)*s;
            if (inside && distance(x,y,p)<best) { best=distance(x,y,p); winner=name; }
        }
        return winner;
    }
    private static int axis(float v) { return Math.max(0,Math.min(255,Math.round((v+1f)*127.5f))); }

    private void publish() {
        pressed.clear();
        int lx=128,ly=128,rx=128,ry=128,l2=0,r2=0;
        boolean left=false,right=false;
        for (int i=0;i<controls.size();i++) {
            String control=controls.valueAt(i);
            PointF p=positions.get(controls.keyAt(i));
            if (p==null) continue;
            if (control.equals("dpad")) {
                PointF c=dpadCenter(); float t=22*dpadSize();
                if (p.x<c.x-t) pressed.add("left");
                if (p.x>c.x+t) pressed.add("right");
                if (p.y<c.y-t) pressed.add("up");
                if (p.y>c.y+t) pressed.add("down");
            } else if (control.equals("leftstick")) {
                left=true; PointF c=center(control); float range=56*size(control);
                lx=axis(Math.max(-1f,Math.min(1f,(p.x-c.x)/range)));
                ly=axis(Math.max(-1f,Math.min(1f,(p.y-c.y)/range)));
            } else if (control.equals("camera")) {
                right=true;
                rx=axis(Math.max(-1f,Math.min(1f,(p.x-cameraAnchor.x)/CAMERA_RANGE)));
                ry=axis(Math.max(-1f,Math.min(1f,(p.y-cameraAnchor.y)/CAMERA_RANGE)));
            } else if (control.equals("l2")) l2=255;
            else if (control.equals("r2")) r2=255;
            else if (!control.equals("none")) pressed.add(control);
        }
        pad.virtual(pressed,lx,ly,rx,ry,left,right,l2,r2);
        invalidate();
    }
    void release() { controls.clear(); positions.clear(); cameraPointer=-1; publish(); }
    void edit(Runnable done) { release(); editing=true; selected=null; onEditDone=done; invalidate(); }
    private void finishEdit() {
        editPointer=pinchPointer=-1; dragged=selected=null; editing=false; invalidate();
        if (onEditDone!=null) onEditDone.run();
        onEditDone=null;
    }
    void resetLayout() {
        SharedPreferences.Editor editor=preferences.edit();
        for (int i=0;i<NAMES.length;i++) {
            String name=NAMES[i]; centers.get(name).set(DEFAULT[i][0],DEFAULT[i][1]); sizes.put(name,1f);
            editor.remove("overlay_x_"+name).remove("overlay_y_"+name).remove("overlay_s_"+name);
        }
        editor.apply(); release(); invalidate();
    }
    private void confirmReset() {
        new android.app.AlertDialog.Builder(getContext()).setTitle("Reset controller layout?")
            .setMessage("All buttons go back to their default places and sizes.")
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Reset",(d,w) -> { resetLayout(); selected=null; invalidate(); }).show();
    }
    private void resize(String name,float s) {
        sizes.put(name,clampSize(s));
        preferences.edit().putFloat("overlay_s_"+name,size(name)).apply();
        invalidate();
    }

    // The editor's bar at the top: RESET (asks first), − (smaller), DONE, + (larger).
    private static final float BAR_TOP=9, BAR_BOTTOM=62;
    private static final float[] RESET={358,458}, MINUS={468,538}, DONE={548,732}, PLUS={742,812};
    private static boolean in(float[] box,float x,float y) { return x>box[0] && x<box[1] && y<BAR_BOTTOM+10; }

    private boolean editTouch(MotionEvent e,int action,int at,int id) {
        if (action==MotionEvent.ACTION_CANCEL) { editPointer=pinchPointer=-1; dragged=null; return true; }
        if (action==MotionEvent.ACTION_DOWN) {
            float x=layoutX(e,at),y=layoutY(e,at);
            if (in(DONE,x,y)) { finishEdit(); return true; }
            if (in(RESET,x,y)) { confirmReset(); return true; }
            if (in(MINUS,x,y) || in(PLUS,x,y)) {
                if (selected!=null) resize(selected,size(selected)*(in(PLUS,x,y) ? SIZE_STEP : 1f/SIZE_STEP));
                return true;
            }
            dragged=hit(x,y); editPointer=dragged==null ? -1 : id;
            if (dragged!=null) selected=dragged;
            invalidate();
        } else if (action==MotionEvent.ACTION_POINTER_DOWN && dragged!=null && pinchPointer<0) {
            // A second finger while one holds a control: pinch to resize it.
            int index=e.findPointerIndex(editPointer);
            if (index>=0) {
                pinchPointer=id;
                pinchDistance=Math.max(1f,(float)Math.hypot(e.getX(at)-e.getX(index),e.getY(at)-e.getY(index)));
                pinchSize=size(dragged);
            }
        } else if (action==MotionEvent.ACTION_MOVE && editPointer>=0 && dragged!=null) {
            int index=e.findPointerIndex(editPointer);
            int other=pinchPointer<0 ? -1 : e.findPointerIndex(pinchPointer);
            if (index>=0 && other>=0) {
                float now=(float)Math.hypot(e.getX(other)-e.getX(index),e.getY(other)-e.getY(index));
                sizes.put(dragged,clampSize(pinchSize*now/pinchDistance));
            } else if (index>=0) {
                float barX=offsetX()/scale(),barY=offsetY()/scale();
                center(dragged).set(
                    Math.max(36-barX,Math.min(1244+barX,layoutX(e,index))),
                    Math.max(36-barY,Math.min(684+barY,layoutY(e,index))));
            }
            invalidate();
        } else if (action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_POINTER_UP) {
            if (id==pinchPointer) {
                pinchPointer=-1;
                if (dragged!=null) resize(dragged,size(dragged));
            } else if (id==editPointer && dragged!=null) {
                PointF p=center(dragged);
                preferences.edit().putFloat("overlay_x_"+dragged,p.x).putFloat("overlay_y_"+dragged,p.y)
                    .putFloat("overlay_s_"+dragged,size(dragged)).apply();
                editPointer=pinchPointer=-1; dragged=null;
                invalidate();
            }
        }
        return true;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int action=e.getActionMasked(),at=e.getActionIndex(),id=e.getPointerId(at);
        if (editing) return editTouch(e,action,at,id);
        if (action==MotionEvent.ACTION_CANCEL) { release(); return true; }
        if (action==MotionEvent.ACTION_DOWN || action==MotionEvent.ACTION_POINTER_DOWN) {
            float x=layoutX(e,at),y=layoutY(e,at);
            String name=hit(x,y);
            // Outside every control: the camera, for one finger at a time (more fingers there do nothing).
            if (name==null) {
                if (cameraPointer<0) { name="camera"; cameraPointer=id; cameraAnchor.set(x,y); }
                else name="none";
            }
            controls.put(id,name);
            positions.put(id,new PointF(x,y));
        } else if (action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_POINTER_UP) {
            controls.remove(id); positions.remove(id);
            if (id==cameraPointer) cameraPointer=-1;
        }
        if (action==MotionEvent.ACTION_MOVE) {
            for (int i=0;i<e.getPointerCount();i++) {
                PointF p=positions.get(e.getPointerId(i));
                if (p!=null) p.set(layoutX(e,i),layoutY(e,i));
            }
        }
        publish(); return true;
    }

    private int fill(String name) {
        return name.equals(dragged) ? 0xffe8dfc9 : pressed.contains(name) ? 0xaae8dfc9 : 0x6630394c;
    }
    /** The control the editor resizes: an outline around it. */
    private void selection(Canvas c,String name,float halfWidth,float halfHeight) {
        if (!editing || !name.equals(selected)) return;
        PointF p=center(name);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(3); paint.setColor(0xffe8dfc9);
        c.drawRoundRect(p.x-halfWidth-8,p.y-halfHeight-8,p.x+halfWidth+8,p.y+halfHeight+8,16,16,paint);
    }
    private void circle(Canvas c,String name,float r,String label,int accent) {
        PointF p=center(name); float s=size(name); r*=s;
        paint.setStyle(Paint.Style.FILL); paint.setColor(fill(name));
        c.drawCircle(p.x,p.y,r,paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(3); paint.setColor(accent);
        c.drawCircle(p.x,p.y,r,paint);
        paint.setStyle(Paint.Style.FILL); paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize((label.length()>2 ? 19 : 33)*s); paint.setTextAlign(Paint.Align.CENTER);
        c.drawText(label,p.x,p.y-(paint.ascent()+paint.descent())/2,paint);
        selection(c,name,r,r);
    }
    private void rectangle(Canvas c,String name,float width,String label) {
        PointF p=center(name); float s=size(name), w=width*s/2, h=25*s;
        paint.setStyle(Paint.Style.FILL); paint.setColor(fill(name));
        c.drawRoundRect(p.x-w,p.y-h,p.x+w,p.y+h,14*s,14*s,paint);
        paint.setStyle(Paint.Style.STROKE); paint.setColor(0xbbc6ad76); paint.setStrokeWidth(2);
        c.drawRoundRect(p.x-w,p.y-h,p.x+w,p.y+h,14*s,14*s,paint);
        paint.setStyle(Paint.Style.FILL); paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(19*s); paint.setTextAlign(Paint.Align.CENTER);
        c.drawText(label,p.x,p.y-(paint.ascent()+paint.descent())/2,paint);
        selection(c,name,w,h);
    }
    private void drawStick(Canvas c,String name) {
        PointF center=center(name); float s=size(name), travel=27*s;
        paint.setStyle(Paint.Style.FILL); paint.setColor(name.equals(dragged) ? 0xffe8dfc9 : 0x6630394c);
        c.drawCircle(center.x,center.y,57*s,paint);
        paint.setStyle(Paint.Style.STROKE); paint.setColor(0xffa0aec0); paint.setStrokeWidth(3);
        c.drawCircle(center.x,center.y,57*s,paint);
        float knobX=center.x,knobY=center.y;
        for (int i=0;i<controls.size();i++) {
            if (name.equals(controls.valueAt(i))) {
                PointF p=positions.get(controls.keyAt(i));
                if (p!=null) {
                    knobX=center.x+Math.max(-travel,Math.min(travel,p.x-center.x));
                    knobY=center.y+Math.max(-travel,Math.min(travel,p.y-center.y));
                }
                break;
            }
        }
        paint.setStyle(Paint.Style.FILL); paint.setColor(0xbba0aec0);
        c.drawCircle(knobX,knobY,28*s,paint);
        paint.setColor(0xff15202c); paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(22*s);
        c.drawText("L",knobX,knobY-(paint.ascent()+paint.descent())/2,paint);
        selection(c,name,57*s,57*s);
    }
    /** The camera touch: a faint ring where it landed and the knob under the finger. */
    private void drawCamera(Canvas c) {
        PointF p=cameraPointer<0 ? null : positions.get(cameraPointer);
        if (p==null) return;
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(3); paint.setColor(0x66a0aec0);
        c.drawCircle(cameraAnchor.x,cameraAnchor.y,CAMERA_RANGE,paint);
        float dx=p.x-cameraAnchor.x,dy=p.y-cameraAnchor.y,length=(float)Math.hypot(dx,dy);
        float k=length>CAMERA_RANGE ? CAMERA_RANGE/length : 1f;
        paint.setStyle(Paint.Style.FILL); paint.setColor(0x55a0aec0);
        c.drawCircle(cameraAnchor.x+dx*k,cameraAnchor.y+dy*k,28,paint);
    }
    private void barButton(Canvas c,float[] box,String label,boolean enabled) {
        paint.setStyle(Paint.Style.FILL); paint.setColor(0xcc15171d);
        c.drawRoundRect(box[0],BAR_TOP,box[1],BAR_BOTTOM,13,13,paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2); paint.setColor(enabled ? 0xffc6ad76 : 0x66c6ad76);
        c.drawRoundRect(box[0],BAR_TOP,box[1],BAR_BOTTOM,13,13,paint);
        paint.setStyle(Paint.Style.FILL); paint.setTextSize(label.length()>1 ? 20 : 30);
        paint.setTextAlign(Paint.Align.CENTER);
        c.drawText(label,(box[0]+box[1])/2,(BAR_TOP+BAR_BOTTOM)/2-(paint.ascent()+paint.descent())/2,paint);
    }
    @Override protected void onDraw(Canvas c) {
        super.onDraw(c); c.save(); c.translate(offsetX(),offsetY()); c.scale(scale(),scale());
        rectangle(c,"l2",108,"L2"); rectangle(c,"l1",108,"L1");
        rectangle(c,"r1",108,"R1"); rectangle(c,"r2",108,"R2");
        rectangle(c,"touchpad",126,"TOUCH"); rectangle(c,"options",108,"OPTIONS");
        circle(c,"up",39,"▲",0xffc6ad76); circle(c,"left",39,"◀",0xffc6ad76);
        circle(c,"right",39,"▶",0xffc6ad76); circle(c,"down",39,"▼",0xffc6ad76);
        circle(c,"triangle",39,"△",0xff83cdaa); circle(c,"circle",39,"○",0xffe38288);
        circle(c,"cross",39,"×",0xff88aaff); circle(c,"square",39,"□",0xffd6a0ce);
        drawStick(c,"leftstick"); drawCamera(c);
        circle(c,"l3",29,"L3",0xffa0aec0); circle(c,"r3",29,"R3",0xffa0aec0);
        if (editing) {
            barButton(c,RESET,"RESET",true); barButton(c,MINUS,"−",selected!=null); barButton(c,DONE,"DONE",true); barButton(c,PLUS,"+",selected!=null);
            paint.setStyle(Paint.Style.FILL); paint.setTextSize(17); paint.setColor(0xffe8dfc9);
            paint.setTextAlign(Paint.Align.CENTER);
            c.drawText(selected==null ? "Drag a button to move it" : "Drag to move · pinch or −/+ to resize",640,BAR_BOTTOM+110,paint);
        }
        c.restore();
    }
}
