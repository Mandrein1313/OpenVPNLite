package net.openvpn.openvpn;

import android.content.Intent;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.TextView.OnEditorActionListener;
import net.openvpn.openvpn.ProxyList.Item;

public class OpenVPNAddProxy extends OpenVPNClientBase implements OnClickListener, OnEditorActionListener {
    private static final String TAG = "OpenVPNAddProxy";

    CheckBox allow_cleartext_auth_checkbox;
    Button cancel_button;
    EditText friendly_name_edit;
    EditText host_edit;
    String mod_proxy_name;
    EditText port_edit;
    private PrefUtil prefs;
    Button save_button;
    TextView title_textview;

    EditText username_edit;
    EditText password_edit;
    EditText user_agent_edit;

@Override
public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.add_proxy);

    // ซ่อนแถบชื่อแอป VPN THAI ด้านบน
    if (getSupportActionBar() != null) {
        getSupportActionBar().hide();
    }
    if (getActionBar() != null) {
        getActionBar().hide();
    }

    this.prefs = new PrefUtil(PreferenceManager.getDefaultSharedPreferences(this));

    this.title_textview = (TextView) findViewById(R.id.proxy_title);
    this.friendly_name_edit = (EditText) findViewById(R.id.proxy_friendly_name);
    this.host_edit = (EditText) findViewById(R.id.proxy_host);
    this.port_edit = (EditText) findViewById(R.id.proxy_port);
    this.username_edit = (EditText) findViewById(R.id.proxy_username);
    this.password_edit = (EditText) findViewById(R.id.proxy_password);
    this.user_agent_edit = (EditText) findViewById(R.id.proxy_user_agent);
    this.allow_cleartext_auth_checkbox = (CheckBox) findViewById(R.id.proxy_allow_cleartext_auth_checkbox);
    this.save_button = (Button) findViewById(R.id.proxy_save_button);
    this.cancel_button = (Button) findViewById(R.id.proxy_cancel_button);

    this.save_button.setOnClickListener(this);
    this.cancel_button.setOnClickListener(this);
    if (this.user_agent_edit != null) {
        this.user_agent_edit.setOnEditorActionListener(this);
    } else if (this.port_edit != null) {
        this.port_edit.setOnEditorActionListener(this);
    }

    doBindService();
}

@Override
    public void onClick(View v) {
        Log.d(TAG, "onClick");
        int viewid = v.getId();

        if (viewid == R.id.proxy_save_button) {
            ProxyList proxy_list = get_proxy_list();
            if (proxy_list == null) {
                Log.d(TAG, "proxy_list is null on save!");
                finish();
                return;
            }

            Item item = new Item();

            String friendly_name = this.friendly_name_edit != null
                    ? this.friendly_name_edit.getText().toString().trim() : "";
            if (friendly_name.length() > 0) {
                item.friendly_name = friendly_name;
            }

            item.host = this.host_edit != null
                    ? this.host_edit.getText().toString().trim() : "";
            item.port = this.port_edit != null
                    ? this.port_edit.getText().toString().trim() : "";
            item.allow_cleartext_auth = this.allow_cleartext_auth_checkbox != null
                    && this.allow_cleartext_auth_checkbox.isChecked();

            item.username = this.username_edit != null
                    ? this.username_edit.getText().toString() : "";
            item.password = this.password_edit != null
                    ? this.password_edit.getText().toString() : "";
            item.user_agent = this.user_agent_edit != null
                    ? this.user_agent_edit.getText().toString().trim() : "";

            if (item.username.length() > 0 || item.password.length() > 0) {
                item.remember_creds = true;
            }

            if (item.is_valid()) {
                String name = item.name();
                if (this.mod_proxy_name != null && !name.equals(this.mod_proxy_name)) {
                    proxy_list.remove(this.mod_proxy_name);
                }
                proxy_list.put(item);
                proxy_list.set_enabled(name);
                proxy_list.save();
                gen_ui_reset_event(false);
                finish();
            } else {
                android.widget.Toast.makeText(this,
                        "กรุณากรอก Host และ Port",
                        android.widget.Toast.LENGTH_SHORT).show();
            }

        } else if (viewid == R.id.proxy_cancel_button) {
            finish();
        }
    }
    @Override
    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
        if (!action_enter(actionId, event)) {
            return false;
        }
        onClick(this.save_button);
        return true;
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy");
        stop();
        super.onDestroy();
    }

    private void stop() {
        doUnbindService();
    }

    @Override
    protected void post_bind() {
        Intent intent = getIntent();
        if (intent != null) {
            this.mod_proxy_name = intent.getStringExtra("net.openvpn.openvpn.PROXY_NAME");
            if (this.mod_proxy_name != null && this.title_textview != null) {
                this.title_textview.setText(R.string.proxy_title_modify);
            }
            ProxyList proxy_list = get_proxy_list();
            if (this.mod_proxy_name != null && proxy_list != null) {
                Item item = proxy_list.get(this.mod_proxy_name);
                if (item != null) {
                    if (item.friendly_name != null && this.friendly_name_edit != null) {
                        this.friendly_name_edit.setText(item.friendly_name);
                    }
                    if (this.host_edit != null) this.host_edit.setText(item.host);
                    if (this.port_edit != null) this.port_edit.setText(item.port);
                    if (this.allow_cleartext_auth_checkbox != null) {
                        this.allow_cleartext_auth_checkbox.setChecked(item.allow_cleartext_auth);
                    }
                    if (this.username_edit != null) {
                        this.username_edit.setText(item.username != null ? item.username : "");
                    }
                    if (this.password_edit != null) {
                        this.password_edit.setText(item.password != null ? item.password : "");
                    }
                    if (this.user_agent_edit != null) {
                        this.user_agent_edit.setText(item.user_agent != null ? item.user_agent : "");
                    }
                }
            }
        }
    }
}
