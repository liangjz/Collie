package com.snail.collie.mem;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.os.Debug;
import android.os.Handler;
import android.os.Process;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.snail.collie.Collie;
import com.snail.collie.core.ActivityStack;
import com.snail.collie.core.CollieHandlerThread;
import com.snail.collie.core.ITracker;
import com.snail.collie.core.SimpleActivityLifecycleCallbacks;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public class MemoryLeakTrack implements ITracker {

    private static volatile MemoryLeakTrack sInstance = null;

    private MemoryLeakTrack() {
    }

    public static MemoryLeakTrack getInstance() {
        if (sInstance == null) {
            synchronized (MemoryLeakTrack.class) {
                if (sInstance == null) {
                    sInstance = new MemoryLeakTrack();
                }
            }
        }
        return sInstance;
    }

    private Handler mHandler = new Handler(CollieHandlerThread.getInstance().getHandlerThread().getLooper());
    private WeakHashMap<Activity, String> mActivityStringWeakHashMap = new WeakHashMap<>();
    private SimpleActivityLifecycleCallbacks mSimpleActivityLifecycleCallbacks = new SimpleActivityLifecycleCallbacks() {
        @Override
        public void onActivityDestroyed(@NonNull Activity activity) {
            super.onActivityDestroyed(activity);
            mActivityStringWeakHashMap.put(activity, activity.getClass().getSimpleName());
        }

        @Override
        public void onActivityStopped(@NonNull final Activity activity) {
            super.onActivityStopped(activity);
            //  退后台，GC 找LeakActivity
            if (!ActivityStack.getInstance().isInBackGround()) {
                return;
            }
            Runtime.getRuntime().gc();
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (!ActivityStack.getInstance().isInBackGround()) {
                            return;
                        }
                        SystemClock.sleep(100);
                        System.runFinalization();
                        HashMap<String, Integer> hashMap = new HashMap<>();
                        for (Map.Entry<Activity, String> activityStringEntry : mActivityStringWeakHashMap.entrySet()) {
                            String name = activityStringEntry.getKey().getClass().getName();
                            Integer value = hashMap.get(name);
                            if (value == null) {
                                hashMap.put(name, 1);
                            } else {
                                hashMap.put(name, value + 1);
                            }
                        }
                        if (mMemoryLeakListeners.size() > 0) {
                            for (Map.Entry<String, Integer> entry : hashMap.entrySet()) {
                                for (ITrackMemoryListener listener : mMemoryLeakListeners) {
                                    listener.onLeakActivity(entry.getKey(), entry.getValue());
                                }
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }, 5000);
        }
    };

    @Override
    public void destroy(final Application application) {
        Collie.getInstance().removeActivityLifecycleCallbacks(mSimpleActivityLifecycleCallbacks);
        mHandler.removeCallbacksAndMessages(null);
    }

    @Override
    public void startTrack(final Application application) {
        Collie.getInstance().addActivityLifecycleCallbacks(mSimpleActivityLifecycleCallbacks);
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (mMemoryLeakListeners.size() > 0) {
                    TrackMemoryInfo trackMemoryInfo = collectMemoryInfo(application);
                    for (ITrackMemoryListener listener : mMemoryLeakListeners) {
                        listener.onCurrentMemoryCost(trackMemoryInfo);
                    }
                }
                mHandler.postDelayed(this, 3 * 1000);
            }
        }, 3 * 1000);
    }

    @Override
    public void pauseTrack(Application application) {

    }

    private Set<ITrackMemoryListener> mMemoryLeakListeners = new HashSet<>();

    public void addOnMemoryLeakListener(ITrackMemoryListener leakListener) {
        mMemoryLeakListeners.add(leakListener);
    }

    public void removeOnMemoryLeakListener(ITrackMemoryListener leakListener) {
        mMemoryLeakListeners.remove(leakListener);
    }

    public interface ITrackMemoryListener {

        void onLeakActivity(String activity, int count);

        void onCurrentMemoryCost(TrackMemoryInfo trackMemoryInfo);
    }

    private static String display;

    private TrackMemoryInfo collectMemoryInfo(Application application) {

        if (TextUtils.isEmpty(display)) {
            display = "" + application.getResources().getDisplayMetrics().widthPixels + "*" + application.getResources().getDisplayMetrics().heightPixels;
        }
        ActivityManager activityManager = (ActivityManager) application.getSystemService(Context.ACTIVITY_SERVICE);
        // 系统内存
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        SystemMemory systemMemory = new SystemMemory();
        systemMemory.availMem = memoryInfo.availMem >> 20;
        systemMemory.totalMem = memoryInfo.totalMem >> 20;
        systemMemory.lowMemory = memoryInfo.lowMemory;
        systemMemory.threshold = memoryInfo.threshold >> 20;

        //java内存
        Runtime rt = Runtime.getRuntime();

        //进程Native内存

        AppMemory appMemory = new AppMemory();
        Debug.MemoryInfo debugMemoryInfo = new Debug.MemoryInfo();
        Debug.getMemoryInfo(debugMemoryInfo);
        appMemory.nativePss = debugMemoryInfo.nativePss >> 10;
        appMemory.dalvikPss = debugMemoryInfo.dalvikPss >> 10;
        appMemory.totalPss = debugMemoryInfo.getTotalPss() >> 10;
        appMemory.mMemoryInfo = debugMemoryInfo;

        TrackMemoryInfo trackMemoryInfo = new TrackMemoryInfo();
        trackMemoryInfo.systemMemoryInfo = systemMemory;
        trackMemoryInfo.appMemory = appMemory;

        trackMemoryInfo.procName = getProcessName(Process.myPid());
        trackMemoryInfo.display = display;

        return trackMemoryInfo;
    }


    private static String getProcessName(int pid) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader("/proc/" + pid + "/cmdline"));
            String processName = reader.readLine();
            if (!TextUtils.isEmpty(processName)) {
                processName = processName.trim();
            }
            return processName;
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (IOException exception) {
                exception.printStackTrace();
            }
        }
        return null;
    }

}