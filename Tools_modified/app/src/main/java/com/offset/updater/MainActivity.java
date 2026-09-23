package com.offset.updater;

import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.text.style.URLSpan;
import android.view.View;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.offset.updater.databinding.ActivityMainBinding;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Obfuscate
public class MainActivity extends AppCompatActivity {

    private static final int REQ_SDK    = 1;
    private static final int REQ_OFFSET = 2;
    private static final int REQ_DUMP   = 3;
    private static final String CREDIT_URL = "https://t.me/itsarisu";

    private ActivityMainBinding binding;
    private Uri sdkUri;
    private Uri offsetUri;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<String> logLines = new ArrayList<>();
    private Future<?> currentTask;

    // Selected target
    private int selectedBits    = 64;                         // 64 or 32
    private int selectedVersion = OffsetUpdater.VERSION_GLOBAL; // 1/2/3

    private int colorDefault;
    private int colorGreen;
    private int colorYellow;
    private int colorRed;
    private int colorMuted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        colorDefault = ContextCompat.getColor(this, R.color.log_default);
        colorGreen   = ContextCompat.getColor(this, R.color.log_green);
        colorYellow  = ContextCompat.getColor(this, R.color.log_yellow);
        colorRed     = ContextCompat.getColor(this, R.color.log_red);
        colorMuted   = ContextCompat.getColor(this, R.color.text_muted);

        binding.btnSelectSdk.setOnClickListener(v -> pick(REQ_SDK));
        binding.btnSelectOffset.setOnClickListener(v -> pick(REQ_OFFSET));
        binding.btnStart.setOnClickListener(v -> startUpdate());
        binding.btnStop.setOnClickListener(v -> stopUpdate());
        binding.btnClearLog.setOnClickListener(v -> clearLog());
        binding.btnDumpLog.setOnClickListener(v -> dumpLog());

        // Bitness radio group
        binding.rgBits.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rb64) selectedBits = 64;
            else if (checkedId == R.id.rb32) selectedBits = 32;
        });
        binding.rb64.setChecked(true);

        // Version radio group
        binding.rgVersion.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rbGlobal)  selectedVersion = OffsetUpdater.VERSION_GLOBAL;
            else if (checkedId == R.id.rbVng) selectedVersion = OffsetUpdater.VERSION_VNG;
            else if (checkedId == R.id.rbBgmi) selectedVersion = OffsetUpdater.VERSION_BGMI;
        });
        binding.rbGlobal.setChecked(true);

        setupCredit();
        renderLog();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
        binding = null;
    }

    private void setupCredit() {
        String prefix = getString(R.string.credit_prefix);
        String name   = getString(R.string.credit_name);
        SpannableString sp = new SpannableString(prefix + name);
        sp.setSpan(new URLSpan(CREDIT_URL),
                prefix.length(), prefix.length() + name.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        binding.creditText.setText(sp);
        binding.creditText.setMovementMethod(LinkMovementMethod.getInstance());
        binding.creditText.setHighlightColor(Color.TRANSPARENT);
    }

    private void setActionRunning(boolean running) {
        if (binding == null) return;
        binding.btnStart.setEnabled(!running);
        binding.btnStart.setAlpha(running ? 0.5f : 1f);
        binding.btnStop.setEnabled(running);
        binding.btnStop.setAlpha(running ? 1f : 0.45f);
        binding.rgBits.setEnabled(!running);
        binding.rgVersion.setEnabled(!running);
    }

    private void renderLog() {
        if (binding == null) return;
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (String line : logLines) {
            int start = sb.length();
            sb.append(line);
            int end = sb.length();
            sb.setSpan(new ForegroundColorSpan(colorFor(line)), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append('\n');
        }
        boolean empty = logLines.isEmpty();
        binding.logText.setText(sb);
        binding.emptyLog.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.logScroll.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (!empty) {
            binding.logScroll.post(() -> {
                if (binding != null) binding.logScroll.fullScroll(View.FOCUS_DOWN);
            });
        }
    }

    private int colorFor(String line) {
        if (line.startsWith("updated") || line.startsWith("Finished")
                || line.startsWith("SDK parsed") || line.startsWith("----")
                || line.contains("Saved") || line.equals("Log exported.")
                || line.startsWith("Target:") || line.startsWith("Found block:"))
            return colorGreen;
        if (line.startsWith("AMBIGUOUS") || line.startsWith("NOT FOUND"))
            return colorYellow;
        if (line.startsWith("WARNING") || line.startsWith("FATAL")
                || line.startsWith("Stopping") || line.toLowerCase().contains("error"))
            return colorRed;
        if (line.startsWith("kept"))
            return colorMuted;
        return colorDefault;
    }

    private void pick(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        startActivityForResult(intent, requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;

        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException ignored) {}

        if (requestCode == REQ_SDK) {
            sdkUri = uri;
            binding.textSdkFile.setText(pathText(uri));
        } else if (requestCode == REQ_OFFSET) {
            offsetUri = uri;
            binding.textOffsetFile.setText(pathText(uri));
            // Auto-detect target from the file
            autoDetectTarget(uri);
        } else if (requestCode == REQ_DUMP) {
            try {
                writeUri(uri, TextUtils.join("\n", logLines) + "\n");
                appendLog("Log exported.");
            } catch (Exception e) {
                appendLog("Error exporting log: " + e.getMessage());
            }
        }
    }

    /** Read the offset file and auto-select the matching radio buttons */
    private void autoDetectTarget(Uri uri) {
        executor.submit(() -> {
            try {
                String offText = readUri(uri);
                boolean hasBoth = OffsetUpdater.hasBothBitnesses(offText);
                int[] detected = OffsetUpdater.detectTarget(offText);
                int detBits = detected[0];
                int detVer  = detected[1];

                runOnUiThread(() -> {
                    if (binding == null) return;

                    // Show/hide bitness selector based on file content
                    if (hasBoth) {
                        binding.labelBits.setVisibility(View.VISIBLE);
                        binding.rgBits.setVisibility(View.VISIBLE);
                        appendLog("Detected: Offsets.h has both 64-bit and 32-bit blocks.");
                    } else if (detBits == 32) {
                        binding.rb32.setChecked(true);
                        selectedBits = 32;
                        binding.labelBits.setVisibility(View.VISIBLE);
                        binding.rgBits.setVisibility(View.VISIBLE);
                        appendLog("Auto-detected: 32-bit only.");
                    } else {
                        binding.rb64.setChecked(true);
                        selectedBits = 64;
                        binding.labelBits.setVisibility(View.VISIBLE);
                        binding.rgBits.setVisibility(View.VISIBLE);
                        appendLog("Auto-detected: 64-bit only.");
                    }

                    if (detVer == OffsetUpdater.VERSION_GLOBAL) {
                        binding.rbGlobal.setChecked(true);
                        selectedVersion = OffsetUpdater.VERSION_GLOBAL;
                        appendLog("Auto-detected version: Global");
                    } else if (detVer == OffsetUpdater.VERSION_VNG) {
                        binding.rbVng.setChecked(true);
                        selectedVersion = OffsetUpdater.VERSION_VNG;
                        appendLog("Auto-detected version: VNG");
                    } else if (detVer == OffsetUpdater.VERSION_BGMI) {
                        binding.rbBgmi.setChecked(true);
                        selectedVersion = OffsetUpdater.VERSION_BGMI;
                        appendLog("Auto-detected version: BGMI");
                    }
                });
            } catch (Exception e) {
                // Ignore detection errors silently
            }
        });
    }

    private String fileName(Uri uri) {
        String name = uri.getLastPathSegment();
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0 && !cursor.isNull(idx)) name = cursor.getString(idx);
            }
        } catch (Exception ignored) {}
        return name == null ? uri.toString() : name;
    }

    private String fullPath(Uri uri) {
        String path = uri.getPath();
        if (path != null) {
            int i = path.indexOf("primary:");
            if (i >= 0) return "/storage/emulated/0/" + path.substring(i + "primary:".length());
        }
        return fileName(uri);
    }

    private String pathText(Uri uri) {
        return middleEllipsize(fullPath(uri), 52);
    }

    private String middleEllipsize(String s, int maxLen) {
        if (s.length() <= maxLen) return s;
        int keep = maxLen - 1;
        int head = (keep + 1) / 2;
        int tail = keep - head;
        return s.substring(0, head) + "\u2026" + s.substring(s.length() - tail);
    }

    private void startUpdate() {
        if (sdkUri == null || offsetUri == null) {
            appendLog("Select both files first.");
            return;
        }
        if (currentTask != null && !currentTask.isDone()) return;

        logLines.clear();
        renderLog();
        setActionRunning(true);

        final Uri sdk = sdkUri;
        final Uri off = offsetUri;
        final int bits    = selectedBits;
        final int version = selectedVersion;

        String verLabel = version == OffsetUpdater.VERSION_GLOBAL ? "Global"
                : version == OffsetUpdater.VERSION_VNG ? "VNG" : "BGMI";
        appendLog("Starting update [" + bits + "-bit / " + verLabel + "] using " + fileName(off) + " ...");
        appendLog("----");

        currentTask = executor.submit(() -> {
            try {
                String sdkText = readUri(sdk);
                String offText = readUri(off);

                appendLog("SDK file: " + fileName(sdk) + " (" + sdkText.length() + " chars)");
                appendLog("Offset file: " + fileName(off) + " (" + offText.length() + " chars)");

                OffsetUpdater.Result res = OffsetUpdater.update(sdkText, offText, bits, version);

                boolean wrote = false;
                String error = null;
                if (!res.stopped && (res.updated > 0 || res.ambiguous > 0)) {
                    try {
                        writeUri(off, res.output);
                        wrote = true;
                    } catch (Exception e) {
                        error = e.getMessage();
                    }
                } else if (!res.stopped) {
                    appendLog("No offsets were updated - file NOT modified.");
                }

                appendLog("----");
                for (String line : res.log) appendLog(line);

                if (wrote) {
                    appendLog("Updated file saved in place.");
                } else if (error != null) {
                    appendLog("Error writing offset file: " + error);
                    appendLog("No changes were applied.");
                }
            } catch (Exception e) {
                appendLog("FATAL: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            } finally {
                runOnUiThread(() -> setActionRunning(false));
            }
        });
    }

    private void stopUpdate() {
        if (currentTask != null) currentTask.cancel(true);
    }

    private void clearLog() {
        logLines.clear();
        renderLog();
    }

    private void dumpLog() {
        if (logLines.isEmpty()) {
            appendLog("No logs to export yet.");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, "offset-updater-log.txt");
        startActivityForResult(intent, REQ_DUMP);
    }

    private void appendLog(final String line) {
        if (binding == null) return;
        logLines.add(line);
        runOnUiThread(this::renderLog);
    }

    private String readUri(Uri uri) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (InputStream is = getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private void writeUri(Uri uri, String content) throws Exception {
        try (OutputStream os = getContentResolver().openOutputStream(uri, "wt")) {
            if (os == null) throw new Exception("Cannot open output stream.");
            os.write(content.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
    }
}
