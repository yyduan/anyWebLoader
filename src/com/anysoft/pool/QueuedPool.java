package com.anysoft.pool;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import com.anysoft.util.BaseException;
import com.anysoft.util.Properties;
import com.anysoft.util.PropertiesConstants;

/**
 * 基于队列的缓冲池
 * 
 * @author duanyy
 * @since 1.1.0
 * 
 * @param <pooled> 缓冲池对象、
 * 
 */
abstract public class QueuedPool<pooled extends Pooled> implements Pool<pooled> {
	/**
	 * a logger of log4j
	 */
	protected Logger logger = LogManager.getLogger(QueuedPool.class);
	/**
	 * 正在工作的对象个数
	 */
	private volatile int workingCnt = 0;
	
	/**
	 * 空闲的对象个数
	 */
	private volatile int idleCnt = 0;
	
	/**
	 * 等待的进程个数
	 */
	private volatile int waitCnt = 0;
	
	/**
	 * 空闲队列
	 */
	private ConcurrentLinkedQueue<pooled> idleQueue = null;
	
	/**
	 * 普通优先级(priority=0)下的队列最大长度
	 *
	 */
	private int maxQueueLength = 10;

	/**
	 * 针对idleQueue的锁
	 */
	protected ReentrantLock lock = new ReentrantLock();

	/**
	 * 条件，空闲队列非空
	 */
	protected Condition notEmpty = lock.newCondition();	
	
	public int getWorkingQueueLength(){return workingCnt;}
	public int getIdleQueueLength(){return idleCnt;}
	public int getWaitQueueLength(){return waitCnt;}
	public int getMaxQueueLength(){return maxQueueLength;}
	/**
	 * 获取maxQueueLength的参数ID
	 * @return 参数ID
	 */
	abstract protected String getIdOfMaxQueueLength();
	
	@Override
	public void create(Properties props) {
		String id = getIdOfMaxQueueLength();
		
		maxQueueLength = PropertiesConstants.getInt(props, id, maxQueueLength,false);
		maxQueueLength = maxQueueLength <= 0 ? 10 : maxQueueLength;
		
		idleQueue = new ConcurrentLinkedQueue<pooled>();		
	}

	@Override
	public void close() {
		pooled found = null;
		while (( found = idleQueue.poll())!= null){
			found.destroy();
		}
		idleCnt = 0;
		waitCnt = 0;
		workingCnt = 0;
	}

	@Override
	public pooled borrowObject(int priority,int timeout) throws BaseException {
		//当前优先级所允许的最大长度
		int maxLength = maxQueueLength * (1+priority);
		if (workingCnt + idleCnt < maxLength){
			//当前对象个数小于所允许的最大长度
			if (idleQueue.isEmpty()){
				//空闲队列为空，直接创建一个新的对象
				pooled found = createObject();
				if (found != null){
					workingCnt ++;
				}
				return found;
			}
		}
		if (timeout <= 0){
			pooled found = idleQueue.poll();
			if (found != null){
				workingCnt ++;
				idleCnt --;
			}
			return found;
		}else{
			lock.lock();
			try {
				long nanos = TimeUnit.MILLISECONDS.toNanos(timeout);
				while (idleQueue.isEmpty()){
					if (nanos <= 0L)
						return null;
					nanos = notEmpty.awaitNanos(nanos);
				}
				
				workingCnt ++;
				idleCnt --;
				waitCnt = lock.getQueueLength() + lock.getWaitQueueLength(notEmpty);
				return idleQueue.poll();
			}catch (Exception ex){
				logger.error("Error when borrowing object from pool",ex);
				return null;
			}finally{
				lock.unlock();
			}
		}
	}

	@Override
	public void returnObject(pooled obj) {
		workingCnt --;
		if (idleCnt > 5 && workingCnt + idleCnt > maxQueueLength){
			//如果还有５个以上的空闲，并且已经超出了最大队列长度
			//不用归还到空闲队列，直接释放
			obj.destroy();
			obj = null;
		}else{
			//归还到队列
			idleQueue.offer(obj);
			idleCnt ++;
		}				
	}

	/**
	 * 创建缓冲池对象
	 * @return
	 * @throws BaseException
	 */
	abstract protected pooled createObject()throws BaseException;
}
