package com.whatname.iam.service.user;

import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/** Deliberately opt-in, non-HTTP local maintenance. No application-startup backfill. */
@Component
@ConditionalOnProperty(name="iam.maintenance.name-index",havingValue="true")
public class NameIndexMaintenance implements ApplicationRunner {
    private final NameSearchIndex index;
    private final DataSource source;
    private final ConfigurableApplicationContext context;
    private final long after,through;
    private final int batch;
    private final String webType;
    public NameIndexMaintenance(NameSearchIndex index,DataSource source,ConfigurableApplicationContext context,
            @Value("${iam.maintenance.after-id}") long after,@Value("${iam.maintenance.through-id}") long through,
            @Value("${iam.maintenance.batch-size:25}") int batch,
            @Value("${spring.main.web-application-type:servlet}") String webType) {
        this.index=index;this.source=source;this.context=context;this.after=after;this.through=through;this.batch=batch;this.webType=webType;
    }
    public void run(ApplicationArguments arguments) throws Exception {
        try {
            if(!"none".equalsIgnoreCase(webType)) throw new IllegalStateException("Non-web maintenance required");
            try(var connection=source.getConnection()) {
                if(!Pattern.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/[A-Za-z0-9_]+(?:\\?[^\\r\\n]*)?",connection.getMetaData().getURL()))
                    throw new IllegalStateException("Explicit loopback MySQL required");
            }
            long cursor=index.backfillBatch(after,through,batch);
            System.out.println("NAME_INDEX_BATCH checkpoint="+cursor+" through="+through+" (repeat until checkpoint stops)");
        } finally { context.close(); }
    }
}
