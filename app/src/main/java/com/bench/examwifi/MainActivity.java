// language: Java, file: MainActivity.java, target: Android API 26+
// phone B app — pairs with phone A via wireless ADB, pulls screen, sends to Groq vision

package com.bench.examwifi;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {

    private static final String GROQ_API_KEY = "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";
    private static final String GROQ_MODEL   = "meta-llama/llama-4-scout-17b-16e-instruct";
    private static final int    INTERVAL_MS  = 2500;

    private EditText  etIP;
    private EditText  etPort;
    private EditText  etPairPort;
    private EditText  etPairCode;
    private TextView  tvStatus;
    private TextView  tvAnswer;
    private ImageView ivPreview;
    private Button    btnPair;
    private Button    btnStart;
    private Button    btnStop;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler         handler  = new Handler(Looper.getMainLooper());
    private final AtomicBoolean   running  = new AtomicBoolean(false);

    // ADB connection state
    private String  adbHost;
    private int     adbPort;
    private boolean paired = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        etIP       = findViewById(R.id.etIP);
        etPort     = findViewById(R.id.etPort);
        etPairPort = findViewById(R.id.etPairPort);
        etPairCode = findViewById(R.id.etPairCode);
        tvStatus   = findViewById(R.id.tvStatus);
        tvAnswer   = findViewById(R.id.tvAnswer);
        ivPreview  = findViewById(R.id.ivPreview);
        btnPair    = findViewById(R.id.btnPair);
        btnStart   = findViewById(R.id.btnStart);
        btnStop    = findViewById(R.id.btnStop);

        btnPair.setOnClickListener(v  -> doPair());
        btnStart.setOnClickListener(v -> doStart());
        btnStop.setOnClickListener(v  -> doStop());
    }

    // ── PAIRING ───────────────────────────────────────────────────────────────

    private void doPair() {
        String ip       = etIP.getText().toString().trim();
        String pairPort = etPairPort.getText().toString().trim();
        String code     = etPairCode.getText().toString().trim();
        String adbPortS = etPort.getText().toString().trim();

        if (ip.isEmpty() || pairPort.isEmpty() || code.isEmpty() || adbPortS.isEmpty()) {
            setStatus("fill all fields", Color.RED); return;
        }

        adbHost = ip;
        adbPort = Integer.parseInt(adbPortS);

        setStatus("⏳ pairing...", Color.YELLOW);

        executor.submit(() -> {
            try {
                // ADB wireless pairing uses MDNS + TLS — we call adb via Runtime
                // since the Android SDK doesn't expose the pairing API directly
                Process p = Runtime.getRuntime().exec(new String[]{
                    "adb", "pair", ip + ":" + pairPort, code
                });
                p.waitFor();
                Scanner sc = new Scanner(p.getInputStream());
                StringBuilder out = new StringBuilder();
                while (sc.hasNextLine()) out.append(sc.nextLine()).append(" ");

                if (out.toString().contains("Successfully")) {
                    paired = true;
                    setStatus("✓ paired — now connect", Color.GREEN);
                    // now connect to the ADB port
                    connectAdb();
                } else {
                    // fallback: try direct connect without pairing (already paired before)
                    connectAdb();
                }
            } catch (Exception e) {
                // adb binary not available — try direct socket connect
                connectAdb();
            }
        });
    }

    private void connectAdb() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                "adb", "connect", adbHost + ":" + adbPort
            });
            p.waitFor();
            Scanner sc = new Scanner(p.getInputStream());
            StringBuilder out = new StringBuilder();
            while (sc.hasNextLine()) out.append(sc.nextLine()).append(" ");

            if (out.toString().contains("connected")) {
                paired = true;
                setStatus("✓ connected — tap Start", Color.GREEN);
            } else {
                paired = true; // try anyway
                setStatus("⚠ connect attempted — try Start", Color.YELLOW);
            }
        } catch (Exception e) {
            paired = true; // try anyway
            setStatus("⚠ adb cmd failed — try Start anyway", Color.YELLOW);
        }
    }

    // ── CAPTURE LOOP ──────────────────────────────────────────────────────────

    private void doStart() {
        if (running.get()) return;
        running.set(true);
        setStatus("● running...", Color.GREEN);
        scheduleCapture();
    }

    private void doStop() {
        running.set(false);
        setStatus("■ stopped", Color.GRAY);
    }

    private void scheduleCapture() {
        if (!running.get()) return;
        handler.postDelayed(() -> executor.submit(this::captureAndAsk), INTERVAL_MS);
    }

    private void captureAndAsk() {
        if (!running.get()) return;

        // pull screenshot from phone A via ADB exec-out
        byte[] png = pullScreen();

        if (png == null || png.length < 200) {
            setStatus("● no frame — check connection", Color.RED);
            scheduleCapture();
            return;
        }

        // decode and show preview
        Bitmap bmp = BitmapFactory.decodeByteArray(png, 0, png.length);
        if (bmp != null) handler.post(() -> ivPreview.setImageBitmap(bmp));

        // encode to base64 JPEG
        String b64 = toBase64Jpeg(bmp);
        int kb = b64.length() / 1024;
        setStatus("⏳ groq vision... (" + kb + "KB)", Color.YELLOW);

        String answer = askGroq(b64);
        setStatus("● done", Color.GREEN);
        setAnswer(answer);

        scheduleCapture();
    }

    // ── ADB SCREEN PULL ───────────────────────────────────────────────────────

    private byte[] pullScreen() {
        try {
            // try adb binary first
            Process p = Runtime.getRuntime().exec(new String[]{
                "adb", "-s", adbHost + ":" + adbPort,
                "exec-out", "screencap", "-p"
            });
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            InputStream is = p.getInputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
            p.waitFor();
            byte[] data = baos.toByteArray();
            if (data.length > 1000) return data;
        } catch (Exception ignored) {}

        // fallback: raw ADB socket protocol
        return pullScreenSocket();
    }

    private byte[] pullScreenSocket() {
        // ADB protocol: connect to host:port, send screencap command
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress(adbHost, adbPort), 5000);
            sock.setSoTimeout(8000);

            DataOutputStream out = new DataOutputStream(sock.getOutputStream());
            DataInputStream  in  = new DataInputStream(sock.getInputStream());

            // ADB CNXN message
            sendAdbConnect(out);
            readAdbPacket(in); // read CNXN response

            // OPEN shell:screencap -p
            sendAdbOpen(out, "shell:screencap -p");
            readAdbPacket(in); // OKAY

            // read DATA packets
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] pkt;
            while ((pkt = readDataPacket(in)) != null) {
                baos.write(pkt);
            }

            byte[] result = baos.toByteArray();
            return result.length > 1000 ? result : null;

        } catch (Exception e) {
            setStatus("● socket err: " + e.getMessage(), Color.RED);
            return null;
        }
    }

    // minimal ADB protocol implementation
    private static final int A_CNXN = 0x4e584e43;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_WRTE = 0x45545257;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_VERSION = 0x01000001;
    private static final int MAX_PAYLOAD = 4096;

    private void sendAdbConnect(DataOutputStream out) throws Exception {
        byte[] banner = "host::features=shell_v2".getBytes("UTF-8");
        writePacket(out, A_CNXN, A_VERSION, MAX_PAYLOAD, banner);
    }

    private void sendAdbOpen(DataOutputStream out, String service) throws Exception {
        byte[] svcBytes = (service + "\0").getBytes("UTF-8");
        writePacket(out, A_OPEN, 1, 0, svcBytes);
    }

    private void writePacket(DataOutputStream out, int cmd, int arg0, int arg1, byte[] data) throws Exception {
        int dataLen = data != null ? data.length : 0;
        int dataCrc = 0;
        if (data != null) for (byte b : data) dataCrc += (b & 0xFF);

        out.writeInt(Integer.reverseBytes(cmd));
        out.writeInt(Integer.reverseBytes(arg0));
        out.writeInt(Integer.reverseBytes(arg1));
        out.writeInt(Integer.reverseBytes(dataLen));
        out.writeInt(Integer.reverseBytes(dataCrc));
        out.writeInt(Integer.reverseBytes(cmd ^ 0xFFFFFFFF));
        if (data != null) out.write(data);
        out.flush();
    }

    private byte[] readAdbPacket(DataInputStream in) throws Exception {
        byte[] header = new byte[24];
        in.readFully(header);
        int dataLen = java.nio.ByteBuffer.wrap(header, 12, 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
        if (dataLen > 0) {
            byte[] data = new byte[dataLen];
            in.readFully(data);
            return data;
        }
        return new byte[0];
    }

    private byte[] readDataPacket(DataInputStream in) throws Exception {
        byte[] header = new byte[24];
        in.readFully(header);
        int cmd = java.nio.ByteBuffer.wrap(header, 0, 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
        int dataLen = java.nio.ByteBuffer.wrap(header, 12, 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();

        if (cmd == A_CLSE) return null;

        if (dataLen > 0) {
            byte[] data = new byte[dataLen];
            in.readFully(data);
            return data;
        }
        return new byte[0];
    }

    // ── IMAGE ─────────────────────────────────────────────────────────────────

    private String toBase64Jpeg(Bitmap bmp) {
        if (bmp == null) return "";
        Bitmap scaled = Bitmap.createScaledBitmap(bmp, bmp.getWidth()/2, bmp.getHeight()/2, true);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG, 60, baos);
        scaled.recycle();
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
    }

    // ── GROQ VISION ───────────────────────────────────────────────────────────

    private String askGroq(String b64Jpeg) {
        try {
            URL url = new URL("https://api.groq.com/openai/v1/chat/completions");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + GROQ_API_KEY);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setDoOutput(true);

            JSONObject imgPart = new JSONObject()
                .put("type", "image_url")
                .put("image_url", new JSONObject()
                    .put("url", "data:image/jpeg;base64," + b64Jpeg));
            JSONObject txtPart = new JSONObject()
                .put("type", "text")
                .put("text",
                    "Screenshot of an exam app. Find the question. " +
                    "MCQ: correct option letter + max 8 word reason. " +
                    "Descriptive: 1-2 sentence answer. " +
                    "No question yet: say 'waiting...'. No preamble.");

            JSONObject body = new JSONObject()
                .put("model", GROQ_MODEL)
                .put("max_tokens", 200)
                .put("temperature", 0.1)
                .put("messages", new JSONArray()
                    .put(new JSONObject().put("role","system")
                        .put("content","Silent exam assistant. Answer only."))
                    .put(new JSONObject().put("role","user")
                        .put("content", new JSONArray().put(imgPart).put(txtPart))));

            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.close();

            int code = conn.getResponseCode();
            InputStream stream = code == 200 ? conn.getInputStream() : conn.getErrorStream();
            Scanner sc = new Scanner(stream, "UTF-8");
            StringBuilder resp = new StringBuilder();
            while (sc.hasNextLine()) resp.append(sc.nextLine());
            sc.close();

            if (code != 200)
                return "API " + code + ": " + resp.toString().substring(0, Math.min(80, resp.length()));

            return new JSONObject(resp.toString())
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim();

        } catch (Exception e) {
            return "err: " + e.getMessage();
        }
    }

    // ── UI ────────────────────────────────────────────────────────────────────

    private void setStatus(String s, int c) {
        handler.post(() -> { tvStatus.setText(s); tvStatus.setTextColor(c); });
    }
    private void setAnswer(String s) {
        handler.post(() -> tvAnswer.setText(s));
    }
}
