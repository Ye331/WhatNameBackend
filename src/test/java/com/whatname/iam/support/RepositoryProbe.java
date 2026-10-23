package com.whatname.iam.support;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.datasource.DataSourceUtils;
import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Test-only interception of repository fragments, including rollback and race injection. */
@Aspect
public abstract class RepositoryProbe {
    @Autowired private DataSource dataSource;

    @Around("execution(public * com.whatname.iam.repository..*OperationsImpl.*(..))")
    public Object around(ProceedingJoinPoint point) throws Throwable {
        return intercept(new Invocation(point, dataSource));
    }

    public abstract Object intercept(Invocation call) throws Throwable;

    public static final class Invocation {
        private final ProceedingJoinPoint point;
        private final DataSource dataSource;
        Invocation(ProceedingJoinPoint point, DataSource dataSource) {
            this.point = point;
            this.dataSource = dataSource;
        }
        public String id() {
            return point.getSignature().getDeclaringTypeName().replace("OperationsImpl", "")
                    + "." + point.getSignature().getName();
        }
        public Map<String, Object> parameters() {
            Object[] args = point.getArgs();
            if (args.length == 1 && args[0] instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked") var result = (Map<String, Object>) map;
                return result;
            }
            var signature = (MethodSignature) point.getSignature();
            var names = signature.getParameterNames();
            var result = new LinkedHashMap<String, Object>();
            for (int i = 0; i < args.length; i++) result.put(names[i], args[i]);
            return result;
        }
        public Connection connection() { return DataSourceUtils.getConnection(dataSource); }
        public Object proceed() throws Throwable { return point.proceed(); }
    }
}
