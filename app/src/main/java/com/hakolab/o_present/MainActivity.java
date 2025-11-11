package com.hakolab.o_present;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.DownloadManager; // optional (nggak dipakai di flow ini, tapi biarin aja)
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private static final int PERM_REQ_CODE = 2001;

    // upload file
    private ValueCallback<Uri[]> filePathCallback;
    private ActivityResultLauncher<Intent> fileChooserLauncher;

    // ===== Bridge simpan file dari JS =====
    public class BlobBridge {
        @JavascriptInterface
        public void saveFile(String base64, String filename, String mime) {
            try {
                byte[] data = Base64.decode(base64, Base64.DEFAULT);
                File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!downloads.exists()) downloads.mkdirs();
                File out = new File(downloads, filename);
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    fos.write(data);
                }
                // scan biar muncul di Files/Galeri
                MediaScannerConnection.scanFile(
                        MainActivity.this,
                        new String[]{ out.getAbsolutePath() },
                        new String[]{ mime != null && !mime.isEmpty() ? mime : "*/*" },
                        null
                );
                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "Saved: " + filename, Toast.LENGTH_LONG).show()
                );
            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show()
                );
            }
        }
    }

    @SuppressLint({"SetJavaScriptEnabled"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);

        // Permission notifikasi (Android 13+) biar notif (kalau dipakai) tampil rapi
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPostNotificationsIfNeeded();
        }

        // ===== WebView Settings =====
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setDatabaseEnabled(true);
        ws.setLoadsImagesAutomatically(true);
        ws.setJavaScriptCanOpenWindowsAutomatically(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setGeolocationEnabled(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        // Cookie/session penting buat export protected
        CookieManager.getInstance().setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        }

        // Bridge untuk terima base64 dari JS dan simpan
        webView.addJavascriptInterface(new BlobBridge(), "AndroidBlob");

        // ===== File chooser (input type=file) =====
        fileChooserLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (filePathCallback != null) {
                        Uri[] resultUris = null;
                        if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                            if (result.getData().getClipData() != null) {
                                int count = result.getData().getClipData().getItemCount();
                                resultUris = new Uri[count];
                                for (int i = 0; i < count; i++) {
                                    resultUris[i] = result.getData().getClipData().getItemAt(i).getUri();
                                }
                            } else if (result.getData().getData() != null) {
                                resultUris = new Uri[]{result.getData().getData()};
                            }
                        }
                        filePathCallback.onReceiveValue(resultUris);
                        filePathCallback = null;
                    }
                });

        // ===== WebViewClient: handle blob: & inject smart export hook =====
        webView.setWebViewClient(new WebViewClient() {

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectSmartExportHook(); // <-- injeksi hook export tiap halaman ready
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                return handleSpecial(uri != null ? uri.toString() : null);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleSpecial(url);
            }

            private boolean handleSpecial(String url) {
                if (url == null) return false;
                if (url.startsWith("blob:")) {
                    // Ambil blob langsung dari JS
                    fetchBlobToDownload(
                            url,
                            "export.xlsx",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    );
                    return true;
                }
                return false;
            }
        });

        // ===== WebChromeClient: permission & <input type=file> & popup window =====
        webView.setWebChromeClient(new WebChromeClient() {

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }

            @Override
            public boolean onShowFileChooser(WebView webView,
                                             ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = filePathCallback;

                Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                Intent contentIntent = fileChooserParams.createIntent();

                Intent chooser = new Intent(Intent.ACTION_CHOOSER);
                chooser.putExtra(Intent.EXTRA_INTENT, contentIntent);
                chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cameraIntent});

                try {
                    fileChooserLauncher.launch(chooser);
                } catch (Exception e) {
                    MainActivity.this.filePathCallback = null;
                    return false;
                }
                return true;
            }

            // Kalau export buka di window/popup baru, kita tangkap URL-nya di sini
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
                WebView child = new WebView(view.getContext());
                child.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, String url) {
                        // Coba GET via hook JS (biar bawa cookie). Kalau gagal, hook akan fallback POST.
                        fetchUrlToFile(url, URLUtil.guessFileName(url, null, null));
                        v.destroy();
                        return true;
                    }
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(child);
                resultMsg.sendToTarget();
                return true;
            }
        });

        // ===== Muat halaman lu =====
        webView.loadUrl("https://hakolabdev.com/o-present/");
    }

    // ==== Helper: ambil blob: URL lewat JS, simpan via AndroidBlob ====
    private void fetchBlobToDownload(String blobUrl, String suggestedName, String mime) {
        String js =
                "(async function(){\n" +
                        "  try {\n" +
                        "    const res = await fetch('" + jsEscape(blobUrl) + "');\n" +
                        "    const blob = await res.blob();\n" +
                        "    const ab = await blob.arrayBuffer();\n" +
                        "    let binary=''; const bytes=new Uint8Array(ab); const chunk=0x8000;\n" +
                        "    for (let i=0;i<bytes.length;i+=chunk){ binary+=String.fromCharCode.apply(null, bytes.subarray(i,i+chunk)); }\n" +
                        "    const b64 = btoa(binary);\n" +
                        "    if (window.AndroidBlob && AndroidBlob.saveFile) AndroidBlob.saveFile(b64,'" + jsEscape(suggestedName) + "','" + jsEscape(mime) + "');\n" +
                        "  } catch(e){ console.error('blob fetch failed', e); }\n" +
                        "})();";
        webView.evaluateJavascript(js, null);
    }

    // ==== Helper: GET → kalau HTML/404, hook POST otomatis (di dalam halaman) ====
    private void fetchUrlToFile(String url, String suggestedName) {
        String safeName = (suggestedName == null || suggestedName.isEmpty()) ? "download.xlsx" : suggestedName;
        String js =
                "(async function(){\n" +
                        "  try {\n" +
                        "    const hdr={'Accept':'*/*','X-Requested-With':'XMLHttpRequest'};\n" +
                        "    let res = await fetch('" + jsEscape(url) + "', {method:'GET', credentials:'include', headers:hdr});\n" +
                        "    const ct = (res.headers.get('content-type')||'').toLowerCase();\n" +
                        "    if (!res.ok || ct.includes('text/html')) {\n" +
                        "      // fallback: kirim POST + semua field + CSRF\n" +
                        "      const fd = (function(){\n" +
                        "        function readCookie(n){ const m=document.cookie.match(new RegExp('(?:^|; )'+n.replace(/([.$?*|{}()\\[\\]\\\\\\/\\+^])/g,'\\\\$1')+'=([^;]*)')); return m? decodeURIComponent(m[1]):null; }\n" +
                        "        function findCsrf(){\n" +
                        "          const m=document.querySelector('meta[name=\"csrf-token\"],meta[name=\"X-CSRF-TOKEN\"],meta[name=\"x-csrf-token\"]');\n" +
                        "          if(m&&m.content) return {key:'_token', header:'X-CSRF-TOKEN', val:m.content};\n" +
                        "          const xsrf=readCookie('XSRF-TOKEN'); if(xsrf) return {key:'_token', header:'X-XSRF-TOKEN', val:xsrf};\n" +
                        "          const ci=readCookie('csrf_cookie_name')||readCookie('ci_csrf_token'); if(ci) return {key:'csrf_test_name', header:'X-CSRF-TOKEN', val:ci};\n" +
                        "          return null;\n" +
                        "        }\n" +
                        "        const fd=new FormData();\n" +
                        "        document.querySelectorAll('input,select,textarea').forEach(el=>{\n" +
                        "          if(!el.name) return;\n" +
                        "          if((el.type==='checkbox'||el.type==='radio')&&!el.checked) return;\n" +
                        "          if(el.type==='file' && el.files && el.files.length){ Array.from(el.files).forEach(f=>fd.append(el.name,f)); }\n" +
                        "          else fd.append(el.name, el.value);\n" +
                        "        });\n" +
                        "        const c=findCsrf(); if(c && !fd.has(c.key)) fd.append(c.key,c.val);\n" +
                        "        return {fd:fd, csrf:c};\n" +
                        "      })();\n" +
                        "      const headers={'Accept':'*/*','X-Requested-With':'XMLHttpRequest'}; if(fd.csrf&&fd.csrf.header) headers[fd.csrf.header]=fd.csrf.val;\n" +
                        "      res = await fetch('" + jsEscape(url) + "', {method:'POST', body:fd.fd, credentials:'include', headers});\n" +
                        "      if(!res.ok){ console.log('HTTP', res.status, '" + jsEscape(url) + "'); return; }\n" +
                        "    }\n" +
                        "    const blob = await res.blob();\n" +
                        "    const ab = await blob.arrayBuffer();\n" +
                        "    let binary=''; const bytes=new Uint8Array(ab); const chunk=0x8000;\n" +
                        "    for (let i=0;i<bytes.length;i+=chunk){ binary+=String.fromCharCode.apply(null, bytes.subarray(i,i+chunk)); }\n" +
                        "    const b64=btoa(binary);\n" +
                        "    const name='" + jsEscape(safeName) + "';\n" +
                        "    if (window.AndroidBlob && AndroidBlob.saveFile)\n" +
                        "      AndroidBlob.saveFile(b64, name, 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet');\n" +
                        "  } catch(e){ console.error('fetchUrlToFile error', e); }\n" +
                        "})();";
        webView.evaluateJavascript(js, null);
    }

    // ==== Injector: hook tombol/link export → GET→POST smart, simpan via bridge ====
    private void injectSmartExportHook() {
        String js =
                "(function(){\n" +
                        "  if(window.__smartExportHook) return; window.__smartExportHook=true;\n" +
                        "  const MIME='application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';\n" +
                        "  const SEL=['#btn-export-excel','[data-export=\"excel\"]','[data-export-excel]','a[href*=\"export\"]','a[href*=\"excel\"]','button[name*=\"export\"]','button[id*=\"export\"]','button[onclick*=\"export\"]'];\n" +
                        "  function ts(){return new Date().toISOString().replace(/[:T]/g,'-').slice(0,19)}\n" +
                        "  function guessName(el){ const base=(el && (el.getAttribute('download')||el.getAttribute('data-filename')||el.textContent||'')).toLowerCase().replace(/[^a-z0-9-_]+/g,'-'); return (base?base+'-':'rekap-presensi-')+ts()+'.xlsx'; }\n" +
                        "  function readCookie(n){ const m=document.cookie.match(new RegExp('(?:^|; )'+n.replace(/([.$?*|{}()\\[\\]\\\\\\/\\+^])/g,'\\\\$1')+'=([^;]*)')); return m?decodeURIComponent(m[1]):null; }\n" +
                        "  function findCsrf(){\n" +
                        "    const m=document.querySelector('meta[name=\"csrf-token\"],meta[name=\"X-CSRF-TOKEN\"],meta[name=\"x-csrf-token\"]');\n" +
                        "    if(m&&m.content) return {header:'X-CSRF-TOKEN', value:m.content, key:'_token'};\n" +
                        "    const xsrf=readCookie('XSRF-TOKEN'); if(xsrf) return {header:'X-XSRF-TOKEN', value:xsrf, key:'_token'};\n" +
                        "    const ci=readCookie('csrf_cookie_name')||readCookie('ci_csrf_token'); if(ci) return {header:'X-CSRF-TOKEN', value:ci, key:'csrf_test_name'};\n" +
                        "    return null;\n" +
                        "  }\n" +
                        "  function serialize(scope){\n" +
                        "    const fd=new FormData();\n" +
                        "    (scope||document).querySelectorAll('input,select,textarea').forEach(el=>{\n" +
                        "      if(!el.name) return;\n" +
                        "      if((el.type==='checkbox'||el.type==='radio')&&!el.checked) return;\n" +
                        "      if(el.type==='file' && el.files && el.files.length){ Array.from(el.files).forEach(f=>fd.append(el.name,f)); }\n" +
                        "      else fd.append(el.name, el.value);\n" +
                        "    });\n" +
                        "    const c=findCsrf(); if(c && !fd.has(c.key)) fd.append(c.key,c.value);\n" +
                        "    return {fd:fd, csrf:c};\n" +
                        "  }\n" +
                        "  async function asB64(blob){ const ab=await blob.arrayBuffer(); let b=''; const bytes=new Uint8Array(ab), chunk=0x8000; for(let i=0;i<bytes.length;i+=chunk){ b+=String.fromCharCode.apply(null, bytes.subarray(i,i+chunk)); } return btoa(b); }\n" +
                        "  async function doGET(url){ return fetch(url,{method:'GET',credentials:'include',headers:{'Accept':'*/*','X-Requested-With':'XMLHttpRequest'}}); }\n" +
                        "  async function doPOST(url,scope){ const s=serialize(scope); const h={'Accept':'*/*','X-Requested-With':'XMLHttpRequest'}; if(s.csrf&&s.csrf.header) h[s.csrf.header]=s.csrf.value; return fetch(url,{method:'POST',body:s.fd,credentials:'include',headers:h}); }\n" +
                        "  async function downloadSmart(el){\n" +
                        "    const href=el.getAttribute('href')||el.dataset.url||el.dataset.href||'';\n" +
                        "    let url=href||location.href; const form=el.closest?el.closest('form'):null; if(form&&form.getAttribute('action')) url=form.getAttribute('action');\n" +
                        "    const name=el.getAttribute('data-filename')||el.getAttribute('download')||guessName(el);\n" +
                        "    try{\n" +
                        "      let res=await doGET(url);\n" +
                        "      const ct=(res.headers.get('content-type')||'').toLowerCase();\n" +
                        "      if(!res.ok || ct.includes('text/html')){ res=await doPOST(url, form||document); }\n" +
                        "      if(!res.ok){ console.log('HTTP', res.status, url); return; }\n" +
                        "      const blob=await res.blob(); const b64=await asB64(blob);\n" +
                        "      if(window.AndroidBlob && AndroidBlob.saveFile) AndroidBlob.saveFile(b64, name, MIME);\n" +
                        "    }catch(e){ console.error('downloadSmart error', e); }\n" +
                        "  }\n" +
                        "  function looksExport(el){ const t=(el.textContent||'').toLowerCase(); const id=(el.id||'').toLowerCase(); const nm=(el.name||'').toLowerCase(); const href=(el.getAttribute&&el.getAttribute('href'))||''; const ds=el.dataset||{}; return href.includes('export')||href.includes('excel')||id.includes('export')||nm.includes('export')||ds.export==='excel'||('export' in ds)||t.includes('export')||t.includes('excel'); }\n" +
                        "  function hook(el){ if(el.__hooked) return; el.__hooked=true; el.addEventListener('click',function(e){ try{ if(!looksExport(e.currentTarget)) return; e.preventDefault(); downloadSmart(e.currentTarget); return false; }catch(err){console.error(err);} }, true); }\n" +
                        "  function scan(){ SEL.forEach(s=>document.querySelectorAll(s).forEach(hook)); document.querySelectorAll('a,button').forEach(el=>{ if(looksExport(el)) hook(el); }); }\n" +
                        "  scan(); const mo=new MutationObserver(scan); mo.observe(document.documentElement,{childList:true,subtree:true});\n" +
                        "})();";
        webView.evaluateJavascript(js, null);
    }

    private String jsEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    private void requestPostNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        PERM_REQ_CODE);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
