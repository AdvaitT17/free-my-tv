package io.freemytv;

import android.app.Activity;
import android.app.ActivityManager;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.KeyEvent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recent Apps: the apps that are open, as big cards over the blurred home screen, with a live memory meter. ← → pick
 * one, OK opens it, ▲ closes it (the TV's own ADB stops the app, which frees its memory) and ▼ or Back leave.
 * HomeView draws it on a layer of its own and sends it the keys. The numbers are in the 1920 × 1080 space and come
 * from measuring the tvOS Launcher's switcher.
 */
final class RecentApps {
    interface Host {
        void open(HomeApps.Item item);
        /** Something moved without a key (the list arrived, memory changed): draw again. */
        void repaint();
    }

    private static final float CARD_W = 670, CARD_H = 377, CENTER_X = 960, CENTER_Y = 560, GLOW = 340;
    /** Where a card's left edge sits at 0, 1, 2 and 3 places from the focused one (the next ones tuck under their neighbour). */
    private static final float[] LEFT = {625, 1035, 1625, 2125};
    private static final float[] SCALE = {1, 0.88f, 0.78f, 0.7f};
    private static final Pattern SIZE = Pattern.compile("sz=(\\d+)");
    private static final Pattern PACKAGE = Pattern.compile("(?:A=\\d+:|I=)([\\w.]+)");

    private final Activity activity;
    private final Host host;
    private final float k;
    private final List<Card> cards = new ArrayList<>();
    private final Spring shown = new Spring(0.3f, 0.85f, 0), scroll = new Spring(0.3f, 0.82f, 0), meter = new Spring(0.5f, 0.9f, 0);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG),
            photo = new Paint(Paint.FILTER_BITMAP_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF(), full = new RectF();
    private final Matrix matrix = new Matrix();
    private Bitmap backdrop;
    private boolean open, loading;
    private int focus;
    private long usedKb, totalKb;
    private String message;

    private static final class Card {
        HomeApps.Item item;
        Paint paint;                 // the card's art as a shader, made once
        int glow;                    // the art's average color
        final Spring gone = new Spring(0.28f, 0.9f, 0);
        boolean closing;
    }

    RecentApps(Activity a, Host host, float k) {
        this.activity = a;
        this.host = host;
        this.k = k;
        text.setTypeface(Ui.semibold);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(2 * k);
    }

    boolean visible() {
        return open || shown.value > 0.001f;
    }

    /** Opaque over the whole screen: what is below needn't be drawn. */
    boolean covering() {
        return open && shown.value > 0.999f && backdrop != null;
    }

    void open() {
        cards.clear();
        focus = 0;
        scroll.snap(0);
        loading = true;
        message = null;
        open = true;
        readMemory();
        meter.snap(total() > 0 ? (float) usedKb / totalKb : 0);
        new Thread(this::load, "recent-apps").start();
    }

    void close() {
        open = false;
    }

    void setBackdrop(Bitmap blurred) {
        backdrop = blurred;
    }

    // MARK: - Data

    private long total() {
        return totalKb;
    }

    private void readMemory() {
        ActivityManager am = (ActivityManager) activity.getSystemService(Activity.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        totalKb = mi.totalMem / 1024;
        usedKb = (mi.totalMem - mi.availMem) / 1024;
        meter.target = totalKb > 0 ? Math.max(0, Math.min(1, (float) usedKb / totalKb)) : 0;
    }

    /** The open apps, newest first: the tasks Android keeps (with an activity, or with a process still running). */
    private void load() {
        List<Card> found = new ArrayList<>();
        String error = null;
        try (Adb adb = Adb.connect(activity.getApplicationContext(), 10_000, null)) {
            String recents = adb.shell("dumpsys activity recents");
            String ps = adb.shell("ps -A -o NAME");
            Set<String> alive = new HashSet<>();
            for (String line : ps.split("\n")) alive.add(line.trim());
            Map<String, String> launchable = new HashMap<>();
            for (String[] app : HomeApps.launchable(activity)) launchable.put(app[0], app[1]);
            Set<String> seen = new HashSet<>();
            for (String line : recents.split("\n")) {
                if (!line.contains("Recent #")) continue;
                Matcher pkg = PACKAGE.matcher(line);
                if (!pkg.find()) continue;
                String name = pkg.group(1);
                Matcher sz = SIZE.matcher(line);
                boolean hasActivity = sz.find() && Integer.parseInt(sz.group(1)) > 0;
                boolean running = alive.contains(name);
                if (!(hasActivity || running) || name.equals(activity.getPackageName()) || !launchable.containsKey(name) || HomeApps.isHidden(name) || !seen.add(name)) continue;
                Card c = new Card();
                c.item = HomeApps.recentItem(activity, name, launchable.get(name));
                found.add(c);
            }
        } catch (IOException e) {
            AppLog.w("recent apps: " + e.getMessage());
            error = Ui.tr("Couldn't read the recent apps. Allow USB debugging for Free My TV.");
        }
        final String shownError = error;
        for (Card c : found) shade(c);
        activity.runOnUiThread(() -> {
            loading = false;
            message = shownError;
            cards.clear();
            cards.addAll(found);
            focus = 0;
            scroll.snap(0);
            readMemory();
            host.repaint();
        });
    }

    /** The card's art as a shader (drawn rounded), and its average color for the glow behind it. */
    private void shade(Card c) {
        Bitmap art = c.item.tile;
        if (art == null) return;
        c.paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        c.paint.setShader(new BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        Bitmap tiny = Bitmap.createScaledBitmap(art, 1, 1, true);
        c.glow = tiny.getPixel(0, 0) | 0xFF000000;
        if (tiny != art) tiny.recycle();
    }

    private void closeFocused() {
        if (focus >= cards.size()) return;
        final Card c = cards.get(focus);
        if (c.closing) return;
        final String pkg = c.item.pkg;
        if (!HomeMode.mayStop(pkg)) return;   // Android's own pieces stay
        c.closing = true;
        c.gone.target = 1;
        new Thread(() -> {
            try (Adb adb = Adb.connect(activity.getApplicationContext(), 10_000, null)) {
                adb.shell("am force-stop " + pkg);
            } catch (IOException e) {
                AppLog.w("close app: " + e.getMessage());
            }
            try { Thread.sleep(900); } catch (InterruptedException ignored) { }
            activity.runOnUiThread(() -> { readMemory(); host.repaint(); });
        }, "close-app").start();
    }

    // MARK: - Drawing

    boolean step(float dt) {
        shown.target = open ? 1 : 0;
        boolean moving = shown.step(dt) | meter.step(dt);
        scroll.target = focus;
        moving |= scroll.step(dt);
        for (int i = cards.size() - 1; i >= 0; i--) {
            Card c = cards.get(i);
            moving |= c.gone.step(dt);
            if (c.closing && c.gone.value > 0.97f) {
                cards.remove(i);
                if (focus >= cards.size()) focus = Math.max(0, cards.size() - 1);
                scroll.target = focus;
                moving = true;
            }
        }
        return moving || loading;
    }

    void draw(Canvas c, float w, float h) {
        float a = Ui.clamp(shown.value);
        if (a <= 0.004f) return;
        if (backdrop != null) {
            photo.setAlpha(Math.round(255 * a));
            full.set(0, 0, w, h);
            c.drawBitmap(backdrop, null, full, photo);
        }
        fill.setShader(null);
        fill.setColor(Ui.alpha(0xFF000000, (backdrop != null ? 0.55f : 0.85f) * a));
        c.drawRect(0, 0, w, h, fill);
        label(c, Ui.tr("Recent Apps"), 108 * k, 108 * k, Ui.alpha(0xFFFFFFFF, a), 37 * k, Paint.Align.LEFT);
        drawMeter(c, a);
        if (cards.isEmpty()) {
            String empty = message != null ? message : loading ? Ui.tr("Looking for open apps…") : Ui.tr("No apps are open.");
            label(c, empty, CENTER_X * k, CENTER_Y * k, Ui.alpha(0xFFFFFFFF, 0.6f * a), 28 * k, Paint.Align.CENTER);
            return;
        }
        // Far cards first, the focused one on top.
        float at = scroll.value;
        int count = cards.size();
        int near = Math.round(at);
        for (int dist = 3; dist >= 0; dist--) {
            for (int side = 1; side >= -1; side -= 2) {
                int i = near + side * dist;
                if (i < 0 || i >= count || (dist == 0 && side == -1)) continue;
                drawCard(c, cards.get(i), i - at, a);
            }
        }
        label(c, Ui.tr("Press ▲ on an app to close it"), CENTER_X * k, 975 * k, Ui.alpha(0xFFFFFFFF, 0.55f * a), 22 * k, Paint.Align.CENTER);
    }

    private void drawMeter(Canvas c, float a) {
        float left = 1197 * k, top = 51 * k, right = 1794 * k, bottom = 129 * k;
        r.set(left, top, right, bottom);
        fill.setColor(Ui.alpha(0xFF3A3A3C, 0.6f * a));
        c.drawRoundRect(r, 39 * k, 39 * k, fill);
        rim.setColor(Ui.alpha(0xFFFFFFFF, 0.28f * a));
        r.inset(k, k);
        c.drawRoundRect(r, 38 * k, 38 * k, rim);
        String used = gigabytes(usedKb), total = gigabytes(totalKb);
        label(c, Ui.trf("RAM {used} used of {total}", "used", used, "total", total), left + 38 * k, 99 * k, Ui.alpha(0xFFFFFFFF, a), 25 * k, Paint.Align.LEFT);
        float barW = 178 * k, barX = right - 38 * k - barW, barY = (top + bottom) / 2;
        r.set(barX, barY - 5 * k, barX + barW, barY + 5 * k);
        fill.setColor(Ui.alpha(0xFFFFFFFF, 0.28f * a));
        c.drawRoundRect(r, 5 * k, 5 * k, fill);
        r.set(barX, barY - 5 * k, barX + barW * Ui.clamp(meter.value), barY + 5 * k);
        fill.setColor(Ui.alpha(0xFFFFFFFF, a));
        c.drawRoundRect(r, 5 * k, 5 * k, fill);
    }

    private static String gigabytes(long kb) {
        return String.format(Ui.locale(), "%.1f GB", kb / 1048576f);
    }

    /** `d` places from the focused card (negative: to its left). */
    private void drawCard(Canvas c, Card card, float d, float a) {
        float ad = Math.abs(d);
        float s = interpolate(SCALE, ad), w = CARD_W * s, h = CARD_H * s;
        float left = interpolate(LEFT, ad);
        float x0 = d >= 0 ? left : 1920 - (left + w);   // to the left they mirror
        float gone = Ui.clamp(card.gone.value);
        float alpha = a * (1 - gone);
        float lift = -260 * gone;
        float top = CENTER_Y - h / 2 + lift;
        r.set(x0 * k, top * k, (x0 + w) * k, (top + h) * k);
        float rad = 30 * k * s;
        if (ad < 0.5f && card.glow != 0) {   // the app's color, soft, behind the focused card
            float gl = (1 - ad * 2) * alpha;
            RadialGradient glow = glowShader(card.glow);
            fill.setShader(glow);
            fill.setAlpha(Math.round(255 * 0.75f * gl));
            c.save();
            c.translate(r.left + 40 * k, r.centerY());
            c.drawCircle(0, 0, GLOW * k, fill);
            c.restore();
            fill.setShader(null);
        }
        fill.setColor(Ui.alpha(0xFF000000, 0.35f * alpha));
        r.offset(0, 10 * k);
        c.drawRoundRect(r, rad, rad, fill);
        r.offset(0, -10 * k);
        if (card.paint != null) {
            float sx = r.width() / card.item.tile.getWidth(), sy = r.height() / card.item.tile.getHeight();
            matrix.setScale(sx, sy);
            matrix.postTranslate(r.left, r.top);
            card.paint.getShader().setLocalMatrix(matrix);
            card.paint.setAlpha(Math.round(255 * alpha));
            c.drawRoundRect(r, rad, rad, card.paint);
        } else {
            fill.setColor(Ui.alpha(0xFF2C2C2E, alpha));
            c.drawRoundRect(r, rad, rad, fill);
        }
        if (ad < 0.5f) {
            rim.setColor(Ui.alpha(0xFFFFFFFF, 0.5f * alpha * (1 - ad * 2)));
            c.drawRoundRect(r, rad, rad, rim);
        }
        label(c, card.item.label, r.left + 12 * k, r.top - 16 * k, Ui.alpha(0xFFFFFFFF, 0.9f * alpha), 22 * k, Paint.Align.LEFT);
    }

    private final Map<Integer, RadialGradient> glows = new HashMap<>();

    private RadialGradient glowShader(int color) {
        RadialGradient g = glows.get(color);
        if (g == null) {
            g = new RadialGradient(0, 0, GLOW * k, new int[] {color, color & 0x00FFFFFF}, new float[] {0.15f, 1}, Shader.TileMode.CLAMP);
            glows.put(color, g);
        }
        return g;
    }

    private static float interpolate(float[] table, float at) {
        int i = Math.min(table.length - 2, (int) at);
        float t = Math.min(1, at - i);
        return table[i] + (table[i + 1] - table[i]) * t;
    }

    private void label(Canvas c, String s, float x, float y, int color, float size, Paint.Align align) {
        text.setTextSize(size);
        text.setColor(color);
        text.setTextAlign(align);
        c.drawText(s, x, y, text);
    }

    // MARK: - Keys

    boolean key(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (focus > 0) focus--;
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (focus < cards.size() - 1) focus++;
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                closeFocused();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (e.getRepeatCount() == 0 && focus < cards.size()) {
                    HomeApps.Item item = cards.get(focus).item;
                    close();
                    host.open(item);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_APP_SWITCH:
                close();
                return true;
            default:
                return true;
        }
    }
}
