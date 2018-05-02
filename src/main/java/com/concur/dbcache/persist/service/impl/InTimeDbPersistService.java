package com.concur.dbcache.persist.service.impl;

import com.alibaba.fastjson.JSON;
import com.concur.dbcache.cache.CacheUnit;
import com.concur.dbcache.conf.DbRuleService;
import com.concur.dbcache.conf.impl.CacheConfig;
import com.concur.dbcache.persist.PersistStatus;
import com.concur.dbcache.persist.service.DbPersistService;
import com.concur.dbcache.CacheObject;
import com.concur.dbcache.IEntity;
import com.concur.dbcache.dbaccess.DbAccessService;
import com.concur.unity.thread.NamedThreadPoolExecutor;
import com.concur.unity.typesafe.SafeActor;
import com.concur.unity.utils.DateUtils;
import com.concur.unity.utils.JsonUtils;
import com.concur.unity.thread.NamedThreadFactory;
import com.concur.unity.thread.ThreadUtils;
import com.concur.unity.typesafe.SafeType;
import com.concur.unity.typesafe.finnal.FinalCommitActor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 即时入库实现
 * @author Jake
 * @date 2014年8月13日上午12:27:50
 */
@Component("inTimeDbPersistService")
public class InTimeDbPersistService implements DbPersistService {

	/**
	 * 线程池处理队列最大大小, 超出则执行同步入库
	 */
	private static final int EXECUTOR_QUEUE_SIZE = 100000;

	/**
	 * logger
	 */
	private static final Logger logger = LoggerFactory.getLogger(InTimeDbPersistService.class);

	/**
	 * WAL logger
	 */
	private static final Logger walLogger = LoggerFactory.getLogger("WAL-LOGGER");

	/**
	 * WCL logger
	 */
	private static final Logger wclLogger = LoggerFactory.getLogger("WCL-LOGGER");

	/**
	 * 缺省入库线程池容量
	 */
	private static final int DEFAULT_DB_POOL_SIZE = Runtime.getRuntime().availableProcessors()/2 + 1;

	/**
	 * 入库线程池
	 */
	private ExecutorService DB_POOL_SERVICE;

	/**
	 * 重试实体队列
	 */
	private final ConcurrentLinkedQueue<PersistAction> retryQueue = new ConcurrentLinkedQueue<PersistAction>();

	/**
	 * 定时检测重试线程
	 */
	private Thread checkRetryThread;

	@Autowired
	private DbRuleService dbRuleService;


	@PostConstruct
	@SuppressWarnings("unchecked")
	public void init() {

		// 初始化入库线程
		ThreadGroup threadGroup = new ThreadGroup("缓存模块");
		NamedThreadFactory threadFactory = new NamedThreadFactory(threadGroup, "即时入库线程池");

		// 设置线程池大小
		int dbPoolSize = dbRuleService.getDbPoolSize();
		if(dbPoolSize <= 0) {
			dbPoolSize = DEFAULT_DB_POOL_SIZE;
		}
		if(dbPoolSize <= 0) {
			dbPoolSize = 4;
		}

		// 初始化线程池
		DB_POOL_SERVICE = new NamedThreadPoolExecutor(dbPoolSize, dbPoolSize,
				0L, TimeUnit.MILLISECONDS,
				new LinkedBlockingQueue<Runnable>(EXECUTOR_QUEUE_SIZE),
				threadFactory);
		
		// 初始化检测线程
		checkRetryThread = new Thread() {
			@Override
			public void run() {
				processRetry();
			}
		};
		checkRetryThread.setName("dbcache即时入库重试线程");
		checkRetryThread.start();
	}

	@Override
	public <T extends IEntity<?>> void handleSave(
			final CacheObject<T> cacheObject,
			final DbAccessService dbAccessService,
			final CacheConfig<T> cacheConfig) {

		this.handlePersist(new PersistAction(cacheObject) {

			@Override
			public void run() {
				// 判断是否有效
				if (!this.valid()) {
					return;
				}
				Object entity = cacheObject.getEntity();
				// 持久化前操作
				cacheObject.doBeforePersist(cacheConfig);
				// 持久化
				dbAccessService.save(entity);
				// 设置状态为持久化
				cacheObject.setPersistStatus(PersistStatus.PERSIST);
				super.run();
			}
			
			@Override
			public void onException(Throwable t) {
				retryQueue.add(this);
			}

			@Override
			public String getPersistInfo() {
				return JsonUtils.object2JsonString(cacheObject.getEntity());
			}
			
			public boolean valid() {
				return cacheObject.getPersistStatus() == PersistStatus.TRANSIENT;
			}

		});

		
	}

	@Override
	public <T extends IEntity<?>> void handleUpdate(
			final CacheObject<T> cacheObject,
			final DbAccessService dbAccessService,
			final CacheConfig<T> cacheConfig) {

		this.handlePersist(new PersistAction(cacheObject) {

			@Override
			public void run() {
				// 持久化前的操作
				cacheObject.doBeforePersist(cacheConfig);
				//持久化
				if (cacheConfig.isEnableDynamicUpdate()) {
					dbAccessService.update(cacheObject.getEntity(), cacheObject.getModifiedFields());
				} else {
					dbAccessService.update(cacheObject.getEntity());
				}
				super.run();
			}

			@Override
			public void onException(Throwable t) {
				retryQueue.add(this);
				t.printStackTrace();
			}

			@Override
			public String getPersistInfo() {
				return JsonUtils.object2JsonString(cacheObject.getEntity());
			}
		});

	}


	@Override
	public void handleDelete(
			final CacheObject<?> cacheObject,
			final DbAccessService dbAccessService,
			final Object key,
			final CacheUnit cacheUnit) {

		this.handlePersist(new PersistAction(cacheObject) {
			@Override
			public void run() {
				// 判断是否有效
				if (!this.valid()) {
					return;
				}
				// 持久化
				dbAccessService.delete(cacheObject.getEntity());
				super.run();
			}
			
			@Override
			public void onException(Throwable t) {
				retryQueue.add(this);
			}

			@Override
			public String getPersistInfo() {
				return JsonUtils.object2JsonString(cacheObject.getEntity());
			}

			public boolean valid() {
				return cacheObject.getPersistStatus() == PersistStatus.PERSIST;
			}
		});

	}
	
	
	// 处理失败任务
	private void processRetry() {
		// 定时检测失败操作
		final long delayWaitTimmer = dbRuleService.getDelayWaitTimmer();//延迟入库时间(毫秒)
		PersistAction action = null;
		while (!Thread.currentThread().isInterrupted()) {
			try {
				action = retryQueue.poll();
				while (action != null) {
					handlePersist(action);
					if (Thread.currentThread().isInterrupted()) {
						break;
					}
					action = retryQueue.poll();
				}
			} catch (Exception e) {
				if (action != null) {
					logger.error("执行入库时产生异常! 如果是主键冲突异常可忽略!" + action.getPersistInfo(), e);
				} else {
					logger.error("执行批量入库时产生异常! 如果是主键冲突异常可忽略!", e);
				}
				e.printStackTrace();
			}
			try {
				Thread.sleep(delayWaitTimmer);
			} catch (InterruptedException e1) {
				throw new RuntimeException(e1);
			}
		}
	}
	

	@Override
	public void destroy() {
		// 中断重试线程
		checkRetryThread.interrupt();

		int retryQueueSize = retryQueue.size();
		if (retryQueueSize > 0) {
			logger.error("正在执行重试队列任务, 数量:{}", retryQueueSize);
		}
		// 消费重试队列
		PersistAction action = retryQueue.poll();
		while (action != null) {
			handlePersist(action);
			action = retryQueue.poll();
		}

		// 关闭消费入库线程池
		Queue<Runnable> queue = ThreadUtils.shundownThreadPool(DB_POOL_SERVICE, 3600);
		if (queue != null) {
			// 消费未完成的任务
			Runnable runnable;
			while ((runnable = queue.poll()) != null) {
				try {
					runnable.run();
				} catch (Exception e) {
					logger.error("消费未完成的任务异常", e);
				}
			}
		}
	}


	/**
	 * 获取入库线程池
	 * @return
	 */
	@Override
	public ExecutorService getThreadPool() {
		return this.DB_POOL_SERVICE;
	}


	@Override
	public void logHadNotPersistEntity() {

	}


	/**
	 * 提交持久化任务
	 * @param persistAction
	 */
	private void handlePersist(final PersistAction persistAction) {
		// 执行异步入库动作
		try {
			persistAction.start(DB_POOL_SERVICE);
		} catch (RejectedExecutionException ex) {
			logger.error("提交任务到更新队列被拒绝,使用同步处理:RejectedExecutionException");
			this.handleTask(persistAction);
		} catch (Exception ex) {
			persistAction.onException(ex);
			logger.error("提交任务到更新队列产生异常", ex);
		}
		// 执行WAL动作
		new WriteAheadLogAction(persistAction.getCacheObject(), persistAction.getTxId()).start();
	}

	/**
	 * 处理持久化操作
	 * @param persistAction 持久化操作
	 */
	private void handleTask(Runnable persistAction) {
		persistAction.run();
	}


	/**
	 * 持久化动作
	 */
	static abstract class PersistAction<T extends IEntity<?>> extends FinalCommitActor {
		final CacheObject<T> cacheObject;

		final static AtomicLong txIdGen = new AtomicLong(0);
		final long txId;

		public PersistAction(CacheObject<T> cacheObject) {
			super(cacheObject);
			this.cacheObject = cacheObject;
			this.txId = genTxId();
		}

		private long genTxId() {
			long num = txIdGen.incrementAndGet();
			if (num >= Long.MAX_VALUE - 10) {
				txIdGen.compareAndSet(num, 1);
				return txIdGen.incrementAndGet();
			}
			return num;
		}

		public abstract String getPersistInfo();

		public CacheObject<?> getCacheObject() {
			return cacheObject;
		}

		public long getTxId() {
			return txId;
		}

		/**
		 * 此方法我异步执行
		 */
		@Override
		public void run() {
			// WAL取消写入操作. 当log写入线程繁忙时, 可以适当减少WAL的写入来减少延迟
			new WriteEmptyAheadLogAction(cacheObject, txId).start();
			// WCL写入操作
			List<Long> skippedtxIds = new ArrayList<Long>();
			for (Runnable runnable : this.getSkipped()) {
				skippedtxIds.add(((PersistAction) runnable).getTxId());
			}
			new WriteCommitLogAction(cacheObject, txId, skippedtxIds).start();
//			logger.error("put empty wal txId={}, {}", txId, cacheObject.getEntity().getId());
		}


		@Override
		public void skip() {
//			walLogger.error("skip " + this.hashCode());
		}
	}

	/**
	 * WAL动作
	 */
	static class WriteAheadLogAction extends FinalCommitActor {
		final CacheObject<?> cacheObject;
		final long txId;
		volatile boolean run = false;
		public WriteAheadLogAction(CacheObject<?> cacheObject, long txId) {
			super(cacheObject.getWalLog());
			this.cacheObject = cacheObject;
			this.txId = txId;
		}

		public CacheObject<?> getCacheObject() {
			return cacheObject;
		}

		@Override
		public void run() {
			// TODO 写入日志
			Object entity = cacheObject.getEntity();
			walLogger.error("{}|{}|{}|{}", txId, DateUtils.currentTimeStr(), entity.getClass().getName(), JsonUtils.object2JsonString(entity));
//			logger.error("log wal txId={}, {}", txId, cacheObject.getEntity().getId());
//			try {
//				Thread.sleep(100);
//			} catch (InterruptedException e) {
//				e.printStackTrace();
//			}
		}

		@Override
		public void skip() {
//			skip it
//			walLogger.error("skip {}-{}-{}", txId, DateUtils.currentTimeStr(), JsonUtils.object2JsonString(cacheObject.getEntity()));
		}
	}


	/**
	 * WAL取消写入操作
	 * 因为线程池异步执行的速度够快, 已经把任务执行完成了, 所以不需要写入日志
	 */
	static class WriteEmptyAheadLogAction extends FinalCommitActor {
		final CacheObject<?> cacheObject;
		final long txId;
		public WriteEmptyAheadLogAction(CacheObject<?> cacheObject, long txId) {
			super(cacheObject.getWalLog());
			this.cacheObject = cacheObject;
			this.txId = txId;
		}

		public CacheObject<?> getCacheObject() {
			return cacheObject;
		}

		@Override
		public void run() {
			// do nothing
			logger.error("log empty wal txId={}, {}", txId, cacheObject.getEntity().getId());
		}
	}


	/**
	 * WCL动作
	 */
	static class WriteCommitLogAction extends SafeActor {
		final long txId;
		final List<Long> skippedtxIds;
		public WriteCommitLogAction(CacheObject<?> cacheObject, long txId, List<Long> skippedtxIds) {
			super(cacheObject.getWclLog());
			this.txId = txId;
			this.skippedtxIds = skippedtxIds;
		}

		@Override
		public void run() {
			// TODO 写入日志
			for (Long txId : skippedtxIds) {
				wclLogger.error("{}|{}", txId, DateUtils.currentTimeStr());
			}
			wclLogger.error("{}|{}", txId, DateUtils.currentTimeStr());
//			logger.error("log wcl txId={}, {}", txId, cacheObject.getEntity().getId());
//			try {
//				Thread.sleep(100);
//			} catch (InterruptedException e) {
//				e.printStackTrace();
//			}
		}
	}

}
