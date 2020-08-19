package com.snail.collie;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.snail.collie.core.ActivityStack;
import com.snail.collie.core.CollieHandlerThread;
import com.snail.collie.debugview.DebugHelper;
import com.snail.collie.fps.FpsTracker;
import com.snail.collie.fps.ITrackFpsListener;
import com.snail.collie.trafficstats.ITrackTrafficStatsListener;
import com.snail.collie.trafficstats.TrafficStatsTracker;

import java.util.ArrayList;
import java.util.List;

public class Collie {

    private static volatile Collie sInstance = null;
    private Handler mHandler;
    private ITrackFpsListener mITrackListener;
    private ITrackTrafficStatsListener mTrackTrafficStatsListener;

    private Collie() {
        mHandler = new Handler(CollieHandlerThread.getInstance().getHandlerThread().getLooper());
        mITrackListener = new ITrackFpsListener() {
            @Override
            public void onHandlerMessageCost(final long currentCostMils, final long currentDropFrame, final boolean isInFrameDraw, final long averageFps) {
                final long currentFps = currentCostMils == 0 ? 60 : Math.min(60, 1000 / currentCostMils);
//                Log.v("Collie", "实时帧率 " + currentFps + " 掉帧 " + currentDropFrame + " 1S平均帧率 " + averageFps + " 本次耗时 " + currentCostMils);
                mHandler.post(new Runnable() {


                    @Override
                    public void run() {

                        if (BuildConfig.DEBUG) {
                            if (currentDropFrame > 0)
                                DebugHelper.getInstance().update("实时fps " + currentFps +
                                        "\n 丢帧 " + currentDropFrame + " \n1s平均fps " + averageFps
                                        + " \n本次耗时 " + currentCostMils);
                        }

                        for (CollieListener collieListener : mCollieListeners) {
                            collieListener.onFpsTrack(ActivityStack.getInstance().getTopActivity(), currentFps, currentDropFrame, averageFps);
                        }
                    }

                });
            }
        };

        mTrackTrafficStatsListener = new ITrackTrafficStatsListener() {
            @Override
            public void onTrafficStats(String activityName, long value) {
                Log.v("Collie", "" + activityName + " 流量消耗 " + value*1.0f/(1024*1024)+"M");
            }
        };
    }

    public static Collie getInstance() {
        if (sInstance == null) {
            synchronized (Collie.class) {
                if (sInstance == null) {
                    sInstance = new Collie();
                }
            }
        }
        return sInstance;
    }

    private List<CollieListener> mCollieListeners = new ArrayList<>();

    private Application.ActivityLifecycleCallbacks mActivityLifecycleCallbacks = new Application.ActivityLifecycleCallbacks() {
        @Override
        public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle bundle) {
            ActivityStack.getInstance().push(activity);
        }

        @Override
        public void onActivityStarted(@NonNull final Activity activity) {
            FpsTracker.getInstance().addTrackerListener(mITrackListener);
            TrafficStatsTracker.getInstance().markActivityStart(activity);
        }

        @Override
        public void onActivityResumed(@NonNull Activity activity) {
            FpsTracker.getInstance().startTracker();
        }

        @Override
        public void onActivityPaused(@NonNull Activity activity) {
            TrafficStatsTracker.getInstance().markActivityPause(activity);
        }

        @Override
        public void onActivityStopped(@NonNull Activity activity) {
            //   只针对TOP Activity
            if (ActivityStack.getInstance().getTopActivity() == ActivityStack.getInstance().getBottomActivity()) {
                FpsTracker.getInstance().stopTracker();
                if (BuildConfig.DEBUG) {
                    DebugHelper.getInstance().hide();
                }
            }

        }

        @Override
        public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle bundle) {

        }

        @Override
        public void onActivityDestroyed(@NonNull Activity activity) {
            ActivityStack.getInstance().pop(activity);
            TrafficStatsTracker.getInstance().markActivityDestroy(activity);

        }
    };

    public void init(@NonNull Application application, final CollieListener listener) {
        application.registerActivityLifecycleCallbacks(mActivityLifecycleCallbacks);
        TrafficStatsTracker.getInstance().addTackTrafficStatsListener(mTrackTrafficStatsListener);
        mCollieListeners.add(listener);
    }

    public void showDebugView(Activity activity) {
        if (BuildConfig.DEBUG) {
            DebugHelper.getInstance().show(activity);
        }
    }

    public void registerCollieListener(CollieListener listener) {
        mCollieListeners.add(listener);
    }

    public void unRegisterCollieListener(CollieListener listener) {
        mCollieListeners.remove(listener);
    }

    public void stop(@NonNull Application application) {
        application.unregisterActivityLifecycleCallbacks(mActivityLifecycleCallbacks);
        CollieHandlerThread.getInstance().getHandlerThread().quitSafely();
    }
}
