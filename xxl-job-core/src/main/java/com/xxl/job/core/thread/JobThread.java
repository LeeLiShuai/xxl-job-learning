package com.xxl.job.core.thread;

import com.xxl.job.core.biz.model.HandleCallbackParam;
import com.xxl.job.core.biz.model.ReturnT;
import com.xxl.job.core.biz.model.TriggerParam;
import com.xxl.job.core.executor.XxlJobExecutor;
import com.xxl.job.core.handler.IJobHandler;
import com.xxl.job.core.log.XxlJobFileAppender;
import com.xxl.job.core.log.XxlJobLogger;
import com.xxl.job.core.util.ShardingUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;


/**
 * 具体处理每个任务的线程，每个handler对应一个
 * @author xuxueli 2016-1-16 19:52:47
 */
public class JobThread extends Thread{
	private static Logger logger = LoggerFactory.getLogger(JobThread.class);
	//对应的任务id
	private int jobId;
	//对应的handler
	private IJobHandler handler;
	//阻塞队列
	private LinkedBlockingQueue<TriggerParam> triggerQueue;
	//执行的器的logId的集合
	private Set<Long> triggerLogIdSet;		// avoid repeat trigger for the same TRIGGER_LOG_ID

	private volatile boolean toStop = false;
	private String stopReason;

    private boolean running = false;    // if running job
	private int idleTimes = 0;			// idel times


	public JobThread(int jobId, IJobHandler handler) {
		this.jobId = jobId;
		this.handler = handler;
		this.triggerQueue = new LinkedBlockingQueue<TriggerParam>();
		this.triggerLogIdSet = Collections.synchronizedSet(new HashSet<Long>());
	}
	public IJobHandler getHandler() {
		return handler;
	}

    /**
     * 执行run方法时，把任务先存入阻塞队列中
     *
     * @param triggerParam
     * @return
     */
	public ReturnT<String> pushTriggerQueue(TriggerParam triggerParam) {
		// avoid repeat
		if (triggerLogIdSet.contains(triggerParam.getLogId())) {
			logger.info(">>>>>>>>>>> repeate trigger job, logId:{}", triggerParam.getLogId());
			return new ReturnT<String>(ReturnT.FAIL_CODE, "repeate trigger job, logId:" + triggerParam.getLogId());
		}

		triggerLogIdSet.add(triggerParam.getLogId());
		triggerQueue.add(triggerParam);
        return ReturnT.SUCCESS;
	}

    /**
     * kill job thread
     *
     * @param stopReason
     */
	public void toStop(String stopReason) {
		/**
		 * Thread.interrupt只支持终止线程的阻塞状态(wait、join、sleep)，
		 * 在阻塞出抛出InterruptedException异常,但是并不会终止运行的线程本身；
		 * 所以需要注意，此处彻底销毁本线程，需要通过共享变量方式；
		 */
		this.toStop = true;
		this.stopReason = stopReason;
	}

    /**
     * 判断是否有任务正在执行或者队列中有元素
     * @return
     */
    public boolean isRunningOrHasQueue() {
        return running || triggerQueue.size()>0;
    }

    @Override
	public void run() {

    	// init
    	try {
			handler.init();
		} catch (Throwable e) {
    		logger.error(e.getMessage(), e);
		}

		// execute
		while(!toStop){
			running = false;
			idleTimes++;

            TriggerParam triggerParam = null;
            ReturnT<String> executeResult = null;
            try {
				// 为了响应toStop的变化，不能使用阻塞的take,使用等待时间的poll,从阻塞队列中取出一个元素
				// to check toStop signal, we need cycle, so wo cannot use queue.take(), instand of poll(timeout)
				triggerParam = triggerQueue.poll(3L, TimeUnit.SECONDS);
				if (triggerParam!=null) {
					running = true;
					idleTimes = 0;
					triggerLogIdSet.remove(triggerParam.getLogId());

					//日志文件
					String logFileName = XxlJobFileAppender.makeLogFileName(new Date(triggerParam.getLogDateTime()), triggerParam.getLogId());
					XxlJobFileAppender.contextHolder.set(logFileName);
					ShardingUtil.setShardingVo(new ShardingUtil.ShardingVO(triggerParam.getBroadcastIndex(), triggerParam.getBroadcastTotal()));

					//开始执行
					XxlJobLogger.log("<br>----------- xxl-job job execute start -----------<br>----------- Param:" + triggerParam.getExecutorParams());
					//有超时时间，用futureTask处理，没有超市时间的直接调用处理
					if (triggerParam.getExecutorTimeout() > 0) {
						// limit timeout
						Thread futureThread = null;
						try {
							final TriggerParam triggerParamTmp = triggerParam;
							FutureTask<ReturnT<String>> futureTask = new FutureTask<ReturnT<String>>(new Callable<ReturnT<String>>() {
								@Override
								public ReturnT<String> call() throws Exception {
									return handler.execute(triggerParamTmp.getExecutorParams());
								}
							});
							futureThread = new Thread(futureTask);
							futureThread.start();

							executeResult = futureTask.get(triggerParam.getExecutorTimeout(), TimeUnit.SECONDS);
						} catch (TimeoutException e) {

							XxlJobLogger.log("<br>----------- xxl-job job execute timeout");
							XxlJobLogger.log(e);

							executeResult = new ReturnT<String>(IJobHandler.FAIL_TIMEOUT.getCode(), "job execute timeout ");
						} finally {
							futureThread.interrupt();
						}
					} else {
						// just execute
						executeResult = handler.execute(triggerParam.getExecutorParams());
					}

					if (executeResult == null) {
						executeResult = IJobHandler.FAIL;
					} else {
						executeResult.setMsg(
								(executeResult!=null&&executeResult.getMsg()!=null&&executeResult.getMsg().length()>50000)
										?executeResult.getMsg().substring(0, 50000).concat("...")
										:executeResult.getMsg());
						executeResult.setContent(null);	// limit obj size
					}
					XxlJobLogger.log("<br>----------- xxl-job job execute end(finish) -----------<br>----------- ReturnT:" + executeResult);

				} else {
					if (idleTimes > 30) {
						if(triggerQueue.size() == 0) {	// avoid concurrent trigger causes jobId-lost
							XxlJobExecutor.removeJobThread(jobId, "excutor idel times over limit.");
						}
					}
				}
			} catch (Throwable e) {
				if (toStop) {
					XxlJobLogger.log("<br>----------- JobThread toStop, stopReason:" + stopReason);
				}

				StringWriter stringWriter = new StringWriter();
				e.printStackTrace(new PrintWriter(stringWriter));
				String errorMsg = stringWriter.toString();
				executeResult = new ReturnT<String>(ReturnT.FAIL_CODE, errorMsg);

				XxlJobLogger.log("<br>----------- JobThread Exception:" + errorMsg + "<br>----------- xxl-job job execute end(error) -----------");
			} finally {
                if(triggerParam != null) {
                    // callback handler info
                    if (!toStop) {
                        //最终，把执行结果推送到回调线程
                        TriggerCallbackThread.pushCallBack(new HandleCallbackParam(triggerParam.getLogId(), triggerParam.getLogDateTime(), executeResult));
                    } else {
                        // is killed
                        ReturnT<String> stopResult = new ReturnT<String>(ReturnT.FAIL_CODE, stopReason + " [job running, killed]");
                        TriggerCallbackThread.pushCallBack(new HandleCallbackParam(triggerParam.getLogId(), triggerParam.getLogDateTime(), stopResult));
                    }
                }
            }
        }

		// callback trigger request in queue
		while(triggerQueue !=null && triggerQueue.size()>0){
			TriggerParam triggerParam = triggerQueue.poll();
			if (triggerParam!=null) {
				// is killed
				ReturnT<String> stopResult = new ReturnT<String>(ReturnT.FAIL_CODE, stopReason + " [job not executed, in the job queue, killed.]");
				TriggerCallbackThread.pushCallBack(new HandleCallbackParam(triggerParam.getLogId(), triggerParam.getLogDateTime(), stopResult));
			}
		}

		// destroy
		try {
			handler.destroy();
		} catch (Throwable e) {
			logger.error(e.getMessage(), e);
		}

		logger.info(">>>>>>>>>>> xxl-job JobThread stoped, hashCode:{}", Thread.currentThread());
	}
}
