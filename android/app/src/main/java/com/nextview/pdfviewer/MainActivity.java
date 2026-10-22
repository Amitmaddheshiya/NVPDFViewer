package com.nextview.pdfviewer;

import android.os.Bundle;
import android.view.WindowManager;
import com.facebook.react.ReactActivity;

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
}
