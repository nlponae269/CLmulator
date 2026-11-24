package app.clm.center;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * CLM Center — приложение тестовых прошивок CLmulator.
 * Пишется под API 4 (Android 1.6), поэтому только самые базовые виджеты.
 */
public class MainActivity extends Activity {
    private static final int BG = 0xFF0F1416;
    private static final int CARD = 0xFF1C2529;
    private static final int ACCENT = 0xFF8BC34A;
    private static final int TEXT = 0xFFE8EEF0;
    private static final int DIM = 0xFF9DB0B6;

    private final Handler handler = new Handler();
    private TextView rootStatus;
    private TextView output;
    private EditText command;
    private ScrollView scroll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(24));
        scroll.addView(root);

        TextView title = text("CLM Center", 30, ACCENT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        root.addView(text("Тестовая прошивка CLmulator • " + prop("ro.clm.edition", "Test Build"), 14, DIM));

        LinearLayout info = card(root, "Устройство");
        info.addView(row("Модель", Build.MANUFACTURER + " " + Build.MODEL));
        info.addView(row("Android", releaseName() + " (API " + Build.VERSION.SDK + ")"));
        info.addView(row("Сборка", Build.DISPLAY));
        info.addView(row("Процессор", cpu()));
        info.addView(row("Память", mem()));
        info.addView(row("Ядро", kernel()));

        LinearLayout rootCard = card(root, "Root-доступ");
        rootStatus = text("Проверяю…", 16, TEXT);
        rootCard.addView(rootStatus);
        rootCard.addView(button("Проверить root ещё раз", new View.OnClickListener() {
            public void onClick(View v) { checkRoot(); }
        }));

        LinearLayout term = card(root, "Root-терминал");
        command = new EditText(this);
        command.setSingleLine(true);
        command.setText("id");
        command.setTypeface(Typeface.MONOSPACE);
        term.addView(command, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(button("Выполнить как root", new View.OnClickListener() {
            public void onClick(View v) { run(command.getText().toString(), true); }
        }));
        buttons.addView(button("Как пользователь", new View.OnClickListener() {
            public void onClick(View v) { run(command.getText().toString(), false); }
        }));
        term.addView(buttons);
        LinearLayout quick = new LinearLayout(this);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        String[][] q = {{"mount", "mount"}, {"ps", "ps"}, {"getprop", "getprop ro.clm.edition; getprop ro.product.model"}, {"df", "df"}};
        for (int i = 0; i < q.length; i++) {
            final String c = q[i][1];
            quick.addView(button(q[i][0], new View.OnClickListener() {
                public void onClick(View v) { command.setText(c); run(c, true); }
            }));
        }
        term.addView(quick);
        output = text("", 12, 0xFFB9F6CA);
        output.setTypeface(Typeface.MONOSPACE);
        output.setBackgroundColor(0xFF000000);
        output.setPadding(dp(8), dp(8), dp(8), dp(8));
        term.addView(output, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout fun = card(root, "Штучки");
        fun.addView(button("Привет из прошлого", new View.OnClickListener() {
            public void onClick(View v) {
                Toast.makeText(MainActivity.this, "Привет! Это " + releaseName() + " внутри CLmulator :)", Toast.LENGTH_LONG).show();
            }
        }));
        fun.addView(button("Перезагрузить (root)", new View.OnClickListener() {
            public void onClick(View v) { run("reboot", true); }
        }));

        TextView footer = text("CLmulator test build • root для всех приложений, только для экспериментов", 11, DIM);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(16), 0, 0);
        root.addView(footer);

        setContentView(scroll);
        checkRoot();
    }

    private void checkRoot() {
        rootStatus.setText("Проверяю…");
        new Thread() {
            public void run() {
                final String r = exec("id", true).trim();
                handler.post(new Runnable() {
                    public void run() {
                        if (r.indexOf("uid=0") >= 0) {
                            rootStatus.setText("ROOT ЕСТЬ ✔\n" + r);
                            rootStatus.setTextColor(ACCENT);
                        } else {
                            rootStatus.setText("root не получен\n" + r);
                            rootStatus.setTextColor(0xFFFF8A80);
                        }
                    }
                });
            }
        }.start();
    }

    private void run(final String cmd, final boolean asRoot) {
        output.setText((asRoot ? "# " : "$ ") + cmd + "\n…");
        new Thread() {
            public void run() {
                final String r = exec(cmd, asRoot);
                handler.post(new Runnable() {
                    public void run() {
                        output.setText((asRoot ? "# " : "$ ") + cmd + "\n" + r);
                    }
                });
            }
        }.start();
    }

    static String exec(String cmd, boolean asRoot) {
        StringBuffer sb = new StringBuffer();
        try {
            Process p;
            if (asRoot) {
                p = Runtime.getRuntime().exec(new String[]{"su"});
                OutputStream os = p.getOutputStream();
                os.write((cmd + " 2>&1\nexit\n").getBytes());
                os.flush();
                os.close();
            } else {
                p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", cmd + " 2>&1"});
            }
            InputStream in = p.getInputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0 && sb.length() < 20000) sb.append(new String(buf, 0, n));
            p.waitFor();
        } catch (Throwable t) {
            sb.append("ошибка: ").append(t.toString());
        }
        return sb.length() == 0 ? "(пусто)" : sb.toString();
    }

    private static String releaseName() {
        String r = Build.VERSION.RELEASE;
        int sdk = 0;
        try { sdk = Integer.parseInt(Build.VERSION.SDK); } catch (Exception e) { }
        String name = sdk <= 4 ? "Donut" : sdk <= 7 ? "Eclair" : sdk <= 8 ? "Froyo" : sdk <= 10 ? "Gingerbread"
                : sdk <= 13 ? "Honeycomb" : sdk <= 15 ? "Ice Cream Sandwich" : sdk <= 18 ? "Jelly Bean"
                : sdk <= 20 ? "KitKat" : sdk <= 22 ? "Lollipop" : "Android";
        return "Android " + r + " " + name;
    }

    private static String prop(String key, String def) {
        String v = exec("getprop " + key, false).trim();
        return v.length() == 0 || v.equals("(пусто)") ? def : v;
    }

    private static String cpu() {
        String m = grep("/proc/cpuinfo", "model name");
        return m == null ? "x86" : m;
    }

    private static String mem() {
        String m = grep("/proc/meminfo", "MemTotal");
        if (m == null) return "?";
        try {
            long kb = Long.parseLong(m.replaceAll("[^0-9]", ""));
            return (kb / 1024) + " МБ";
        } catch (Exception e) { return m; }
    }

    private static String kernel() {
        try {
            BufferedReader r = new BufferedReader(new FileReader("/proc/version"), 1024);
            String l = r.readLine();
            r.close();
            String[] p = l.split(" ");
            return p.length > 2 ? p[2] : l;
        } catch (Exception e) { return "?"; }
    }

    private static String grep(String file, String key) {
        try {
            BufferedReader r = new BufferedReader(new FileReader(file), 4096);
            String l;
            while ((l = r.readLine()) != null) {
                if (l.startsWith(key)) {
                    r.close();
                    int i = l.indexOf(':');
                    return i >= 0 ? l.substring(i + 1).trim() : l;
                }
            }
            r.close();
        } catch (Exception e) { }
        return null;
    }

    private LinearLayout card(LinearLayout parent, String title) {
        TextView t = text(title.toUpperCase(), 13, ACCENT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(18), 0, dp(6));
        parent.addView(t);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundColor(CARD);
        c.setPadding(dp(12), dp(12), dp(12), dp(12));
        parent.addView(c, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return c;
    }

    private View row(String k, String v) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(0, dp(4), 0, dp(4));
        l.addView(text(k, 12, DIM));
        l.addView(text(v == null ? "?" : v, 16, TEXT));
        return l;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setOnClickListener(l);
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
