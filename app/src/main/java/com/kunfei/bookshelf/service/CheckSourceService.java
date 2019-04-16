package com.kunfei.bookshelf.service;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.text.TextUtils;

import com.hwangjr.rxbus.RxBus;
import com.kunfei.bookshelf.MApplication;
import com.kunfei.bookshelf.R;
import com.kunfei.bookshelf.bean.BookSourceBean;
import com.kunfei.bookshelf.bean.SearchBookBean;
import com.kunfei.bookshelf.constant.RxBusTag;
import com.kunfei.bookshelf.model.BookSourceManager;
import com.kunfei.bookshelf.model.WebBookModel;
import com.kunfei.bookshelf.model.analyzeRule.AnalyzeRule;
import com.kunfei.bookshelf.view.activity.BookSourceActivity;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.script.SimpleBindings;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import io.reactivex.Observable;
import io.reactivex.Observer;
import io.reactivex.Scheduler;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

import static com.kunfei.bookshelf.constant.AppConstant.SCRIPT_ENGINE;

public class CheckSourceService extends Service {
    public static final String ActionStartService = "startService";
    public static final String ActionDoneService = "doneService";
    private static final int notificationId = 3333;
    private static final String ActionOpenActivity = "openActivity";

    private List<BookSourceBean> bookSourceBeanList;
    private int threadsNum;
    private int checkIndex;
    private CompositeDisposable compositeDisposable;
    private ExecutorService executorService;
    private Scheduler scheduler;

    /**
     * 启动服务
     */
    public static void start(Context context) {
        Intent intent = new Intent(context, CheckSourceService.class);
        intent.setAction(ActionStartService);
        context.startService(intent);
    }

    /**
     * 停止服务
     */
    public static void stop(Context context) {
        Intent intent = new Intent(context, CheckSourceService.class);
        intent.setAction(ActionDoneService);
        context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        SharedPreferences preference = MApplication.getConfigPreferences();
        threadsNum = preference.getInt(this.getString(R.string.pk_threads_num), 6);
        executorService = Executors.newFixedThreadPool(threadsNum);
        scheduler = Schedulers.from(executorService);
        compositeDisposable = new CompositeDisposable();
        bookSourceBeanList = BookSourceManager.getAllBookSource();
        updateNotification(0);
        startCheck();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (action != null) {
                switch (action) {
                    case ActionDoneService:
                        doneService();
                        break;
                }
            }
        }
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executorService.shutdown();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void doneService() {
        RxBus.get().post(RxBusTag.CHECK_SOURCE_STATE, -1);
        compositeDisposable.dispose();
        stopSelf();
    }

    /**
     * 更新通知
     */
    private void updateNotification(int state) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, MApplication.channelIdReadAloud)
                .setSmallIcon(R.drawable.ic_network_check)
                .setOngoing(true)
                .setContentTitle(getString(R.string.check_book_source))
                .setContentText(String.format(getString(R.string.progress_show), state, bookSourceBeanList.size()))
                .setContentIntent(getActivityPendingIntent(ActionOpenActivity));
        builder.addAction(R.drawable.ic_stop_black_24dp, getString(R.string.cancel), getThisServicePendingIntent(ActionDoneService));
        builder.setProgress(bookSourceBeanList.size(), state, false);
        builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        Notification notification = builder.build();
        startForeground(notificationId, notification);
    }

    private PendingIntent getActivityPendingIntent(String actionStr) {
        Intent intent = new Intent(this, BookSourceActivity.class);
        intent.setAction(actionStr);
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent getThisServicePendingIntent(String actionStr) {
        Intent intent = new Intent(this, this.getClass());
        intent.setAction(actionStr);
        return PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    public void startCheck() {
        if (bookSourceBeanList != null && bookSourceBeanList.size() > 0) {
            RxBus.get().post(RxBusTag.CHECK_SOURCE_STATE, 0);
            checkIndex = -1;
            for (int i = 1; i <= threadsNum; i++) {
                nextCheck();
            }
        }
    }

    private synchronized void nextCheck() {
        checkIndex++;
        if (checkIndex > threadsNum) {
            RxBus.get().post(RxBusTag.CHECK_SOURCE_STATE, checkIndex - threadsNum);
            updateNotification(checkIndex - threadsNum);
        }

        if (checkIndex < bookSourceBeanList.size()) {
            CheckSource checkSource = new CheckSource(bookSourceBeanList.get(checkIndex));
            checkSource.startCheck();
        } else {
            if (checkIndex >= bookSourceBeanList.size() + threadsNum - 1) {
                doneService();
            }
        }
    }

    private class CheckSource {
        CheckSource checkSource;
        BookSourceBean sourceBean;

        CheckSource(BookSourceBean sourceBean) {
            checkSource = this;
            this.sourceBean = sourceBean;
        }

        private void startCheck() {
            if (!TextUtils.isEmpty(sourceBean.getRuleSearchUrl())) {
                WebBookModel.getInstance().searchBook("我的", 1, sourceBean.getBookSourceUrl())
                        .subscribeOn(scheduler)
                        .timeout(60, TimeUnit.SECONDS)
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(getObserver());
            } else if (!TextUtils.isEmpty(sourceBean.getRuleFindUrl())) {
                Observable.create(emitter -> {
                    String kindA[];
                    if (!TextUtils.isEmpty(sourceBean.getRuleFindUrl())) {
                        if (sourceBean.getRuleFindUrl().startsWith("<js>")) {
                            String jsStr = sourceBean.getRuleFindUrl().substring(4, sourceBean.getRuleFindUrl().lastIndexOf("<"));
                            Object object = evalJS(jsStr, sourceBean.getBookSourceUrl());
                            kindA = object.toString().split("(&&|\n)+");
                        } else {
                            kindA = sourceBean.getRuleFindUrl().split("(&&|\n)+");
                        }
                        if (kindA.length > 0) {
                            emitter.onNext(kindA[0]);
                            emitter.onComplete();
                        }
                    }
                });
            }
        }

        private Observer<List<SearchBookBean>> getObserver() {
            return new Observer<List<SearchBookBean>>() {
                @Override
                public void onSubscribe(Disposable d) {
                    compositeDisposable.add(d);
                }

                @Override
                public void onNext(List<SearchBookBean> value) {
                    if (value.isEmpty()) {
                        sourceBean.addGroup("失效");
                        sourceBean.setSerialNumber(10000 + checkIndex);
                        BookSourceManager.addBookSource(sourceBean);
                    } else {
                        if (sourceBean.containsGroup("失效")) {
                            sourceBean.removeGroup("失效");
                            BookSourceManager.addBookSource(sourceBean);
                        }
                    }
                    nextCheck();
                }

                @Override
                public void onError(Throwable e) {
                    sourceBean.addGroup("失效");
                    sourceBean.setSerialNumber(10000 + checkIndex);
                    BookSourceManager.addBookSource(sourceBean);
                    nextCheck();
                }

                @Override
                public void onComplete() {
                    checkSource = null;
                }
            };
        }

        /**
         * 执行JS
         */
        private Object evalJS(String jsStr, String baseUrl) throws Exception {
            SimpleBindings bindings = new SimpleBindings();
            bindings.put("java", new AnalyzeRule(null));
            bindings.put("baseUrl", baseUrl);
            return SCRIPT_ENGINE.eval(jsStr, bindings);
        }
    }
}
