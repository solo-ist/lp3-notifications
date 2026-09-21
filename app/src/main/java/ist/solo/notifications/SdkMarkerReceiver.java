package ist.solo.notifications;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * A deliberately empty receiver whose only job is to exist.
 *
 * LightOS decides what counts as a "tool" by querying broadcast receivers for
 * com.thelightphone.sdk.ACTION_SDK_MARKER and reading their SDK_VERSION
 * metadata. Apps carrying that marker stay in the toolbox even when external
 * tools are switched off; plain apps are filtered out.
 *
 * Declaring the marker lets Menu be listed as a tool while remaining an
 * ordinary Android app — which it must be, because a real light-sdk tool is
 * forbidden from launching other apps.
 *
 * This is an undocumented mechanism inferred from the device. It may break on
 * any LightOS update.
 */
public class SdkMarkerReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // Nothing to do: the declaration is the whole point.
    }
}
