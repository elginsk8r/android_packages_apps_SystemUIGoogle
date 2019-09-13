package com.google.android.systemui.smartspace;

public interface SmartSpaceUpdateListener {
    default void onGsaChanged() { }
    default void onSensitiveModeChanged(boolean change) {  }
    void onSmartSpaceUpdated(SmartSpaceData smartSpaceData);
}
