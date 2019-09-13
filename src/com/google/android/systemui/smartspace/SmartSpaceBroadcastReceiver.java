package com.google.android.systemui.smartspace;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.UserHandle;
import android.util.Log;
import com.google.android.systemui.smartspace.nano.SmartspaceProto.SmartspaceUpdate;
import com.google.android.systemui.smartspace.nano.SmartspaceProto.SmartspaceUpdate.SmartspaceCard;
import com.google.protobuf.nano.InvalidProtocolBufferNanoException;
import com.google.protobuf.nano.MessageNano;

public class SmartSpaceBroadcastReceiver extends BroadcastReceiver {
    private static final String TAG = "SmartSpaceBroadcastReceiver";

    private final SmartSpaceController mController;

    public SmartSpaceBroadcastReceiver(SmartSpaceController smartSpaceController) {
        mController = smartSpaceController;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        int myUserId = UserHandle.myUserId();
        if (myUserId != 0) {
            if (!intent.getBooleanExtra("rebroadcast", false)) {
                intent.putExtra("rebroadcast", true);
                intent.putExtra("uid", myUserId);
                context.sendBroadcastAsUser(intent, UserHandle.ALL);
                return;
            }
            return;
        }

        if (!intent.hasExtra("uid")) {
            intent.putExtra("uid", myUserId);
        }

        byte[] byteArrayExtra = intent.getByteArrayExtra("com.google.android.apps.nexuslauncher.extra.SMARTSPACE_CARD");
        if (byteArrayExtra != null) {
            SmartspaceUpdate smartspaceUpdate = new SmartspaceUpdate();
            try {
                MessageNano.mergeFrom(smartspaceUpdate, byteArrayExtra);
                for (SmartspaceCard smartspaceCard : smartspaceUpdate.card) {
                    if (smartspaceCard.cardPriority != 1) {
                        if (smartspaceCard.cardPriority != 2) {
                            Log.w(TAG, "unrecognized card priority");
                        }
                    }
                    notify(smartspaceCard, context, intent, smartspaceCard.cardPriority == 1);
                }
            } catch (InvalidProtocolBufferNanoException e) {
            }
        }
    }

    private void notify(SmartspaceCard smartspaceCard, Context context, Intent intent, boolean z) {
        PackageInfo packageInfo;
        long currentTimeMillis = System.currentTimeMillis();
        try {
            packageInfo = context.getPackageManager().getPackageInfo(SmartSpaceController.GSA_PACKAGE, 0);
        } catch (NameNotFoundException e) {
            packageInfo = null;
        }
        NewCardInfo newCardInfo = new NewCardInfo(smartspaceCard, intent, z, currentTimeMillis, packageInfo);
        mController.onNewCard(newCardInfo);
    }
}
