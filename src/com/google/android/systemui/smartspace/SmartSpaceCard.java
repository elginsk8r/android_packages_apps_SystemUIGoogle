package com.google.android.systemui.smartspace;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import com.android.systemui.R;

import com.google.android.systemui.smartspace.nano.SmartspaceProto.CardWrapper;
import com.google.android.systemui.smartspace.nano.SmartspaceProto.SmartspaceUpdate.SmartspaceCard;
import com.google.android.systemui.smartspace.nano.SmartspaceProto.SmartspaceUpdate.SmartspaceCard.ExpiryCriteria;
import com.google.android.systemui.smartspace.nano.SmartspaceProto.SmartspaceUpdate.SmartspaceCard.Message;
import com.google.android.systemui.smartspace.nano.SmartspaceProto.SmartspaceUpdate.SmartspaceCard.Message.FormattedText;
import com.google.android.systemui.smartspace.nano.SmartspaceProto.SmartspaceUpdate.SmartspaceCard.Message.FormattedText.FormatParam;

public class SmartSpaceCard {
    private static final String TAG = "SmartSpaceCard";

    private static int sRequestCode;

    private final SmartspaceCard mCard;
    private final Context mContext;
    private Bitmap mIcon;
    private boolean mIconProcessed;
    private final Intent mIntent;
    private final boolean mIsIconGrayscale;
    private final boolean mIsWeather;
    private final long mPublishTime;
    private int mRequestCode;

    public SmartSpaceCard(Context context, SmartspaceCard smartspaceCard, Intent intent, boolean isWeather, Bitmap bitmap, boolean iconGrayscale, long publishTime) {
        mContext = context.getApplicationContext();
        mCard = smartspaceCard;
        mIsWeather = isWeather;
        mIntent = intent;
        mIcon = bitmap;
        mPublishTime = publishTime;
        mIsIconGrayscale = iconGrayscale;

        int requestCodes = sRequestCode + 1;
        if (requestCodes > 2147483646) {
            sRequestCode = 0;
        } else {
            sRequestCode = requestCodes;
        }
        mRequestCode = sRequestCode;
    }

    public Intent getIntent() {
        return mIntent;
    }

    public Bitmap getIcon() {
        return mIcon;
    }

    public void setIcon(Bitmap bitmap) {
        mIcon = bitmap;
    }

    public void setIconProcessed(boolean iconProcessed) {
        mIconProcessed = iconProcessed;
    }

    public boolean isIconProcessed() {
        return mIconProcessed;
    }

    public String getTitle() {
        Message message = getMessage();
        if (message != null) {
            FormattedText formattedText = message.title;
            if (formattedText.text != null) {
                return hasParams(formattedText) ? String.format(formattedText.text, (Object[]) getTextArgs(formattedText.formatParam)) : formattedText.text;
            }
        }
        return "";
    }

    public CharSequence getFormattedTitle() {
        FormatParam[] params;

        Message message = getMessage();
        if (message == null) {
            return "";
        }

        FormattedText formattedText = message.title;
        if (formattedText != null) {
            String msg = formattedText.text;
            if (msg != null) {
                if (!hasParams(formattedText)) {
                    return msg;
                }

                String arg = null;
                String duration = null;
                int i = 0;
                while (true) {
                    params = formattedText.formatParam;
                    if (i >= params.length) {
                        break;
                    }
                    FormatParam formatParam = params[i];
                    if (formatParam != null) {
                        if (formatParam.formatParamArgs == 1
                                || formatParam.formatParamArgs == 2) {
                            duration = getDurationText(formatParam);
                        } else if (formatParam.formatParamArgs == 3) {
                            arg = formatParam.text;
                        }
                    }
                    i++;
                }

                if (mCard.cardType == 3 && params.length == 2) {
                    duration = params[0].text;
                    arg = params[1].text;
                }

                if (arg == null) {
                    return "";
                }

                if (duration == null) {
                    if (message != mCard.duringEvent) {
                        return msg;
                    }
                    duration = mContext.getString(R.string.smartspace_now);
                }
                return mContext.getString(R.string.smartspace_pill_text_format, new Object[]{duration, arg});
            }
        }
        return "";
    }

    public String getSubtitle() {
        Message message = getMessage();
        if (message != null) {
            FormattedText formattedText = message.subtitle;
            if (formattedText.text != null) {
                return hasParams(formattedText) ? String.format(formattedText.text, (Object[]) getTextArgs(formattedText.formatParam)) : formattedText.text;
            }
        }
        return "";
    }

    private Message getMessage() {
        long currentTimeMillis = System.currentTimeMillis();
        SmartspaceCard smartspaceCard = mCard;
        Message message = null;
        if (currentTimeMillis < smartspaceCard.eventTimeMillis) {
            message = smartspaceCard.preEvent;
        } else if (currentTimeMillis > 
                (smartspaceCard.eventDurationMillis + smartspaceCard.eventTimeMillis)) {
            message = mCard.postEvent;
        } else {
            message = mCard.duringEvent;
        }
        return message;
    }

    private FormattedText getFormattedText(boolean z) {
        Message message = getMessage();
        if (message == null) {
            return null;
        }
        return z ? message.title : message.subtitle;
    }

    private boolean hasParams(FormattedText formattedText) {
        if (!(formattedText == null || formattedText.text == null)) {
            FormatParam[] params = formattedText.formatParam;
            if (params != null && params.length > 0) {
                return true;
            }
        }
        return false;
    }

    public long getMillisToEvent(FormatParam formatParam) {
        long timeMillis;
        if (formatParam.formatParamArgs == 2) {
            SmartspaceCard smartspaceCard = mCard;
            timeMillis = smartspaceCard.eventTimeMillis + smartspaceCard.eventDurationMillis;
        } else {
            timeMillis = mCard.eventTimeMillis;
        }
        return Math.abs(System.currentTimeMillis() - timeMillis);
    }

    private int getMinutesToEvent(FormatParam formatParam) {
        return (int) Math.ceil(((double) getMillisToEvent(formatParam)) / 60000.0d);
    }

    private String[] getTextArgs(FormatParam[] params) {
        String[] strArr = new String[params.length];
        for (int i = 0; i < strArr.length; i++) {
            String arg = "";
            if (params[i].formatParamArgs == 1
                    || params[i].formatParamArgs == 2) {
                arg = getDurationText(params[i]);
            } else if (params[i].formatParamArgs == 3 && params[i].text != null) {
                    arg = params[i].text;
            }
            strArr[i] = arg;
        }
        return strArr;
    }

    private String getDurationText(FormatParam formatParam) {
        int minutesToEvent = getMinutesToEvent(formatParam);
        if (minutesToEvent >= 60) {
            int hours = minutesToEvent / 60;
            int minutes = minutesToEvent % 60;

            String hourString = mContext.getResources().getQuantityString(R.plurals.smartspace_hours, hours, new Object[] { Integer.valueOf(hours) });
            if (minutes <= 0) {
                return hourString;
            }

            String minuteString = mContext.getResources().getQuantityString(R.plurals.smartspace_minutes, minutes, new Object[]{Integer.valueOf(minutes)});
            return mContext.getString(R.string.smartspace_hours_mins, new Object[] { hourString, minuteString });
        }
        return mContext.getResources().getQuantityString(R.plurals.smartspace_minutes, minutesToEvent, new Object[] { Integer.valueOf(minutesToEvent) });
    }

    public boolean isExpired() {
        return System.currentTimeMillis() > getExpiration();
    }

    public long getExpiration() {
        SmartspaceCard smartspaceCard = mCard;
        if (smartspaceCard != null) {
            ExpiryCriteria expiryCriteria = smartspaceCard.expiryCriteria;
            if (expiryCriteria != null) {
                return expiryCriteria.expirationTimeMillis;
            }
        }
        return 0;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("title:");
        sb.append(getTitle());
        sb.append(" subtitle:");
        sb.append(getSubtitle());
        sb.append(" expires:");
        sb.append(getExpiration());
        sb.append(" published:");
        sb.append(mPublishTime);
        return sb.toString();
    }

    static SmartSpaceCard fromWrapper(Context context, CardWrapper cardWrapper, boolean isWeather) {
        if (cardWrapper == null) {
            return null;
        }

        try {
            Bitmap decodeByteArray = cardWrapper.icon != null ? BitmapFactory.decodeByteArray(cardWrapper.icon, 0, cardWrapper.icon.length, null) : null;
            int dimensionPixelSize = context.getResources().getDimensionPixelSize(R.dimen.header_icon_size);
            if (decodeByteArray != null && decodeByteArray.getHeight() > dimensionPixelSize) {
                int dimensionWidth = (int) (((float) decodeByteArray.getWidth()) * (((float) dimensionPixelSize) / ((float) decodeByteArray.getHeight())));
                decodeByteArray = Bitmap.createScaledBitmap(decodeByteArray, dimensionWidth, dimensionPixelSize, true);
            }

            boolean hasAction = cardWrapper.card.tapAction != null && !TextUtils.isEmpty(cardWrapper.card.tapAction.intent);
            Intent actionIntent = hasAction ? Intent.parseUri(cardWrapper.card.tapAction.intent, 0) : null;
            return new SmartSpaceCard(context, cardWrapper.card,
                    actionIntent, isWeather, decodeByteArray, cardWrapper.isIconGrayscale,
                    cardWrapper.publishTime);
        } catch (Exception e) {
        }

        return null;
    }

    public void performCardAction(View view) {
        if (mCard.tapAction == null) {
            return;
        }

        Intent intent = new Intent(getIntent());
        if (mCard.tapAction.actionType == 1) {
            intent.addFlags(268435456);
            intent.setPackage(SmartSpaceController.GSA_PACKAGE);
            try {
                view.getContext().sendBroadcast(intent);
            } catch (SecurityException e) {
            }
            return;
        } else if (mCard.tapAction.actionType != 2) {
            return;
        }
        mContext.startActivity(intent);
    }

    public PendingIntent getPendingIntent() {
        if (mCard.tapAction == null) {
            return null;
        }

        Intent intent = new Intent(getIntent());
        if (mCard.tapAction.actionType == 1) {
            intent.addFlags(268435456);
            intent.setPackage(SmartSpaceController.GSA_PACKAGE);
            return PendingIntent.getBroadcast(mContext, mRequestCode, intent, 0);
        } else if (mCard.tapAction.actionType != 2) {
            return null;
        }
        return PendingIntent.getActivity(mContext, mRequestCode, intent, 0);
    }
}
