package com.maxz.lanmessenger;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Typeface;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.text.format.Formatter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int PORT = 8080;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool();
    private EditText nameInput, ipInput, messageInput;
    private TextView statusText, messagesText;
    private ScrollView messagesScroll;
    private volatile boolean polling = false;
    private volatile LanHttpServer server;
    private String lastRendered = "";
    private boolean darkTheme;
    private int bgColor, surfaceColor, textColor, mutedColor, accentColor, inputColor, borderColor;
    private final Runnable poller = new Runnable() {
        @Override public void run() {
            if (polling) fetchMessages();
            if (polling) handler.postDelayed(this, 2000);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        darkTheme = getPreferences(MODE_PRIVATE).getBoolean("dark_theme", false);
        applyPalette();
        buildUi();
        renderSavedLocalHistory();
        statusText.setText("Готово. Для двух телефонов подключите их к одной Wi-Fi-сети.");
    }

    private void applyPalette() {
        if (darkTheme) {
            bgColor = Color.rgb(16, 20, 28);
            surfaceColor = Color.rgb(27, 33, 44);
            textColor = Color.rgb(239, 243, 249);
            mutedColor = Color.rgb(158, 171, 190);
            accentColor = Color.rgb(92, 219, 171);
            inputColor = Color.rgb(35, 42, 55);
            borderColor = Color.rgb(54, 64, 80);
        } else {
            bgColor = Color.rgb(246, 248, 251);
            surfaceColor = Color.WHITE;
            textColor = Color.rgb(25, 35, 52);
            mutedColor = Color.rgb(105, 119, 139);
            accentColor = Color.rgb(20, 151, 111);
            inputColor = Color.rgb(248, 250, 252);
            borderColor = Color.rgb(225, 231, 239);
        }
    }

    private GradientDrawable rounded(int color, int strokeColor, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) d.setStroke(dp(1), strokeColor);
        return d;
    }

    private TextView label(String text, int size, boolean bold) {
        TextView t = new TextView(this); t.setText(text); t.setTextSize(size);
        t.setTextColor(textColor); if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(14));
        root.setBackgroundColor(bgColor);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout brand = new LinearLayout(this); brand.setOrientation(LinearLayout.VERTICAL);
        TextView title = label("LAN Messenger", 25, true);
        TextView subtitle = label("Личное общение в вашей сети", 12, false); subtitle.setTextColor(mutedColor);
        brand.addView(title); brand.addView(subtitle);
        top.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        Button theme = button(darkTheme ? "☀ Светлая" : "☾ Тёмная", true);
        top.addView(theme, lp(-2, dp(42), dp(8), 0, 0, 0));
        theme.setOnClickListener(v -> {
            String oldName = nameInput == null ? "Пользователь" : nameInput.getText().toString();
            String oldIp = ipInput == null ? "" : ipInput.getText().toString();
            String oldMessage = messageInput == null ? "" : messageInput.getText().toString();
            String oldStatus = statusText == null ? "" : statusText.getText().toString();
            String oldHistory = messagesText == null ? "" : messagesText.getText().toString();
            darkTheme = !darkTheme; getPreferences(MODE_PRIVATE).edit().putBoolean("dark_theme", darkTheme).apply();
            applyPalette(); buildUi();
            nameInput.setText(oldName); ipInput.setText(oldIp); messageInput.setText(oldMessage);
            statusText.setText(oldStatus); messagesText.setText(oldHistory);
        });
        root.addView(top, lp(-1, -2, 0, 0, 0, dp(18)));

        LinearLayout profileCard = card(); profileCard.setOrientation(LinearLayout.VERTICAL);
        TextView section = label("ВАШ ПРОФИЛЬ", 11, true); section.setTextColor(accentColor);
        profileCard.addView(section, lp(-1, -2, 0, 0, 0, dp(8)));
        nameInput = field("Ваше имя", "Пользователь");
        profileCard.addView(nameInput, lp(-1, dp(48), 0, 0, 0, dp(10)));
        TextView netLabel = label("ПОДКЛЮЧЕНИЕ", 11, true); netLabel.setTextColor(accentColor);
        profileCard.addView(netLabel, lp(-1, -2, 0, dp(2), 0, dp(8)));
        ipInput = field("IP сервера, например 192.168.1.10", "");
        profileCard.addView(ipInput, lp(-1, dp(48), 0, 0, 0, dp(10)));
        LinearLayout buttons = new LinearLayout(this); buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button start = button("Запустить сервер", false);
        Button connect = button("Подключиться", true);
        buttons.addView(start, new LinearLayout.LayoutParams(0, dp(48), 1));
        buttons.addView(connect, lp(0, dp(48), dp(8), 0, 0, 0));
        ((LinearLayout.LayoutParams)connect.getLayoutParams()).weight = 1;
        profileCard.addView(buttons, lp(-1, -2, 0, 0, 0, 0));
        start.setOnClickListener(v -> startServer()); connect.setOnClickListener(v -> connectToServer());
        root.addView(profileCard, lp(-1, -2, 0, 0, 0, dp(12)));

        LinearLayout statusCard = card(); statusCard.setOrientation(LinearLayout.VERTICAL);
        TextView statusHeading = label("СОСТОЯНИЕ СЕТИ", 11, true); statusHeading.setTextColor(accentColor);
        statusCard.addView(statusHeading, lp(-1, -2, 0, 0, 0, dp(5)));
        statusText = label("Готово. Подключите оба телефона к одной Wi-Fi-сети.", 13, false);
        statusText.setTextColor(mutedColor); statusText.setLineSpacing(dp(2), 1.0f);
        statusCard.addView(statusText, lp(-1, -2, 0, 0, 0, 0));
        root.addView(statusCard, lp(-1, -2, 0, 0, 0, dp(12)));

        LinearLayout chatCard = card(); chatCard.setOrientation(LinearLayout.VERTICAL);
        LinearLayout chatHeading = new LinearLayout(this); chatHeading.setOrientation(LinearLayout.HORIZONTAL);
        chatHeading.setGravity(android.view.Gravity.CENTER_VERTICAL);
        chatHeading.addView(label("Сообщения", 18, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView localBadge = label("ЛОКАЛЬНАЯ СЕТЬ", 10, true); localBadge.setTextColor(accentColor);
        chatHeading.addView(localBadge);
        chatCard.addView(chatHeading, lp(-1, -2, 0, 0, 0, dp(10)));
        messagesScroll = new ScrollView(this); messagesScroll.setFillViewport(true);
        messagesText = new TextView(this); messagesText.setTextSize(14); messagesText.setTextColor(textColor);
        messagesText.setPadding(dp(13), dp(13), dp(13), dp(13));
        messagesText.setBackground(rounded(inputColor, borderColor, 16));
        messagesScroll.addView(messagesText);
        LinearLayout.LayoutParams historyLp = new LinearLayout.LayoutParams(-1, 0, 1);
        chatCard.addView(messagesScroll, historyLp);
        root.addView(chatCard, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout sendRow = new LinearLayout(this); sendRow.setOrientation(LinearLayout.HORIZONTAL);
        sendRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        messageInput = field("Напишите сообщение…", "");
        sendRow.addView(messageInput, new LinearLayout.LayoutParams(0, dp(52), 1));
        Button send = button("Отправить  ➤", true);
        sendRow.addView(send, lp(dp(124), dp(52), dp(8), 0, 0, 0));
        root.addView(sendRow, lp(-1, -2, 0, dp(12), 0, 0));
        send.setOnClickListener(v -> sendMessage());
        setContentView(root);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this); c.setPadding(dp(14), dp(14), dp(14), dp(14));
        c.setBackground(rounded(surfaceColor, borderColor, 20)); return c;
    }

    private EditText field(String hint, String value) {
        EditText e = new EditText(this); e.setSingleLine(true); e.setTextSize(14); e.setHint(hint);
        e.setText(value); e.setTextColor(textColor); e.setHintTextColor(mutedColor);
        e.setPadding(dp(13), 0, dp(13), 0); e.setBackground(rounded(inputColor, borderColor, 14));
        return e;
    }
    private Button button(String text, boolean primary) {
        Button b = new Button(this); b.setText(text); b.setTextSize(12); b.setAllCaps(false);
        b.setTypeface(null, Typeface.BOLD); b.setPadding(dp(8), 0, dp(8), 0);
        b.setTextColor(primary ? (darkTheme ? Color.rgb(15, 32, 28) : Color.WHITE) : textColor);
        b.setBackground(rounded(primary ? accentColor : surfaceColor, primary ? 0 : borderColor, 14));
        return b;
    }
    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b); return p;
    }
    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }
    private String username() {
        String n = nameInput.getText().toString().trim();
        return n.isEmpty() ? "Пользователь" : n;
    }

    private void startServer() {
        if (server != null && server.running) { statusText.setText("Сервер уже работает: " + localIp() + ":" + PORT); return; }
        io.execute(() -> {
            try {
                LanHttpServer s = new LanHttpServer(this, PORT); server = s; s.start();
                runOnUiThread(() -> statusText.setText("Сервер запущен. IP: " + localIp() + ", порт " + PORT + ". Введите этот IP на втором телефоне."));
            } catch (Exception e) {
                runOnUiThread(() -> statusText.setText("Не удалось запустить сервер: " + e.getMessage()));
            }
        });
    }

    private String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!a.isLoopbackAddress() && a.getHostAddress().indexOf(':') < 0 && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        try {
            WifiManager wm = (WifiManager)getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            return Formatter.formatIpAddress(wm.getConnectionInfo().getIpAddress());
        } catch (Exception ignored) { return "не определён"; }
    }

    private void connectToServer() {
        String ip = ipInput.getText().toString().trim();
        if (ip.isEmpty()) { statusText.setText("Введите IP-адрес телефона, на котором запущен сервер."); return; }
        if (ip.startsWith("http://")) ip = ip.substring(7);
        if (ip.endsWith("/")) ip = ip.substring(0, ip.length()-1);
        final String base = ip.contains(":") ? "http://" + ip : "http://" + ip + ":" + PORT;
        io.execute(() -> {
            try {
                JSONObject result = request(base + "/health", "GET", null);
                if (!result.optBoolean("ok", false)) throw new Exception("сервер не подтвердил подключение");
                runOnUiThread(() -> {
                    ipInput.setText(base.replace("http://", ""));
                    statusText.setText("Подключено к " + base);
                    polling = true; handler.removeCallbacks(poller); handler.post(poller);
                });
            } catch (Exception e) {
                runOnUiThread(() -> statusText.setText("Не удалось подключиться. Проверьте IP, Wi-Fi и что сервер запущен. " + e.getMessage()));
            }
        });
    }

    private String baseUrl() {
        String ip = ipInput.getText().toString().trim();
        if (ip.isEmpty()) return "";
        if (ip.startsWith("http://")) return ip.indexOf(":", 7) >= 0 ? ip : ip + ":" + PORT;
        return ip.contains(":") ? "http://" + ip : "http://" + ip + ":" + PORT;
    }

    private void sendMessage() {
        String message = messageInput.getText().toString().trim();
        String sender = username();
        String base = baseUrl();
        if (message.isEmpty()) return;
        if (base.isEmpty()) { statusText.setText("Сначала введите IP сервера и нажмите «Подключиться»."); return; }
        messageInput.setText("");
        io.execute(() -> {
            try {
                JSONObject body = new JSONObject(); body.put("user", sender); body.put("text", message);
                request(base + "/send", "POST", body.toString());
                fetchMessages();
            } catch (Exception e) {
                runOnUiThread(() -> statusText.setText("Сообщение не отправлено: " + e.getMessage()));
            }
        });
    }

    private void fetchMessages() {
        String base = baseUrl(); if (base.isEmpty()) return;
        io.execute(() -> {
            try {
                JSONObject response = request(base + "/messages", "GET", null);
                JSONArray arr = response.optJSONArray("messages"); if (arr == null) return;
                StringBuilder out = new StringBuilder();
                for (int i=0; i<arr.length(); i++) {
                    JSONObject m = arr.getJSONObject(i);
                    out.append('[').append(m.optString("time", "")).append("] ")
                       .append(m.optString("user", "?" )).append(":\n")
                       .append(m.optString("text", "")).append("\n\n");
                }
                String rendered = out.length() == 0 ? "Пока сообщений нет. Отправьте первое сообщение." : out.toString();
                runOnUiThread(() -> {
                    if (!rendered.equals(lastRendered)) {
                        lastRendered = rendered; messagesText.setText(rendered);
                        messagesScroll.post(() -> messagesScroll.fullScroll(View.FOCUS_DOWN));
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> statusText.setText("Связь с сервером потеряна: " + e.getMessage()));
            }
        });
    }

    private JSONObject request(String urlString, String method, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL(urlString).openConnection();
        c.setRequestMethod(method); c.setConnectTimeout(2500); c.setReadTimeout(3000);
        c.setRequestProperty("Accept", "application/json");
        if (body != null) {
            c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream os = c.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        int code = c.getResponseCode();
        InputStream is = code >= 200 && code < 400 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (is != null) { byte[] buf = new byte[2048]; int n; while ((n=is.read(buf))!=-1) bos.write(buf,0,n); is.close(); }
        c.disconnect();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
        return new JSONObject(bos.toString("UTF-8"));
    }

    private void renderSavedLocalHistory() {
        String saved = getPreferences(MODE_PRIVATE).getString("last_history", "");
        if (!saved.isEmpty()) messagesText.setText(saved);
    }

    @Override protected void onDestroy() {
        polling = false; handler.removeCallbacks(poller);
        if (server != null) server.stop();
        io.shutdownNow(); super.onDestroy();
    }

    static class LanHttpServer {
        final Context context; final int port; volatile boolean running = false; ServerSocket socket;
        final Object lock = new Object();
        JSONArray messages;
        LanHttpServer(Context c, int p) {
            context = c.getApplicationContext(); port = p;
            String saved = context.getSharedPreferences("lan_messenger", MODE_PRIVATE).getString("messages", "[]");
            try { messages = new JSONArray(saved); } catch (Exception e) { messages = new JSONArray(); }
        }
        void start() throws Exception {
            socket = new ServerSocket(port, 20, InetAddress.getByName("0.0.0.0")); running = true;
            Thread t = new Thread(() -> {
                while (running) {
                    try { Socket client = socket.accept(); new Thread(() -> handle(client)).start(); }
                    catch (Exception e) { if (running) e.printStackTrace(); }
                }
            }, "lan-messenger-server"); t.setDaemon(true); t.start();
        }
        void stop() { running = false; try { if (socket != null) socket.close(); } catch (Exception ignored) {} }
        void handle(Socket client) {
            try (Socket s = client) {
                s.setSoTimeout(4000);
                InputStream in = s.getInputStream(); OutputStream out = s.getOutputStream();
                ByteArrayOutputStream head = new ByteArrayOutputStream(); int state=0, b;
                while ((b=in.read())!=-1) {
                    head.write(b);
                    if ((state==0 && b=='\r') || (state==2 && b=='\r')) state++;
                    else if ((state==1 && b=='\n') || (state==3 && b=='\n')) state++;
                    else state = (b=='\r') ? 1 : 0;
                    if (state==4) break;
                    if (head.size()>16384) throw new Exception("HTTP headers too large");
                }
                String headers = head.toString("ISO-8859-1"); String[] lines = headers.split("\r\n");
                if (lines.length==0) return;
                String[] first = lines[0].split(" "); if (first.length<2) return;
                String method=first[0], path=first[1]; int length=0;
                for (String line: lines) if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) length=Integer.parseInt(line.substring(line.indexOf(':')+1).trim());
                if (length<0 || length>100000) throw new Exception("Invalid body size");
                byte[] body = new byte[length]; int off=0; while(off<length) { int n=in.read(body,off,length-off); if(n<0) break; off+=n; }
                JSONObject result = new JSONObject(); int status=200;
                if ("GET".equals(method) && "/health".equals(path)) result.put("ok", true);
                else if ("GET".equals(method) && "/messages".equals(path)) { synchronized(lock) { result.put("messages", new JSONArray(messages.toString())); } }
                else if ("POST".equals(method) && "/send".equals(path)) {
                    JSONObject incoming = new JSONObject(new String(body, StandardCharsets.UTF_8));
                    String text = incoming.optString("text", "").trim(); String user = incoming.optString("user", "Пользователь").trim();
                    if (text.isEmpty()) { status=400; result.put("error", "empty message"); }
                    else {
                        JSONObject m = new JSONObject(); m.put("user", user.isEmpty()?"Пользователь":user); m.put("text", text);
                        m.put("time", new SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()).format(new Date()));
                        synchronized(lock) {
                            messages.put(m);
                            while(messages.length()>500) { JSONArray next=new JSONArray(); for(int i=1;i<messages.length();i++) next.put(messages.get(i)); messages=next; }
                            context.getSharedPreferences("lan_messenger", MODE_PRIVATE).edit().putString("messages", messages.toString()).apply();
                        }
                        result.put("ok", true);
                    }
                } else { status=404; result.put("error", "not found"); }
                byte[] payload=result.toString().getBytes(StandardCharsets.UTF_8);
                String statusText=status==200?"OK":status==400?"Bad Request":"Not Found";
                String response="HTTP/1.1 "+status+" "+statusText+"\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: "+payload.length+"\r\nConnection: close\r\nAccess-Control-Allow-Origin: *\r\n\r\n";
                out.write(response.getBytes(StandardCharsets.ISO_8859_1)); out.write(payload); out.flush();
            } catch (Exception ignored) { }
        }
    }
}
