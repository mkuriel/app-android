package com.mariopitch.livebpm;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

public final class MainActivity extends Activity {
    private BpmView bpmView;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        bpmView = new BpmView(this);
        setContentView(bpmView);
    }

    void requestMicrophone() {
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 7);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 7 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED)
            bpmView.startListening();
        else if (requestCode == 7) bpmView.showPermissionMessage();
    }

    @Override protected void onPause() { super.onPause(); if (bpmView != null) bpmView.stopListening(); }
    @Override protected void onResume() { super.onResume(); }
}
