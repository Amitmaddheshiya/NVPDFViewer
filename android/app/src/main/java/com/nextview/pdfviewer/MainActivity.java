package com.nextview.pdfviewer;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;
import com.facebook.react.ReactActivity;
import com.facebook.react.ReactInstanceManager;

public class MainActivity extends ReactActivity {

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    // Keep the TV panel awake for as long as this viewer activity is visible.
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  }

  @Override
  protected String getMainComponentName() {
    return "NVPDFViewer";
  }

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event) {
    // Emit hardware key events to React Native
    HardwareKeyModule hardwareKeyModule = HardwareKeyModule.getInstance();
    if (hardwareKeyModule != null) {
      hardwareKeyModule.emitKeyEvent(keyCode, event.getAction());
    }
    return super.onKeyDown(keyCode, event);
  }
}
