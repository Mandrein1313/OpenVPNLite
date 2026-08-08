package net.openvpn.openvpn;

import android.graphics.Color;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayDeque;
import java.util.ArrayList;
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

        // ซ่อนแถบชื่อแอป
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
            mTextView.setTextColor(0xFF9CA3AF);
        }

        doBindService();
    }

    // ฟังก์ชันช่วยใส่สีสันให้กับแต่ละบรรทัดของ Log
    private CharSequence formatColoredLog(String line) {
        SpannableStringBuilder sb = new SpannableStringBuilder(line);
        int color = 0xFFE5E7EB; // สีขาวนวลเริ่มต้น (Default)

        String upperLine = line.toUpperCase();

        if (upperLine.contains("SUCCESS") || upperLine.contains("CONNECTED") || upperLine.contains("ASSIGN_IP")) {
            color = 0xFF4ADE80; // เขียวสว่าง (สำเร็จ/เชื่อมต่อได้)
        } else if (upperLine.contains("ERROR") || upperLine.contains("FAIL") || upperLine.contains("AUTH_FAILED")) {
            color = 0xFFF87171; // แดงสว่าง (ข้อผิดพลาด)
        } else if (upperLine.contains("WARN") || upperLine.contains("RECONNECT") || upperLine.contains("WAIT")) {
            color = 0xFFFBBF24; // เหลือง/ส้ม (เตือน/รอการเชื่อมต่อ)
        } else if (upperLine.contains("EVENT") || upperLine.contains("INFO")) {
            color = 0xFF38BDF8; // ฟ้าสว่าง (ข้อมูลสถานะ/อีเวนต์)
        } else if (upperLine.contains("RESOLVE") || upperLine.contains("DNS")) {
            color = 0xFFC084FC; // ม่วงสว่าง (เกี่ยวกับการค้นหา IP/DNS)
        }

        sb.setSpan(new ForegroundColorSpan(color), 0, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
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
                mStatusLabel.setTextColor(0xFFF59E0B);
            }
            if (pause_buffer != null && mTextView != null) {
                if (mTextView.getCurrentTextColor() == 0xFF9CA3AF) {
                    mTextView.setText("");
                }
                for (LogMsg lm : pause_buffer) {
                    mTextView.append(formatColoredLog(lm.line));
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
            SpannableStringBuilder builder = new SpannableStringBuilder();
            for (LogMsg lm : hist) {
                builder.append(formatColoredLog(lm.line));
            }
            mTextView.setText(builder);
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

        if (mTextView.getCurrentTextColor() == 0xFF9CA3AF) {
            mTextView.setText("");
        }

        if (pause_buffer == null) {
            mTextView.append(formatColoredLog(lm.line));
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
