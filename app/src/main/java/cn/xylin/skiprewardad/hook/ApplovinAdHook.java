package cn.xylin.skiprewardad.hook;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class ApplovinAdHook extends BaseHook {
    // Store the MaxRewardedAd instance for later callback invocation
    private static Object cachedMaxRewardedAd = null;
    private static Object cachedListener = null;

    public ApplovinAdHook(Context ctx) {
        super(ctx);
    }

    @Override
    protected void runHook() throws Throwable {
        XposedBridge.log("SkipRewardAd: ======== HOOK INIT ========");
        
        // Hook MaxRewardedAd for capturing listeners and ad instance
        final Class<?> maxRewardedAdClass = XposedHelpers.findClassIfExists(
            "com.applovin.mediation.ads.MaxRewardedAd", context.getClassLoader());
        
        if (maxRewardedAdClass != null) {
            XposedBridge.log("SkipRewardAd: Found MaxRewardedAd class");
            hookMaxRewardedAd(maxRewardedAdClass);
        }
        
        // Hook MaxUnityAdManager for Unity games
        final Class<?> unityManagerClass = XposedHelpers.findClassIfExists(
            "com.applovin.mediation.unity.MaxUnityAdManager", context.getClassLoader());
        
        if (unityManagerClass != null) {
            XposedBridge.log("SkipRewardAd: Found MaxUnityAdManager");
            hookUnityManager(unityManagerClass);
        }
        
        XposedBridge.log("SkipRewardAd: ======== HOOK COMPLETE ========");
    }

    private void hookMaxRewardedAd(final Class<?> maxAdClass) {
        // Capture listener when setListener is called
        XposedBridge.hookAllMethods(maxAdClass, "setListener", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (param.args.length > 0 && param.args[0] != null) {
                    cachedListener = param.args[0];
                    cachedMaxRewardedAd = param.thisObject;
                    XposedBridge.log("SkipRewardAd: Captured listener and MaxRewardedAd instance");
                }
            }
        });

        // Force isReady to return true
        XposedBridge.hookAllMethods(maxAdClass, "isReady", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Object before = param.getResult();
                param.setResult(true);
                // Only log occasionally to reduce spam
                if (!Boolean.TRUE.equals(before)) {
                    XposedBridge.log("SkipRewardAd: isReady: " + before + " -> true");
                }
            }
        });

        // Hook showAd to intercept and fake reward
        XposedBridge.hookAllMethods(maxAdClass, "showAd", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                XposedBridge.log("SkipRewardAd: showAd() BLOCKED on MaxRewardedAd");
                param.setResult(null);
                
                final Object adInstance = param.thisObject;
                
                // Delay slightly then trigger reward
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        triggerRewardDirectly(adInstance);
                    }
                }, 150);
            }
        });
    }

    private void hookUnityManager(final Class<?> unityManagerClass) {
        // Hook showRewardedAd - this is the main hook for Unity games
        XposedBridge.hookAllMethods(unityManagerClass, "showRewardedAd", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                XposedBridge.log("SkipRewardAd: showRewardedAd() BLOCKED on Unity");
                
                String adUnitId = "";
                if (param.args.length > 0 && param.args[0] != null) {
                    adUnitId = param.args[0].toString();
                }
                final String finalAdUnitId = adUnitId;
                final Object manager = param.thisObject;
                
                // Block the ad
                param.setResult(null);
                XposedBridge.log("SkipRewardAd: Ad BLOCKED! adUnitId=" + adUnitId);
                
                // Trigger reward after delay
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        // Try multiple methods to trigger reward
                        triggerUnityReward(unityManagerClass, manager, finalAdUnitId);
                    }
                }, 150);
            }
        });
        
        // Force isRewardedAdReady
        XposedBridge.hookAllMethods(unityManagerClass, "isRewardedAdReady", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                param.setResult(true);
            }
        });
    }

    private void triggerRewardDirectly(Object adInstance) {
        try {
            XposedBridge.log("SkipRewardAd: Triggering reward via listener...");
            
            // Get the listener from the ad instance
            Object listener = null;
            try {
                Field listenerField = adInstance.getClass().getDeclaredField("adListener");
                listenerField.setAccessible(true);
                listener = listenerField.get(adInstance);
            } catch (NoSuchFieldException e) {
                // Try other field names
                for (Field f : adInstance.getClass().getDeclaredFields()) {
                    if (f.getType().getName().contains("Listener")) {
                        f.setAccessible(true);
                        listener = f.get(adInstance);
                        if (listener != null) break;
                    }
                }
            }
            
            if (listener == null) {
                listener = cachedListener;
            }
            
            if (listener == null) {
                XposedBridge.log("SkipRewardAd: No listener found");
                return;
            }
            
            XposedBridge.log("SkipRewardAd: Found listener: " + listener.getClass().getName());
            
            // Create fake MaxAd and MaxReward
            Object fakeAd = createFakeMaxAd("");
            Object fakeReward = createFakeReward();
            
            // Find and invoke the onUserRewarded method
            for (Method m : listener.getClass().getMethods()) {
                if (m.getName().equals("onUserRewarded") || m.getName().contains("Reward")) {
                    try {
                        m.setAccessible(true);
                        if (m.getParameterCount() == 2) {
                            m.invoke(listener, fakeAd, fakeReward);
                            XposedBridge.log("SkipRewardAd: Called " + m.getName() + " SUCCESS!");
                        }
                    } catch (Throwable t) {
                        XposedBridge.log("SkipRewardAd: " + m.getName() + " failed: " + t.getMessage());
                    }
                }
            }
            
        } catch (Throwable t) {
            XposedBridge.log("SkipRewardAd: triggerRewardDirectly error: " + t.getMessage());
        }
    }

    private void triggerUnityReward(Class<?> managerClass, Object manager, String adUnitId) {
        XposedBridge.log("SkipRewardAd: Triggering Unity reward...");
        
        try {
            // Create fake objects
            Object fakeAd = createFakeMaxAd(adUnitId);
            Object fakeReward = createFakeReward();
            
            if (fakeAd == null || fakeReward == null) {
                XposedBridge.log("SkipRewardAd: Could not create fake objects");
                return;
            }
            
            // Try calling onUserRewarded directly on the manager
            for (Method m : managerClass.getDeclaredMethods()) {
                String name = m.getName();
                
                // Look for onUserRewarded or any lambda containing "Reward"
                if (name.equals("onUserRewarded") || 
                    (name.contains("lambda") && name.contains("Reward"))) {
                    
                    try {
                        m.setAccessible(true);
                        int paramCount = m.getParameterCount();
                        
                        if (paramCount == 2) {
                            m.invoke(manager, fakeAd, fakeReward);
                            XposedBridge.log("SkipRewardAd: Invoked " + name + " with 2 params");
                        } else if (paramCount == 1) {
                            m.invoke(manager, fakeAd);
                            XposedBridge.log("SkipRewardAd: Invoked " + name + " with 1 param");
                        } else if (paramCount == 0) {
                            m.invoke(manager);
                            XposedBridge.log("SkipRewardAd: Invoked " + name + " with 0 params");
                        }
                    } catch (Throwable t) {
                        XposedBridge.log("SkipRewardAd: " + name + " error: " + t.getClass().getSimpleName());
                    }
                }
                
                // Also try Hidden callbacks to complete the flow
                if (name.contains("Hidden") || name.contains("hidden")) {
                    try {
                        m.setAccessible(true);
                        if (m.getParameterCount() == 1) {
                            m.invoke(manager, fakeAd);
                            XposedBridge.log("SkipRewardAd: Invoked hidden: " + name);
                        }
                    } catch (Throwable ignored) {}
                }
            }
            
            // Also try UnitySendMessage as fallback
            sendUnityMessage(adUnitId);
            
        } catch (Throwable t) {
            XposedBridge.log("SkipRewardAd: triggerUnityReward error: " + t.getMessage());
        }
    }

    private void sendUnityMessage(String adUnitId) {
        try {
            Class<?> unityPlayerClass = XposedHelpers.findClassIfExists(
                "com.unity3d.player.UnityPlayer", context.getClassLoader());
            
            if (unityPlayerClass == null) return;
            
            // The MaxSdk uses this exact format internally
            String rewardEvent = "{\"name\":\"OnRewardedAdReceivedRewardEvent\",\"body\":{" +
                "\"adUnitId\":\"" + adUnitId + "\"," +
                "\"rewardLabel\":\"Reward\"," +
                "\"rewardAmount\":1" +
                "}}";
            
            String hiddenEvent = "{\"name\":\"OnRewardedAdHiddenEvent\",\"body\":{" +
                "\"adUnitId\":\"" + adUnitId + "\"}}";
            
            try {
                XposedHelpers.callStaticMethod(unityPlayerClass, "UnitySendMessage",
                    "MaxSdkCallbacks", "ForwardEvent", rewardEvent);
                XposedBridge.log("SkipRewardAd: Sent reward Unity msg");
            } catch (Throwable ignored) {}
            
            try {
                XposedHelpers.callStaticMethod(unityPlayerClass, "UnitySendMessage",
                    "MaxSdkCallbacks", "ForwardEvent", hiddenEvent);
                XposedBridge.log("SkipRewardAd: Sent hidden Unity msg");
            } catch (Throwable ignored) {}
            
        } catch (Throwable t) {
            XposedBridge.log("SkipRewardAd: Unity msg error: " + t.getMessage());
        }
    }

    private Object createFakeMaxAd(final String adUnitId) {
        try {
            Class<?> maxAdInterface = XposedHelpers.findClass("com.applovin.mediation.MaxAd", context.getClassLoader());
            
            // Get MaxAdFormat.REWARDED - this is crucial!
            Object rewardedFormat = null;
            try {
                Class<?> formatClass = XposedHelpers.findClass("com.applovin.mediation.MaxAdFormat", context.getClassLoader());
                rewardedFormat = XposedHelpers.getStaticObjectField(formatClass, "REWARDED");
                XposedBridge.log("SkipRewardAd: Got REWARDED format: " + rewardedFormat);
            } catch (Throwable e) {
                XposedBridge.log("SkipRewardAd: Failed to get REWARDED format: " + e.getMessage());
            }
            final Object finalFormat = rewardedFormat;
            
            return Proxy.newProxyInstance(maxAdInterface.getClassLoader(), new Class[]{maxAdInterface}, (proxy, method, args) -> {
                String name = method.getName();
                if (name.equals("getAdUnitId")) return adUnitId;
                if (name.equals("getNetworkName")) return "AppLovin";
                if (name.equals("getCreativeId")) return "skip_reward_ad";
                if (name.equals("getRevenue")) return 0.0;
                if (name.equals("getPlacement")) return "";
                if (name.equals("getFormat")) return finalFormat; // Return actual REWARDED format!
                if (name.equals("getSize")) return null;
                if (name.equals("getWaterfall")) return null;
                if (name.equals("getDspName")) return "";
                if (name.equals("getDspId")) return "";
                if (name.equals("getRequestLatencyMillis")) return 0L;
                if (name.equals("getAdReviewCreativeId")) return "";
                if (name.equals("toString")) return "FakeMaxAd[" + adUnitId + "]";
                if (name.equals("hashCode")) return adUnitId.hashCode();
                if (name.equals("equals")) return false;
                return null;
            });
        } catch (Throwable e) {
            XposedBridge.log("SkipRewardAd: createFakeMaxAd error: " + e.getMessage());
            return null;
        }
    }

    private Object createFakeReward() {
        try {
            Class<?> rewardInterface = XposedHelpers.findClass("com.applovin.mediation.MaxReward", context.getClassLoader());
            return Proxy.newProxyInstance(rewardInterface.getClassLoader(), new Class[]{rewardInterface}, (proxy, method, args) -> {
                String name = method.getName();
                if (name.equals("getLabel")) return "Reward";
                if (name.equals("getAmount")) return 1;
                if (name.equals("toString")) return "FakeReward";
                if (name.equals("hashCode")) return 54321;
                if (name.equals("equals")) return false;
                return null;
            });
        } catch (Throwable e) {
            return null;
        }
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
