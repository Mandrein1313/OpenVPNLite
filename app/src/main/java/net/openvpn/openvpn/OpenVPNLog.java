package net.openvpn.openvpn;

import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import net.openvpn.openvpn.OpenVPNService.LogMsg;

public class OpenVPNLog extends OpenVPNClientBase implements OnClickListener {
    private static final String TAG = "OpenVPNClientLog";
    private Button mPause;
    private Button mResume;
    private ScrollView mScrollView;
    private TextView mTextView;
    private TextView mStatusLabel;
    private ArrayList<LogMsg> pause_buffer;

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.log);

        this.mTextView = (TextView) findViewById(R.id.log_textview);
        this.mScrollView = (ScrollView) findViewById(R.id.log_scrollview);
        this.mPause = (Button) findViewById(R.id.log_pause);
        this.mResume = (Button) findViewById(R.id.log_resume);
        this.mStatusLabel = (TextView) findViewById(R.id.log_status_label);

        this.mPause.setOnClickListener(this);
        this.mResume.setOnClickListener(this);
        this.mTextView.setMovementMethod(ScrollingMovementMethod.getInstance());

        // ข้อความเริ่มต้นเมื่อยังไม่มี log
        this.mTextView.setText("ยังไม่มีข้อมูล log\nรอการเชื่อมต่อหรือเหตุการณ์จาก VPN...");
        this.mTextView.setTextColor(0xFF94A3B8);

        doBindService();
    }

    private void set_pause_state(boolean paused) {
        if (paused) {
            this.mPause.setVisibility(View.GONE);
            this.mResume.setVisibility(View.VISIBLE);
            this.pause_buffer = new ArrayList<>();
            if (this.mStatusLabel != null) {
                this.mStatusLabel.setText("หยุดชั่วคราว");
                this.mStatusLabel.setTextColor(0xFFEF4444);
            }
        } else {
            this.mPause.setVisibility(View.VISIBLE);
            this.mResume.setVisibility(View.GONE);
            if (this.mStatusLabel != null) {
                this.mStatusLabel.setText("กำลังอัปเดต");
                this.mStatusLabel.setTextColor(0xFF0EA5E9);
            }
            if (this.pause_buffer != null) {
                if (this.mTextView.getCurrentTextColor() == 0xFF94A3B8) {
                    this.mTextView.setText("");
                    this.mTextView.setTextColor(0xFF1E293B);
                }
                Iterator<LogMsg> it = this.pause_buffer.iterator();
                while (it.hasNext()) {
                    this.mTextView.append(it.next().line);
                }
                scroll_textview_to_bottom();
                this.pause_buffer = null;
            }
        }
    }

    private void scroll_textview_to_bottom() {
        this.mScrollView.post(new Runnable() {
            public void run() {
                OpenVPNLog.this.mScrollView.smoothScrollTo(0, OpenVPNLog.this.mTextView.getBottom());
            }
        });
    }

    private void refresh_log_view() {
        ArrayDeque<LogMsg> hist = log_history();
        if (hist != null && !hist.isEmpty()) {
            StringBuilder builder = new StringBuilder();
            Iterator<LogMsg> it = hist.iterator();
            while (it.hasNext()) {
                builder.append(it.next().line);
            }
            this.mTextView.setTextColor(0xFF1E293B);
            this.mTextView.setText(builder.toString());
            scroll_textview_to_bottom();
        }
    }

    public void onClick(View v) {
        Log.d(TAG, "LOG: onClick");
        int viewid = v.getId();
        if (viewid == R.id.log_pause) {
            Log.d(TAG, "LOG: onClick PAUSE");
            set_pause_state(true);
        } else if (viewid == R.id.log_resume) {
            Log.d(TAG, "LOG: onClick RESUME");
            set_pause_state(false);
        }
    }

    public void log(LogMsg lm) {
        if (this.mTextView.getCurrentTextColor() == 0xFF94A3B8) {
            this.mTextView.setText("");
            this.mTextView.setTextColor(0xFF1E293B);
        }

        if (this.pause_buffer == null) {
            this.mTextView.append(lm.line);
            scroll_textview_to_bottom();
        } else {
            this.pause_buffer.add(lm);
        }
    }

    protected void onDestroy() {
        Log.d(TAG, "LOG: onDestroy");
        stop();
        super.onDestroy();
    }

    private void stop() {
        doUnbindService();
    }

    protected void post_bind() {
        Log.d(TAG, "LOG: post_bind");
        refresh_log_view();
        set_pause_state(false);
    }
}