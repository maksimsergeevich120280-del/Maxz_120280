package com.maxz.lanmessenger;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Typeface;
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
    private final Runnable poller = new Runnable() {
        @Override public void run() {
            if (polling) fetchMessages();
            if (polling) handler.postDelayed(this, 2000);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        renderSavedLocalHistory();
        statusText.setText("Готово. Для двух телефонов подключите их к одной Wi-Fi-сети.");
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(12));
        root.setBackgroundColor(0xFFF4F6F8);

        TextView title = new TextView(this);
        title.setText("LAN Messenger"); title.setTextSize(25); title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(0xFF183153); root.addView(title);
        TextView subtitle = new TextView(this);
        subtitle.setText("Пробная версия · сообщения в локальной сети"); subtitle.setTextSize(13);
        subtitle.setTextColor(0xFF526173); root.addView(subtitle, lp(-1, -2, 0, 0, 0, dp(12)));

        nameInput = field("Ваше имя", "Пользователь"); root.addView(nameInput, lp(-1, dp(48), 0, 0, 0, dp(8)));
        ipInput = field("IP телефона-сервера, например 192.168.1.10", "");
        root.addView(ipInput, lp(-1, dp(48), 0, 0, 0, dp(8)));

        LinearLayout buttons = new LinearLayout(this); buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button start = button("Запустить сервер");
        Button connect = button("Подключиться");
        buttons.addView(start, new LinearLayout.LayoutParams(0, dp(48), 1));
        buttons.addView(connect, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(buttons, lp(-1, -2, 0, 0, 0, dp(8)));
        start.setOnClickListener(v -> startServer());
        connect.setOnClickListener(v -> connectToServer());

        statusText = new TextView(this); statusText.setTextSize(13); statusText.setTextColor(0xFF334155);
        root.addView(statusText, lp(-1, -2, 0, 0, 0, dp(8)));

        TextView historyTitle = new TextView(this); historyTitle.setText("Переписка"); historyTitle.setTextSize(17);
        historyTitle.setTypeface(null, Typeface.BOLD); historyTitle.setTextColor(0xFF183153);
        root.addView(historyTitle, lp(-1, -2, 0, 0, 0, dp(6)));
        messagesScroll = new ScrollView(this); messagesScroll.setFillViewport(true);
        messagesText = new TextView(this); messagesText.setTextSize(15); messagesText.setTextColor(0xFF172033);
        messagesText.setPadding(dp(10), dp(10), dp(10), dp(10));
        messagesText.setBackgroundColor(0xFFFFFFFF);
        messagesScroll.addView(messagesText);
        LinearLayout.LayoutParams historyLp = new LinearLayout.LayoutParams(-1, 0, 1);
        root.addView(messagesScroll, historyLp);

        messageInput = field("Напишите сообщение…", "");
        LinearLayout sendRow = new LinearLayout(this); sendRow.setOrientation(LinearLayout.HORIZONTAL);
        sendRow.addView(messageInput, new LinearLayout.LayoutParams(0, dp(50), 1));
        Button send = button("Отправить"); sendRow.addView(send, lp(dp(112), dp(50), dp(6), 0, 0, 0));
        root.addView(sendRow, lp(-1, -2, 0, dp(8), 0, 0));
        send.setOnClickListener(v -> sendMessage());
        setContentView(root);
    }

    private EditText field(String hint, String value) {
        EditText e = new EditText(this); e.setSingleLine(true); e.setTextSize(14); e.setHint(hint);
        e.setText(value); e.setPadding(dp(12), 0, dp(12), 0); e.setBackgroundColor(0xFFFFFFFF);
        return e;
    }
    private Button button(String text) {
        Button b = new Button(this); b.setText(text); b.setTextSize(12); b.setAllCaps(false); return b;
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
