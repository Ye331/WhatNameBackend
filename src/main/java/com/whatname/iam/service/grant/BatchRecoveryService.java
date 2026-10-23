package com.whatname.iam.service.grant;

import com.whatname.iam.repository.grant.BatchRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;

/** 有界数据库发现和进程内去重；跨进程正确性由 Worker 的数据库任务/逐项锁负责。 */
@Service
public class BatchRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(BatchRecoveryService.class);
    private final BatchRepository repository;
    private final BatchWorker worker;
    private final Executor executor;
    private final boolean enabled;
    private final Set<Long> dispatched = new HashSet<>();
    private long cursor;

    public BatchRecoveryService(
            BatchRepository repository,
            BatchWorker worker,
            @Qualifier("grantBatchExecutor") Executor executor,
            @Value("${iam.grant-batch.recovery.enabled:true}") boolean enabled) {
        this.repository = repository;
        this.worker = worker;
        this.executor = executor;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        if (enabled) scan();
    }

    @Scheduled(
            fixedDelayString = "${iam.grant-batch.recovery.delay-ms:5000}",
            initialDelayString = "${iam.grant-batch.recovery.delay-ms:5000}")
    public void poll() {
        if (enabled) scan();
    }

    public synchronized void scan() {
        try {
            var ids = repository.unfinished(cursor, 100);
            if (ids.isEmpty()) {
                cursor = 0;
                return;
            }
            for (long id : ids) dispatch(id);
            cursor = ids.getLast();
        } catch (RuntimeException failure) {
            log.warn("Batch recovery scan deferred: {}", failure.getClass().getSimpleName());
        }
    }

    public synchronized void dispatch(long batchId) {
        if (dispatched.size() >= 100 || !dispatched.add(batchId)) return;
        try {
            executor.execute(
                    () -> {
                        try {
                            worker.run(batchId);
                        } finally {
                            synchronized (BatchRecoveryService.this) {
                                dispatched.remove(batchId);
                            }
                        }
                    });
        } catch (RuntimeException failure) {
            dispatched.remove(batchId);
            log.warn(
                    "Batch {} persisted but not dispatched: {}",
                    batchId,
                    failure.getClass().getSimpleName());
        }
    }
}
