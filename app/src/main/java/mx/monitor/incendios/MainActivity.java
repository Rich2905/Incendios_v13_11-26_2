package mx.monitor.incendios;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Monitor de Incendios Forestales.
 * El tablero (assets/index.html) se ejecuta en un WebView y descarga la
 * tarjeta semanal directamente de CONAFOR; no depende de ningún servidor intermedio.
 */
public class MainActivity extends Activity {

    private static final String START_URL = "file:///android_asset/index.html";
    private static final long REFRESH_AFTER_MS = 30L * 60L * 1000L; // 30 min

    private WebView web;
    private long lastRefresh = 0L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        // Permite que la página local (file://) consulte CONAFOR sin restricciones de origen.
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);

        web.addJavascriptInterface(new Bridge(), "AndroidApp");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("file".equals(u.getScheme())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) { }
                return true;
            }
        });

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(START_URL);
        }
        lastRefresh = SystemClock.elapsedRealtime();
    }

    @Override
    protected void onResume() {
        super.onResume();
        long now = SystemClock.elapsedRealtime();
        if (web != null && now - lastRefresh > REFRESH_AFTER_MS) {
            lastRefresh = now;
            web.evaluateJavascript("window.__appRefresh && window.__appRefresh(false)", null);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (web != null) web.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    private void toast(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    /** Funciones disponibles para la página como window.AndroidApp. */
    private class Bridge {

        /** Descarga una URL (respaldo si fetch falla en el WebView). Devuelve el texto o "ERR:motivo". */
        @JavascriptInterface
        public String httpGet(String url) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(30000);
                c.setReadTimeout(60000);
                c.setRequestProperty("Accept", "application/json, text/plain, */*");
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) MonitorIncendios/1.0");
                int code = c.getResponseCode();
                if (code < 200 || code >= 300) return "ERR:HTTP " + code;
                return new String(readAll(c.getInputStream()), StandardCharsets.UTF_8);
            } catch (Exception e) {
                return "ERR:" + e.getClass().getSimpleName() + (e.getMessage() != null ? " " + e.getMessage() : "");
            } finally {
                if (c != null) c.disconnect();
            }
        }

        @JavascriptInterface
        public void saveCache(String json) {
            try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), "datos.json"))) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public String loadCache() {
            File f = new File(getFilesDir(), "datos.json");
            if (!f.exists()) return "";
            try (FileInputStream in = new FileInputStream(f)) {
                return new String(readAll(in), StandardCharsets.UTF_8);
            } catch (Exception e) {
                return "";
            }
        }

        /** Guarda un archivo exportado (Excel, PDF, PPT, PNG) en Descargas y lo abre. Devuelve "OK" o el error. */
        @JavascriptInterface
        public String saveFile(String filename, String base64) {
            byte[] data;
            try {
                data = Base64.decode(base64, Base64.DEFAULT);
            } catch (Exception e) {
                return "datos inválidos";
            }
            String name = filename.replaceAll("[\\\\/:*?\"<>|]", "_");
            String mime = mimeFor(name);
            String err = "";
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Monitor Incendios");
                    ContentResolver r = getContentResolver();
                    Uri uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri != null) {
                        try (OutputStream out = r.openOutputStream(uri)) {
                            if (out != null) {
                                out.write(data);
                                toast("Guardado en Descargas/Monitor Incendios/" + name);
                                openFile(uri, mime);
                                return "OK";
                            }
                        }
                    }
                    err = "MediaStore sin respuesta";
                } catch (Exception e) {
                    err = e.getClass().getSimpleName() + " " + e.getMessage();
                }
            }
            try {
                File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null) dir = new File(getFilesDir(), "Descargas");
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, name);
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write(data);
                }
                toast("Guardado en " + f.getAbsolutePath());
                return "OK";
            } catch (Exception e) {
                return "Error al guardar (" + err + " / " + e.getMessage() + ")";
            }
        }
        /** Copia una imagen PNG al portapapeles (se guarda en Imágenes/Monitor Incendios). Devuelve "OK" o el error. */
        @JavascriptInterface
        public String copyImage(String filename, String base64) {
            if (Build.VERSION.SDK_INT < 29) return "copiar imágenes requiere Android 10 o superior; usa Guardar PNG";
            try {
                byte[] data = Base64.decode(base64, Base64.DEFAULT);
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, filename.replaceAll("[\\\\/:*?\"<>|]", "_"));
                v.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Monitor Incendios");
                ContentResolver r = getContentResolver();
                Uri uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (uri == null) return "no se pudo crear la imagen";
                try (OutputStream out = r.openOutputStream(uri)) {
                    if (out == null) return "no se pudo escribir la imagen";
                    out.write(data);
                }
                final Uri u = uri;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        if (cm != null) cm.setPrimaryClip(ClipData.newUri(getContentResolver(), "Imagen", u));
                    }
                });
                return "OK";
            } catch (Exception e) {
                return e.getClass().getSimpleName() + " " + e.getMessage();
            }
        }
    }

    private static String mimeFor(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (n.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    private void openFile(final Uri uri, final String mime) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(uri, mime);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception ignored) {
                    // Sin app para abrirlo: queda guardado en Descargas.
                }
            }
        });
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) != -1) buf.write(b, 0, n);
        in.close();
        return buf.toByteArray();
    }
}
