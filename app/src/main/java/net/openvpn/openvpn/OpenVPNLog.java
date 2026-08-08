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

@Override
public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.log);

    // ซ่อนแถบชื่อแอป VPN THAI ด้านบน
    if (getSupportActionBar() != null) {
        getSupportActionBar().hide();
    }
    if (getActionBar() != null) {
        getActionBar().hide();
    }

    mTextView = (TextView) findViewById(R.id.log_textview);
    mScrollView = (ScrollView) findViewById(R.id.log_scrollview);
    mPause = (Button) findViewById(R.id.log_pause);
    mResume = (Button) findViewById(R.id.log_resume);
    mStatusLabel = (TextView) findViewById(R.id.log_status_label);

    if (mPause != null) mPause.setOnClickListener(this);
    if (mResume != null) mResume.setOnClickListener(this);

    if (mTextView != null) {
        mTextView.setMovementMethod(ScrollingMovementMethod.getInstance());
        mTextView.setText("ยังไม่มีข้อมูล log\nรอการเชื่อมต่อหรือเหตุการณ์จาก VPN...");
        mTextView.setTextColor(0xFF94A3B8);
    }

    doBindService();
}

    private void set_pause_state(boolean paused) {
        if (paused) {
            if (mPause != null) mPause.setVisibility(View.GONE);
            if (mResume != null) mResume.setVisibility(View.VISIBLE);
            pause_buffer = new ArrayList<>();
            if (mStatusLabel != null) {
                mStatusLabel.setText("หยุดชั่วคราว");
                mStatusLabel.setTextColor(0xFFEF4444);
            }
        } else {
            if (mPause != null) mPause.setVisibility(View.VISIBLE);
            if (mResume != null) mResume.setVisibility(View.GONE);
            if (mStatusLabel != null) {
                mStatusLabel.setText("กำลังอัปเดต");
                mStatusLabel.setTextColor(0xFF0EA5E9);
            }
            if (pause_buffer != null && mTextView != null) {
                if (mTextView.getCurrentTextColor() == 0xFF94A3B8) {
                    mTextView.setText("");
                    mTextView.setTextColor(0xFF1E293B);
                }
                for (LogMsg lm : pause_buffer) {
                    mTextView.append(lm.line);
                }
                scroll_textview_to_bottom();
                pause_buffer = null;
            }
        }
    }

    private void scroll_textview_to_bottom() {
        if (mScrollView == null || mTextView == null) return;
        mScrollView.post(new Runnable() {
            @Override
            public void run() {
                mScrollView.smoothScrollTo(0, mTextView.getBottom());
            }
        });
    }

    private void refresh_log_view() {
        ArrayDeque<LogMsg> hist = log_history();
        if (hist != null && !hist.isEmpty() && mTextView != null) {
            StringBuilder builder = new StringBuilder();
            for (LogMsg lm : hist) {
                builder.append(lm.line);
            }
            mTextView.setTextColor(0xFF1E293B);
            mTextView.setText(builder.toString());
            scroll_textview_to_bottom();
        }
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.log_pause) {
            set_pause_state(true);
        } else if (id == R.id.log_resume) {
            set_pause_state(false);
        }
    }

    public void log(LogMsg lm) {
        if (mTextView == null) return;

        if (mTextView.getCurrentTextColor() == 0xFF94A3B8) {
            mTextView.setText("");
            mTextView.setTextColor(0xFF1E293B);
        }

        if (pause_buffer == null) {
            mTextView.append(lm.line);
            scroll_textview_to_bottom();
        } else {
            pause_buffer.add(lm);
        }
    }

    @Override
    protected void onDestroy() {
        doUnbindService();
        super.onDestroy();
    }

    @Override
    protected void post_bind() {
        refresh_log_view();
        set_pause_state(false);
    }
}
