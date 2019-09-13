package com.google.android.systemui.smartspace;

import android.app.AlarmManager;
import android.app.AlarmManager.OnAlarmListener;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings.Global;
import android.util.KeyValueListParser;
import android.util.Log;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.Dumpable;
import com.android.systemui.util.Assert;

import com.google.android.systemui.smartspace.nano.SmartspaceProto.CardWrapper;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;

public class SmartSpaceController implements Dumpable {

    private static final String TAG = "SmartSpaceController";

    public static final String GSA_PACKAGE = "com.google.android.googlequicksearchbox";

    private static SmartSpaceController sInstance;

    private final AlarmManager mAlarmManager;
    private boolean mAlarmRegistered;
    private final Context mAppContext;
    private final Handler mBackgroundHandler;
    private final Context mContext;
    public int mCurrentUserId;
    public final SmartSpaceData mData;
    private boolean mHidePrivateData;

    private final ArrayList<SmartSpaceUpdateListener> mListeners = new ArrayList<>();
    private boolean mSmartSpaceEnabledBroadcastSent;
    private final ProtoStore mStore;
    private final Handler mUiHandler;

    private final OnAlarmListener mExpireAlarmAction = new OnAlarmListener() {
        @Override
        public final void onAlarm() {
            onExpire(false);
        }
    };

    private final KeyguardUpdateMonitorCallback mKeyguardMonitorCallback = new KeyguardUpdateMonitorCallback() {
        public void onTimeChanged() {
            if (mData != null && mData.hasCurrent() && mData.getExpirationRemainingMillis() > 0) {
                update();
            }
        }
    };

    private final BroadcastReceiver mUserSwitchReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_USER_SWITCHED)) {
                mCurrentUserId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                mData.clear();
                onExpire(true);
            }
            onExpire(true);
        }
    };

    private final BroadcastReceiver mGsaPackageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            onGsaChanged();
        }
    };

    public static SmartSpaceController get(Context context) {
        if (sInstance == null) {
            sInstance = new SmartSpaceController(context.getApplicationContext());
        }
        return sInstance;
    }

    private SmartSpaceController(Context context) {
        mContext = context;
        mUiHandler = new Handler(Looper.getMainLooper());
        mStore = new ProtoStore(mContext);
        HandlerThread handlerThread = new HandlerThread("smartspace-background");
        handlerThread.start();
        mBackgroundHandler = new Handler(handlerThread.getLooper());
        mCurrentUserId = UserHandle.myUserId();
        mAppContext = context;
        mAlarmManager = (AlarmManager) context.getSystemService(AlarmManager.class);
        mData = new SmartSpaceData();

        if (!isSmartSpaceDisabledByExperiments()) {
            KeyguardUpdateMonitor.getInstance(mContext).registerCallback(mKeyguardMonitorCallback);
            reloadData();
            onGsaChanged();

            IntentFilter packageFilter = new IntentFilter();
            packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
            packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
            packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            packageFilter.addAction(Intent.ACTION_PACKAGE_DATA_CLEARED);
            packageFilter.addDataScheme("package");
            packageFilter.addDataSchemeSpecificPart(GSA_PACKAGE, 0);
            context.registerReceiver(mGsaPackageReceiver, packageFilter);

            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(Intent.ACTION_USER_SWITCHED);
            intentFilter.addAction(Intent.ACTION_USER_UNLOCKED);
            context.registerReceiver(mUserSwitchReceiver, intentFilter);

            context.registerReceiver(new SmartSpaceBroadcastReceiver(this),
                    new IntentFilter("com.google.android.apps.nexuslauncher.UPDATE_SMARTSPACE"));
        }
    }

    private SmartSpaceCard loadSmartSpaceData(boolean primary) {
        CardWrapper cardWrapper = new CardWrapper();
        ProtoStore protoStore = mStore;
        StringBuilder sb = new StringBuilder();
        sb.append("smartspace_");
        sb.append(mCurrentUserId);
        sb.append("_");
        sb.append(primary);
        if (protoStore.load(sb.toString(), cardWrapper)) {
            return SmartSpaceCard.fromWrapper(mContext, cardWrapper, !primary);
        }
        return null;
    }

    public void onNewCard(final NewCardInfo newCardInfo) {
        if (newCardInfo != null) {
            if (newCardInfo.getUserId() != mCurrentUserId) {
                return;
            }
            mBackgroundHandler.post(new Runnable() {
                @Override
                public final void run() {
                    final CardWrapper wrapper = newCardInfo.toWrapper(mContext);
                    if (!mHidePrivateData) {
                        ProtoStore protoStore = mStore;
                        StringBuilder sb = new StringBuilder();
                        sb.append("smartspace_");
                        sb.append(mCurrentUserId);
                        sb.append("_");
                        sb.append(newCardInfo.isPrimary());
                        protoStore.store(wrapper, sb.toString());
                    }
                    mUiHandler.post(new Runnable() {
                        @Override
                        public final void run() {
                            SmartSpaceCard smartSpaceCard = newCardInfo.shouldDiscard() ? null :
                                    SmartSpaceCard.fromWrapper(mContext, wrapper, newCardInfo.isPrimary());
                            if (newCardInfo.isPrimary()) {
                                mData.mCurrentCard = smartSpaceCard;
                            } else {
                                mData.mWeatherCard = smartSpaceCard;
                            }
                            mData.handleExpire();
                            update();

                        }
                    });
                }
            });
        }
    }

    public void update() {
        Assert.isMainThread();
        if (mAlarmRegistered) {
            mAlarmManager.cancel(mExpireAlarmAction);
            mAlarmRegistered = false;
        }
        long expiresAtMillis = mData.getExpiresAtMillis();
        if (expiresAtMillis > 0) {
            mAlarmManager.set(0, expiresAtMillis, "SmartSpace", mExpireAlarmAction, mUiHandler);
            mAlarmRegistered = true;
        }
        if (mListeners != null) {
            ArrayList arrayList = new ArrayList(mListeners);
            int size = arrayList.size();
            for (int i = 0; i < size; i++) {
                ((SmartSpaceUpdateListener) arrayList.get(i)).onSmartSpaceUpdated(mData);
            }
        }
    }

    public void onExpire(boolean z) {
        Assert.isMainThread();
        mAlarmRegistered = false;
        if (mData.handleExpire() || z) {
            update();
            if (UserHandle.myUserId() == 0) {
                mAppContext.sendBroadcast(new Intent("com.google.android.systemui.smartspace.EXPIRE_EVENT").setPackage(GSA_PACKAGE).addFlags(268435456));
            }
        }
    }

    public void setHideSensitiveData(boolean z) {
        mHidePrivateData = z;
        ArrayList arrayList = new ArrayList(mListeners);
        for (int i = 0; i < arrayList.size(); i++) {
            ((SmartSpaceUpdateListener) arrayList.get(i)).onSensitiveModeChanged(z);
        }
        if (mHidePrivateData) {
            ProtoStore protoStore = mStore;
            StringBuilder sb = new StringBuilder();
            sb.append("smartspace_");
            sb.append(mCurrentUserId);
            sb.append("_true");
            protoStore.store(null, sb.toString());

            ProtoStore protoStore2 = mStore;
            StringBuilder sb2 = new StringBuilder();
            sb2.append("smartspace_");
            sb2.append(mCurrentUserId);
            sb2.append("_false");
             protoStore2.store(null, sb2.toString());
        }
    }

    public void onGsaChanged() {
        if (UserHandle.myUserId() == 0) {
            mAppContext.sendBroadcast(new Intent("com.google.android.systemui.smartspace.ENABLE_UPDATE").setPackage(GSA_PACKAGE).addFlags(268435456));
            mSmartSpaceEnabledBroadcastSent = true;
        }
        ArrayList arrayList = new ArrayList(mListeners);
        for (int i = 0; i < arrayList.size(); i++) {
            ((SmartSpaceUpdateListener) arrayList.get(i)).onGsaChanged();
        }
    }

    public void reloadData() {
        mData.mCurrentCard = loadSmartSpaceData(true);
        mData.mWeatherCard = loadSmartSpaceData(false);
        update();
    }

    private boolean isSmartSpaceDisabledByExperiments() {
        boolean enabled;
        String string = Global.getString(mContext.getContentResolver(), "always_on_display_constants");
        KeyValueListParser keyValueListParser = new KeyValueListParser(',');
        try {
            keyValueListParser.setString(string);
            enabled = keyValueListParser.getBoolean("smart_space_enabled", true);
        } catch (IllegalArgumentException unused) {
            Log.e(TAG, "Bad AOD constants");
            enabled = true;
        }

        if (!enabled) {
            return true;
        }
        return false;
    }

    @Override
    public void dump(FileDescriptor fileDescriptor, PrintWriter printWriter, String[] strArr) {
        printWriter.println(TAG);
        printWriter.println("broadcast: " + mSmartSpaceEnabledBroadcastSent);
        printWriter.println("weather: " + mData.mWeatherCard);
        printWriter.println("current: " + mData.mCurrentCard);
        printWriter.println("disabled: " + isSmartSpaceDisabledByExperiments());
    }

    public void addListener(SmartSpaceUpdateListener smartSpaceUpdateListener) {
        Assert.isMainThread();
        mListeners.add(smartSpaceUpdateListener);
        SmartSpaceData smartSpaceData = mData;
        if (smartSpaceData != null && smartSpaceUpdateListener != null) {
            smartSpaceUpdateListener.onSmartSpaceUpdated(smartSpaceData);
        }
    }

    public void removeListener(SmartSpaceUpdateListener smartSpaceUpdateListener) {
        Assert.isMainThread();
        mListeners.remove(smartSpaceUpdateListener);
    }
}
