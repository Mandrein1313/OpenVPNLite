package net.openvpn.openvpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.VpnService;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.security.KeyChain;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.security.PrivateKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;

import javax.crypto.Cipher;

import net.openvpn.openvpn.ProxyList.Item;

public class OpenVPNService extends VpnService implements Handler.Callback, net.openvpn.openvpn.OpenVPNClientThread.EventReceiver {
    
    public static final String ACTION_BASE = "net.openvpn.openvpn.";
    public static final String ACTION_BIND = "net.openvpn.openvpn.BIND";
    public static final String ACTION_CONNECT = "net.openvpn.openvpn.CONNECT";
    public static final String ACTION_DELETE_PROFILE = "net.openvpn.openvpn.DELETE_PROFILE";
    public static final String ACTION_DISCONNECT = "net.openvpn.openvpn.DISCONNECT";
    public static final String ACTION_IMPORT_PROFILE = "net.openvpn.openvpn.IMPORT_PROFILE";
    public static final String ACTION_IMPORT_PROFILE_VIA_PATH = "net.openvpn.openvpn.ACTION_IMPORT_PROFILE_VIA_PATH";
    public static final String ACTION_RENAME_PROFILE = "net.openvpn.openvpn.RENAME_PROFILE";
    public static final String ACTION_SUBMIT_PROXY_CREDS = "net.openvpn.openvpn.ACTION_SUBMIT_PROXY_CREDS";

    public static final int EV_PRIO_HIGH = 3;
    public static final int EV_PRIO_INVISIBLE = 0;
    public static final int EV_PRIO_LOW = 1;
    public static final int EV_PRIO_MED = 2;

    private static final int GCI_REQ_ESTABLISH = 0;
    private static final int GCI_REQ_NOTIFICATION = 1;

    public static final String INTENT_PREFIX = "net.openvpn.openvpn";
    private static final int MSG_EVENT = 1;
    private static final int MSG_LOG = 2;
    private static final int NOTIFICATION_ID = 1642;
    private static final String CHANNEL_ID = "openvpn_service_channel";
    private static final String TAG = "OpenVPNService";
    public static final int log_deque_max = 250;

    private boolean active = false;
    private final ArrayDeque<EventReceiver> clients = new ArrayDeque<>();
    private CPUUsage cpu_usage;
    private Profile current_profile;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private boolean enable_notifications;
    private HashMap<String, EventInfo> event_info;
    private JellyBeanHack jellyBeanHack;
    private EventMsg last_event;
    private EventMsg last_event_prof_manage;
    private final ArrayDeque<LogMsg> log_deque = new ArrayDeque<>();
    private final IBinder mBinder = new LocalBinder();
    private ConnectivityReceiver mConnectivityReceiver;
    private Handler mHandler;
    private Notification.Builder mNotifyBuilder;
    private OpenVPNClientThread mThread;
    private PrefUtil prefs;
    private ProfileList profile_list;
    public ProxyList proxy_list;
    private PasswordUtil pwds;
    private boolean shutdown_pending = false;
    private long thread_started = 0;

    public interface EventReceiver {
        void event(EventMsg eventMsg);
        PendingIntent get_configure_intent(int requestCode);
        void log(LogMsg logMsg);
    }

    public static class Challenge {
        private String challenge;
        private boolean echo;
        private boolean response_required;

        public String get_challenge() { return this.challenge; }
        public boolean get_echo() { return this.echo; }
        public boolean get_response_required() { return this.response_required; }

        @Override
        public String toString() {
            return String.format(Locale.US, "%s/%b/%b", this.challenge, this.echo, this.response_required);
        }
    }

    public enum Transition {
        NO_CHANGE,
        TO_CONNECTED,
        TO_DISCONNECTED
    }

    public static class ConnectionStats {
        public long bytes_in;
        public long bytes_out;
        public int duration;
        public int last_packet_received;
    }

    private class ConnectivityReceiver extends BroadcastReceiver {
        public boolean conn_on = false;
        public boolean conn_on_defined = false;
        private boolean initialized = false;
        private long last_action_time = 0;
        private boolean last_ok = true;
        public boolean screen_on = false;
        public boolean screen_on_defined = false;

        @Override
        public void onReceive(Context context, Intent intent) {
            boolean screen_on_mod = false;
            boolean conn_on_mod = false;
            boolean failover = false;
            String act = intent.getAction();
            boolean pvbs = OpenVPNService.this.prefs.get_boolean("pause_vpn_on_blanked_screen", false);

            if (Intent.ACTION_SCREEN_ON.equals(act)) {
                Log.i(TAG, String.format(Locale.US, "ConnectivityReceiver: SCREEN_ON pvbs=%b", pvbs));
                this.screen_on = true;
                screen_on_mod = true;
                this.screen_on_defined = true;
            } else if (Intent.ACTION_SCREEN_OFF.equals(act)) {
                Log.i(TAG, String.format(Locale.US, "ConnectivityReceiver: SCREEN_OFF pvbs=%b", pvbs));
                this.screen_on = false;
                screen_on_mod = true;
                this.screen_on_defined = true;
            } else if ("android.net.conn.CONNECTIVITY_CHANGE".equals(act)) {
                this.conn_on = !intent.getBooleanExtra("noConnectivity", false);
                failover = intent.getBooleanExtra("isFailover", false);
                conn_on_mod = true;
                this.conn_on_defined = true;
                Log.i(TAG, String.format(Locale.US, "ConnectivityReceiver: CONNECTIVITY_ACTION conn=%b fo=%b", this.conn_on, failover));
            }

            if (screen_on_mod || conn_on_mod) {
                boolean ok = !(pvbs && this.screen_on_defined && !this.screen_on) && (!this.conn_on_defined || this.conn_on);
                if (OpenVPNService.this.active) {
                    if (ok != this.last_ok) {
                        if (ok) {
                            Log.i(TAG, "ConnectivityReceiver: triggering VPN resume");
                            OpenVPNService.this.network_resume();
                        } else {
                            Log.i(TAG, "ConnectivityReceiver: triggering VPN pause");
                            OpenVPNService.this.network_pause();
                        }
                        this.last_ok = ok;
                    } else if (screen_on_mod && this.screen_on && ok && !pvbs) {
                        Log.i(TAG, "ConnectivityReceiver: triggering special VPN resume");
                        OpenVPNService.this.network_resume();
                    } else if (conn_on_mod && failover && ok && this.initialized && time_since_last_action() > 10000) {
                        Log.i(TAG, "ConnectivityReceiver: triggering VPN reconnect");
                        OpenVPNService.this.network_reconnect(OpenVPNService.MSG_LOG);
                    }
                }
                if (conn_on_mod) {
                    this.initialized = true;
                    update_last_action_time();
                }
            }
        }

        private void update_last_action_time() {
            this.last_action_time = SystemClock.elapsedRealtime();
        }

        private long time_since_last_action() {
            return SystemClock.elapsedRealtime() - this.last_action_time;
        }
    }

    private static class DynamicChallenge {
        public Challenge challenge = new Challenge();
        public String cookie;
        public long expires;

        public boolean is_expired() {
            return SystemClock.elapsedRealtime() > this.expires;
        }

        public long expire_delay() {
            return this.expires - SystemClock.elapsedRealtime();
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "%s/%s/%d", this.challenge.toString(), this.cookie, this.expires);
        }
    }

    private static class EventInfo {
        public int flags;
        public int icon_res_id;
        public int priority;
        public int progress;
        public int res_id;

        public EventInfo(int res_id_arg, int icon_res_id_arg, int progress_arg, int priority_arg, int flags_arg) {
            this.res_id = res_id_arg;
            this.icon_res_id = icon_res_id_arg;
            this.progress = progress_arg;
            this.priority = priority_arg;
            this.flags = flags_arg;
        }
    }

    public static class EventMsg {
        public static final int F_ERROR = 1;
        public static final int F_EXCLUDE_SELF = 16;
        public static final int F_FROM_JAVA = 2;
        public static final int F_PROF_IMPORT = 32;
        public static final int F_PROF_MANAGE = 4;
        public static final int F_UI_RESET = 8;

        public ClientAPI_ConnectionInfo conn_info;
        public long expires = 0;
        public int flags = 0;
        public int icon_res_id = -1;
        public String info = "";
        public String name = "";
        public int priority = F_ERROR;
        public String profile_override;
        public int progress = 0;
        public int res_id = -1;
        public EventReceiver sender;
        public Transition transition = Transition.NO_CHANGE;

        public static EventMsg disconnected() {
            EventMsg e = new EventMsg();
            e.flags = F_FROM_JAVA;
            e.res_id = R.string.disconnected;
            e.icon_res_id = R.drawable.disconnected;
            e.name = "DISCONNECTED";
            e.info = "";
            return e;
        }

        public boolean is_expired() {
            return this.expires != 0 && SystemClock.elapsedRealtime() > this.expires;
        }

        public boolean is_reflected(EventReceiver caller) {
            if (this.sender == null) return false;
            return (this.flags & F_EXCLUDE_SELF) != 0 || this.sender != caller;
        }

        public String toStringFull() {
            return String.format(Locale.US, "EVENT: name=%s info='%s' trans=%s flags=%d progress=%d prio=%d res=%d",
                    this.name, this.info, this.transition, this.flags, this.progress, this.priority, this.res_id);
        }

        @Override
        public String toString() {
            StringBuilder buffer = new StringBuilder();
            buffer.append(String.format(Locale.US, "EVENT: %s", this.name));
            if (this.info != null && !this.info.isEmpty()) {
                buffer.append(String.format(Locale.US, " info='%s'", this.info));
            }
            if (this.transition != Transition.NO_CHANGE) {
                buffer.append(String.format(Locale.US, " trans=%s", this.transition));
            }
            return buffer.toString();
        }
    }

    public static class InternalError extends RuntimeException {}

    public class LocalBinder extends Binder {
        public OpenVPNService getService() {
            return OpenVPNService.this;
        }
    }

    public static class LogMsg {
        public String line;
    }

    public class Profile {
        private boolean allow_password_save;
        private boolean autologin;
        private DynamicChallenge dynamic_challenge;
        private String errorText;
        private boolean external_pki;
        private String external_pki_alias;
        public String location;
        private String name;
        public String orig_filename;
        private boolean private_key_password_required;
        private ProxyContext proxy_context;
        private ServerList server_list;
        private Challenge static_challenge;
        private String userlocked_username;

        private Profile(String location_arg, String filename, boolean filename_is_url_encoded_profile_name, ClientAPI_EvalConfig ec) {
            this.location = location_arg;
            this.orig_filename = filename;
            if (filename_is_url_encoded_profile_name) {
                this.name = filename;
                if (ProfileFN.has_ovpn_ext(this.name)) {
                    this.name = ProfileFN.strip_ovpn_ext(this.name);
                }
                try {
                    this.name = URLDecoder.decode(this.name, "UTF-8");
                } catch (UnsupportedEncodingException e) {
                    Log.e(TAG, "UnsupportedEncodingException when decoding profile filename", e);
                }
            } else {
                this.name = filename;
            }

            if (ec.getError()) {
                this.errorText = ec.getMessage();
                return;
            }

            this.userlocked_username = ec.getUserlockedUsername();
            this.autologin = ec.getAutologin();
            this.external_pki = ec.getExternalPki();
            this.private_key_password_required = ec.getPrivateKeyPasswordRequired();
            this.allow_password_save = ec.getAllowPasswordSave();
            
            String cs = ec.getStaticChallenge();
            if (cs != null && !cs.isEmpty()) {
                Challenge chal = new Challenge();
                chal.challenge = cs;
                chal.echo = ec.getStaticChallengeEcho();
                chal.response_required = true;
                this.static_challenge = chal;
            }

            if (!filename_is_url_encoded_profile_name) {
                String profile_name = ec.getProfileName();
                String friendly_name = ec.getFriendlyName();
                String loc = null;
                if (this.location != null && !this.location.equals("imported")) {
                    loc = this.location;
                }
                boolean is_friendly = false;
                String f1 = profile_name;
                if (friendly_name != null && !friendly_name.isEmpty()) {
                    f1 = friendly_name;
                    is_friendly = true;
                }
                String f2 = filename;
                if (f2 != null && f2.equalsIgnoreCase("client.ovpn")) {
                    f2 = null;
                }
                if (ProfileFN.has_ovpn_ext(f2)) {
                    f2 = ProfileFN.strip_ovpn_ext(f2);
                }
                if (f2 != null && f1 != null && f2.equals(f1)) {
                    f2 = null;
                }
                StringBuilder n = new StringBuilder();
                if (f1 != null) n.append(f1);
                if (this.autologin && !is_friendly && f2 == null) {
                    n.append(OpenVPNService.this.getText(R.string.autologin_suffix));
                }
                if (loc != null || f2 != null) {
                    n.append(" [");
                    if (loc != null) {
                        n.append(loc).append(":");
                    }
                    if (f2 != null) {
                        n.append(f2);
                    }
                    n.append("]");
                }
                this.name = n.toString();
            }

            this.server_list = new ServerList();
            ClientAPI_ServerEntryVector sev = ec.getServerList();
            int n2 = (int) sev.size();
            for (int i = 0; i < n2; i++) {
                ClientAPI_ServerEntry se = sev.get(i);
                ServerEntry e2 = new ServerEntry();
                e2.server = se.getServer();
                e2.friendly_name = se.getFriendlyName();
                this.server_list.list.add(e2);
            }
            this.external_pki_alias = OpenVPNService.this.prefs.get_string_by_profile(this.name, "epki_alias");
        }

        public String get_location() { return this.location; }
        public String get_filename() {
            if ("bundled".equals(this.location)) {
                return this.orig_filename;
            }
            String ret = ProfileFN.encode_profile_fn(this.name);
            return ret == null ? this.orig_filename : ret;
        }
        public String get_error() { return this.errorText; }
        public String get_type_string() {
            if (get_autologin()) return OpenVPNService.this.getString(R.string.profile_type_autologin);
            if (get_epki()) return OpenVPNService.this.getString(R.string.profile_type_epki);
            return OpenVPNService.this.getString(R.string.profile_type_standard);
        }
        public String get_name() { return this.name; }
        public String get_userlocked_username() { return this.userlocked_username; }
        public boolean get_autologin() { return this.autologin; }
        public ServerList get_server_list() { return this.server_list; }
        public boolean server_list_defined() { return !this.server_list.list.isEmpty(); }
        public boolean userlocked_username_defined() { return this.userlocked_username != null && !this.userlocked_username.isEmpty(); }
        public boolean need_external_pki_alias() { return this.external_pki && this.external_pki_alias == null; }
        public boolean have_external_pki_alias() { return this.external_pki && this.external_pki_alias != null; }
        public boolean get_private_key_password_required() { return this.private_key_password_required; }
        public boolean get_allow_password_save() { return this.allow_password_save; }
        public boolean is_deleteable() { return !("bundled".equals(this.location) || this.location == null); }
        public boolean is_renameable() { return is_deleteable(); }

        public ProxyContext get_proxy_context(boolean create_if_necessary) {
            if (this.proxy_context != null && !this.proxy_context.is_expired()) {
                return this.proxy_context;
            }
            this.proxy_context = create_if_necessary ? new ProxyContext() : null;
            return this.proxy_context;
        }

        public void reset_proxy_context() { this.proxy_context = null; }

        public boolean is_dynamic_challenge() {
            expire_dynamic_challenge();
            return this.dynamic_challenge != null;
        }

        public long get_dynamic_challenge_expire_delay() {
            return is_dynamic_challenge() ? this.dynamic_challenge.expire_delay() : 0;
        }

        public boolean challenge_defined() {
            expire_dynamic_challenge();
            return this.static_challenge != null || this.dynamic_challenge != null;
        }

        public Challenge get_challenge() {
            expire_dynamic_challenge();
            if (this.dynamic_challenge != null) {
                return this.dynamic_challenge.challenge;
            }
            return this.static_challenge;
        }

        public void reset_dynamic_challenge() { this.dynamic_challenge = null; }

        private void expire_dynamic_challenge() {
            if (this.dynamic_challenge != null && this.dynamic_challenge.is_expired()) {
                this.dynamic_challenge = null;
            }
        }

        private boolean get_epki() { return this.external_pki; }
        private String get_epki_alias() { return this.external_pki_alias; }

        private void persist_epki_alias(String epki_alias) {
            this.external_pki_alias = epki_alias;
            OpenVPNService.this.prefs.set_string_by_profile(this.name, "epki_alias", epki_alias);
            OpenVPNService.this.jellyBeanHackPurge();
        }

        private void invalidate_epki_alias(String epki_alias) {
            if (this.external_pki_alias != null && this.external_pki_alias.equals(epki_alias)) {
                this.external_pki_alias = null;
                OpenVPNService.this.prefs.delete_key_by_profile(this.name, "epki_alias");
                OpenVPNService.this.jellyBeanHackPurge();
            }
        }

        public void forget_cert() {
            if (this.external_pki_alias != null) {
                this.external_pki_alias = null;
                OpenVPNService.this.prefs.delete_key_by_profile(this.name, "epki_alias");
                OpenVPNService.this.jellyBeanHackPurge();
            }
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "Profile name='%s' ofn='%s' userlock=%s auto=%b epki=%b/%s sl=%s sc=%s dc=%s",
                    this.name, this.orig_filename, this.userlocked_username, this.autologin,
                    this.external_pki, this.external_pki_alias, this.server_list,
                    this.static_challenge != null ? this.static_challenge.toString() : "null",
                    this.dynamic_challenge != null ? this.dynamic_challenge.toString() : "null");
        }
    }

    public class MergedProfile extends Profile {
        public String profile_content;
        private MergedProfile(String location_arg, String filename, boolean filename_is_url_encoded_profile_name, ClientAPI_EvalConfig ec) {
            super(location_arg, filename, filename_is_url_encoded_profile_name, ec);
        }
    }

    private static class ProfileFN {
        public static boolean has_ovpn_ext(String fn) {
            return fn != null && (fn.endsWith(".ovpn") || fn.endsWith(".OVPN"));
        }

        public static String strip_ovpn_ext(String fn) {
            if (fn == null || !has_ovpn_ext(fn)) return fn;
            return fn.substring(0, fn.length() - 5);
        }

        public static String encode_profile_fn(String name) {
            try {
                return URLEncoder.encode(name, "UTF-8") + ".ovpn";
            } catch (UnsupportedEncodingException e) {
                Log.e(TAG, "UnsupportedEncodingException when encoding profile filename", e);
                return null;
            }
        }
    }

    public class ProfileList extends ArrayList<Profile> {
        private class CustomComparator implements Comparator<Profile> {
            @Override
            public int compare(Profile p1, Profile p2) {
                return p1.name.compareTo(p2.name);
            }
        }

        private void load_profiles(String location) throws IOException {
            String storage_title;
            String[] fnlist;
            boolean filename_is_url_encoded_profile_name;

            if ("bundled".equals(location)) {
                storage_title = "assets";
                fnlist = OpenVPNService.this.getResources().getAssets().list("");
                filename_is_url_encoded_profile_name = true;
            } else if ("imported".equals(location)) {
                storage_title = "app private storage";
                fnlist = OpenVPNService.this.fileList();
                filename_is_url_encoded_profile_name = true;
            } else {
                throw new InternalError();
            }

            if (fnlist == null) return;

            for (String fn : fnlist) {
                if (ProfileFN.has_ovpn_ext(fn)) {
                    try {
                        String profile_content = OpenVPNService.this.read_file(location, fn);
                        ClientAPI_Config config = new ClientAPI_Config();
                        config.setContent(profile_content);
                        ClientAPI_EvalConfig ec = ClientAPI_OpenVPNClient.eval_config_static(config);
                        if (ec.getError()) {
                            Log.i(TAG, String.format(Locale.US, "PROFILE: error evaluating %s: %s", fn, ec.getMessage()));
                        } else {
                            add(new Profile(location, fn, filename_is_url_encoded_profile_name, ec));
                        }
                    } catch (IOException e) {
                        Log.i(TAG, String.format(Locale.US, "PROFILE: error reading %s from %s", fn, storage_title));
                    }
                }
            }
        }

        public String[] profile_names() {
            String[] ret = new String[size()];
            for (int i = 0; i < size(); i++) {
                ret[i] = get(i).name;
            }
            return ret;
        }

        public Profile get_profile_by_name(String name) {
            if (name != null) {
                for (Profile prof : this) {
                    if (name.equals(prof.name)) {
                        return prof;
                    }
                }
            }
            return null;
        }

        public void forget_certs() {
            OpenVPNService.this.jellyBeanHackPurge();
            for (Profile prof : this) {
                prof.forget_cert();
            }
        }

        private void invalidate_epki_alias(String epki_alias) {
            for (Profile prof : this) {
                prof.invalidate_epki_alias(epki_alias);
            }
        }

        private void sort() {
            Collections.sort(this, new CustomComparator());
        }
    }

    private static class ProxyContext {
        private boolean allow_creds_dialog;
        private Intent connect_intent;
        private long expires;
        private boolean explicit_creds;
        private int n_retries;
        private String profile_name;
        private Item proxy;
        private String proxy_password;
        private String proxy_username;

        public void new_connection(Intent connect_intent, String profile_name, String proxy_name, String username, String password, boolean allow_creds_dialog, ProxyList proxy_list, boolean proxy_retry) {
            if (!proxy_retry) {
                Item p = proxy_list.get(proxy_name);
                if (p != null) {
                    this.proxy = p;
                    this.profile_name = profile_name;
                    this.connect_intent = connect_intent;
                    this.allow_creds_dialog = allow_creds_dialog;
                    this.n_retries = 0;
                    this.expires = SystemClock.elapsedRealtime() + 120000;
                    if (!this.explicit_creds) {
                        if (username == null || password == null) {
                            this.proxy_username = p.username;
                            this.proxy_password = p.password;
                        } else {
                            this.proxy_username = username;
                            this.proxy_password = password;
                        }
                    }
                    return;
                }
                reset();
            }
        }

        public Intent submit_proxy_creds(String proxy_name, String username, String password, boolean remember_creds, ProxyList proxy_list) {
            if (this.proxy == null || !this.proxy.name().equals(proxy_name) || username == null || password == null) {
                return null;
            }
            this.proxy_username = username;
            this.proxy_password = password;
            this.explicit_creds = true;
            if (remember_creds) {
                this.proxy.username = username;
                this.proxy.password = password;
                this.proxy.remember_creds = remember_creds;
                proxy_list.put(this.proxy);
                proxy_list.save();
            }
            this.n_retries++;
            return this.connect_intent;
        }

public void client_api_config(ClientAPI_Config config) {
            if (this.proxy == null) {
                return;
            }

            // ต้องมี host และ port ครบก่อนส่งเข้า OpenVPN
            String host = this.proxy.host != null ? this.proxy.host.trim() : "";
            String port = this.proxy.port != null ? this.proxy.port.trim() : "";

            if (host.length() == 0 || port.length() == 0) {
                Log.w(TAG, "Skip http-proxy: host or port is empty");
                return;
            }

            config.setProxyHost(host);
            config.setProxyPort(port);

            if (this.proxy_username != null && this.proxy_password != null
                    && this.proxy_username.length() > 0) {
                config.setProxyUsername(this.proxy_username);
                config.setProxyPassword(this.proxy_password);
            }

            config.setProxyAllowCleartextAuth(this.proxy.allow_cleartext_auth);
        }

        public boolean should_launch_creds_dialog() {
            return this.proxy != null && this.allow_creds_dialog;
        }

        public void configure_creds_dialog_intent(Intent intent) {
            if (this.proxy != null && this.profile_name != null) {
                intent.putExtra("net.openvpn.openvpn.PROFILE", this.profile_name);
                intent.putExtra("net.openvpn.openvpn.PROXY_NAME", this.proxy.name());
                intent.putExtra("net.openvpn.openvpn.N_RETRIES", this.n_retries);
                intent.putExtra("net.openvpn.openvpn.EXPIRES", this.expires);
            }
        }

        public void invalidate_proxy_creds(ProxyList proxy_list) {
            if (this.proxy != null && this.proxy.invalidate_creds()) {
                proxy_list.put(this.proxy);
                proxy_list.save();
            }
            this.proxy_username = null;
            this.proxy_password = null;
        }

        public String name() {
            return this.proxy != null ? this.proxy.name() : null;
        }

        public boolean is_expired() {
            return this.expires != 0 && SystemClock.elapsedRealtime() > this.expires;
        }

        private void reset() {
            this.profile_name = null;
            this.proxy = null;
            this.connect_intent = null;
            this.expires = 0;
            this.explicit_creds = false;
            this.proxy_username = null;
            this.proxy_password = null;
            this.allow_creds_dialog = false;
            this.n_retries = 0;
        }
    }

    public static class ServerEntry {
        private String friendly_name;
        private String server;

        public String display_name() {
            return (this.friendly_name != null && !this.friendly_name.isEmpty()) ? this.friendly_name : this.server;
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "%s/%s", this.server, this.friendly_name);
        }
    }

    public static class ServerList {
        private final ArrayList<ServerEntry> list = new ArrayList<>();

        public String[] display_names() {
            int size = this.list.size();
            String[] ret = new String[size];
            for (int i = 0; i < size; i++) {
                ret[i] = this.list.get(i).display_name();
            }
            return ret;
        }

        @Override
        public String toString() {
            StringBuilder buffer = new StringBuilder();
            for (ServerEntry se : this.list) {
                buffer.append(se.toString()).append(",");
            }
            return buffer.toString();
        }
    }

    private class TunBuilder extends VpnService.Builder implements net.openvpn.openvpn.OpenVPNClientThread.TunBuilder {
        public TunBuilder() {
            super();
        }

        @Override
        public boolean tun_builder_set_remote_address(String address, boolean ipv6) {
            try {
                Log.d(TAG, String.format(Locale.US, "BUILDER: set_remote_address %s ipv6=%b", address, ipv6));
                return true;
            } catch (Exception e) {
                log_error("tun_builder_set_remote_address", e);
                return false;
            }
        }

        @Override
        public boolean tun_builder_add_address(String address, int prefix_length, String gateway, boolean ipv6, boolean net30) {
            try {
                Log.d(TAG, String.format(Locale.US, "BUILDER: add_address %s/%d %s ipv6=%b net30=%b", address, prefix_length, gateway, ipv6, net30));
                addAddress(address, prefix_length);
                return true;
            } catch (Exception e) {
                log_error("tun_builder_add_address", e);
                return false;
            }
        }

        @Override
        public boolean tun_builder_reroute_gw(boolean ipv4, boolean ipv6, long flags) {
            try {
                Log.d(TAG, String.format(Locale.US, "BUILDER: reroute_gw ipv4=%b ipv6=%b flags=%d", ipv4, ipv6, flags));
                if ((65536 & flags) != 0) return true;
                if (ipv4) addRoute("0.0.0.0", 0);
                if (ipv6) addRoute("::", 0);
                return true;
            } catch (Exception e) {
                log_error("tun_builder_reroute_gw", e);
                return false;
            }
        }

        @Override
        public boolean tun_builder_add_route(String address, int prefix_length, boolean ipv6) {
            try {
                Log.d(TAG, String.format(Locale.US, "BUILDER: add_route %s/%d ipv6=%b", address, prefix_length, ipv6));
                addRoute(address, prefix_length);
                return true;
            } catch (Exception e) {
                log_error("tun_builder_add_route", e);
                return false;
            }
        }

        @Override
        public boolean tun_builder_exclude_route(String address, int prefix_length, boolean ipv6) {
            try {
                Log.d(TAG, String.format(Locale.US, "BUILDER: exclude_route %s/%d ipv6=%b (NOT IMPLEMENTED)", address, prefix_length, ipv6));
                return true;
            } catch (Exception e) {
                log_error("tun_builder_exclude_route", e);
                return false;
            }
        }

        @Override
        public boolean tun_builder_add_dns_server(String address, boolean ipv6) {
            try {
                Log.d(TAG, String.format(Locale.US, "BUILDER: add_dns_server %s ipv6=%b", address, ipv6));
                addDnsServer(address);
                return true;
            } catch (Exception e) {
                log_error("tun_builder_add_dns_server", e);
                return false;
            }
        }

        @Override
        public boolean tun_builder_add_search_domain(String domain) {
            try {
                Log.d(TAG, String.format(Locale.US, "BUILDER: add_search_domain %s", domain));
                addSearchDomain(domain);
                return true;
            } catch (Exception e) {
                log_error("tun_builder_add_search_domain", e);
                return false;
            }
        }

        @Override
        public boolean tun_builder_set_mtu(int mtu) {
            try {
                Log.d(TAG, String.format(Locale.US, "BUILDER: set_mtu %d", mtu));
                setMtu(mtu);
                return true;
            } catch (Exception e) {
                log_error("tun_builder_set_mtu", e);
                return false;
            }
        }

        @Override
        public boolean tun_builder_set_session_name(String name) {
            try {
                Log.d(TAG, String.format(Locale.US, "BUILDER: set_session_name %s", name));
                setSession("Open Vpn");
                return true;
            } catch (Exception e) {
                log_error("tun_builder_set_session_name", e);
                return false;
            }
        }

        @Override
        public int tun_builder_establish() {
            try {
                Log.d(TAG, "BUILDER: establish");
                PendingIntent pi = OpenVPNService.this.get_configure_intent(GCI_REQ_ESTABLISH);
                if (pi != null) setConfigureIntent(pi);
                return establish().detachFd();
            } catch (Exception e) {
                log_error("tun_builder_establish", e);
                return -1;
            }
        }

        @Override
        public void tun_builder_teardown(boolean disconnect) {
            try {
                Log.d(TAG, String.format(Locale.US, "BUILDER: teardown disconnect=%b", disconnect));
            } catch (Exception e) {
                log_error("tun_builder_teardown", e);
            }
        }

        private void log_error(String meth_name, Exception e) {
            Log.d(TAG, String.format(Locale.US, "BUILDER_ERROR: %s %s", meth_name, e.toString()));
        }
    }

    static {
        System.loadLibrary("ovpncli");
        ClientAPI_OpenVPNClient.init_process();
        Log.d(TAG, ClientAPI_OpenVPNClient.crypto_self_test());
    }

    public ArrayDeque<LogMsg> log_history() {
        return this.log_deque;
    }

    private void register_connectivity_receiver() {
        this.mConnectivityReceiver = new ConnectivityReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction("android.net.conn.CONNECTIVITY_CHANGE");
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(this.mConnectivityReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(this.mConnectivityReceiver, filter);
        }
    }

    private void unregister_connectivity_receiver() {
        if (this.mConnectivityReceiver != null) {
            unregisterReceiver(this.mConnectivityReceiver);
        }
    }

    public void jellyBeanHackPurge() {
        if (this.jellyBeanHack != null) {
            this.jellyBeanHack.resetPrivateKey();
        }
    }

    private void crypto_self_test() {
        String st = ClientAPI_OpenVPNClient.crypto_self_test();
        if (st != null && !st.isEmpty()) {
            Log.d(TAG, String.format(Locale.US, "SERV: crypto_self_test\n%s", st));
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "SERV: Service onCreate called");
        crypto_self_test();
        this.mHandler = new Handler(this);
        populate_event_info_map();
        register_connectivity_receiver();
        this.prefs = new PrefUtil(PreferenceManager.getDefaultSharedPreferences(this));
        this.pwds = new PasswordUtil(PreferenceManager.getDefaultSharedPreferences(this));
        this.jellyBeanHack = JellyBeanHack.newJellyBeanHack();
        this.proxy_list = new ProxyList(resString(R.string.proxy_none));
        this.proxy_list.set_backing_file(this, "proxies.json");
        this.proxy_list.load();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String prefix = INTENT_PREFIX;
            String action = intent.getAction();
            Log.d(TAG, String.format(Locale.US, "SERV: onStartCommand action=%s", action));

            try {
                if (action.equals(ACTION_CONNECT)) {
                    connect_action(prefix, intent, false);
                } else if (action.equals(ACTION_SUBMIT_PROXY_CREDS)) {
                    submit_proxy_creds_action(prefix, intent);
                } else if (action.equals(ACTION_DISCONNECT)) {
                    disconnect_action(prefix, intent);
                } else if (action.equals(ACTION_IMPORT_PROFILE)) {
                    import_profile_action(prefix, intent);
                } else if (action.equals(ACTION_IMPORT_PROFILE_VIA_PATH)) {
                    import_profile_via_path_action(prefix, intent);
                } else if (action.equals(ACTION_DELETE_PROFILE)) {
                    delete_profile_action(prefix, intent);
                } else if (action.equals(ACTION_RENAME_PROFILE)) {
                    rename_profile_action(prefix, intent);
                }
            } catch (IOException e) {
                Log.e(TAG, "Error handling start command action", e);
            }
        }
        return START_STICKY;
    }

    private boolean import_profile_via_path_action(String prefix, Intent intent) {
        ClientAPI_MergeConfig mc = ClientAPI_OpenVPNClient.merge_config_static(intent.getStringExtra(prefix + ".PATH"), true);
        String status = "PROFILE_" + mc.getStatus();
        if (status.equals("PROFILE_MERGE_SUCCESS")) {
            return import_profile(mc.getProfileContent(), mc.getBasename(), false);
        }
        gen_event(MSG_EVENT, status, mc.getErrorText());
        return false;
    }

    private boolean import_profile_action(String prefix, Intent intent) {
        return import_profile(
                intent.getStringExtra(prefix + ".CONTENT"),
                intent.getStringExtra(prefix + ".FILENAME"),
                intent.getBooleanExtra(prefix + ".MERGE", false)
        );
    }

    private boolean import_profile(String profile_content, String filename, boolean merge) {
        if (ProfileFN.has_ovpn_ext(filename) && FileUtil.dirname(filename) == null) {
            if (merge) {
                ClientAPI_MergeConfig mc = ClientAPI_OpenVPNClient.merge_config_string_static(profile_content);
                String status = "PROFILE_" + mc.getStatus();
                if (status.equals("PROFILE_MERGE_SUCCESS")) {
                    profile_content = mc.getProfileContent();
                } else {
                    gen_event(MSG_EVENT, status, mc.getErrorText());
                    return false;
                }
            }
            ClientAPI_Config config = new ClientAPI_Config();
            config.setContent(profile_content);
            ClientAPI_EvalConfig ec = ClientAPI_OpenVPNClient.eval_config_static(config);
            if (ec.getError()) {
                gen_event(MSG_EVENT, "PROFILE_PARSE_ERROR", String.format(Locale.US, "%s : %s", filename, ec.getMessage()));
                return false;
            }
            Profile prof = new Profile("imported", filename, false, ec);
            try {
                FileUtil.writeFileAppPrivate(this, prof.get_filename(), profile_content);
                String profile_name = prof.get_name();
                this.pwds.remove("auth", profile_name);
                this.pwds.remove("pk", profile_name);
                refresh_profile_list();
                gen_event(GCI_REQ_ESTABLISH, "PROFILE_IMPORT_SUCCESS", profile_name, profile_name);
                return true;
            } catch (IOException e) {
                gen_event(MSG_EVENT, "PROFILE_WRITE_ERROR", filename);
                return false;
            }
        }
        gen_event(MSG_EVENT, "PROFILE_FILENAME_ERROR", filename);
        return false;
    }

    private boolean delete_profile_action(String prefix, Intent intent) throws IOException {
        String profile_name = intent.getStringExtra(prefix + ".PROFILE");
        get_profile_list();
        Profile profile = this.profile_list.get_profile_by_name(profile_name);
        if (profile == null) return false;

        if (profile.is_deleteable()) {
            if (this.active && profile == this.current_profile) {
                stop_thread();
            }
            if (deleteFile(profile.get_filename())) {
                this.pwds.remove("auth", profile_name);
                this.pwds.remove("pk", profile_name);
                refresh_profile_list();
                gen_event(GCI_REQ_ESTABLISH, "PROFILE_DELETE_SUCCESS", profile.get_name());
                return true;
            }
            gen_event(MSG_EVENT, "PROFILE_DELETE_FAILED", profile.get_name());
            return false;
        }
        gen_event(MSG_EVENT, "PROFILE_DELETE_FAILED", profile_name);
        return false;
    }

    private boolean rename_profile_action(String prefix, Intent intent) throws IOException {
        String profile_name = intent.getStringExtra(prefix + ".PROFILE");
        String new_profile_name = intent.getStringExtra(prefix + ".NEW_PROFILE");
        get_profile_list();
        Profile profile = this.profile_list.get_profile_by_name(profile_name);
        if (profile == null) return false;

        if (!profile.is_renameable() || new_profile_name == null || new_profile_name.isEmpty()) {
            Log.d(TAG, "PROFILE_RENAME_FAILED: rename preliminary checks");
            gen_event(MSG_EVENT, "PROFILE_RENAME_FAILED", profile_name);
            return false;
        }
        File dir = getFilesDir();
        String from_path = String.format(Locale.US, "%s/%s", dir.getPath(), profile.orig_filename);
        String to_path = String.format(Locale.US, "%s/%s", dir.getPath(), ProfileFN.encode_profile_fn(new_profile_name));

        if (FileUtil.renameFile(from_path, to_path)) {
            refresh_profile_list();
            Profile new_profile = this.profile_list.get_profile_by_name(new_profile_name);
            if (new_profile == null) {
                Log.d(TAG, "PROFILE_RENAME_FAILED: post-rename profile get");
                gen_event(MSG_EVENT, "PROFILE_RENAME_FAILED", profile_name);
                return false;
            }
            this.pwds.remove("auth", profile_name);
            this.pwds.remove("pk", profile_name);
            gen_event(GCI_REQ_ESTABLISH, "PROFILE_RENAME_SUCCESS", new_profile.get_name(), new_profile.get_name());
            return true;
        }
        Log.d(TAG, String.format(Locale.US, "PROFILE_RENAME_FAILED: rename operation from='%s' to='%s'", from_path, to_path));
        gen_event(MSG_EVENT, "PROFILE_RENAME_FAILED", profile_name);
        return false;
    }

    private Profile locate_profile(String profile_name) throws IOException {
        get_profile_list();
        Profile profile = this.profile_list.get_profile_by_name(profile_name);
        if (profile != null) return profile;
        gen_event(MSG_EVENT, "PROFILE_NOT_FOUND", profile_name);
        return null;
    }

    private boolean submit_proxy_creds_action(String prefix, Intent intent) throws IOException {
        Profile profile = locate_profile(intent.getStringExtra(prefix + ".PROFILE"));
        if (profile != null) {
            ProxyContext proxy_context = profile.get_proxy_context(false);
            if (proxy_context != null) {
                Intent connect_intent = proxy_context.submit_proxy_creds(
                        intent.getStringExtra(prefix + ".PROXY_NAME"),
                        intent.getStringExtra(prefix + ".PROXY_USERNAME"),
                        intent.getStringExtra(prefix + ".PROXY_PASSWORD"),
                        intent.getBooleanExtra(prefix + ".PROXY_REMEMBER_CREDS", false),
                        this.proxy_list
                );
                if (connect_intent != null) {
                    connect_action(prefix, connect_intent, true);
                    return true;
                }
            }
        }
        gen_event(MSG_EVENT, "PROXY_CONTEXT_EXPIRED", null);
        return false;
    }

    private boolean connect_action(final String prefix, final Intent intent, final boolean proxy_retry) {
        if (this.active) {
            stop_thread();
            new Handler().postDelayed(() -> {
                try {
                    OpenVPNService.this.do_connect_action(prefix, intent, proxy_retry);
                } catch (IOException e) {
                    Log.e(TAG, "Delayed connect action failed", e);
                }
            }, 2000);
        } else {
            try {
                do_connect_action(prefix, intent, proxy_retry);
            } catch (IOException e) {
                Log.e(TAG, "Connect action failed", e);
            }
        }
        return true;
    }

    private boolean do_connect_action(String prefix, Intent intent, boolean proxy_retry) throws IOException {
        String profile_name = intent.getStringExtra(prefix + ".PROFILE");
        String gui_version = intent.getStringExtra(prefix + ".GUI_VERSION");
        String proxy_name = intent.getStringExtra(prefix + ".PROXY_NAME");
        String proxy_username = intent.getStringExtra(prefix + ".PROXY_USERNAME");
        String proxy_password = intent.getStringExtra(prefix + ".PROXY_PASSWORD");
        boolean proxy_allow_creds_dialog = intent.getBooleanExtra(prefix + ".PROXY_ALLOW_CREDS_DIALOG", false);
        String server = intent.getStringExtra(prefix + ".SERVER");
        String proto = intent.getStringExtra(prefix + ".PROTO");
        String ipv6 = intent.getStringExtra(prefix + ".IPv6");
        String conn_timeout = intent.getStringExtra(prefix + ".CONN_TIMEOUT");
        String username = intent.getStringExtra(prefix + ".USERNAME");
        String password = intent.getStringExtra(prefix + ".PASSWORD");
        boolean cache_password = intent.getBooleanExtra(prefix + ".CACHE_PASSWORD", false);
        String pk_password = intent.getStringExtra(prefix + ".PK_PASSWORD");
        String response = intent.getStringExtra(prefix + ".RESPONSE");
        String epki_alias = intent.getStringExtra(prefix + ".EPKI_ALIAS");
        String compression_mode = intent.getStringExtra(prefix + ".COMPRESSION_MODE");

        password = OpenVPNDebug.pw_repl(username, password);
        Profile profile = locate_profile(profile_name);
        if (profile == null) return false;

        ProxyContext proxy_context;
        if (proxy_name != null) {
            proxy_context = profile.get_proxy_context(true);
            proxy_context.new_connection(intent, profile_name, proxy_name, proxy_username, proxy_password, proxy_allow_creds_dialog, this.proxy_list, proxy_retry);
        } else {
            proxy_context = null;
            profile.reset_proxy_context();
        }

        String location = profile.get_location();
        String filename = profile.get_filename();
        try {
            String profile_content = read_file(location, filename);
            Log.d(TAG, String.format(Locale.US, "SERV: profile file len=%d", profile_content.length()));
            return start_connection(profile, profile_content, gui_version, proxy_context, server, proto, ipv6, conn_timeout, username, password, cache_password, pk_password, response, epki_alias, compression_mode);
        } catch (IOException e) {
            gen_event(MSG_EVENT, "PROFILE_NOT_FOUND", String.format(Locale.US, "%s/%s", location, filename));
            return false;
        }
    }

    private void disconnect_action(String prefix, Intent intent) {
        boolean stop = intent.getBooleanExtra(prefix + ".STOP", false);
        stop_thread();
        if (stop) {
            stopSelf();
        }
    }

    private boolean start_connection(Profile profile, String profile_content, String gui_version, ProxyContext proxy_context, String server, String proto, String ipv6, String conn_timeout, String username, String password, boolean cache_password, String pk_password, String response, String epki_alias, String compression_mode) {
        if (this.active) return false;

        this.enable_notifications = this.prefs.get_boolean("enable_notifications", false);
        OpenVPNClientThread thread = new OpenVPNClientThread();
        ClientAPI_Config config = new ClientAPI_Config();
        config.setContent(profile_content);
        config.setInfo(true);

        if (server != null) config.setServerOverride(server);
        if (proto != null) config.setProtoOverride(proto);
        if (ipv6 != null) config.setIpv6(ipv6);

        if (conn_timeout != null) {
            int ct = 0;
            try { ct = Integer.parseInt(conn_timeout); } catch (NumberFormatException ignored) {}
            config.setConnTimeout(ct);
        }

        if (compression_mode != null) config.setCompressionMode(compression_mode);
        if (pk_password != null) config.setPrivateKeyPassword(pk_password);

        boolean tun_persist = this.prefs.get_boolean("tun_persist", false);
        if (tun_persist && Build.VERSION.SDK_INT == 19) {
            Log.i(TAG, "Seamless Tunnel disabled for KitKat 4.4 - 4.4.2");
            tun_persist = false;
        }

        config.setTunPersist(tun_persist);
        config.setGoogleDnsFallback(this.prefs.get_boolean("google_dns_fallback", false));
        config.setForceAesCbcCiphersuites(this.prefs.get_boolean("force_aes_cbc_ciphersuites_v2", false));
        config.setAltProxy(this.prefs.get_boolean("alt_proxy", false));

        String tls_version_min_override = this.prefs.get_string("tls_version_min_override");
        if (tls_version_min_override != null) {
            config.setTlsVersionMinOverride(tls_version_min_override);
        }

        if (gui_version != null) config.setGuiVersion(gui_version);

        if (profile.get_epki()) {
            if (epki_alias != null) {
                profile.persist_epki_alias(epki_alias);
            } else {
                epki_alias = profile.get_epki_alias();
            }
            if (epki_alias != null) {
                if ("DISABLE_CLIENT_CERT".equals(epki_alias)) {
                    config.setDisableClientCert(true);
                } else {
                    config.setExternalPkiAlias(epki_alias);
                }
            }
        }

        if (proxy_context != null) proxy_context.client_api_config(config);

        ClientAPI_EvalConfig ec = thread.eval_config(config);
        if (ec.getError()) {
            gen_event(MSG_EVENT, "CONFIG_FILE_PARSE_ERROR", ec.getMessage());
            return false;
        }

        ClientAPI_ProvideCreds creds = new ClientAPI_ProvideCreds();
        if (profile.is_dynamic_challenge()) {
            if (response != null) creds.setResponse(response);
            creds.setDynamicChallengeCookie(profile.dynamic_challenge.cookie);
            profile.reset_dynamic_challenge();
        } else if (ec.getAutologin() || (username != null && !username.isEmpty())) {
            if (username != null) creds.setUsername(username);
            if (password != null) creds.setPassword(password);
            if (response != null) creds.setResponse(response);
        } else {
            gen_event(MSG_EVENT, "NEED_CREDS_ERROR", null);
            return false;
        }

        creds.setCachePassword(cache_password);
        creds.setReplacePasswordWithSessionID(true);

        ClientAPI_Status status = thread.provide_creds(creds);
        if (status.getError()) {
            gen_event(MSG_EVENT, "CREDS_ERROR", status.getMessage());
            return false;
        }

        Log.i(TAG, String.format(Locale.US, "SERV: CONNECT prof=%s user=%s proxy=%s serv=%s proto=%s ipv6=%s to=%s resp=%s epki_alias=%s comp=%s",
                profile.name, username, proxy_context != null ? proxy_context.name() : "undef",
                server, proto, ipv6, conn_timeout, response, epki_alias, compression_mode));

        this.current_profile = profile;
        set_autostart_profile_name(profile.get_name());
        start_notification();
        gen_event(GCI_REQ_ESTABLISH, "CORE_THREAD_ACTIVE", null);

        thread.connect(this);
        this.mThread = thread;
        this.thread_started = SystemClock.elapsedRealtime();
        this.cpu_usage = new CPUUsage();
        this.active = true;
        return true;
    }

    private void start_notification() {
        NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "OpenVPN Status",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setLightColor(Color.WHITE);
            channel.setShowBadge(true);
            if (mNotificationManager != null) {
                mNotificationManager.createNotificationChannel(channel);
            }
        }

        if (this.mNotifyBuilder == null && this.current_profile != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                this.mNotifyBuilder = new Notification.Builder(this, CHANNEL_ID);
            } else {
                this.mNotifyBuilder = new Notification.Builder(this);
            }

            this.mNotifyBuilder.setContentIntent(get_configure_intent(MSG_EVENT))
                    .setSmallIcon(R.drawable.icon)
                    .setContentTitle(this.current_profile.get_name())
                    .setContentText(resString(R.string.notification_initial_content))
                    .setOnlyAlertOnce(true)
                    .setOngoing(true)
                    .setWhen(System.currentTimeMillis());

            startForeground(NOTIFICATION_ID, this.mNotifyBuilder.build());
        }
    }

    private void update_notification_event(EventMsg evm) {
        if (this.mNotifyBuilder != null && evm.priority >= MSG_EVENT) {
            int iconRes = evm.icon_res_id;
            if (iconRes == R.drawable.connected || 
                iconRes == R.drawable.connecting || 
                iconRes == R.drawable.error) {
                this.mNotifyBuilder.setSmallIcon(R.drawable.openvpn_notification);
            } else {
                this.mNotifyBuilder.setSmallIcon(R.drawable.icon);
            }
            this.mNotifyBuilder.setContentText(resString(evm.res_id));
            startForeground(NOTIFICATION_ID, this.mNotifyBuilder.build());
        }
    }

    private void stop_notification() {
        if (this.mNotifyBuilder != null) {
            this.mNotifyBuilder = null;
            stopForeground(true);
        }
    }


    @Override
    public IBinder onBind(Intent intent) {
        if (intent == null || !ACTION_BIND.equals(intent.getAction())) {
            Log.d(TAG, String.format(Locale.US, "SERV: onBind SUPER intent=%s", intent));
            return super.onBind(intent);
        }
        Log.d(TAG, String.format(Locale.US, "SERV: onBind intent=%s", intent));
        return this.mBinder;
    }

    public void client_attach(EventReceiver evr) {
        this.clients.remove(evr);
        this.clients.addFirst(evr);
        Log.d(TAG, String.format(Locale.US, "SERV: client attach n_clients=%d", this.clients.size()));
    }

    public void client_detach(EventReceiver evr) {
        this.clients.remove(evr);
        Log.d(TAG, String.format(Locale.US, "SERV: client detach n_clients=%d", this.clients.size()));
    }

    public void refresh_profile_list() throws IOException {
        ProfileList pl = new ProfileList();
        pl.load_profiles("bundled");
        pl.load_profiles("imported");
        pl.sort();
        Log.d(TAG, "SERV: refresh profiles:");
        for (Profile p : pl) {
            Log.d(TAG, String.format(Locale.US, "SERV: %s", p.toString()));
        }
        this.profile_list = pl;
    }

    public Profile get_current_profile() throws IOException {
        if (this.current_profile != null) return this.current_profile;
        ProfileList pl = get_profile_list();
        return !pl.isEmpty() ? pl.get(0) : null;
    }

    public ProfileList get_profile_list() throws IOException {
        if (this.profile_list == null) {
            refresh_profile_list();
        }
        return this.profile_list;
    }

    public static long max_profile_size() {
        return (long) ClientAPI_OpenVPNClient.max_profile_size();
    }

    public MergedProfile merge_parse_profile(String basename, String profile_content) {
        if (basename == null || profile_content == null) return null;

        ClientAPI_MergeConfig mc = ClientAPI_OpenVPNClient.merge_config_string_static(profile_content);
        String status = "PROFILE_" + mc.getStatus();
        if (status.equals("PROFILE_MERGE_SUCCESS")) {
            String merged_content = mc.getProfileContent();
            ClientAPI_Config config = new ClientAPI_Config();
            config.setContent(merged_content);
            MergedProfile mp = new MergedProfile("imported", basename, false, ClientAPI_OpenVPNClient.eval_config_static(config));
            mp.profile_content = merged_content;
            return mp;
        }

        ClientAPI_EvalConfig ec = new ClientAPI_EvalConfig();
        EventInfo evi = this.event_info.get(status);
        if (evi != null) {
            status = resString(evi.res_id);
        }
        ec.setError(true);
        ec.setMessage(status + " : " + mc.getErrorText());
        return new MergedProfile("imported", basename, false, ec);
    }

    public void gen_ui_reset_event(boolean exclude_self, EventReceiver cli) {
        int flags = exclude_self ? 16 : 0;
        gen_event(flags, "UI_RESET", null, null, cli);
    }

    public void gen_proxy_context_expired_event() {
        gen_event(GCI_REQ_ESTABLISH, "PROXY_CONTEXT_EXPIRED", null);
    }

    public boolean is_active() { return this.active; }

    public EventMsg get_last_event() {
        if (this.last_event == null || this.last_event.is_expired()) return null;
        return this.last_event;
    }

    public EventMsg get_last_event_prof_manage() {
        if (this.last_event_prof_manage == null || this.last_event_prof_manage.is_expired()) return null;
        return this.last_event_prof_manage;
    }

    public void network_pause() {
        if (this.active && this.mThread != null) {
            this.mThread.pause("");
        }
    }

    public void network_resume() {
        if (this.active && this.mThread != null) {
            this.mThread.resume();
        }
    }

    public void network_reconnect(int seconds) {
        if (this.active && this.mThread != null) {
            this.mThread.reconnect(seconds);
        }
    }

    public ConnectionStats get_connection_stats() {
        ConnectionStats cs = new ConnectionStats();
        if (this.active && this.mThread != null) {
            ClientAPI_TransportStats stats = this.mThread.transport_stats();
            cs.duration = ((int) (SystemClock.elapsedRealtime() - this.thread_started)) / 1000;
            if (cs.duration < 0) cs.duration = 0;
            cs.bytes_in = stats.getBytesIn();
            cs.bytes_out = stats.getBytesOut();
            int lpr_bms = stats.getLastPacketReceived();
            cs.last_packet_received = lpr_bms >= 0 ? lpr_bms >> 10 : -1;
        } else {
            cs.duration = 0;
            cs.bytes_in = 0;
            cs.bytes_out = 0;
            cs.last_packet_received = -1;
        }
        return cs;
    }

    public long get_tunnel_bytes_per_cpu_second() {
        if (this.cpu_usage != null && this.mThread != null) {
            double cpu_seconds = this.cpu_usage.usage();
            if (cpu_seconds > 0.0d) {
                ClientAPI_InterfaceStats stats = this.mThread.tun_stats();
                return (long) (((double) (stats.getBytesIn() + stats.getBytesOut())) / cpu_seconds);
            }
        }
        return 0;
    }

    private PendingIntent get_configure_intent(int requestCode) {
        for (EventReceiver client : this.clients) {
            PendingIntent pi = client.get_configure_intent(requestCode);
            if (pi != null) return pi;
        }
        return null;
    }

    private void stop_thread() {
        if (this.active && this.mThread != null) {
            this.mThread.stop();
            this.mThread.wait_thread_short();
            Log.d(TAG, "SERV: stop_thread succeeded");
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.d(TAG, String.format(Locale.US, "SERV: onUnbind called intent=%s", intent));
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "SERV: onDestroy called");
        this.shutdown_pending = true;
        stop_thread();
        unregister_connectivity_receiver();
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        Log.d(TAG, "SERV: onRevoke called");
        stop_thread();
    }

    private void gen_event(int flags, String name, String extra_info) {
        gen_event(flags, name, extra_info, null, null);
    }

    private void gen_event(int flags, String name, String extra_info, String profile_override) {
        gen_event(flags, name, extra_info, profile_override, null);
    }

    private void gen_event(int flags, String name, String extra_info, String profile_override, EventReceiver sender) {
        EventInfo evi = this.event_info.get(name);
        EventMsg evm = new EventMsg();
        evm.flags = flags | MSG_LOG;
        if (evi != null) {
            evm.progress = evi.progress;
            evm.priority = evi.priority;
            evm.res_id = evi.res_id;
            evm.icon_res_id = evi.icon_res_id;
            evm.sender = sender;
            evm.flags |= evi.flags;
        } else {
            evm.res_id = R.string.unknown;
        }
        evm.name = name;
        evm.info = extra_info != null ? extra_info : "";

        if ((evm.flags & 4) != 0) {
            evm.expires = SystemClock.elapsedRealtime() + 60000;
        }
        evm.profile_override = profile_override;
        this.mHandler.sendMessage(this.mHandler.obtainMessage(MSG_EVENT, evm));
    }

    @Override
    public boolean handleMessage(Message msg) {
        EventMsg lastev = get_last_event();
        switch (msg.what) {
            case MSG_EVENT:
                EventMsg evm = (EventMsg) msg.obj;
                if (evm.res_id == R.string.connected) {
                    if (this.current_profile != null) {
                        this.current_profile.reset_proxy_context();
                    }
                } else if (evm.res_id == R.string.core_thread_inactive) {
                    if (this.cpu_usage != null) {
                        this.cpu_usage.stop();
                    }
                    stop_notification();
                    if (!this.shutdown_pending) {
                        set_autostart_profile_name(null);
                    }
                } else if (evm.res_id == R.string.disconnected) {
                    if (lastev != null) {
                        if ((lastev.flags & MSG_EVENT) != 0) {
                            evm.priority = GCI_REQ_ESTABLISH;
                        }
                        if (this.current_profile != null && lastev.res_id != R.string.proxy_need_creds && lastev.res_id != R.string.dynamic_challenge) {
                            this.current_profile.reset_proxy_context();
                        }
                    }
                } else if (evm.res_id == R.string.dynamic_challenge) {
                    if (this.current_profile != null) {
                        ClientAPI_DynamicChallenge dcsrc = new ClientAPI_DynamicChallenge();
                        if (ClientAPI_OpenVPNClient.parse_dynamic_challenge(evm.info, dcsrc)) {
                            DynamicChallenge dc = new DynamicChallenge();
                            dc.expires = SystemClock.elapsedRealtime() + 60000;
                            dc.cookie = evm.info;
                            dc.challenge.challenge = dcsrc.getChallenge();
                            dc.challenge.echo = dcsrc.getEcho();
                            dc.challenge.response_required = dcsrc.getResponseRequired();
                            this.current_profile.dynamic_challenge = dc;
                            evm.info = "";
                        }
                    }
                } else if (evm.res_id == R.string.proxy_need_creds) {
                    if (this.current_profile != null) {
                        ProxyContext proxy_context = this.current_profile.get_proxy_context(false);
                        if (proxy_context != null && proxy_context.should_launch_creds_dialog()) {
                            proxy_context.invalidate_proxy_creds(this.proxy_list);
                            Intent intent = new Intent(getBaseContext(), OpenVPNProxyCreds.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            proxy_context.configure_creds_dialog_intent(intent);
                            getApplication().startActivity(intent);
                        }
                    }
                }

                if (evm.res_id == R.string.epki_invalid_alias && this.profile_list != null) {
                    this.profile_list.invalidate_epki_alias(evm.info);
                }

                if (this.enable_notifications) {
                    if (evm.priority == MSG_LOG) {
                        Toast.makeText(this, evm.res_id, Toast.LENGTH_SHORT).show();
                    } else if (evm.priority == EV_PRIO_HIGH) {
                        Toast.makeText(this, evm.res_id, Toast.LENGTH_LONG).show();
                    }
                }

                if ((evm.flags & 4) != 0) {
                    this.last_event_prof_manage = evm;
                } else if (evm.priority >= MSG_LOG) {
                    this.last_event = evm;
                }

                String msg_str = evm.res_id != R.string.ui_reset ? evm.toString() : null;
                if (msg_str != null) {
                    Log.i(TAG, msg_str);
                }

                if (evm.res_id == R.string.core_thread_active) {
                    log_message("----- OpenVPN Start -----");
                }
                if (msg_str != null) {
                    log_message(msg_str);
                }
                if (evm.res_id == R.string.core_thread_inactive) {
                    log_message(String.format(Locale.US, "Tunnel bytes per CPU second: %d", get_tunnel_bytes_per_cpu_second()));
                    log_message("----- OpenVPN Stop -----");
                }

                update_notification_event(evm);

                for (EventReceiver cli : this.clients) {
                    if ((evm.flags & 16) == 0 || cli != evm.sender) {
                        cli.event(evm);
                    }
                }
                break;

            case MSG_LOG:
                LogMsg lm = (LogMsg) msg.obj;
                Log.i(TAG, String.format(Locale.US, "LOG: %s", lm.line));
                log_message(lm);
                break;

            default:
                Log.d(TAG, "SERV: unhandled message");
                break;
        }
        return true;
    }

    private void log_message(String line) {
        LogMsg lm = new LogMsg();
        lm.line = line + "\n";
        log_message(lm);
    }

    private void log_message(LogMsg lm) {
        lm.line = String.format(Locale.US, "%s -- %s", this.dateFormat.format(new Date()), lm.line);
        this.log_deque.addLast(lm);
        while (this.log_deque.size() > log_deque_max) {
            this.log_deque.removeFirst();
        }
        for (EventReceiver cli : this.clients) {
            cli.log(lm);
        }
    }

    public boolean socket_protect(int socket) {
        boolean status = protect(socket);
        Log.d(TAG, String.format(Locale.US, "SOCKET PROTECT: fd=%d protected status=%b", socket, status));
        return status;
    }

    public boolean pause_on_connection_timeout() {
        boolean ret = this.mConnectivityReceiver != null && this.mConnectivityReceiver.screen_on_defined && !this.mConnectivityReceiver.screen_on;
        Log.d(TAG, String.format(Locale.US, "pause_on_connection_timeout %b", ret));
        return ret;
    }

    public net.openvpn.openvpn.OpenVPNClientThread.TunBuilder tun_builder_new() {
        return new TunBuilder();
    }

    @Override
    public void event(ClientAPI_Event event) {
        EventMsg evm = new EventMsg();
        if (event.getError()) {
            evm.flags |= MSG_EVENT;
        }
        evm.name = event.getName();
        evm.info = event.getInfo();
        EventInfo evi = this.event_info.get(evm.name);
        if (evi != null) {
            evm.progress = evi.progress;
            evm.priority = evi.priority;
            evm.res_id = evi.res_id;
            evm.icon_res_id = evi.icon_res_id;
            evm.flags |= evi.flags;
            if (evi.res_id == R.string.connected && this.mThread != null) {
                evm.conn_info = this.mThread.connection_info();
            }
        } else {
            evm.res_id = R.string.unknown;
        }
        this.mHandler.sendMessage(this.mHandler.obtainMessage(MSG_EVENT, evm));
    }

    @Override
    public void log(ClientAPI_LogInfo loginfo) {
        LogMsg lm = new LogMsg();
        lm.line = loginfo.getText();
        this.mHandler.sendMessage(this.mHandler.obtainMessage(MSG_LOG, lm));
    }

    private String cert_format_pem(X509Certificate cert) throws CertificateEncodingException {
        return String.format(Locale.US, "-----BEGIN CERTIFICATE-----%n%s-----END CERTIFICATE-----%n",
                Base64.encodeToString(cert.getEncoded(), Base64.DEFAULT));
    }

    @Override
    public void external_pki_cert_request(ClientAPI_ExternalPKICertRequest req) {
        try {
            X509Certificate[] chain = KeyChain.getCertificateChain(this, req.getAlias());
            if (chain == null) {
                req.setError(true);
                req.setInvalidAlias(true);
            } else if (chain.length >= MSG_EVENT) {
                req.setCert(cert_format_pem(chain[0]));
                if (chain.length >= MSG_LOG) {
                    StringBuilder builder = new StringBuilder();
                    for (int i = 1; i < chain.length; i++) {
                        builder.append(cert_format_pem(chain[i]));
                    }
                    req.setSupportingChain(builder.toString());
                }
            } else {
                req.setError(true);
                req.setInvalidAlias(true);
                req.setErrorText(resString(R.string.epki_missing_cert));
            }
        } catch (Exception e) {
            Log.e(TAG, "EPKI error in external_pki_cert_request", e);
            req.setError(true);
            req.setInvalidAlias(true);
            req.setErrorText(e.toString());
        }
    }

    @Override
    public void external_pki_sign_request(ClientAPI_ExternalPKISignRequest req) {
        try {
            byte[] data_bytes = Base64.decode(req.getData(), Base64.DEFAULT);
            byte[] sig_bytes = null;
            PrivateKey pk;

            if (this.jellyBeanHack == null) {
                Log.d(TAG, "EPKI: normal mode");
                pk = KeyChain.getPrivateKey(this, req.getAlias());
                if (pk != null) {
                    Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1PADDING");
                    cipher.init(Cipher.ENCRYPT_MODE, pk);
                    sig_bytes = cipher.doFinal(data_bytes);
                } else {
                    req.setError(true);
                    req.setInvalidAlias(true);
                }
            } else {
                Log.d(TAG, "EPKI: Jelly bean mode");
                if (this.jellyBeanHack.enabled()) {
                    pk = this.jellyBeanHack.getPrivateKey(this, req.getAlias());
                    if (pk != null) {
                        sig_bytes = this.jellyBeanHack.rsaSign(pk, data_bytes);
                    } else {
                        req.setError(true);
                        req.setInvalidAlias(true);
                    }
                } else {
                    String err = "Android OpenSSL not accessible";
                    Log.e(TAG, String.format(Locale.US, "EPKI error in external_pki_sign_request: %s", err));
                    req.setError(true);
                    req.setInvalidAlias(true);
                    req.setErrorText(err);
                    return;
                }
            }
            if (sig_bytes != null) {
                req.setSig(Base64.encodeToString(sig_bytes, Base64.NO_WRAP));
            }
        } catch (Exception e) {
            Log.e(TAG, "EPKI error in external_pki_sign_request", e);
            req.setError(true);
            req.setInvalidAlias(true);
            req.setErrorText(e.toString());
        }
    }

    @Override
    public void done(ClientAPI_Status status) {
        boolean err = status.getError();
        String msg = status.getMessage();
        Log.d(TAG, String.format(Locale.US, "EXIT: connect() exited, err=%b, msg='%s'", err, msg));
        log_stats();
        if (err) {
            if (msg == null || !"CORE_THREAD_ABANDONED".equals(msg)) {
                String label = status.getStatus();
                if (label == null || label.isEmpty()) {
                    label = "CORE_THREAD_ERROR";
                }
                gen_event(MSG_EVENT, label, msg);
            } else {
                gen_event(MSG_EVENT, "CORE_THREAD_ABANDONED", null);
            }
        }
        gen_event(GCI_REQ_ESTABLISH, "CORE_THREAD_INACTIVE", null);
        this.active = false;
    }

    public void set_autostart_profile_name(String profile_name) {
        if (profile_name != null) {
            this.prefs.set_string("autostart_profile_name", profile_name);
        } else {
            this.prefs.delete_key("autostart_profile_name");
        }
    }

    public static String[] stat_names() {
        int size = ClientAPI_OpenVPNClient.stats_n();
        String[] ret = new String[size];
        for (int i = 0; i < size; i++) {
            ret[i] = ClientAPI_OpenVPNClient.stats_name(i);
        }
        return ret;
    }

    public ClientAPI_LLVector stat_values_full() {
        return this.mThread != null ? this.mThread.stats_bundle() : null;
    }

    protected static Date get_app_expire() {
        int expire = ClientAPI_OpenVPNClient.app_expire();
        return expire > 0 ? new Date(((long) expire) * 1000) : null;
    }

    protected static String get_openvpn_core_platform() {
        return ClientAPI_OpenVPNClient.platform();
    }

    private void log_stats() {
        if (this.active) {
            String[] sn = stat_names();
            ClientAPI_LLVector sv = stat_values_full();
            if (sv != null) {
                for (int i = 0; i < sn.length; i++) {
                    String name = sn[i];
                    long value = sv.get(i);
                    if (value > 0) {
                        Log.i(TAG, String.format(Locale.US, "STAT %s=%d", name, value));
                    }
                }
            }
        }
    }

    public String read_file(String location, String filename) throws IOException {
        if ("bundled".equals(location)) {
            return FileUtil.readAsset(this, filename);
        }
        if ("imported".equals(location)) {
            return FileUtil.readFileAppPrivate(this, filename);
        }
        throw new InternalError();
    }

    private String resString(int res_id) {
        return getResources().getString(res_id);
    }

    private void populate_event_info_map() {
        this.event_info = new HashMap<>();
        this.event_info.put("RECONNECTING", new EventInfo(R.string.reconnecting, R.drawable.connecting, 20, MSG_LOG, GCI_REQ_ESTABLISH));
        this.event_info.put("RESOLVE", new EventInfo(R.string.resolve, R.drawable.connecting, 30, MSG_EVENT, GCI_REQ_ESTABLISH));
        this.event_info.put("WAIT_PROXY", new EventInfo(R.string.wait_proxy, R.drawable.connecting, 40, MSG_EVENT, GCI_REQ_ESTABLISH));
        this.event_info.put("WAIT", new EventInfo(R.string.wait, R.drawable.connecting, 50, MSG_EVENT, GCI_REQ_ESTABLISH));
        this.event_info.put("CONNECTING", new EventInfo(R.string.connecting, R.drawable.connecting, 60, MSG_EVENT, GCI_REQ_ESTABLISH));
        this.event_info.put("GET_CONFIG", new EventInfo(R.string.get_config, R.drawable.connecting, 70, MSG_EVENT, GCI_REQ_ESTABLISH));
        this.event_info.put("ASSIGN_IP", new EventInfo(R.string.assign_ip, R.drawable.connecting, 80, MSG_EVENT, GCI_REQ_ESTABLISH));
        this.event_info.put("ADD_ROUTES", new EventInfo(R.string.add_routes, R.drawable.connecting, 90, MSG_EVENT, GCI_REQ_ESTABLISH));
        this.event_info.put("CONNECTED", new EventInfo(R.string.connected, R.drawable.connected, 100, EV_PRIO_HIGH, GCI_REQ_ESTABLISH));
        this.event_info.put("DISCONNECTED", new EventInfo(R.string.disconnected, R.drawable.disconnected, GCI_REQ_ESTABLISH, MSG_LOG, GCI_REQ_ESTABLISH));
        this.event_info.put("AUTH_FAILED", new EventInfo(R.string.auth_failed, R.drawable.error, GCI_REQ_ESTABLISH, EV_PRIO_HIGH, GCI_REQ_ESTABLISH));
        this.event_info.put("PEM_PASSWORD_FAIL", new EventInfo(R.string.pem_password_fail, R.drawable.error, GCI_REQ_ESTABLISH, EV_PRIO_HIGH, GCI_REQ_ESTABLISH));
        this.event_info.put("CERT_VERIFY_FAIL", new EventInfo(R.string.cert_verify_fail, R.drawable.error, GCI_REQ_ESTABLISH, EV_PRIO_HIGH, GCI_REQ_ESTABLISH));
        this.event_info.put("TLS_VERSION_MIN", new EventInfo(R.string.tls_version_min, R.drawable.error, GCI_REQ_ESTABLISH, EV_PRIO_HIGH, GCI_REQ_ESTABLISH));
        this.event_info.put("DYNAMIC_CHALLENGE", new EventInfo(R.string.dynamic_challenge, R.drawable.error, GCI_REQ_ESTABLISH, MSG_LOG, GCI_REQ_ESTABLISH));
        this.event_info.put("TUN_SETUP_FAILED", new EventInfo(R.string.tun_setup_failed, R.drawable.error, GCI_REQ_ESTABLISH, EV_PRIO_HIGH, GCI_REQ_ESTABLISH));
        this.event_info.put("TUN_IFACE_CREATE", new EventInfo(R.string.tun_iface_create, R.drawable.error, GCI_REQ_ESTABLISH, EV_PRIO_HIGH, GCI_REQ_ESTABLISH));
        this.event_info.put("TAP_NOT_SUPPORTED", new EventInfo(R.string.tap_not_supported, R.drawable.error, GCI_REQ_ESTABLISH, EV_PRIO_HIGH, GCI_REQ_ESTABLISH));
        this.event_info.put("PROFILE_NOT_FOUND", new EventInfo(R.string.profile_not_found, R.drawable.error, GCI_REQ_ESTABLISH, EV_PRIO_HIGH, GCI_REQ_ESTABLISH));
        this.event_info.put("CONFIG_FILE_PARSE_ERROR", new EventInfo(R.string.config_file_parse_error, R.drawable.error, GCI_REQ_ESTABLISH, EV_PRIO_HIGH, GCI_REQ_ESTABLISH));
        this.event_info.put("NEED_CREDS_ERROR", new EventInfo(R.string.need_creds_error, R.drawable.error, GCI_REQ_ESTABLISH, EV_PRIO_HIGH, GCI_REQ_ESTABLISH));
        this.event_info.put("CREDS_ERROR", new EventInfo(R.string.creds_error, R.drawable.error, GCI_REQ_ESTABLISH, EV_PRIO_HIGH, GCI_REQ_ESTABLISH));
        this.event_info.put("CONNECTION_TIMEOUT", new EventInfo(R.string.connection_timeout, R.drawable.error, GCI_REQ_ESTABLISH, EV_PRIO_HIGH, GCI_REQ_ESTABLISH));
        this.event_info.put("INACTIVE_TIMEOUT", new EventInfo(R.string.inactive_timeout, R.drawable.error, GCI_REQ_ESTABLISH, EV_PRIO_HIGH, GCI_REQ_ESTABLISH));
        this.event_info.put("INFO", new EventInfo(R.string.info_msg, R.drawable.rightarrow, GCI_REQ_ESTABLISH, GCI_REQ_ESTABLISH, GCI_REQ_ESTABLISH));
        this.event_info.put("PROXY_NEED_CREDS", new EventInfo(R.string.proxy_need_creds, R.drawable.error, GCI_REQ_ESTABLISH, EV_PRIO_HIGH, GCI_REQ_ESTABLISH));
        this.event_info.put("PROXY_ERROR", new EventInfo(R.string.proxy_error, R.drawable.error, GCI_REQ_ESTABLISH, EV_PRIO_HIGH, GCI_REQ_ESTABLISH));
        this.event_info.put("PROXY_CONTEXT_EXPIRED", new EventInfo(R.string.proxy_context_expired, R.drawable.error, GCI_REQ_ESTABLISH, EV_PRIO_HIGH, GCI_REQ_ESTABLISH));
        this.event_info.put("EPKI_ERROR", new EventInfo(R.string.epki_error, R.drawable.error, GCI_REQ_ESTABLISH, EV_PRIO_HIGH, GCI_REQ_ESTABLISH));
        this.event_info.put("EPKI_INVALID_ALIAS", new EventInfo(R.string.epki_invalid_alias, R.drawable.error, GCI_REQ_ESTABLISH, GCI_REQ_ESTABLISH, GCI_REQ_ESTABLISH));
        this.event_info.put("PAUSE", new EventInfo(R.string.pause, R.drawable.pause, GCI_REQ_ESTABLISH, EV_PRIO_HIGH, GCI_REQ_ESTABLISH));
        this.event_info.put("RESUME", new EventInfo(R.string.resume, R.drawable.connecting, GCI_REQ_ESTABLISH, MSG_LOG, GCI_REQ_ESTABLISH));
        this.event_info.put("CORE_THREAD_ACTIVE", new EventInfo(R.string.core_thread_active, R.drawable.connecting, 10, MSG_EVENT, GCI_REQ_ESTABLISH));
        this.event_info.put("CORE_THREAD_INACTIVE", new EventInfo(R.string.core_thread_inactive, -1, GCI_REQ_ESTABLISH, GCI_REQ_ESTABLISH, GCI_REQ_ESTABLISH));
        this.event_info.put("CORE_THREAD_ERROR", new EventInfo(R.string.core_thread_error, R.drawable.error, GCI_REQ_ESTABLISH, EV_PRIO_HIGH, GCI_REQ_ESTABLISH));
        this.event_info.put("CORE_THREAD_ABANDONED", new EventInfo(R.string.core_thread_abandoned, R.drawable.error, GCI_REQ_ESTABLISH, EV_PRIO_HIGH, GCI_REQ_ESTABLISH));
        this.event_info.put("CLIENT_HALT", new EventInfo(R.string.client_halt, R.drawable.error, GCI_REQ_ESTABLISH, EV_PRIO_HIGH, GCI_REQ_ESTABLISH));
        this.event_info.put("CLIENT_RESTART", new EventInfo(R.string.client_restart, R.drawable.connecting, GCI_REQ_ESTABLISH, MSG_LOG, GCI_REQ_ESTABLISH));
        this.event_info.put("PROFILE_IMPORT_SUCCESS", new EventInfo(R.string.profile_import_success, R.drawable.rightarrow, GCI_REQ_ESTABLISH, MSG_LOG, 44));
        this.event_info.put("PROFILE_DELETE_SUCCESS", new EventInfo(R.string.profile_delete_success, R.drawable.delete, GCI_REQ_ESTABLISH, MSG_LOG, 12));
        this.event_info.put("PROFILE_DELETE_FAILED", new EventInfo(R.string.profile_delete_failed, R.drawable.error, GCI_REQ_ESTABLISH, MSG_LOG, 4));
        this.event_info.put("PROFILE_PARSE_ERROR", new EventInfo(R.string.profile_parse_error, R.drawable.error, GCI_REQ_ESTABLISH, EV_PRIO_HIGH, 4));
        this.event_info.put("PROFILE_CONFLICT", new EventInfo(R.string.profile_conflict, R.drawable.error, GCI_REQ_ESTABLISH, EV_PRIO_HIGH, 4));
        this.event_info.put("PROFILE_WRITE_ERROR", new EventInfo(R.string.profile_write_error, R.drawable.error, GCI_REQ_ESTABLISH, EV_PRIO_HIGH, 4));
        this.event_info.put("PROFILE_FILENAME_ERROR", new EventInfo(R.string.profile_filename_error, R.drawable.error, GCI_REQ_ESTABLISH, EV_PRIO_HIGH, 4));
        this.event_info.put("PROFILE_RENAME_SUCCESS", new EventInfo(R.string.profile_rename_success, R.drawable.rightarrow, GCI_REQ_ESTABLISH, MSG_LOG, 12));
        this.event_info.put("PROFILE_RENAME_FAILED", new EventInfo(R.string.profile_rename_failed, R.drawable.error, GCI_REQ_ESTABLISH, MSG_LOG, 4));
        this.event_info.put("PROFILE_MERGE_EXCEPTION", new EventInfo(R.string.profile_merge_exception, R.drawable.error, GCI_REQ_ESTABLISH, MSG_LOG, 4));
        this.event_info.put("PROFILE_MERGE_OVPN_EXT_FAIL", new EventInfo(R.string.profile_merge_ovpn_ext_fail, R.drawable.error, GCI_REQ_ESTABLISH, MSG_LOG, 4));
        this.event_info.put("PROFILE_MERGE_OVPN_FILE_FAIL", new EventInfo(R.string.profile_merge_ovpn_file_fail, R.drawable.error, GCI_REQ_ESTABLISH, MSG_LOG, 4));
        this.event_info.put("PROFILE_MERGE_REF_FAIL", new EventInfo(R.string.profile_merge_ref_fail, R.drawable.error, GCI_REQ_ESTABLISH, MSG_LOG, 4));
        this.event_info.put("PROFILE_MERGE_MULTIPLE_REF_FAIL", new EventInfo(R.string.profile_merge_multiple_ref_fail, R.drawable.error, GCI_REQ_ESTABLISH, MSG_LOG, 4));
        this.event_info.put("UI_RESET", new EventInfo(R.string.ui_reset, R.drawable.rightarrow, GCI_REQ_ESTABLISH, GCI_REQ_ESTABLISH, 8));
    }
}
