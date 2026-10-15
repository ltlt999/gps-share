package com.gpsbt.transfer.boot;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import com.gpsbt.transfer.link.Role;
import com.gpsbt.transfer.service.GpsOutputService;
import com.gpsbt.transfer.service.ReceiveService;
import com.gpsbt.transfer.state.LogBus;
import com.gpsbt.transfer.util.Prefs;

/**
 * 服务看门狗：定期检查「用户希望服务在跑，但它已经没了」，把它拉回来。
 *
 * 为什么需要它：两个服务都用 {@code START_STICKY} 让系统在回收后重启，
 * 但国产 ROM 的省电策略常常直接冻结/杀掉进程并拦截重启——那时只有
 * JobScheduler 这种系统级调度还能把应用唤醒。没有它的话，服务一旦被
 * 回收就再也不会自己起来，表现为「必须手动打开一次应用才恢复」。
 *
 * 只对「用户主动启动过、且没主动停止」的服务生效（见
 * {@link Prefs#isServiceWanted()}），不会凭空启动用户没要的东西。
 */
public class ServiceWatchdog extends JobService {

    private static final int JOB_ID = 3001;
    /** JobScheduler 允许的最小周期是 15 分钟。 */
    private static final long INTERVAL_MILLIS = 15 * 60 * 1000L;

    /** 安排周期自检。重复调用安全（同 ID 会覆盖）。 */
    public static void schedule(Context context) {
        JobScheduler scheduler =
                (JobScheduler) context.getApplicationContext()
                        .getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) {
            return;
        }
        JobInfo job = new JobInfo.Builder(JOB_ID,
                new ComponentName(context.getApplicationContext(), ServiceWatchdog.class))
                // 进程被杀、设备重启后任务仍在——这正是它存在的意义
                .setPersisted(true)
                .setPeriodic(INTERVAL_MILLIS)
                .build();
        scheduler.schedule(job);
    }

    /** 用户主动停止时取消自检，避免「停了又被拉起来」。 */
    public static void cancel(Context context) {
        JobScheduler scheduler =
                (JobScheduler) context.getApplicationContext()
                        .getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler != null) {
            scheduler.cancel(JOB_ID);
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        Prefs prefs = Prefs.get(this);
        if (!prefs.isServiceWanted()) {
            // 用户没要求它跑（或已主动停止），什么都不做
            return false;
        }
        if (prefs.getRole() == Role.RECEIVE) {
            if (!ReceiveService.isRunning()) {
                String blocked = ReceiveService.prerequisiteFailure(this);
                if (blocked == null) {
                    LogBus.get().log(LogBus.Level.WARN, "看门狗：接收服务已不在运行，自动拉起");
                    ReceiveService.start(this, prefs.getLastServerHost(), prefs.getLastServerPort());
                }
            }
        } else if (!GpsOutputService.isRunning()) {
            String blocked = GpsOutputService.prerequisiteFailure(this);
            if (blocked == null) {
                LogBus.get().log(LogBus.Level.WARN, "看门狗：发送服务已不在运行，自动拉起");
                // 走「开机自启」那条通道：它按后台启动处理，Android 12+
                // 拿不到定位时会推一条「需要手动开始传输」的通知让用户点
                GpsOutputService.startFromBoot(this, prefs.getMode(), prefs.getLastDeviceMac());
            }
        }
        // 同步完成，不需要再回调 onStopJob
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // 被系统打断时要求重排，保证自检不会永久丢失
        return true;
    }
}
