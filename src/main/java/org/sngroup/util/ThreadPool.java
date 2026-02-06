package org.sngroup.util;

import java.util.concurrent.*;

public class ThreadPool {
    protected ThreadPoolExecutor threadPool;

    final BlockingQueue<Future<?>> futures;

    boolean _isShutdown = false;

    public ThreadPool(){
        futures = new LinkedBlockingQueue<>();
    }

    public static ThreadPool FixedThreadPool(int size){
        ThreadPool t = new ThreadPool();
        t.threadPool = (ThreadPoolExecutor) Executors.newFixedThreadPool(size);
        return t;
    }
    
    public void execute(Runnable command) {
        Future<?> future = threadPool.submit(command);
        futures.add(future);
    }

    public void awaitAllTaskFinished(){
        awaitAllTaskFinished(100);
    }

    /**
     * 修复后的方法：正确等待所有任务完成
     */
    public void awaitAllTaskFinished(int timeout){
        while (!futures.isEmpty()) {
            Future<?> future = futures.poll(); // 直接获取，不使用超时
            if (future == null) {
                // 队列为空，所有任务已提交给线程池处理
                break;
            }
            
            try {
                future.get(); // 无限期等待任务完成
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // 恢复中断状态
                System.err.println("等待任务完成时被中断");
                break;
            } catch (ExecutionException e) {
                System.err.println("任务执行失败: " + e.getMessage());
                e.printStackTrace();
                // 继续处理其他任务，不要break
            }
        }
    }

    /**
     * 备选方案：使用线程池的awaitTermination
     */
    public void awaitAllTaskFinishedAlternative() {
        // 1. 首先处理futures队列中的所有任务
        while (!futures.isEmpty()) {
            Future<?> future = futures.poll();
            if (future != null) {
                try {
                    future.get();
                } catch (Exception e) {
                    System.err.println("任务执行异常: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }
        
        // 2. 然后等待线程池中所有活跃任务完成
        while (threadPool.getActiveCount() > 0) {
            try {
                Thread.sleep(10); // 短暂休眠，避免busy waiting
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public void shutdownNow(){
        threadPool.shutdownNow();
        _isShutdown = true;
    }

    public boolean isShutdown(){
        return _isShutdown;
    }
}