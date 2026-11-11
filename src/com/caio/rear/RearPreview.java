package com.caio.rear;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Presentation;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Display;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Espelha o visor da GCam na tela traseira do Mi 11 Ultra.
 * Toque no botão: liga/desliga. Toque longo: opções.
 */
public final class RearPreview {
    private static final String TAG = "RearPreview";
    private static final String PREFS = "rear_preview_mod";

    private static Activity act;
    private static TextView btn;
    private static SharedPreferences prefs;

    private static Presentation pres;
    private static WindowManager overlayWm;
    private static View overlayView;
    private static RearView rearView;

    private static boolean running;
    private static long lastFrameMs;
    private static long lastSearchMs;
    private static TextureView srcTv;
    private static SurfaceView srcSv;
    private static Bitmap capA, capB;
    private static boolean copyPending;
    private static int targetW = 300, targetH = 300;
    private static Handler main;
    private static int rearId = -1;
    private static boolean kicked;
    private static int retries;
    private static int wakeRestarts;
    private static android.os.Binder wakeToken;

    /** Mesma chamada da câmera da Xiaomi (Display.switchPresentationDisplay / IMiuiSubScreenManager). */
    private static void subScreen(Context c, boolean on) {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
            android.os.IBinder pw = (android.os.IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "power");
            if (pw == null) { log("power service null"); return; }
            data.writeInterfaceToken("android.os.IPowerManager");
            int code;
            if (on) {
                wakeToken = new android.os.Binder();
                data.writeStrongBinder(wakeToken);
                data.writeLong(android.os.SystemClock.uptimeMillis());
                data.writeInt(1);
                data.writeString("CAMERA_CALL");
                code = 0xfffffa;
            } else {
                // sempre apagar a telinha (em vez de devolver pro relógio)
                data.writeLong(android.os.SystemClock.uptimeMillis());
                data.writeString("CAMERA_CALL");
                code = 0xfffffb;
                wakeToken = null;
            }
            boolean r = pw.transact(code, data, reply, 1 /*FLAG_ONEWAY*/);
            log("subScreen " + (on ? "ON" : "OFF") + " code=0x" + Integer.toHexString(code) + " ok=" + r);
        } catch (Throwable e) {
            log("subScreen falhou: " + e);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }
    private static final java.util.ArrayList<String> LOG = new java.util.ArrayList<String>();
    private static long t0 = android.os.SystemClock.uptimeMillis();

    private static void log(String m) {
        String line = (android.os.SystemClock.uptimeMillis() - t0) + "ms " + m;
        Log.i(TAG, line);
        LOG.add(line);
        while (LOG.size() > 25) LOG.remove(0);
    }
    private static DisplayManager.DisplayListener dispListener;

    private static final Choreographer.FrameCallback FRAME = new Choreographer.FrameCallback() {
        public void doFrame(long t) {
            if (!running) return;
            try { tick(); } catch (Throwable e) { Log.w(TAG, "tick", e); }
            if (running) Choreographer.getInstance().postFrameCallback(this);
        }
    };

    // ---------- hooks chamados pela CameraActivity ----------

    public static void onResume(Activity a) {
        try {
            act = a;
            main = new Handler(Looper.getMainLooper());
            prefs = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            addButton(a);
            kicked = false;
            retries = 0;
            wakeRestarts = 0;
            registerListener(a);
            if (prefs.getBoolean("auto", false)) {
                // espera o visor ficar pronto
                main.postDelayed(new Runnable() { public void run() { if (act != null && !running) start(false); } }, 800);
            }
        } catch (Throwable e) { Log.w(TAG, "onResume", e); }
    }

    public static void onPause(Activity a) {
        try {
            if (main != null) main.removeCallbacksAndMessages(null);
            unregisterListener(a);
            stop();
            removeButton();
            act = null;
            srcTv = null; srcSv = null;
        } catch (Throwable e) { Log.w(TAG, "onPause", e); }
    }

    // ---------- botão ----------

    private static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    private static void addButton(final Activity a) {
        removeButton();
        ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
        TextView b = new TextView(a);
        b.setText("▣");
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(Color.WHITE);
        btn = b;
        refreshButton();
        int s = dp(a, 40);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(s, s, Gravity.TOP | Gravity.END);
        lp.topMargin = dp(a, prefs.getInt("btn_top_dp", 110));
        lp.rightMargin = dp(a, 12);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (running) { stop(); }
                else { kicked = false; retries = 0; wakeRestarts = 0; start(true); }
            }
        });
        b.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { showMenu(v); return true; }
        });
        decor.addView(b, lp);
        b.bringToFront();
    }

    private static void refreshButton() {
        if (btn == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(running ? 0xCCFF8C00 : 0x66000000);
        bg.setStroke(2, 0xAAFFFFFF);
        btn.setBackground(bg);
    }

    private static void removeButton() {
        if (btn != null) {
            ViewGroup p = (ViewGroup) btn.getParent();
            if (p != null) p.removeView(btn);
        }
        btn = null;
    }

    private static void showMenu(View anchor) {
        PopupMenu pm = new PopupMenu(anchor.getContext(), anchor);
        Menu m = pm.getMenu();
        m.add(0, 1, 0, (prefs.getBoolean("mirror", true) ? "✓ " : "") + "Espelhar");
        m.add(0, 2, 0, "Girar 90° (atual: " + prefs.getInt("rot", 0) + "°)");
        m.add(0, 3, 0, prefs.getBoolean("fill", false) ? "Modo: preencher" : "Modo: ajustar");
        m.add(0, 4, 0, "Mover botão");
        m.add(0, 5, 0, "Escolher display (atual: " + (prefs.getInt("disp", -1) < 0 ? "auto" : String.valueOf(prefs.getInt("disp", -1))) + ")");
        m.add(0, 6, 0, "Info dos displays");
        m.add(0, 7, 0, (prefs.getBoolean("auto", false) ? "\u2713 " : "") + "Ligar automaticamente");
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            public boolean onMenuItemClick(MenuItem it) {
                SharedPreferences.Editor e = prefs.edit();
                switch (it.getItemId()) {
                    case 1: e.putBoolean("mirror", !prefs.getBoolean("mirror", true)); break;
                    case 2: e.putInt("rot", (prefs.getInt("rot", 0) + 90) % 360); break;
                    case 3: e.putBoolean("fill", !prefs.getBoolean("fill", false)); break;
                    case 4: {
                        int top = prefs.getInt("btn_top_dp", 110) + 60;
                        if (top > 520) top = 50;
                        e.putInt("btn_top_dp", top).apply();
                        if (act != null) addButton(act);
                        return true;
                    }
                    case 5: e.apply(); pickDisplay(); return true;
                    case 6: e.apply(); showInfo(); return true;
                    case 7: e.putBoolean("auto", !prefs.getBoolean("auto", false)); break;
                }
                e.apply();
                if (rearView != null) rearView.postInvalidate();
                return true;
            }
        });
        pm.show();
    }

    private static String describe(Display d) {
        Point p = new Point();
        d.getRealSize(p);
        return "#" + d.getDisplayId() + " \"" + d.getName() + "\" " + p.x + "x" + p.y
                + " flags=0x" + Integer.toHexString(d.getFlags()) + " state=" + d.getState();
    }

    private static void showInfo() {
        if (act == null) return;
        DisplayManager dm = (DisplayManager) act.getSystemService(Context.DISPLAY_SERVICE);
        StringBuilder sb = new StringBuilder();
        for (Display d : dm.getDisplays()) sb.append(describe(d)).append("\n\n");
        sb.append("Log:\n");
        for (String l : LOG) sb.append(l).append("\n");
        sb.append("\nOverlay permitido: ").append(Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(act));
        new AlertDialog.Builder(act).setTitle("Displays visíveis").setMessage(sb.toString())
                .setPositiveButton("OK", null).show();
    }

    private static void pickDisplay() {
        if (act == null) return;
        DisplayManager dm = (DisplayManager) act.getSystemService(Context.DISPLAY_SERVICE);
        final Display[] ds = dm.getDisplays();
        String[] items = new String[ds.length + 1];
        items[0] = "Automático";
        for (int i = 0; i < ds.length; i++) items[i + 1] = describe(ds[i]);
        new AlertDialog.Builder(act).setTitle("Display da tela traseira")
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        prefs.edit().putInt("disp", w == 0 ? -1 : ds[w - 1].getDisplayId()).apply();
                        if (running) { stop(); start(true); }
                    }
                }).show();
    }

    // ---------- display traseiro ----------

    private static Display findRear(Context c) {
        DisplayManager dm = (DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
        int want = prefs.getInt("disp", -1);
        if (want >= 0) {
            Display d = dm.getDisplay(want);
            if (d != null) return d;
        }
        Display best = null;
        long bestArea = Long.MAX_VALUE;
        for (Display d : dm.getDisplays()) {
            if (d.getDisplayId() == Display.DEFAULT_DISPLAY) continue;
            Point p = new Point();
            d.getRealSize(p);
            long area = (long) p.x * p.y;
            if (area > 0 && area < bestArea) { best = d; bestArea = area; }
        }
        return best;
    }

    private static void start(boolean userAction) {
        final Activity a = act;
        if (a == null) return;
        Display d = findRear(a);
        if (d == null) {
            Toast.makeText(a, "Tela traseira não encontrada. Toque longo no botão → Info dos displays.", Toast.LENGTH_LONG).show();
            return;
        }
        Point sz = new Point();
        d.getRealSize(sz);
        targetW = Math.max(64, sz.x);
        targetH = Math.max(64, sz.y);
        rearId = d.getDisplayId();
        log("start " + describe(d) + " user=" + userAction);
        final boolean wasOn = d.getState() == Display.STATE_ON;

        if (prefs.getBoolean("wake", true)) subScreen(a, true);
        boolean ok = false;
        try {
            pres = new Presentation(a, d);
            rearView = new RearView(pres.getContext());
            pres.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_FULLSCREEN);
            pres.setContentView(rearView);
            pres.setOnDismissListener(new DialogInterface.OnDismissListener() {
                public void onDismiss(DialogInterface di) { if (pres != null) { pres = null; stopLoop(); } }
            });
            pres.show();
            ok = true;
            log("presentation ok");
        } catch (Throwable e) {
            log("presentation falhou: " + e);
            pres = null;
        }
        if (!ok) {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(a)) {
                if (userAction) {
                    Toast.makeText(a, "Permita \"sobrepor a outros apps\" e toque no botão de novo.", Toast.LENGTH_LONG).show();
                    try {
                        a.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + a.getPackageName())));
                    } catch (Throwable ignored) { }
                }
                rearView = null;
                return;
            }
            try {
                Context dc = a.createDisplayContext(d);
                overlayWm = (WindowManager) dc.getSystemService(Context.WINDOW_SERVICE);
                int type = Build.VERSION.SDK_INT >= 26 ? 2038 /*APPLICATION_OVERLAY*/ : 2002 /*PHONE*/;
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                        PixelFormat.OPAQUE);
                rearView = new RearView(dc);
                overlayWm.addView(rearView, lp);
                overlayView = rearView;
                ok = true;
                log("overlay ok");
            } catch (Throwable e) {
                log("overlay falhou: " + e);
                Toast.makeText(a, "Não consegui desenhar na tela traseira: " + e.getClass().getSimpleName()
                        + (e.getMessage() != null ? " - " + e.getMessage() : ""), Toast.LENGTH_LONG).show();
                overlayWm = null; overlayView = null; rearView = null;
                return;
            }
        }
        running = true;
        refreshButton();
        // A tela traseira costuma acordar só com a 1a janela; a 2a (o "segundo toque") é que aparece.
        // Então refaz sozinho uma vez, e de novo se ela ainda não estiver ligada.
        if (!kicked || retries < 2) {
            final boolean first = !kicked;
            kicked = true;
            retries++;
            main.postDelayed(new Runnable() { public void run() {
                if (act == null || !running) return;
                log("kick first=" + first + " healthy=" + healthy());
                if (healthy()) return;
                stop();
                main.postDelayed(new Runnable() { public void run() {
                    if (act != null && !running) start(false);
                } }, 600);
            } }, 1500);
        }
        lastSearchMs = 0;
        Choreographer.getInstance().postFrameCallback(FRAME);
    }

    private static boolean healthy() {
        if (act == null || rearView == null) return false;
        DisplayManager dm = (DisplayManager) act.getSystemService(Context.DISPLAY_SERVICE);
        Display d = rearId >= 0 ? dm.getDisplay(rearId) : null;
        return d != null && d.getState() == Display.STATE_ON && rearView.isAttachedToWindow()
                && rearView.getWidth() > 0 && rearView.isShown();
    }

    private static void restartIfNeeded(boolean force) {
        if (act == null || !running) return;
        if (!force && healthy()) return;
        stop();
        start(false);
    }

    private static void registerListener(Context c) {
        unregisterListener(c);
        DisplayManager dm = (DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
        dispListener = new DisplayManager.DisplayListener() {
            private int lastState = -1;
            public void onDisplayAdded(int id) { }
            public void onDisplayRemoved(int id) { if (id == rearId && running) stop(); }
            public void onDisplayChanged(int id) {
                if (id != rearId || act == null) return;
                DisplayManager dm2 = (DisplayManager) act.getSystemService(Context.DISPLAY_SERVICE);
                Display d = dm2.getDisplay(id);
                int st = d == null ? -1 : d.getState();
                // tela traseira acabou de acordar com a gente ligado: recria a janela nela

                lastState = st;
            }
        };
        dm.registerDisplayListener(dispListener, main);
    }

    private static void unregisterListener(Context c) {
        if (dispListener == null) return;
        try { ((DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE)).unregisterDisplayListener(dispListener); } catch (Throwable ignored) { }
        dispListener = null;
    }

    private static void stopLoop() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(FRAME);
        refreshButton();
    }

    private static void stop() {
        boolean was = running || pres != null || overlayView != null;
        if (was) log("stop");
        stopLoop();
        if (was && wakeToken != null && act != null) subScreen(act, false);
        try { if (pres != null) { Presentation p = pres; pres = null; p.dismiss(); } } catch (Throwable ignored) { }
        try { if (overlayWm != null && overlayView != null) overlayWm.removeViewImmediate(overlayView); } catch (Throwable ignored) { }
        overlayWm = null; overlayView = null; rearView = null;
        if (capA != null) { capA.recycle(); capA = null; }
        if (capB != null) { capB.recycle(); capB = null; }
        copyPending = false;
    }

    // ---------- captura do visor ----------

    private static void findSource() {
        srcTv = null; srcSv = null;
        if (act == null) return;
        View root = act.getWindow().getDecorView();
        long[] bestTv = {0}, bestSv = {0};
        walk(root, bestTv, bestSv);
    }

    private static void walk(View v, long[] bestTv, long[] bestSv) {
        if (v.getVisibility() != View.VISIBLE) return;
        if (v instanceof TextureView) {
            TextureView t = (TextureView) v;
            long a = (long) t.getWidth() * t.getHeight();
            if (t.isAvailable() && a > bestTv[0]) { bestTv[0] = a; srcTv = t; }
        } else if (v instanceof SurfaceView) {
            SurfaceView s = (SurfaceView) v;
            long a = (long) s.getWidth() * s.getHeight();
            if (a > bestSv[0]) { bestSv[0] = a; srcSv = s; }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), bestTv, bestSv);
        }
    }

    private static Bitmap ensure(Bitmap b, int w, int h) {
        if (b != null && b.getWidth() == w && b.getHeight() == h && !b.isRecycled()) return b;
        if (b != null) b.recycle();
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    }

    private static void tick() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastFrameMs < 30) return; // ~30 fps
        lastFrameMs = now;
        boolean srcOk = (srcTv != null && srcTv.isAttachedToWindow() && srcTv.isAvailable() && srcTv.getWidth() > 0)
                || (srcTv == null && srcSv != null && srcSv.isAttachedToWindow() && srcSv.getWidth() > 0);
        if (!srcOk || now - lastSearchMs > 1500) { findSource(); lastSearchMs = now; }

        View src = srcTv != null ? srcTv : srcSv;
        if (src == null || src.getWidth() <= 0 || src.getHeight() <= 0) return;

        // resolução de captura: proporcional à fonte, lado maior ~ lado maior da tela traseira
        int maxSide = Math.max(targetW, targetH);
        float sc = (float) maxSide / Math.max(src.getWidth(), src.getHeight());
        int cw = Math.max(16, Math.round(src.getWidth() * sc));
        int ch = Math.max(16, Math.round(src.getHeight() * sc));

        if (srcTv != null) {
            capA = ensure(capA, cw, ch);
            srcTv.getBitmap(capA);
            if (rearView != null) rearView.setFrame(capA);
        } else if (Build.VERSION.SDK_INT >= 24 && !copyPending) {
            capB = ensure(capB, cw, ch);
            final Bitmap dst = capB;
            copyPending = true;
            try {
                PixelCopy.request(srcSv, dst, new PixelCopy.OnPixelCopyFinishedListener() {
                    public void onPixelCopyFinished(int r) {
                        copyPending = false;
                        if (r == PixelCopy.SUCCESS && running && rearView != null) {
                            Bitmap t = capA; capA = dst; capB = t;
                            rearView.setFrame(capA);
                        }
                    }
                }, main);
            } catch (Throwable e) { copyPending = false; }
        }
    }

    // ---------- view da tela traseira ----------

    static final class RearView extends View {
        private Bitmap frame;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Matrix m = new Matrix();

        RearView(Context c) { super(c); setBackgroundColor(Color.BLACK); }

        void setFrame(Bitmap b) { frame = b; invalidate(); }

        @Override
        protected void onDraw(Canvas c) {
            c.drawColor(Color.BLACK);
            Bitmap b = frame;
            if (b == null || b.isRecycled()) return;
            int vw = getWidth(), vh = getHeight();
            if (vw <= 0 || vh <= 0) return;
            int rot = prefs != null ? prefs.getInt("rot", 0) : 0;
            boolean mirror = prefs == null || prefs.getBoolean("mirror", true);
            boolean fill = prefs != null && prefs.getBoolean("fill", false);
            float bw = b.getWidth(), bh = b.getHeight();
            boolean swap = rot == 90 || rot == 270;
            float rw = swap ? bh : bw, rh = swap ? bw : bh;
            float s = fill ? Math.max(vw / rw, vh / rh) : Math.min(vw / rw, vh / rh);
            m.reset();
            m.postTranslate(-bw / 2f, -bh / 2f);
            if (mirror) m.postScale(-1f, 1f);
            m.postRotate(rot);
            m.postScale(s, s);
            m.postTranslate(vw / 2f, vh / 2f);
            c.drawBitmap(b, m, paint);
        }
    }

    private RearPreview() { }
}
