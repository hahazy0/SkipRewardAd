package cn.xylin.skiprewardad.hook;

import android.content.Context;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Hook for Google Mobile Services internal ads (zzdtc bridge).
 * This targets the internal GMS ads dispatcher to intercept rewarded ads
 * and instantly grant rewards without watching the ad.
 */
public class GoogleAdHook2 extends BaseHook {

    // The Bridge Class (Dispatcher)
    private static final String CLASS_BRIDGE = "com.google.android.gms.internal.ads.zzdtc";
    // The Reward Interface
    private static final String CLASS_REWARD_INTERFACE = "com.google.android.gms.internal.ads.zzbwm";

    public GoogleAdHook2(Context ctx) {
        super(ctx);
    }

    @Override
    protected void runHook() throws Throwable {
        Class<?> bridgeClass = findClass(CLASS_BRIDGE);
        Class<?> rewardInterface = findClass(CLASS_REWARD_INTERFACE);

        // Only proceed if both classes exist
        if (bridgeClass == null || rewardInterface == null) {
            return;
        }

        XposedBridge.log("SkipRewardAd: Found GMS Ads Bridge in " + context.getPackageName());

        // Hook 'zzr': This method runs when the Ad OPENS ("onRewardedAdOpened")
        XposedHelpers.findAndHookMethod(
                bridgeClass,
                "zzr",         // Method name found in zzdtc
                long.class,    // Argument type
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        // 1. Capture the Ad ID
                        long adId = (long) param.args[0];
                        XposedBridge.log("SkipRewardAd: Ad Opened (ID: " + adId + "). Bypassing...");

                        // 2. Create the Fake Reward Object (Proxy)
                        // We need an object that looks like 'zzbwm' to pass to the game.
                        Object fakeReward = Proxy.newProxyInstance(
                                rewardInterface.getClassLoader(),
                                new Class<?>[]{rewardInterface},
                                new InvocationHandler() {
                                    @Override
                                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                                        String methodName = method.getName();

                                        // Return the Reward Type (String)
                                        if ("zzf".equals(methodName)) {
                                            return "SkippedReward";
                                        }
                                        // Return the Reward Amount (int)
                                        if ("zze".equals(methodName)) {
                                            return 1;
                                        }

                                        // Handle Object methods to prevent crashes
                                        if ("toString".equals(methodName)) return "FakeRewardObject";
                                        if ("hashCode".equals(methodName)) return 12345;
                                        if ("equals".equals(methodName)) return false;

                                        return null;
                                    }
                                }
                        );

                        // 3. Force the "Reward Earned" event (zzl)
                        // Method signature: void zzl(long j, zzbwm zzbwmVar)
                        try {
                            XposedHelpers.callMethod(param.thisObject, "zzl", adId, fakeReward);
                            XposedBridge.log("SkipRewardAd: Fake reward sent to 'zzl'!");
                        } catch (Throwable e) {
                            XposedBridge.log("SkipRewardAd: Failed to call zzl: " + e.getMessage());
                        }

                        // 4. Force the "Ad Closed" event (zzk)
                        // This makes the ad screen go away immediately
                        try {
                            XposedHelpers.callMethod(param.thisObject, "zzk", adId);
                            XposedBridge.log("SkipRewardAd: Ad closed via 'zzk'!");
                        } catch (Throwable e) {
                            XposedBridge.log("SkipRewardAd: Failed to call zzk: " + e.getMessage());
                        }

                        log("GoogleAd2-发放奖励 (GMS Internal)");
                    }
                }
        );
    }

    @Override
    protected String targetPackageName() {
        return null;
    }

    @Override
    protected boolean isTarget() {
        return true;
    }
}
