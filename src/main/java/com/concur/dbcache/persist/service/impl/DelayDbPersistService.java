package com.concur.dbcache.persist.service.impl;

import com.concur.dbcache.cache.CacheUnit;
import com.concur.dbcache.conf.DbRuleService;
import com.concur.dbcache.conf.impl.CacheConfig;
import com.concur.dbcache.persist.PersistStatus;
import com.concur.dbcache.persist.service.DbPersistService;
import com.concur.dbcache.CacheObject;
import com.concur.dbcache.IEntity;
import com.concur.dbcache.dbaccess.DbAccessService;
import com.concur.unity.thread.NamedThreadPoolExecutor;
import com.concur.unity.utils.JsonUtils;
import com.concur.unity.thread.NamedThreadFactory;
import com.concur.unity.thread.ThreadUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.concurrent.*;


/**
 * 延时入库实现类
 * <br/>单线程执行入库
 * @author Jake
 * @date 2014年8月13日上午12:31:06
 */
@Component("delayDbPersistService")
public class DelayDbPersistService implements DbPersistService, ApplicationListener<ContextClosedEvent> {

	/**
	 * logger
	 */
	private static final Logger logger = LoggerFactory.getLogger(DelayDbPersistService.class);
	/**
	 * 更改实体队列
	 */
	private final ConcurrentLinkedQueue<QueuedAction> updateQueue = new ConcurrentLinkedQueue<QueuedAction>();
	/**
	 * 当前延迟更新的动作
	 */
	private volatile QueuedAction currentDelayUpdateAction;

	@Autowired
	private DbRuleService dbRuleService;

	/**
	 * 入库线程池
	 */
	private ExecutorService DB_POOL_SERVICE;


	@PostConstruct
	public void init() {
		// 初始化入库线程
		ThreadGroup threadGroup = new ThreadGroup("缓存模块");
		NamedThreadFactory threadFactory = new NamedThreadFactory(threadGroup, "延时入库线程池");
		DB_POOL_SERVICE = new NamedThreadPoolExecutor(1, 1,
				0L, TimeUnit.MILLISECONDS,
				new LinkedBlockingQueue<Runnable>(),
				threadFactory);
		// 初始化入库线程
		DB_POOL_SERVICE.submit(new Runnable() {
			@Override
			public void run() {
				processAction();
			}
		});
	}


	@Override
	public <T extends IEntity<?>> void handleSave(
			final CacheObject<T> cacheObject,
			final DbAccessService dbAccessService,
			final CacheConfig<T> cacheConfig) {

		this.handlePersist(new PersistAction() {
			@Override
			public void run() {
				// 判断是否有效
				if(!this.valid()) {
					return;
				}
				Object entity = cacheObject.getEntity();
				// 持久化前操作
				cacheObject.doBeforePersist(cacheConfig);
				// 持久化
				dbAccessService.save(entity);
				// 设置更新状态
				cacheObject.setPersistStatus(PersistStatus.PERSIST);
			}

			@Override
			public String getPersistInfo() {
				// 判断状态有效性
				if(!this.valid()) {
					return null;
				}
				return JsonUtils.object2JsonString(cacheObject.getEntity());
			}

			@Override
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

		this.handlePersist(new PersistAction() {
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
			}

			@Override
			public String getPersistInfo() {
				return JsonUtils.object2JsonString(cacheObject.getEntity());
			}

			@Override
			public boolean valid() {
				return true;
			}
		});
	}

	@Override
	public void handleDelete(
			final CacheObject<?> cacheObject,
			final DbAccessService dbAccessService,
			final Object key,
			final CacheUnit cacheUnit) {

		this.handlePersist(new PersistAction() {
			@Override
			public void run() {
				// 判断是否有效
				if (!this.valid()) {
					return;
				}
				// 持久化
				dbAccessService.delete(cacheObject.getEntity());
			}

			@Override
			public String getPersistInfo() {
				return JsonUtils.object2JsonString(cacheObject.getEntity());
			}

			@Override
			public boolean valid() {
				return cacheObject.getPersistStatus() == PersistStatus.PERSIST;
			}
		});
	}


	/**
	 * 提交持久化任务
	 * @param persistAction
	 */
	private void handlePersist(PersistAction persistAction) {
		updateQueue.add(QueuedAction.valueOf(persistAction));
	}
	
	
	// 处理入库任务
	private void processAction() {
		//延迟入库队列检测时间间隔(毫秒)
		final long delayCheckTimmer = 1000;
		//延迟入库时间(毫秒)
		final long delayWaitTimmer = dbRuleService.getDelayWaitTimmer();
		//循环定时检测入库,失败自动进入重试
		QueuedAction updateAction = updateQueue.poll();

		while (!Thread.interrupted()) {
			try {
				long timeDiff;
				do {
					if (updateAction == null) {
						try {
							Thread.sleep(delayCheckTimmer);//等待下一个检测时间
						} catch (InterruptedException e) {}
					} else if (updateAction.persistAction.valid()) {
						timeDiff = System.currentTimeMillis() - updateAction.createTime;
						//未到延迟入库时间
						if (timeDiff < delayWaitTimmer) {
							currentDelayUpdateAction = updateAction;
							try {
								//等待
								Thread.sleep(delayWaitTimmer - timeDiff);
							} catch (InterruptedException e) {}
						}
						//执行入库
						updateAction.doRunTask();
					}

					if (Thread.interrupted()) {
						break;
					}
					//获取下一个有效的元素
					updateAction = updateQueue.poll();
				} while (true);

			} catch (Exception e) {
				e.printStackTrace();
				// 记录日志
				if (updateAction != null && updateAction.persistAction != null) {
					logger.error(
							"执行入库时产生异常! 如果是主键冲突异常可忽略!"
									+ updateAction.persistAction.getPersistInfo(), e);
				} else {
					logger.error("执行批量入库时产生异常! 如果是主键冲突异常可忽略!", e);
				}

				//等待下一个检测时间重试入库
				try {
					Thread.sleep(delayCheckTimmer);
				} catch (InterruptedException e1) {}

			}
		}
	}


	@Override
	public void destroy() {
		// 关闭消费入库线程池
		ThreadUtils.shundownThreadPool(DB_POOL_SERVICE, false);
		int failCount = 0;
		while (failCount < 3) {
			try {
				//刷新所有延时入库的实体到库中
				this.flushAllEntity();
				break;
			} catch (Exception e) {
				e.printStackTrace();
				try {
					Thread.sleep(3000);
				} catch (InterruptedException e1) {}
				failCount ++;
			}
		}
	}


	/**
	 * 持久化所有实体
	 */
	public void flushAllEntity() {
		//入库延迟队列中的实体
		QueuedAction updateAction = this.updateQueue.poll();
		while (updateAction != null) {
			//执行入库
			updateAction.persistAction.run();
			updateAction = this.updateQueue.poll();
		}

		//入库正在延迟处理的实体
		if(currentDelayUpdateAction != null) {
			currentDelayUpdateAction.persistAction.run();
		}
	}



	@Override
	public void logHadNotPersistEntity() {
		QueuedAction updateAction;
		for (QueuedAction anUpdateQueue : this.updateQueue) {
			updateAction = anUpdateQueue;
			String persistInfo = updateAction.persistAction.getPersistInfo();
			if (!StringUtils.isBlank(persistInfo)) {
				logger.error("检测到可能未入库对象! " + persistInfo);
			}
		}
	}


	@Override
	public ExecutorService getThreadPool() {
		return DB_POOL_SERVICE;
	}

	@Override
	public void onApplicationEvent(ContextClosedEvent event) {
		destroy();
	}


	/**
	 * 延迟更新操作
	 * @author Jake
	 * @date 2014年9月17日上午12:38:21
	 */
	static class QueuedAction {

		final PersistAction persistAction;

		final long createTime = System.currentTimeMillis();

		public QueuedAction(PersistAction persistAction) {
			this.persistAction = persistAction;
		}

		public static QueuedAction valueOf(PersistAction persistAction) {
			return new QueuedAction(persistAction);
		}

		public void doRunTask() {
			if (persistAction.valid()) {
				persistAction.run();
			}
		}

	}


	/**
	 * 持久化行为接口
	 * @author Administrator
	 *
	 */
	interface PersistAction extends Runnable {
		/**
		 * 是否有效
		 * @return
		 */
		boolean valid();
		/**
		 * 执行持久化操作
		 */
		@Override
		void run();
		/**
		 * 转换成字符串
		 * @return
		 */
		String getPersistInfo();
	}

}
