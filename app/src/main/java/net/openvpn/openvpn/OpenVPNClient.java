package net.openvpn.openvpn;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.text.method.PasswordTransformationMethod;
import android.text.method.SingleLineTransformationMethod;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnTouchListener;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.webkit.DownloadListener;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.TextView.OnEditorActionListener;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.app.ActivityCompat.OnRequestPermissionsResultCallback;
import androidx.core.content.ContextCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.lingala.zip4j.core.ZipFile;
import net.openvpn.openvpn.OpenVPNService.Challenge;
import net.openvpn.openvpn.OpenVPNService.ConnectionStats;
import net.openvpn.openvpn.OpenVPNService.EventMsg;
import net.openvpn.openvpn.OpenVPNService.Profile;
import net.openvpn.openvpn.OpenVPNService.ProfileList;
import org.json.JSONObject;

public class OpenVPNClient extends OpenVPNClientBase implements OnRequestPermissionsResultCallback, OnClickListener, OnTouchListener, OnItemSelectedListener, OnEditorActionListener {
    private static final int REQUEST_IMPORT_PKCS12 = 3;
    private static final int REQUEST_IMPORT_PROFILE = 2;
    private static final int REQUEST_VPN_ACTOR_RIGHTS = 1;
    private static final boolean RETAIN_AUTH = false;
    private static final int S_BIND_CALLED = 1;
    private static final int S_ONSTART_CALLED = 2;
    private static final String TAG = "OpenVPNClient";
    private static final int UIF_PROFILE_SETTING_FROM_SPINNER = 262144;
    private static final int UIF_REFLECTED = 131072;
    private static final int UIF_RESET = 65536;
    private static final boolean UI_OVERLOADED = false;

    private String autostart_profile_name;
    private View button_group;
    private TextView bytes_in_view;
    private TextView bytes_out_view;
    private TextView challenge_view;
    private View conn_details_group;
    private Button connect_button;
    private View cr_group;
    private FinishOnConnect delayed_finish_on_connect = FinishOnConnect.DISABLED;
    private TextView details_more_less;
    private Button disconnect_button;
    private TextView duration_view;
    private FinishOnConnect finish_on_connect = FinishOnConnect.DISABLED;
    private View info_group;
    private boolean last_active = RETAIN_AUTH;
    private TextView last_pkt_recv_view;
    private ScrollView main_scroll_view;
    private EditText password_edit;
    private View password_group;
    private CheckBox password_save_checkbox;
    private EditText pk_password_edit;
    private View pk_password_group;
    private CheckBox pk_password_save_checkbox;
    private View post_import_help_blurb;
    private PrefUtil prefs;
    private ImageButton profile_edit;
    private View profile_group;
    private Spinner profile_spin;
    private ProgressBar progress_bar;
    private ImageButton proxy_edit;
    private View proxy_group;
    private Spinner proxy_spin;
    private PasswordUtil pwds;
    private EditText response_edit;
    private View server_group;
    private Spinner server_spin;
    private int startup_state = 0;
    private View stats_expansion_group;
    private View stats_group;
    
    private final Handler stats_timer_handler = new Handler(Looper.getMainLooper());
    private final Runnable stats_timer_task = new Runnable() {
        public void run() {
            OpenVPNClient.this.show_stats();
            OpenVPNClient.this.schedule_stats();
        }
    };
    
    private ImageView status_icon_view;
    private TextView status_view;
    private boolean stop_service_on_client_exit = RETAIN_AUTH;
    private View[] textgroups;
    private TextView[] textviews;
    
    private final Handler ui_reset_timer_handler = new Handler(Looper.getMainLooper());
    private final Runnable ui_reset_timer_task = new Runnable() {
        public void run() {
            if (!OpenVPNClient.this.is_active()) {
                OpenVPNClient.this.ui_setup(OpenVPNClient.RETAIN_AUTH, OpenVPNClient.UIF_RESET, null);
            }
        }
    };
    
    private EditText username_edit;
    private View username_group;

    private enum FinishOnConnect {
        DISABLED,
        ENABLED,
        ENABLED_ACROSS_ONSTART,
        PENDING
    }

    private enum ProfileSource {
        UNDEF,
        SERVICE,
        PRIORITY,
        PREFERENCES,
        SPINNER,
        LIST0
    }

    private SharedPreferences pref;
    private SharedPreferences.Editor editor;
    private UpdateManager updateManager;

    public static final String ZIP_PASSWORD = "myvpn123";

    public static class Constant {
        public static String getCheckUpdateUrl() {
            return "https://raw.githubusercontent.com/Mandrein1313/vpn-updates/main/update.txt?t=" + System.currentTimeMillis();
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        Log.d(TAG, String.format("CLI: onCreate intent=%s", intent != null ? intent.toString() : "null"));
        
        this.prefs = new PrefUtil(PreferenceManager.getDefaultSharedPreferences(this));
        this.pwds = new PasswordUtil(PreferenceManager.getDefaultSharedPreferences(this));
        pref = PreferenceManager.getDefaultSharedPreferences(this);
        editor = pref.edit();
        
        if (this.prefs.get_boolean("ui_dark_theme", RETAIN_AUTH)) {
            setCurrentTheme(com.google.android.material.R.style.Theme_MaterialComponents_NoActionBar);
        } else {
            setCurrentTheme(com.google.android.material.R.style.Theme_MaterialComponents_Light_NoActionBar);
        }

        setContentView(R.layout.form);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            android.view.Window window = getWindow();
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            window.setStatusBarColor(android.graphics.Color.BLACK);
        }

        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        load_ui_elements();
        
        updateManager = new UpdateManager(this);
        updateManager.checkUpdateAuto();

        doBindService();
        warn_app_expiration(this.prefs);
        new AppRate(this).setMinDaysUntilPrompt(14).setMinLaunchesUntilPrompt(10).init();
    }

    public class UpdateManager {

        private final OpenVPNClient activity;
        private final SharedPreferences preference;
        private final SharedPreferences.Editor upEditor;
        private final WebView webView;
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        private boolean isSilentCheck = false;
        private String pendingVersionJson = null;

        public UpdateManager(OpenVPNClient activity) {
            this.activity = activity;
            this.preference = PreferenceManager.getDefaultSharedPreferences(activity);
            this.upEditor = preference.edit();

            webView = new WebView(activity);
            webView.getSettings().setJavaScriptEnabled(true);
            webView.setWebViewClient(new WebViewClient());
            webView.setWebChromeClient(new WebChromeClient());
            webView.setDownloadListener(new ConfigDownloadListener());
            webView.setVisibility(View.GONE);

            activity.getWindow().addContentView(webView, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));

            File versionFile = new File(activity.getFilesDir(), "Version.txt");
            if (!versionFile.exists()) {
                initDefaultVersionFile();
            }
        }

        public void checkUpdateAuto() {
            this.isSilentCheck = true;
            startCheckUpdateTask();
        }

        public void checkUpdateManual() {
            this.isSilentCheck = false;
            startCheckUpdateTask();
        }
        
        public void forceCheckUpdate() {
            File versionFile = new File(activity.getFilesDir(), "Version.txt");
            if (versionFile.exists()) {
                versionFile.delete();
            }
            this.isSilentCheck = false;
            startCheckUpdateTask();
        }

        public void shutdown() {
            if (executor != null && !executor.isShutdown()) {
                executor.shutdown();
            }
        }

        private void startCheckUpdateTask() {
            executor.execute(() -> {
                JSONObject result = null;
                HttpURLConnection conn = null;
                try {
                    URL url = new URL(Constant.getCheckUpdateUrl());
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    conn.connect();

                    StringBuilder builder = new StringBuilder();
                    try (Reader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        char[] buffer = new char[1024];
                        int read;
                        while ((read = reader.read(buffer, 0, buffer.length)) > 0) {
                            builder.append(buffer, 0, read);
                        }
                    }
                    result = new JSONObject(builder.toString());
                } catch (Exception e) {
                    Log.e(TAG, "Check update failed", e);
                } finally {
                    if (conn != null) conn.disconnect();
                }

                final JSONObject finalResult = result;
                mainHandler.post(() -> onCheckUpdateFinished(finalResult));
            });
        }

        private void onCheckUpdateFinished(JSONObject result) {
            if (result == null || activity.isFinishing() || activity.isDestroyed()) return;

            try {
                File versionFile = new File(activity.getFilesDir(), "Version.txt");
                String currentVersion = "0.0";

                if (versionFile.exists()) {
                    JSONObject localJson = new JSONObject(readFileToString(versionFile));
                    currentVersion = localJson.optString("Version", "0.0");
                }

                String serverVersion = result.optString("Version", "0.0");
                String downloadUrl = result.optString("Url", "");
                String changelog = result.optString("Changelog", "");

                if (isNewVersionAvailable(serverVersion, currentVersion)) {
                    showUpdateDialog(activity, serverVersion, changelog, downloadUrl, result.toString());
                } else {
                    if (!isSilentCheck) {
                        showAlreadyLatestToast();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error handling update response", e);
            }
        }

        private void showAlreadyLatestToast() {
            if (!activity.isFinishing() && !activity.isDestroyed()) {
                Toast.makeText(activity, "เซิร์ฟเวอร์เป็นเวอร์ชั่นล่าสุดแล้ว", Toast.LENGTH_LONG).show();
            }
        }

        private void showUpdateDialog(Context context, String version, String changelog, String downloadUrl, String versionJson) {
            this.pendingVersionJson = versionJson;

            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            View view = LayoutInflater.from(context).inflate(R.layout.dialog_update, null);
            builder.setView(view);
            builder.setCancelable(false);

            AlertDialog dialog = builder.create();
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            }

            TextView tvTitle = view.findViewById(R.id.tv_title);
            TextView tvVersion = view.findViewById(R.id.tv_version);
            TextView tvChangelog = view.findViewById(R.id.tv_changelog);
            TextView tvStatus = view.findViewById(R.id.tv_status);
            ProgressBar progressUpdate = view.findViewById(R.id.progress_update);
            Button btnCancel = view.findViewById(R.id.btn_cancel);
            Button btnUpdate = view.findViewById(R.id.btn_update);

            if (tvTitle != null) tvTitle.setText("พบอัปเดตใหม่");
            if (tvVersion != null) tvVersion.setText("เวอร์ชัน " + version);
            if (tvChangelog != null) tvChangelog.setText(changelog != null ? changelog : "");

            if (btnCancel != null) {
                btnCancel.setOnClickListener(v -> {
                    this.pendingVersionJson = null;
                    dialog.dismiss();
                });
            }

            if (btnUpdate != null) {
                btnUpdate.setOnClickListener(v -> {
                    if (tvTitle != null) tvTitle.setText("กำลังอัปเดตเซิร์ฟเวอร์");
                    if (tvStatus != null) {
                        tvStatus.setVisibility(View.VISIBLE);
                        tvStatus.setText("กรุณารอสักครู่...");
                    }
                    if (progressUpdate != null) progressUpdate.setVisibility(View.VISIBLE);
                    if (btnCancel != null) btnCancel.setEnabled(false);
                    if (btnUpdate != null) {
                        btnUpdate.setEnabled(false);
                        btnUpdate.setText("กำลังโหลด...");
                    }

                    startDownloadZipWithDialog(downloadUrl, dialog, tvTitle, tvStatus, progressUpdate, btnCancel, btnUpdate);
                });
            }

            dialog.show();
        }

        private void initDefaultVersionFile() {
            try {
                File file = new File(activity.getFilesDir(), "Version.txt");
                JSONObject json = new JSONObject();
                json.put("Version", "1.0");

                saveStringToFile(file, json.toString());
                upEditor.putBoolean("isFirstRun", true).apply();
            } catch (Exception e) {
                Log.e(TAG, "Failed to init default version file", e);
            }
        }

        private boolean isNewVersionAvailable(String newVersion, String currentVersion) {
            if (newVersion == null || newVersion.trim().isEmpty()) return false;
            if (currentVersion == null || currentVersion.trim().isEmpty()) return true;

            String[] newParts = newVersion.trim().split("\\.");
            String[] currentParts = currentVersion.trim().split("\\.");
            int length = Math.max(newParts.length, currentParts.length);

            for (int i = 0; i < length; i++) {
                int v1 = i < newParts.length ? parseVersionPart(newParts[i]) : 0;
                int v2 = i < currentParts.length ? parseVersionPart(currentParts[i]) : 0;
                if (v1 < v2) return false;
                if (v1 > v2) return true;
            }
            return false;
        }

        private int parseVersionPart(String part) {
            try {
                String numberOnly = part.replaceAll("[^0-9]", "");
                return numberOnly.isEmpty() ? 0 : Integer.parseInt(numberOnly);
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        public String readFileToString(File file) {
            StringBuilder builder = new StringBuilder();
            try (InputStream in = new FileInputStream(file);
                 Reader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                char[] buffer = new char[1024];
                int read;
                while ((read = reader.read(buffer, 0, buffer.length)) > 0) {
                    builder.append(buffer, 0, read);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error reading file", e);
            }
            return builder.toString();
        }

        private void saveStringToFile(File file, String content) throws Exception {
            try (OutputStream out = new FileOutputStream(file)) {
                out.write(content.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        }

        private void showToast(String message) {
            if (!activity.isFinishing() && !activity.isDestroyed()) {
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
            }
        }

        private class ConfigDownloadListener implements DownloadListener {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimetype, long contentLength) {
                startDownloadZip(url);
            }
        }

        private void startDownloadZip(String downloadUrl) {
            ProgressDialog progressDialog = new ProgressDialog(activity);
            progressDialog.setTitle("กำลังอัพเดทเซิร์ฟเวอร์");
            progressDialog.setMessage("กรุณารอสักครู่...");
            progressDialog.setCancelable(false);
            if (!activity.isFinishing() && !activity.isDestroyed()) progressDialog.show();

            executor.execute(() -> {
                File zipFile = null;
                HttpURLConnection conn = null;
                try {
                    URL url = new URL(downloadUrl);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);
                    conn.connect();

                    zipFile = new File(activity.getFilesDir(), "Configs.zip");
                    try (InputStream in = conn.getInputStream();
                         OutputStream out = new FileOutputStream(zipFile)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = in.read(buffer, 0, buffer.length)) > 0) {
                            out.write(buffer, 0, read);
                        }
                        out.flush();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Download zip failed", e);
                    zipFile = null;
                } finally {
                    if (conn != null) conn.disconnect();
                }

                final File finalZipFile = zipFile;
                mainHandler.post(() -> {
                    if (progressDialog.isShowing() && !activity.isFinishing() && !activity.isDestroyed()) {
                        progressDialog.dismiss();
                    }
                    onZipDownloaded(finalZipFile);
                });
            });
        }

// เปลี่ยนจาก ProgressDialog เป็น ProgressBar แบบเต็ม
private void startDownloadZipWithDialog(String downloadUrl, AlertDialog dialog,
                                        TextView tvTitle, TextView tvStatus,
                                        ProgressBar progressUpdate,
                                        Button btnCancel, Button btnUpdate) {
    executor.execute(() -> {
        File zipFile = null;
        HttpURLConnection conn = null;
        boolean isSuccessExtracted = false;
        String errorMessage = null;
        int totalBytes = 0;

        try {
            URL url = new URL(downloadUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile)");
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.connect();

            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_MOVED_PERM || responseCode == HttpURLConnection.HTTP_MOVED_TEMP) {
                String newUrl = conn.getHeaderField("Location");
                conn.disconnect();
                url = new URL(newUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile)");
                conn.connect();
                responseCode = conn.getResponseCode();
            }

            if (responseCode == HttpURLConnection.HTTP_OK) {
                // ตรวจสอบ Content-Length เพื่อความแม่นยำ
                totalBytes = conn.getContentLength();
                Log.d("Update", "Total file size: " + totalBytes + " bytes");

                zipFile = new File(activity.getFilesDir(), "Configs.zip");
                try (InputStream in = conn.getInputStream();
                     OutputStream out = new FileOutputStream(zipFile)) {
                    
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    long totalRead = 0;
                    int lastProgress = 0;

                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                        totalRead += bytesRead;

                        // อัพเดท UI ทุก 500ms (เพื่อความ smooth)
                        if (System.currentTimeMillis() % 500 < 50) {
                            final int progress = (totalBytes > 0) ? (int) ((totalRead * 100) / totalBytes) : 0;
                            final int finalProgress = progress;
                            mainHandler.post(() -> {
                                if (progressUpdate != null) {
                                    progressUpdate.setProgress(progress);
                                    if (tvStatus != null) {
                                        tvStatus.setText("กำลังดาวน์โหลด... " + finalProgress + "%");
                                    }
                                }
                            });
                        }
                    }
                    out.flush();
                }

                cleanOldFiles();
                ZipFile zip = new ZipFile(zipFile);
                if (zip.isEncrypted()) {
                    zip.setPassword(OpenVPNClient.ZIP_PASSWORD);
                }
                zip.extractAll(activity.getFilesDir().getAbsolutePath());
                if (zipFile.exists()) zipFile.delete();

                renameOvpnFilesToEncoded();

                if (pendingVersionJson != null) {
                    try {
                        File file = new File(activity.getFilesDir(), "Version.txt");
                        saveStringToFile(file, pendingVersionJson);
                    } catch (Exception e) {
                        Log.e(TAG, "Save version failed", e);
                    }
                    pendingVersionJson = null;
                }

                isSuccessExtracted = true;
            } else {
                errorMessage = "HTTP Error: " + responseCode;
            }
        } catch (Exception e) {
            errorMessage = e.getMessage();
            isSuccessExtracted = false;
        } finally {
            if (conn != null) conn.disconnect();
        }

        final boolean success = isSuccessExtracted;
        final String errorText = errorMessage;
        final int finalTotal = totalBytes;

        mainHandler.post(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;

            if (!success) {
                if (tvTitle != null) tvTitle.setText("อัปเดตล้มเหลว");
                if (tvStatus != null) tvStatus.setText(errorText != null ? errorText : "ดาวน์โหลดไม่สำเร็จ");
                if (progressUpdate != null) progressUpdate.setVisibility(View.GONE);
                if (btnCancel != null) {
                    btnCancel.setEnabled(true);
                    btnCancel.setText("ปิด");
                    btnCancel.setOnClickListener(v -> dialog.dismiss());
                }
                if (btnUpdate != null) btnUpdate.setVisibility(View.GONE);
                return;
            }

            refreshProfilesInApp();

            if (tvTitle != null) tvTitle.setText("อัปเดตสำเร็จ");
            if (tvStatus != null) tvStatus.setText("โปรไฟล์ถูกอัปเดตเรียบร้อยแล้ว");
            if (progressUpdate != null) progressUpdate.setVisibility(View.GONE);
            if (btnCancel != null) {
                btnCancel.setEnabled(true);
                btnCancel.setText("ปิด");
                btnCancel.setOnClickListener(v -> dialog.dismiss());
            }
            if (btnUpdate != null) btnUpdate.setVisibility(View.GONE);
        });
    });
}
        private void onZipDownloaded(File zipFile) {
            if (zipFile == null || !zipFile.exists()) {
                showToast("ดาวน์โหลดไฟล์ล้มเหลว");
                return;
            }

            executor.execute(() -> {
                boolean success = false;
                try {
                    cleanOldFiles();
                    ZipFile zip = new ZipFile(zipFile);
                    if (zip.isEncrypted()) {
                        zip.setPassword(OpenVPNClient.ZIP_PASSWORD);
                    }
                    zip.extractAll(activity.getFilesDir().getAbsolutePath());
                    if (zipFile.exists()) {
                        zipFile.delete();
                    }

                    renameOvpnFilesToEncoded();

                    if (pendingVersionJson != null) {
                        try {
                            File file = new File(activity.getFilesDir(), "Version.txt");
                            saveStringToFile(file, pendingVersionJson);
                        } catch (Exception e) {
                            Log.e(TAG, "Save version failed", e);
                        }
                        pendingVersionJson = null;
                    }
                    success = true;
                } catch (Exception e) {
                    Log.e(TAG, "Zip extract failed", e);
                }

                final boolean isSuccess = success;
                mainHandler.post(() -> {
                    if (isSuccess) {
                        showSuccessAndRestartDialog();
                    } else {
                        showToast("เกิดข้อผิดพลาดในการแตกไฟล์โปรไฟล์");
                    }
                });
            });
        }

        private void renameOvpnFilesToEncoded() {
            File dir = activity.getFilesDir();
            File[] files = dir.listFiles();
            if (files == null) return;

            for (File file : files) {
                String name = file.getName();
                if (!name.toLowerCase().endsWith(".ovpn")) continue;

                String baseName = name.substring(0, name.length() - 5);

                try {
                    String encoded = java.net.URLEncoder.encode(baseName, "UTF-8") + ".ovpn";
                    if (!encoded.equals(name)) {
                        File newFile = new File(dir, encoded);
                        if (!newFile.exists()) {
                            file.renameTo(newFile);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Rename ovpn failed: " + name, e);
                }
            }
        }

        private void cleanOldFiles() {
            File dir = activity.getFilesDir();
            if (dir.isDirectory()) {
                String[] children = dir.list();
                if (children != null) {
                    for (String child : children) {
                        if (!child.contains("txt") && !child.contains("zip")) {
                            new File(dir, child).delete();
                        }
                    }
                }
            }
        }

        private void showSuccessAndRestartDialog() {
            if (activity.isFinishing() || activity.isDestroyed()) return;

            refreshProfilesInApp();

            new AlertDialog.Builder(activity)
                    .setTitle("อัปเดตเซิร์ฟเวอร์สำเร็จ")
                    .setMessage("โปรไฟล์ถูกอัปเดตแล้ว")
                    .setCancelable(true)
                    .setPositiveButton("ตกลง", (dialog, which) -> dialog.dismiss())
                    .show();
        }
        
        private void refreshProfilesInApp() {
            try {
                File dir = activity.getFilesDir();
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        String name = f.getName().toLowerCase();
                        if (name.endsWith(".ovpn")) {
                            activity.submitImportProfileViaPathIntent(f.getAbsolutePath());
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "refreshProfilesInApp failed", e);
            }

            activity.runOnUiThread(() -> {
                try {
                    activity.gen_ui_reset_event(true);
                    activity.ui_setup(activity.is_active(), OpenVPNClient.UIF_RESET, null);
                } catch (Exception e) {
                    Log.e(TAG, "UI refresh failed", e);
                }
            });
        }
    }

    private void setCurrentTheme(int resId) {
        setTheme(resId);
    }

    public void createConnectShortcut(String prof_name, String shortcutName) {
        Intent shortcutIntent = new Intent(this, OpenVPNClient.class);
        shortcutIntent.putExtra("net.openvpn.openvpn.AUTOSTART_PROFILE_NAME", prof_name);
        shortcutIntent.setAction(Intent.ACTION_MAIN);

        Intent addIntent = new Intent();
        addIntent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, shortcutIntent);
        addIntent.putExtra(Intent.EXTRA_SHORTCUT_NAME, shortcutName);
        addIntent.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE,
                Intent.ShortcutIconResource.fromContext(this, R.drawable.icon));
        addIntent.setAction("com.android.launcher.action.INSTALL_SHORTCUT");
        sendBroadcast(addIntent);
    }

    private void ok_dialog(String title, String message, Runnable onDismiss) {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    if (onDismiss != null) {
                        onDismiss.run();
                    }
                })
                .show();
    }

    protected void ok_dialog(String title, String message) {
        ok_dialog(title, message, null);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Log.d(TAG, String.format("CLI: onNewIntent intent=%s", intent != null ? intent.toString() : "null"));
        setIntent(intent);
    }

    @Override
    protected void post_bind() {
        Log.d(TAG, "CLI: post bind");
        this.startup_state |= S_BIND_CALLED;
        process_autostart_intent(is_active());
        render_last_event();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.menu, menu);
        return true;
    }

    @Override
    public void event(EventMsg ev) {
        render_event(ev, RETAIN_AUTH, is_active(), RETAIN_AUTH);
    }

    private void render_last_event() {
        boolean active = is_active();
        EventMsg ev = get_last_event();
        if (ev != null) {
            render_event(ev, true, active, true);
        } else if (n_profiles_loaded() > 0) {
            render_event(EventMsg.disconnected(), true, active, true);
        } else {
            hide_status();
            ui_setup(active, UIF_RESET, null);
            show_progress(0, active);
        }
        EventMsg pev = get_last_event_prof_manage();
        if (pev != null) {
            render_event(pev, true, active, true);
        }
    }

    private boolean show_conn_info_field(String text, int field_id, int row_id) {
        int i = 0;
        boolean vis = text != null && text.length() > 0;
        TextView tv = (TextView) findViewById(field_id);
        View row = findViewById(row_id);
        if (tv != null) tv.setText(text);
        if (!vis) {
            i = 8;
        }
        if (row != null) row.setVisibility(i);
        return vis;
    }

    private void reset_conn_info() {
        show_conn_info(new ClientAPI_ConnectionInfo());
    }

    private void show_conn_info(ClientAPI_ConnectionInfo ci) {
        if (this.info_group != null) {
            this.info_group.setVisibility((((((((RETAIN_AUTH | show_conn_info_field(ci.getVpnIp4(), R.id.ipv4_addr, R.id.ipv4_addr_row)) | show_conn_info_field(ci.getVpnIp6(), R.id.ipv6_addr, R.id.ipv6_addr_row)) | show_conn_info_field(ci.getUser(), R.id.user, R.id.user_row)) | show_conn_info_field(ci.getClientIp(), R.id.client_ip, R.id.client_ip_row)) | show_conn_info_field(ci.getServerHost(), R.id.server_host, R.id.server_host_row)) | show_conn_info_field(ci.getServerIp(), R.id.server_ip, R.id.server_ip_row)) | show_conn_info_field(ci.getServerPort(), R.id.server_port, R.id.server_port_row)) | show_conn_info_field(ci.getServerProto(), R.id.server_proto, R.id.server_proto_row) ? 0 : 8);
        }
        set_visibility_stats_expansion_group();
    }

    private void set_visibility_stats_expansion_group() {
        int i = 0;
        boolean expand_stats = this.prefs.get_boolean("expand_stats", RETAIN_AUTH);
        View view = this.stats_expansion_group;
        if (!expand_stats) {
            i = 8;
        }
        if (view != null) view.setVisibility(i);
        if (this.details_more_less != null) {
            this.details_more_less.setText(expand_stats ? R.string.touch_less : R.string.touch_more);
        }
    }

    private void render_event(EventMsg ev, boolean reset, boolean active, boolean cached) {
        int flags = ev.flags;
        if (ev.is_reflected(this)) {
            flags |= UIF_REFLECTED;
        }
        if (reset || (flags & 8) != 0 || ev.profile_override != null) {
            ui_setup(active, UIF_RESET | flags, ev.profile_override);
        } else if (ev.res_id == R.string.core_thread_active) {
            active = true;
            ui_setup(true, flags, null);
        } else if (ev.res_id == R.string.core_thread_inactive) {
            active = RETAIN_AUTH;
            ui_setup(RETAIN_AUTH, flags, null);
        }
        
        int resId = ev.res_id;
        if (resId == R.string.connected) {
            this.main_scroll_view.fullScroll(ScrollView.FOCUS_UP);
        } else if (resId == R.string.info_msg) {
            if (ev.info != null && ev.info.startsWith("OPEN_URL:")) {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(ev.info.substring(9)));
                intent.putExtra("com.android.browser.application_id", getPackageName());
                if (intent.resolveActivity(getPackageManager()) != null) {
                    startActivity(intent);
                }
            }
        } else if (resId == R.string.tap_not_supported) {
            if (!cached) {
                ok_dialog(resString(R.string.tap_unsupported_title), resString(R.string.tap_unsupported_error));
            }
        } else if (resId == R.string.tun_iface_create) {
            if (!cached) {
                ok_dialog(resString(R.string.tun_ko_title), resString(R.string.tun_ko_error));
            }
        } else if (resId == R.string.warn_msg) {
            this.delayed_finish_on_connect = FinishOnConnect.PENDING;
            final Activity self = this;
            ok_dialog(resString(R.string.warning_title), ev.info, () -> {
                if (!(OpenVPNClient.this.delayed_finish_on_connect == FinishOnConnect.PENDING || OpenVPNClient.this.delayed_finish_on_connect == FinishOnConnect.DISABLED)) {
                    self.finish();
                }
                OpenVPNClient.this.delayed_finish_on_connect = FinishOnConnect.DISABLED;
            });
        }

        if (ev.priority >= S_BIND_CALLED) {
            if (ev.icon_res_id >= 0) {
                show_status_icon(ev.icon_res_id);
            }
            if (ev.res_id == R.string.connected) {
                show_status(ev.res_id);
                if (ev.conn_info != null) {
                    show_conn_info(ev.conn_info);
                }
            } else if (ev.info != null && ev.info.length() > 0) {
                show_status(String.format("%s : %s", resString(ev.res_id), ev.info));
            } else {
                show_status(ev.res_id);
            }
        }
        show_progress(ev.progress, active);
        show_stats();
        if (ev.res_id == R.string.connected && this.finish_on_connect != FinishOnConnect.DISABLED) {
            if (this.prefs.get_boolean("autostart_finish_on_connect", RETAIN_AUTH)) {
                final OpenVPNClient self = this;
                if (this.delayed_finish_on_connect == FinishOnConnect.PENDING) {
                    this.delayed_finish_on_connect = this.finish_on_connect;
                    return;
                }
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (OpenVPNClient.this.finish_on_connect != FinishOnConnect.DISABLED) {
                        self.finish();
                    }
                }, 1000);
                return;
            }
            this.finish_on_connect = FinishOnConnect.DISABLED;
        }
    }

    private void stop_service() {
        submitDisconnectIntent(true);
    }

    private void stop() {
        cancel_stats();
        doUnbindService();
        if (this.stop_service_on_client_exit) {
            Log.d(TAG, "CLI: stopping service");
            stop_service();
        }
    }

    @Override
    protected void onStop() {
        Log.d(TAG, "CLI: onStop");
        cancel_stats();
        super.onStop();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.d(TAG, "CLI: onStart");
        this.startup_state |= S_ONSTART_CALLED;
        if (this.finish_on_connect == FinishOnConnect.ENABLED) {
            this.finish_on_connect = FinishOnConnect.ENABLED_ACROSS_ONSTART;
        }
        boolean active = is_active();
        if (active) {
            schedule_stats();
        }
        if (process_autostart_intent(active)) {
            ui_setup(active, UIF_RESET, null);
        }
    }

    @Override
    protected void onDestroy() {
        stop();
        if (updateManager != null) {
            updateManager.shutdown();
        }
        Log.d(TAG, "CLI: onDestroy called");
        super.onDestroy();
    }

    private boolean process_autostart_intent(boolean active) {
        if ((this.startup_state & REQUEST_IMPORT_PKCS12) == REQUEST_IMPORT_PKCS12) {
            Intent intent = getIntent();
            String apn_key = "net.openvpn.openvpn.AUTOSTART_PROFILE_NAME";
            if (intent != null && intent.hasExtra(apn_key)) {
                String apn = intent.getStringExtra(apn_key);
                if (apn != null) {
                    this.autostart_profile_name = null;
                    Log.d(TAG, String.format("CLI: autostart: %s", apn));
                    intent.removeExtra(apn_key);
                    if (!active) {
                        ProfileList proflist = profile_list();
                        if (proflist == null || proflist.get_profile_by_name(apn) == null) {
                            ok_dialog(resString(R.string.profile_not_found), apn);
                        } else {
                            this.autostart_profile_name = apn;
                            return true;
                        }
                    } else if (!current_profile().get_name().equals(apn)) {
                        this.autostart_profile_name = apn;
                        submitDisconnectIntent(RETAIN_AUTH);
                    }
                }
            }
        }
        return RETAIN_AUTH;
    }

    private void cancel_ui_reset() {
        this.ui_reset_timer_handler.removeCallbacks(this.ui_reset_timer_task);
    }

    private void schedule_ui_reset(long delay) {
        cancel_ui_reset();
        this.ui_reset_timer_handler.postDelayed(this.ui_reset_timer_task, delay);
    }

    private void hide_status() {
        this.status_view.setVisibility(View.GONE);
    }

    private void show_status(String text) {
        this.status_view.setVisibility(View.VISIBLE);
        this.status_view.setText(text);
    }

    private void show_status(int res_id) {
        this.status_view.setVisibility(View.VISIBLE);
        this.status_view.setText(res_id);
    }

    private void show_status_icon(int res_id) {
        this.status_icon_view.setImageResource(res_id);
    }

    private void show_progress(int progress, boolean active) {
        if (progress <= 0 || progress >= 99) {
            this.progress_bar.setVisibility(View.GONE);
            return;
        }
        this.progress_bar.setVisibility(View.VISIBLE);
        this.progress_bar.setProgress(progress);
    }

    private void cancel_stats() {
        this.stats_timer_handler.removeCallbacks(this.stats_timer_task);
    }

    private void schedule_stats() {
        cancel_stats();
        this.stats_timer_handler.postDelayed(this.stats_timer_task, 1000);
    }

    private static String render_bandwidth(long bw) {
        String postfix;
        float div;
        float bwf = (float) bw;
        if (bwf >= 1.0E12f) {
            postfix = "TB";
            div = 1.0995116E12f;
        } else if (bwf >= 1.0E9f) {
            postfix = "GB";
            div = 1.0737418E9f;
        } else if (bwf >= 1000000.0f) {
            postfix = "MB";
            div = 1048576.0f;
        } else if (bwf >= 1000.0f) {
            postfix = "KB";
            div = 1024.0f;
        } else {
            return String.format("%.0f", bwf);
        }
        return String.format("%.2f %s", bwf / div, postfix);
    }

    private String render_last_pkt_recv(int sec) {
        if (sec >= 3600) {
            return resString(R.string.lpr_gt_1_hour_ago);
        }
        if (sec >= 120) {
            return String.format(resString(R.string.lpr_gt_n_min_ago), sec / 60);
        } else if (sec >= S_ONSTART_CALLED) {
            return String.format(resString(R.string.lpr_n_sec_ago), sec);
        } else if (sec == S_BIND_CALLED) {
            return resString(R.string.lpr_1_sec_ago);
        } else if (sec == 0) {
            return resString(R.string.lpr_lt_1_sec_ago);
        } else {
            return "";
        }
    }

    private void show_stats() {
        if (is_active()) {
            ConnectionStats stats = get_connection_stats();
            this.last_pkt_recv_view.setText(render_last_pkt_recv(stats.last_packet_received));
            this.duration_view.setText(OpenVPNClientBase.render_duration(stats.duration));
            this.bytes_in_view.setText(render_bandwidth(stats.bytes_in));
            this.bytes_out_view.setText(render_bandwidth(stats.bytes_out));
        }
    }

    private void clear_stats() {
        this.last_pkt_recv_view.setText("");
        this.duration_view.setText("");
        this.bytes_in_view.setText("");
        this.bytes_out_view.setText("");
        reset_conn_info();
    }

    private int n_profiles_loaded() {
        ProfileList proflist = profile_list();
        if (proflist != null) {
            return proflist.size();
        }
        return 0;
    }

    private String selected_profile_name() {
        String ret = null;
        ProfileList proflist = profile_list();
        if (proflist != null && proflist.size() > 0) {
            ret = proflist.size() == S_BIND_CALLED ? ((Profile) proflist.get(0)).get_name() : SpinUtil.get_spinner_selected_item(this.profile_spin);
        }
        if (ret == null) {
            return "UNDEFINED_PROFILE";
        }
        return ret;
    }

    private Profile selected_profile() {
        ProfileList proflist = profile_list();
        if (proflist != null) {
            return proflist.get_profile_by_name(selected_profile_name());
        }
        return null;
    }

    private void clear_auth() {
        if (this.username_edit != null) this.username_edit.setText("");
        if (this.pk_password_edit != null) this.pk_password_edit.setText("");
        if (this.password_edit != null) this.password_edit.setText("");
        if (this.response_edit != null) this.response_edit.setText("");
    }

    private void ui_setup(boolean active, int flags, String profile_override) {
        boolean orig_active = active;
        boolean autostart = RETAIN_AUTH;
        cancel_ui_reset();
        if (!((UIF_RESET & flags) == 0 && orig_active == this.last_active)) {
            clear_auth();
            if (!(active || this.autostart_profile_name == null)) {
                autostart = true;
                profile_override = this.autostart_profile_name;
                this.autostart_profile_name = null;
            }
            ProfileList proflist = profile_list();
            Profile prof = null;
            if (proflist == null || proflist.size() <= 0) {
                if (this.profile_group != null) this.profile_group.setVisibility(View.GONE);
            } else {
                ProfileSource ps = ProfileSource.UNDEF;
                SpinUtil.show_spinner(this, this.profile_spin, proflist.profile_names());
                if (active) {
                    ps = ProfileSource.SERVICE;
                    prof = current_profile();
                }
                if (prof == null && profile_override != null) {
                    ps = ProfileSource.PRIORITY;
                    prof = proflist.get_profile_by_name(profile_override);
                    if (prof == null) {
                        Log.d(TAG, "CLI: profile override not found");
                        autostart = RETAIN_AUTH;
                    }
                }
                if (prof == null) {
                    if ((UIF_PROFILE_SETTING_FROM_SPINNER & flags) != 0) {
                        ps = ProfileSource.SPINNER;
                        prof = proflist.get_profile_by_name(SpinUtil.get_spinner_selected_item(this.profile_spin));
                    } else {
                        ps = ProfileSource.PREFERENCES;
                        prof = proflist.get_profile_by_name(this.prefs.get_string("profile"));
                    }
                }
                if (prof == null) {
                    ps = ProfileSource.LIST0;
                    prof = (Profile) proflist.get(0);
                }
                if (ps != ProfileSource.PREFERENCES && (UIF_REFLECTED & flags) == 0) {
                    this.prefs.set_string("profile", prof.get_name());
                    gen_ui_reset_event(true);
                }
                if (ps != ProfileSource.SPINNER) {
                    SpinUtil.set_spinner_selected_item(this.profile_spin, prof.get_name());
                }
                if (this.profile_group != null) this.profile_group.setVisibility(View.VISIBLE);
                if (this.profile_spin != null) this.profile_spin.setEnabled(!active);
                if (this.profile_edit != null) this.profile_edit.setVisibility(active ? View.GONE : View.VISIBLE);
            }
            if (prof != null) {
                if ((UIF_RESET & flags) != 0) {
                    prof.reset_dynamic_challenge();
                }
                EditText focus = null;
                if (!active && (flags & 32) != 0) {
                    if (this.post_import_help_blurb != null) this.post_import_help_blurb.setVisibility(View.VISIBLE);
                } else if (active) {
                    if (this.post_import_help_blurb != null) this.post_import_help_blurb.setVisibility(View.GONE);
                }
                ProxyList proxy_list = get_proxy_list();
                if (active || proxy_list == null || proxy_list.size() <= 0) {
                    if (this.proxy_group != null) this.proxy_group.setVisibility(View.GONE);
                } else {
                    SpinUtil.show_spinner(this, this.proxy_spin, proxy_list.get_name_list(true));
                    String name = proxy_list.get_enabled(true);
                    if (name != null) {
                        SpinUtil.set_spinner_selected_item(this.proxy_spin, name);
                    }
                    if (this.proxy_group != null) this.proxy_group.setVisibility(View.VISIBLE);
                }
                if (active || !prof.server_list_defined()) {
                    if (this.server_group != null) this.server_group.setVisibility(View.GONE);
                } else {
                    SpinUtil.show_spinner(this, this.server_spin, prof.get_server_list().display_names());
                    String server = this.prefs.get_string_by_profile(prof.get_name(), "server");
                    if (server != null) {
                        SpinUtil.set_spinner_selected_item(this.server_spin, server);
                    }
                    if (this.server_group != null) this.server_group.setVisibility(View.VISIBLE);
                }
                if (active) {
                    if (this.username_group != null) this.username_group.setVisibility(View.GONE);
                    if (this.pk_password_group != null) this.pk_password_group.setVisibility(View.GONE);
                    if (this.password_group != null) this.password_group.setVisibility(View.GONE);
                } else {
                    boolean is_pwd_save;
                    String saved_pwd;
                    boolean udef = prof.userlocked_username_defined();
                    boolean autologin = prof.get_autologin();
                    boolean pk_pwd_req = prof.get_private_key_password_required();
                    boolean dynamic_challenge = prof.is_dynamic_challenge();
                    if ((!autologin || udef) && !dynamic_challenge) {
                        if (udef) {
                            if (this.username_edit != null) {
                                this.username_edit.setText(prof.get_userlocked_username());
                                set_enabled(this.username_edit, RETAIN_AUTH);
                            }
                        } else {
                            if (this.username_edit != null) {
                                set_enabled(this.username_edit, true);
                                String pref_username = this.prefs.get_string_by_profile(prof.get_name(), "username");
                                if (pref_username != null) {
                                    this.username_edit.setText(pref_username);
                                } else {
                                    focus = this.username_edit;
                                }
                            }
                        }
                        if (this.username_group != null) this.username_group.setVisibility(View.VISIBLE);
                    } else {
                        if (this.username_group != null) this.username_group.setVisibility(View.GONE);
                    }
                    if (pk_pwd_req) {
                        is_pwd_save = this.prefs.get_boolean_by_profile(prof.get_name(), "pk_password_save", RETAIN_AUTH);
                        saved_pwd = null;
                        if (this.pk_password_group != null) this.pk_password_group.setVisibility(View.VISIBLE);
                        if (this.pk_password_save_checkbox != null) this.pk_password_save_checkbox.setChecked(is_pwd_save);
                        if (is_pwd_save) {
                            saved_pwd = this.pwds.get("pk", prof.get_name());
                        }
                        if (saved_pwd != null && this.pk_password_edit != null) {
                            this.pk_password_edit.setText(saved_pwd);
                        } else if (focus == null) {
                            focus = this.pk_password_edit;
                        }
                    } else {
                        if (this.pk_password_group != null) this.pk_password_group.setVisibility(View.GONE);
                    }
                    if (autologin || dynamic_challenge) {
                        if (this.password_group != null) this.password_group.setVisibility(View.GONE);
                    } else {
                        boolean is_auth_pw_save = prof.get_allow_password_save();
                        is_pwd_save = (is_auth_pw_save && this.prefs.get_boolean_by_profile(prof.get_name(), "auth_password_save", RETAIN_AUTH));
                        saved_pwd = null;
                        if (this.password_group != null) this.password_group.setVisibility(View.VISIBLE);
                        if (this.password_save_checkbox != null) {
                            this.password_save_checkbox.setEnabled(is_auth_pw_save);
                            this.password_save_checkbox.setChecked(is_pwd_save);
                        }
                        if (is_pwd_save) {
                            saved_pwd = this.pwds.get("auth", prof.get_name());
                        }
                        if (saved_pwd != null && this.password_edit != null) {
                            this.password_edit.setText(saved_pwd);
                        } else if (focus == null) {
                            focus = this.password_edit;
                        }
                    }
                }
                if (active || prof.get_autologin() || !prof.challenge_defined()) {
                    if (this.cr_group != null) this.cr_group.setVisibility(View.GONE);
                } else {
                    if (this.cr_group != null) this.cr_group.setVisibility(View.VISIBLE);
                    Challenge chal = prof.get_challenge();
                    if (this.challenge_view != null) {
                        this.challenge_view.setText(chal.get_challenge());
                        this.challenge_view.setVisibility(View.VISIBLE);
                    }
                    if (chal.get_response_required()) {
                        if (this.response_edit != null) {
                            if (chal.get_echo()) {
                                this.response_edit.setTransformationMethod(SingleLineTransformationMethod.getInstance());
                            } else {
                                this.response_edit.setTransformationMethod(PasswordTransformationMethod.getInstance());
                            }
                            this.response_edit.setVisibility(View.VISIBLE);
                        }
                        if (focus == null) {
                            focus = this.response_edit;
                        }
                    } else {
                        if (this.response_edit != null) this.response_edit.setVisibility(View.GONE);
                    }
                    if (prof.is_dynamic_challenge()) {
                        schedule_ui_reset(prof.get_dynamic_challenge_expire_delay());
                    }
                }
                
                if (this.button_group != null) this.button_group.setVisibility(View.VISIBLE);
                if (this.status_view != null) this.status_view.setVisibility(View.VISIBLE);
                
if (orig_active) {
    if (this.conn_details_group != null) this.conn_details_group.setVisibility(View.VISIBLE);
    if (this.connect_button != null) this.connect_button.setVisibility(View.GONE);
    if (this.disconnect_button != null) this.disconnect_button.setVisibility(View.VISIBLE);
} else {
    if (this.conn_details_group != null) this.conn_details_group.setVisibility(View.VISIBLE); // เปลี่ยนจาก GONE เป็น VISIBLE
    if (this.connect_button != null) this.connect_button.setVisibility(View.VISIBLE);
    if (this.disconnect_button != null) this.disconnect_button.setVisibility(View.GONE);
}
                if (focus != null) {
                    autostart = RETAIN_AUTH;
                }
                req_focus(focus);
            } else {
                if (this.post_import_help_blurb != null) this.post_import_help_blurb.setVisibility(View.GONE);
                if (this.proxy_group != null) this.proxy_group.setVisibility(View.GONE);
                if (this.server_group != null) this.server_group.setVisibility(View.GONE);
                if (this.username_group != null) this.username_group.setVisibility(View.GONE);
                if (this.pk_password_group != null) this.pk_password_group.setVisibility(View.GONE);
                if (this.password_group != null) this.password_group.setVisibility(View.GONE);
                if (this.cr_group != null) this.cr_group.setVisibility(View.GONE);
                if (this.conn_details_group != null) this.conn_details_group.setVisibility(View.GONE);
                
                if (this.button_group != null) this.button_group.setVisibility(View.VISIBLE);
                if (this.connect_button != null) this.connect_button.setVisibility(View.VISIBLE);
                if (this.disconnect_button != null) this.disconnect_button.setVisibility(View.GONE);
                
                show_status_icon(R.drawable.info);
                show_status(R.string.no_profiles_loaded);
            }
            if (orig_active) {
                schedule_stats();
            } else {
                cancel_stats();
            }
        }
        this.last_active = orig_active;
        if (autostart && !this.last_active) {
            this.finish_on_connect = FinishOnConnect.ENABLED;
            start_connect();
        }
    }
    
    private void set_enabled(EditText editText, boolean state) {
        editText.setEnabled(state);
        editText.setFocusable(state);
        editText.setFocusableInTouchMode(state);
    }

    private void raise_file_selection_dialog(int requestCode) {
        switch (requestCode) {
            case S_ONSTART_CALLED:
                raise_file_selection_dialog(S_ONSTART_CALLED, R.string.select_profile);
                return;
            case REQUEST_IMPORT_PKCS12:
                raise_file_selection_dialog(REQUEST_IMPORT_PKCS12, R.string.select_pkcs12);
                return;
            default:
                break;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length != 0) {
            switch (requestCode) {
                case S_ONSTART_CALLED:
                case REQUEST_IMPORT_PKCS12:
                    for (int i = 0; i < grantResults.length; i++) {
                        if (permissions[i].equals("android.permission.READ_EXTERNAL_STORAGE") && grantResults[i] == 0) {
                            raise_file_selection_dialog(requestCode);
                        }
                    }
                    return;
                default:
                    break;
            }
        }
    }
    
    private static final int REQUEST_IMPORT_PROFILE_SAF = 1002;
    private static final int REQUEST_IMPORT_PKCS12_SAF = 1003;

    private void request_file_selection_dialog(int requestCode) {
        Toast.makeText(this, "กำลังเปิดตัวเลือกไฟล์...", Toast.LENGTH_SHORT).show();

        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        int safRequest;
        if (requestCode == S_ONSTART_CALLED || requestCode == REQUEST_IMPORT_PROFILE) {
            safRequest = REQUEST_IMPORT_PROFILE_SAF;
        } else if (requestCode == REQUEST_IMPORT_PKCS12) {
            safRequest = REQUEST_IMPORT_PKCS12_SAF;
        } else {
            Toast.makeText(this, "requestCode ไม่รู้จัก: " + requestCode, Toast.LENGTH_LONG).show();
            return;
        }

        try {
            startActivityForResult(Intent.createChooser(intent, "เลือกไฟล์"), safRequest);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "ไม่พบแอปเลือกไฟล์", Toast.LENGTH_LONG).show();
            Log.e(TAG, "No file picker", e);
        } catch (Exception e) {
            Toast.makeText(this, "เปิดไฟล์ล้มเหลว: " + e.getMessage(), Toast.LENGTH_LONG).show();
            Log.e(TAG, "file picker error", e);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.about_menu) {
            startActivityForResult(new Intent(this, OpenVPNAbout.class), 0);
            return true;
        } else if (id == R.id.help_menu) {
            startActivityForResult(new Intent(this, OpenVPNHelp.class), 0);
            return true;
        } else if (id == R.id.import_profile_remote) {
            startActivityForResult(new Intent(this, OpenVPNImportProfile.class), 0);
            return true;
        } else if (id == R.id.import_profile) {
            request_file_selection_dialog(S_ONSTART_CALLED);
            return true;
        } else if (id == R.id.import_pkcs12) {
            request_file_selection_dialog(REQUEST_IMPORT_PKCS12);
            return true;
        } else if (id == R.id.preferences) {
            startActivityForResult(new Intent(this, OpenVPNPrefs.class), 0);
            return true;
        } else if (id == R.id.add_proxy) {
            startActivityForResult(new Intent(this, OpenVPNAddProxy.class), 0);
            return true;
        } else if (id == R.id.add_shortcut_connect) {
            startActivityForResult(
                    new Intent(this, OpenVPNAddShortcut.class)
                            .putExtra("net.openvpn.openvpn.SHORTCUT_TYPE", "connect"), 0);
            return true;
        } else if (id == R.id.add_shortcut_disconnect) {
            startActivityForResult(
                    new Intent(this, OpenVPNAddShortcut.class)
                            .putExtra("net.openvpn.openvpn.SHORTCUT_TYPE", "disconnect"), 0);
            return true;
        } else if (id == R.id.add_shortcut_app) {
            startActivityForResult(
                    new Intent(this, OpenVPNAddShortcut.class)
                            .putExtra("net.openvpn.openvpn.SHORTCUT_TYPE", "app"), 0);
            return true;
        } else if (id == R.id.show_log) {
            startActivityForResult(new Intent(this, OpenVPNLog.class), 0);
            return true;
        } else if (id == R.id.show_raw_stats) {
            startActivityForResult(new Intent(this, OpenVPNStats.class), 0);
            return true;
        } else if (id == R.id.forget_creds) {
            forget_creds_with_confirm();
            return true;
        } else if (id == R.id.exit_partial) {
            finish();
            return true;
        } else if (id == R.id.exit_full) {
            this.stop_service_on_client_exit = true;
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onClick(View v) {
        cancel_ui_reset();
        this.autostart_profile_name = null;
        this.finish_on_connect = FinishOnConnect.DISABLED;
        int viewid = v.getId();
        if (viewid == R.id.connect) {
            start_connect();
        } else if (viewid == R.id.disconnect) {
            submitDisconnectIntent(RETAIN_AUTH);
        } else if (viewid == R.id.profile_edit || viewid == R.id.proxy_edit) {
            openContextMenu(v);
        }
    }

    private void start_connect() {
        cancel_ui_reset();
        Intent intent = VpnService.prepare(this);
        if (intent != null) {
            try {
                Log.d(TAG, "CLI: requesting VPN actor rights");
                startActivityForResult(intent, S_BIND_CALLED);
                return;
            } catch (ActivityNotFoundException e) {
                Log.e(TAG, "CLI: requesting VPN actor rights failed", e);
                ok_dialog(resString(R.string.vpn_permission_dialog_missing_title), resString(R.string.vpn_permission_dialog_missing_text));
                return;
            }
        }
        Log.d(TAG, "CLI: app is already authorized as VPN actor");
        resolve_epki_alias_then_connect();
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        boolean new_expand_stats = RETAIN_AUTH;
        if (v.getId() != R.id.conn_details_boxed || event.getAction() != MotionEvent.ACTION_DOWN) {
            return RETAIN_AUTH;
        }
        if (!this.prefs.get_boolean("expand_stats", RETAIN_AUTH)) {
            new_expand_stats = true;
        }
        this.prefs.set_boolean("expand_stats", new_expand_stats);
        set_visibility_stats_expansion_group();
        return true;
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
        cancel_ui_reset();
        int viewid = parent.getId();
        if (viewid == R.id.profile) {
            ui_setup(is_active(), 327680, null);
        } else if (viewid == R.id.proxy) {
            ProxyList proxy_list = get_proxy_list();
            if (proxy_list != null) {
                proxy_list.set_enabled(SpinUtil.get_spinner_list_item(this.proxy_spin, position));
                proxy_list.save();
                gen_ui_reset_event(true);
            }
        } else if (viewid == R.id.server) {
            String server = SpinUtil.get_spinner_list_item(this.server_spin, position);
            this.prefs.set_string_by_profile(SpinUtil.get_spinner_selected_item(this.profile_spin), "server", server);
            gen_ui_reset_event(true);
        }
    }

    @Override
    public void onNothingSelected(AdapterView<?> adapterView) {
    }

    private void menu_add(ContextMenu menu, int id, boolean enabled, String menu_key) {
        MenuItem item = menu.add(0, id, 0, id).setEnabled(enabled);
        if (menu_key != null) {
            item.setIntent(new Intent().putExtra("net.openvpn.openvpn.MENU_KEY", menu_key));
        }
    }

    private String get_menu_key(MenuItem item) {
        if (item != null) {
            Intent intent = item.getIntent();
            if (intent != null) {
                return intent.getStringExtra("net.openvpn.openvpn.MENU_KEY");
            }
        }
        return null;
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        boolean z = RETAIN_AUTH;
        Log.d(TAG, "CLI: onCreateContextMenu");
        super.onCreateContextMenu(menu, v, menuInfo);
        int viewid = v.getId();
        if (!is_active() && (viewid == R.id.profile || viewid == R.id.profile_edit)) {
            Profile prof = selected_profile();
            if (prof != null) {
                String profile_name = prof.get_name();
                menu.setHeaderTitle(profile_name);
                if (SpinUtil.get_spinner_count(this.profile_spin) > S_BIND_CALLED) {
                    z = true;
                }
                menu_add(menu, R.string.profile_context_menu_change_profile, z, null);
                menu_add(menu, R.string.profile_context_menu_create_shortcut, true, profile_name);
                menu_add(menu, R.string.profile_context_menu_delete, prof.is_deleteable(), profile_name);
                menu_add(menu, R.string.profile_context_menu_rename, prof.is_renameable(), profile_name);
                menu_add(menu, R.string.profile_context_forget_creds, true, profile_name);
            } else {
                menu.setHeaderTitle(R.string.profile_context_none_selected);
            }
            menu_add(menu, R.string.profile_context_cancel, true, null);
        } else if (!is_active()) {
            if (viewid == R.id.proxy || viewid == R.id.proxy_edit) {
                ProxyList proxy_list = get_proxy_list();
                if (proxy_list != null) {
                    String proxy_name = proxy_list.get_enabled(true);
                    boolean is_none = proxy_list.is_none(proxy_name);
                    menu.setHeaderTitle(proxy_name);
                    menu_add(menu, R.string.proxy_context_change_proxy, SpinUtil.get_spinner_count(this.proxy_spin) > S_BIND_CALLED, null);
                    menu_add(menu, R.string.proxy_context_edit, !is_none, proxy_name);
                    if (!is_none) {
                        z = true;
                    }
                    menu_add(menu, R.string.proxy_context_delete, z, proxy_name);
                    menu_add(menu, R.string.proxy_context_forget_creds, proxy_list.has_saved_creds(proxy_name), proxy_name);
                } else {
                    menu.setHeaderTitle(R.string.proxy_context_none_selected);
                }
                menu_add(menu, R.string.proxy_context_cancel, true, null);
            }
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        Log.d(TAG, "CLI: onContextItemSelected");
        String prof_name;
        String proxy_name;
        int itemId = item.getItemId();
        
        if (itemId == R.string.profile_context_cancel || itemId == R.string.proxy_context_cancel) {
            return true;
        } else if (itemId == R.string.profile_context_forget_creds) {
            ProfileList proflist = profile_list();
            if (proflist == null) return true;
            Profile prof = proflist.get_profile_by_name(get_menu_key(item));
            if (prof == null) return true;
            prof_name = prof.get_name();
            this.pwds.remove("pk", prof_name);
            this.pwds.remove("auth", prof_name);
            prof.forget_cert();
            ui_setup(is_active(), UIF_RESET, null);
            return true;
        } else if (itemId == R.string.profile_context_menu_change_profile) {
            this.profile_spin.performClick();
            return true;
        } else if (itemId == R.string.profile_context_menu_create_shortcut) {
            prof_name = get_menu_key(item);
            if (prof_name == null) return true;
            launch_create_profile_shortcut_dialog(prof_name);
            return true;
        } else if (itemId == R.string.profile_context_menu_delete) {
            prof_name = get_menu_key(item);
            if (prof_name == null) return true;
            submitDeleteProfileIntentWithConfirm(prof_name);
            return true;
        } else if (itemId == R.string.profile_context_menu_rename) {
            prof_name = get_menu_key(item);
            if (prof_name == null) return true;
            launch_rename_profile_dialog(prof_name);
            return true;
        } else if (itemId == R.string.proxy_context_change_proxy) {
            this.proxy_spin.performClick();
            return true;
        } else if (itemId == R.string.proxy_context_delete) {
            delete_proxy_with_confirm(get_menu_key(item));
            return true;
        } else if (itemId == R.string.proxy_context_edit) {
            proxy_name = get_menu_key(item);
            if (proxy_name == null) return true;
            startActivityForResult(new Intent(this, OpenVPNAddProxy.class).putExtra("net.openvpn.openvpn.PROXY_NAME", proxy_name), 0);
            return true;
        } else if (itemId == R.string.proxy_context_forget_creds) {
            proxy_name = get_menu_key(item);
            ProxyList proxy_list = get_proxy_list();
            if (proxy_list == null) return true;
            proxy_list.forget_creds(proxy_name);
            proxy_list.save();
            return true;
        }
        return RETAIN_AUTH;
    }

    private void launch_create_profile_shortcut_dialog(final String prof_name) {
        if (isFinishing() || isDestroyed()) return;
        View view = getLayoutInflater().inflate(R.layout.create_shortcut_dialog, null);
        final EditText name_field = (EditText) view.findViewById(R.id.shortcut_name);
        name_field.setText(prof_name);
        name_field.selectAll();
        DialogInterface.OnClickListener dialogClickListener = (dialog, which) -> {
            if (which == DialogInterface.BUTTON_POSITIVE) {
                OpenVPNClient.this.createConnectShortcut(prof_name, name_field.getText().toString());
            }
        };
        new Builder(this)
                .setTitle(R.string.create_shortcut_title)
                .setView(view)
                .setPositiveButton(R.string.create_shortcut_yes, dialogClickListener)
                .setNegativeButton(R.string.create_shortcut_cancel, dialogClickListener)
                .show();
    }

    private void launch_rename_profile_dialog(final String orig_prof_name) {
        if (isFinishing() || isDestroyed()) return;
        View view = getLayoutInflater().inflate(R.layout.rename_profile_dialog, null);
        final EditText name_field = (EditText) view.findViewById(R.id.rename_profile_name);
        name_field.setText(orig_prof_name);
        name_field.selectAll();
        DialogInterface.OnClickListener dialogClickListener = (dialog, which) -> {
            if (which == DialogInterface.BUTTON_POSITIVE) {
                OpenVPNClient.this.submitRenameProfileIntent(orig_prof_name, name_field.getText().toString());
            }
        };
        new Builder(this)
                .setTitle(R.string.rename_profile_title)
                .setView(view)
                .setPositiveButton(R.string.rename_profile_yes, dialogClickListener)
                .setNegativeButton(R.string.rename_profile_cancel, dialogClickListener)
                .show();
    }

    private void delete_proxy_with_confirm(final String proxy_name) {
        if (isFinishing() || isDestroyed()) return;
        final ProxyList proxy_list = get_proxy_list();
        DialogInterface.OnClickListener dialogClickListener = (dialog, which) -> {
            if (which == DialogInterface.BUTTON_POSITIVE) {
                if (proxy_list != null) {
                    proxy_list.remove(proxy_name);
                    proxy_list.save();
                    OpenVPNClient.this.gen_ui_reset_event(OpenVPNClient.RETAIN_AUTH);
                }
            }
        };
        new Builder(this)
                .setTitle(R.string.proxy_delete_confirm_title)
                .setMessage(proxy_name)
                .setPositiveButton(R.string.proxy_delete_confirm_yes, dialogClickListener)
                .setNegativeButton(R.string.proxy_delete_confirm_cancel, dialogClickListener)
                .show();
    }

    private void forget_creds_with_confirm() {
        if (isFinishing() || isDestroyed()) return;
        final Context context = this;
        DialogInterface.OnClickListener dialogClickListener = (dialog, which) -> {
            if (which == DialogInterface.BUTTON_POSITIVE) {
                OpenVPNClient.this.pwds.regenerate(true);
                ProfileList proflist = OpenVPNClient.this.profile_list();
                if (proflist != null) {
                    proflist.forget_certs();
                }
                TrustMan.forget_certs(context);
                OpenVPNImportProfile.forget_server_history(OpenVPNClient.this.prefs);
                ProxyList proxy_list = OpenVPNClient.this.get_proxy_list();
                if (proxy_list != null) {
                    proxy_list.forget_creds();
                    proxy_list.save();
                }
                OpenVPNClient.this.ui_setup(OpenVPNClient.this.is_active(), OpenVPNClient.UIF_RESET, null);
            }
        };
        new Builder(this)
                .setTitle(R.string.forget_creds_title)
                .setMessage(R.string.forget_creds_message)
                .setPositiveButton(R.string.forget_creds_yes, dialogClickListener)
                .setNegativeButton(R.string.forget_creds_cancel, dialogClickListener)
                .show();
    }

    public PendingIntent get_configure_intent(int requestCode) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(this, requestCode, getIntent(), flags);
    }

    private void resolve_epki_alias_then_connect() {
        resolveExternalPkiAlias(selected_profile(), new EpkiPost() {
            @Override
            public void post_dispatch(String alias) {
                OpenVPNClient.this.do_connect(alias);
            }
        });
    }

    private void do_connect(String epki_alias) {
        String app_name = "net.openvpn.connect.android";
        String proxy_name = null;
        String server = null;
        String username = null;
        String password = null;
        String pk_password = null;
        String response = null;
        boolean is_auth_pwd_save = RETAIN_AUTH;
        String profile_name = selected_profile_name();
        if (this.proxy_group.getVisibility() == View.VISIBLE) {
            ProxyList proxy_list = get_proxy_list();
            if (proxy_list != null) {
                proxy_name = proxy_list.get_enabled(RETAIN_AUTH);
            }
        }
        if (this.server_group.getVisibility() == View.VISIBLE) {
            server = SpinUtil.get_spinner_selected_item(this.server_spin);
        }
        if (this.username_group.getVisibility() == View.VISIBLE) {
            username = this.username_edit.getText().toString();
            if (username.length() > 0) {
                this.prefs.set_string_by_profile(profile_name, "username", username);
            }
        }
        if (this.pk_password_group.getVisibility() == View.VISIBLE) {
            pk_password = this.pk_password_edit.getText().toString();
            boolean is_pk_pwd_save = this.pk_password_save_checkbox.isChecked();
            this.prefs.set_boolean_by_profile(profile_name, "pk_password_save", is_pk_pwd_save);
            if (is_pk_pwd_save) {
                this.pwds.set("pk", profile_name, pk_password);
            } else {
                this.pwds.remove("pk", profile_name);
            }
        }
        if (this.password_group.getVisibility() == View.VISIBLE) {
            password = this.password_edit.getText().toString();
            is_auth_pwd_save = this.password_save_checkbox.isChecked();
            this.prefs.set_boolean_by_profile(profile_name, "auth_password_save", is_auth_pwd_save);
            if (is_auth_pwd_save) {
                this.pwds.set("auth", profile_name, password);
            } else {
                this.pwds.remove("auth", profile_name);
            }
        }
        if (this.cr_group.getVisibility() == View.VISIBLE) {
            response = this.response_edit.getText().toString();
        }
        clear_auth();
        String vpn_proto = this.prefs.get_string("vpn_proto");
        String ipv6 = this.prefs.get_string("ipv6");
        String conn_timeout = this.prefs.get_string("conn_timeout");
        String compression_mode = this.prefs.get_string("compression_mode");
        clear_stats();
        submitConnectIntent(profile_name, server, vpn_proto, ipv6, conn_timeout, username, password, is_auth_pwd_save, pk_password, response, epki_alias, compression_mode, proxy_name, null, null, true, get_gui_version(app_name));
    }

    private void import_profile(String path) {
        submitImportProfileViaPathIntent(path);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        Log.d(TAG, String.format("CLI: onActivityResult request=%d result=%d", request, result));
        String path;
        switch (request) {
            case S_BIND_CALLED:
                if (result == RESULT_OK) {
                    resolve_epki_alias_then_connect();
                    return;
                } else if (result != RESULT_CANCELED) {
                    return;
                } else {
                    if (this.finish_on_connect == FinishOnConnect.ENABLED) {
                        finish();
                        return;
                    } else if (this.finish_on_connect == FinishOnConnect.ENABLED_ACROSS_ONSTART) {
                        this.finish_on_connect = FinishOnConnect.ENABLED;
                        start_connect();
                        return;
                    } else {
                        return;
                    }
                }

            case REQUEST_IMPORT_PROFILE_SAF:
                if (result == RESULT_OK && data != null && data.getData() != null) {
                    importProfileFromUri(data.getData());
                }
                return;

            case REQUEST_IMPORT_PKCS12_SAF:
                if (result == RESULT_OK && data != null && data.getData() != null) {
                    path = copyUriToCache(data.getData(), "import.p12");
                    if (path != null) {
                        import_pkcs12(path);
                    } else {
                        Toast.makeText(this, "ไม่สามารถอ่านไฟล์ PKCS12 ได้", Toast.LENGTH_LONG).show();
                    }
                }
                return;

            case S_ONSTART_CALLED:
                if (result == RESULT_OK && data != null) {
                    path = data.getStringExtra(FileDialog.RESULT_PATH);
                    Log.d(TAG, String.format("CLI: IMPORT_PROFILE: %s", path));
                    if (path != null) {
                        import_profile(path);
                    }
                    return;
                }
                return;

            case REQUEST_IMPORT_PKCS12:
                if (result == RESULT_OK && data != null) {
                    path = data.getStringExtra(FileDialog.RESULT_PATH);
                    Log.d(TAG, String.format("CLI: IMPORT_PKCS12: %s", path));
                    if (path != null) {
                        import_pkcs12(path);
                    }
                    return;
                }
                return;

            default:
                super.onActivityResult(request, result, data);
                return;
        }
    }

    private void importProfileFromUri(Uri uri) {
        try {
            String filename = "imported.ovpn";
            try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) {
                        String name = c.getString(idx);
                        if (name != null && name.length() > 0) {
                            filename = name;
                        }
                    }
                }
            }

            filename = new java.io.File(filename).getName();
            if (!filename.toLowerCase(java.util.Locale.US).endsWith(".ovpn")) {
                filename = filename + ".ovpn";
            }

            java.io.InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) {
                throw new java.io.IOException("openInputStream returned null");
            }

            StringBuilder sb = new StringBuilder();
            try (java.io.Reader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
                char[] buf = new char[8192];
                int n;
                while ((n = reader.read(buf)) > 0) {
                    sb.append(buf, 0, n);
                }
            }

            String content = sb.toString();
            if (content.trim().isEmpty()) {
                Toast.makeText(this, "ไฟล์ว่างหรืออ่านไม่ได้", Toast.LENGTH_LONG).show();
                return;
            }

            submitImportProfileIntent(content, filename, true);
            Toast.makeText(this, "กำลังนำเข้า: " + filename, Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            Log.e(TAG, "importProfileFromUri failed", e);
            Toast.makeText(this, "นำเข้าไม่สำเร็จ: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String copyUriToCache(Uri uri, String fallbackName) {
        try {
            String name = fallbackName;
            try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) {
                        String n = c.getString(idx);
                        if (n != null && !n.isEmpty()) name = n;
                    }
                }
            }
            name = new java.io.File(name).getName();
            java.io.File out = new java.io.File(getCacheDir(), name);
            try (java.io.InputStream in = getContentResolver().openInputStream(uri);
                 java.io.OutputStream os = new java.io.FileOutputStream(out)) {
                if (in == null) return null;
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
                os.flush();
            }
            return out.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "copyUriToCache failed", e);
            return null;
        }
    }

    private TextView last_visible_edittext() {
        for (int i = 0; i < this.textgroups.length; i++) {
            if (this.textgroups[i].getVisibility() == View.VISIBLE) {
                return this.textviews[i];
            }
        }
        return null;
    }

    @Override
    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
        if (v != last_visible_edittext()) {
            return RETAIN_AUTH;
        }
        if (action_enter(actionId, event) && this.connect_button.getVisibility() == View.VISIBLE) {
            onClick(this.connect_button);
        }
        return true;
    }

    private void req_focus(EditText editText) {
        boolean auto_keyboard = this.prefs.get_boolean("auto_keyboard", RETAIN_AUTH);
        if (editText != null) {
            editText.requestFocus();
            if (auto_keyboard) {
                raise_keyboard(editText);
                return;
            }
            return;
        }
        this.main_scroll_view.requestFocus();
        if (auto_keyboard) {
            dismiss_keyboard();
        }
    }

    private void raise_keyboard(EditText editText) {
        InputMethodManager mgr = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (mgr != null) {
            mgr.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void dismiss_keyboard() {
        InputMethodManager mgr = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (mgr != null && this.textviews != null) {
            for (TextView tv : this.textviews) {
                if (tv != null) {
                    mgr.hideSoftInputFromWindow(tv.getWindowToken(), 0);
                }
            }
        }
    }

private void load_ui_elements() {
    this.main_scroll_view = (ScrollView) findViewById(R.id.main_scroll_view);
    this.post_import_help_blurb = findViewById(R.id.post_import_help_blurb);
    this.profile_group = findViewById(R.id.profile_group);
    this.proxy_group = findViewById(R.id.proxy_group);
    this.server_group = findViewById(R.id.server_group);
    this.username_group = findViewById(R.id.username_group);
    this.password_group = findViewById(R.id.password_group);
    this.pk_password_group = findViewById(R.id.pk_password_group);
    this.cr_group = findViewById(R.id.cr_group);
    this.conn_details_group = findViewById(R.id.conn_details_group);
    this.stats_group = findViewById(R.id.stats_group);
    this.stats_expansion_group = findViewById(R.id.stats_expansion_group);
    this.info_group = findViewById(R.id.info_group);
    this.button_group = findViewById(R.id.button_group);
    this.profile_spin = (Spinner) findViewById(R.id.profile);
    this.profile_edit = (ImageButton) findViewById(R.id.profile_edit);
    this.proxy_spin = (Spinner) findViewById(R.id.proxy);
    this.proxy_edit = (ImageButton) findViewById(R.id.proxy_edit);
    this.server_spin = (Spinner) findViewById(R.id.server);
    this.challenge_view = (TextView) findViewById(R.id.challenge);
    this.username_edit = (EditText) findViewById(R.id.username);
    this.password_edit = (EditText) findViewById(R.id.password);
    this.pk_password_edit = (EditText) findViewById(R.id.pk_password);
    this.response_edit = (EditText) findViewById(R.id.response);
    this.password_save_checkbox = (CheckBox) findViewById(R.id.password_save);
    this.pk_password_save_checkbox = (CheckBox) findViewById(R.id.pk_password_save);
    this.status_view = (TextView) findViewById(R.id.status);
    this.status_icon_view = (ImageView) findViewById(R.id.status_icon);
    this.progress_bar = (ProgressBar) findViewById(R.id.progress);
    this.connect_button = (Button) findViewById(R.id.connect);
    this.disconnect_button = (Button) findViewById(R.id.disconnect);
    this.details_more_less = (TextView) findViewById(R.id.details_more_less);
    this.last_pkt_recv_view = (TextView) findViewById(R.id.last_pkt_recv);
    this.duration_view = (TextView) findViewById(R.id.duration);
    this.bytes_in_view = (TextView) findViewById(R.id.bytes_in);
    this.bytes_out_view = (TextView) findViewById(R.id.bytes_out);

    if (this.connect_button != null) this.connect_button.setOnClickListener(this);
    if (this.disconnect_button != null) this.disconnect_button.setOnClickListener(this);

    if (this.profile_spin != null) {
        this.profile_spin.setOnItemSelectedListener(this);
        registerForContextMenu(this.profile_spin);
    }
    if (this.proxy_spin != null) {
        this.proxy_spin.setOnItemSelectedListener(this);
        registerForContextMenu(this.proxy_spin);
    }
    if (this.server_spin != null) this.server_spin.setOnItemSelectedListener(this);

    View connDetails = findViewById(R.id.conn_details_boxed);
    if (connDetails != null) connDetails.setOnTouchListener(this);

    if (this.profile_edit != null) {
        this.profile_edit.setOnClickListener(this);
        registerForContextMenu(this.profile_edit);
    }
    if (this.proxy_edit != null) {
        this.proxy_edit.setOnClickListener(this);
        registerForContextMenu(this.proxy_edit);
    }

    if (this.username_edit != null) this.username_edit.setOnEditorActionListener(this);
    if (this.password_edit != null) this.password_edit.setOnEditorActionListener(this);
    if (this.pk_password_edit != null) this.pk_password_edit.setOnEditorActionListener(this);
    if (this.response_edit != null) this.response_edit.setOnEditorActionListener(this);

    this.textgroups = new View[]{this.cr_group, this.password_group, this.pk_password_group, this.username_group};
    this.textviews = new EditText[]{this.response_edit, this.password_edit, this.pk_password_edit, this.username_edit};

    // =============================================
    // 🔥 แถบเมนูด้านบน (BottomAppBar) - ตามที่คุณต้องการ
    // =============================================
    com.google.android.material.bottomappbar.BottomAppBar bottomBar = findViewById(R.id.bottom_bar);
    if (bottomBar != null) {
        bottomBar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.menu_home) {
                Toast.makeText(this, "กำลังตรวจสอบอัปเดต...", Toast.LENGTH_SHORT).show();
                if (updateManager != null) {
                    updateManager.checkUpdateManual();
                } else {
                    Toast.makeText(this, "ระบบอัปเดตยังไม่พร้อม", Toast.LENGTH_SHORT).show();
                }
                return true;
            } else if (id == R.id.menu_settings) {
                startActivityForResult(new Intent(this, OpenVPNPrefs.class), 0);
                return true;
            } else if (id == R.id.menu_more) {
                android.view.ContextThemeWrapper darkContext = new android.view.ContextThemeWrapper(this, R.style.AppTheme);
                android.widget.PopupMenu popup = new android.widget.PopupMenu(darkContext, bottomBar);
                popup.getMenuInflater().inflate(R.menu.menu, popup.getMenu());
                popup.setOnMenuItemClickListener(this::onOptionsItemSelected);
                popup.show();
                return true;
            }
            return false;
        });
    }

    // ฟังก์ชันอื่นๆ (เหมือนเดิม)
    View fabMenu = findViewById(R.id.fab_menu);
    if (fabMenu != null) {
        fabMenu.setOnClickListener(v -> {
            request_file_selection_dialog(S_ONSTART_CALLED);
        });
    }

    if (this.button_group != null) {
        this.button_group.setVisibility(View.VISIBLE);
    }
    if (this.connect_button != null) {
        this.connect_button.setVisibility(View.VISIBLE);
    }
}}
