package org.kin.framework.asyncdb;

import org.kin.framework.Closeable;
import org.kin.framework.concurrent.SimpleThreadFactory;
import org.kin.framework.concurrent.ThreadManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;

/**
 * Created by huangjianqin on 2019/4/1.
 */
public class AsyncDBExecutor implements Closeable{
    private static final Logger log = LoggerFactory.getLogger("asyncDB");
    private final AsyncDBEntity POISON = new AsyncDBEntity() {};

    private ThreadManager threadManager;
    private AsyncDBOperator[] asyncDBOperators;
    private volatile boolean isStopped = false;
    private AsyncDBStrategy asyncDBStrategy;

    public void init(int num, AsyncDBStrategy asyncDBStrategy){
        threadManager = new ThreadManager(
                Executors.newFixedThreadPool(num, new SimpleThreadFactory("asyncDB")),
                Executors.newSingleThreadScheduledExecutor(new SimpleThreadFactory("asyncDB-monitor")));
        this.asyncDBStrategy = asyncDBStrategy;
        asyncDBOperators = new AsyncDBOperator[num];
        for(int i = 0; i < num; i++){
            AsyncDBOperator asyncDBOperator = new AsyncDBOperator();
            threadManager.execute(asyncDBOperator);
            asyncDBOperators[i] = asyncDBOperator;
        }
        threadManager.scheduleAtFixedRate(() -> {
            int totalTaskOpredNum = 0;
            int totalWaittingOprNum = 0;
            for(AsyncDBOperator asyncDBOperator: asyncDBOperators){
                SyncState syncState = asyncDBOperator.getSyncState();
                log.info("{} -> taskOpredNum: {}, taittingOprNum: {}, taskOpredPeriodNum: {}",
                        syncState.getThreadName(), syncState.getSyncNum(), syncState.getWaittingOprNum(),
                        syncState.getSyncPeriodNum());
                totalTaskOpredNum += syncState.getSyncNum();
                totalWaittingOprNum += syncState.getWaittingOprNum();
            }
            if(totalWaittingOprNum > 500){
                log.warn("totalTaskOpredNum: {}, totalWaittingOprNum: {}", totalTaskOpredNum, totalWaittingOprNum);
            }
            else{
                log.info("totalTaskOpredNum: {}, totalWaittingOprNum: {}", totalTaskOpredNum, totalWaittingOprNum);
            }

        }, 5, 5, TimeUnit.MINUTES);
    }

    @Override
    public void close() {
        isStopped = true;
        for(AsyncDBOperator asyncDBOperator: asyncDBOperators){
            asyncDBOperator.close();
        }
        threadManager.shutdown();
        try {
            threadManager.awaitTermination(2, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            log.error("", e);
        }
    }

    public boolean submit(AsyncDBEntity asyncDBEntity){
        if(!isStopped){
            int key = asyncDBEntity.hashCode();
            int index = key % asyncDBOperators.length;
            AsyncDBOperator asyncDBOperator = asyncDBOperators[index];

            asyncDBOperator.submit(asyncDBEntity);
            return true;
        }

        return false;
    }

    private class AsyncDBOperator implements Runnable, Closeable{
        private BlockingQueue<AsyncDBEntity> queue = new LinkedBlockingQueue<>();
        private volatile boolean isStopped = false;
        private long syncNum = 0;
        private String threadName = "";
        private long preSyncNum = 0;

        public void submit(AsyncDBEntity asyncDBEntity){
            if(!isStopped){
                queue.offer(asyncDBEntity);
            }
        }

        @Override
        public void run() {
            threadName = Thread.currentThread().getName();
            while(true){
                int oprNum = asyncDBStrategy.getOprNum();
                for(int i = 0; i < oprNum; i ++){
                    try {
                        AsyncDBEntity entity = queue.take();

                        if(entity == POISON){
                            log.info("AsyncDBOperator return");
                            return;
                        }

                        entity.tryBDOpr(asyncDBStrategy.getTryTimes());

                        syncNum++;
                    } catch (InterruptedException e) {
                        log.error("", e);
                    }
                }

                int duration = asyncDBStrategy.getDuration(queue.size());
                if(!isStopped){
                    try {
                        Thread.sleep(duration);
                    } catch (InterruptedException e) {
                        log.error("", e);
                    }
                }
                else{
                    if(queue.isEmpty()){
                        return;
                    }
                }
            }
        }

        @Override
        public void close() {
            submit(POISON);
            isStopped = true;
        }

        public SyncState getSyncState(){
            long syncNum = this.syncNum;
            long preSyncNum = this.preSyncNum;
            int waittingOprNum = queue.size();
            long syncNumPeriodNum = syncNum - preSyncNum;
            this.preSyncNum = syncNum;

            return new SyncState(threadName, syncNum, waittingOprNum, syncNumPeriodNum);
        }
    }
}
