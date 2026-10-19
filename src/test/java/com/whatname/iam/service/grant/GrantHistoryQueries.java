package com.whatname.iam.service.grant;

import com.whatname.iam.common.security.JwtTokenService;
import com.whatname.iam.service.permission.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.*;
import org.springframework.mock.web.MockHttpServletRequest;
import static com.whatname.iam.service.permission.SignedDataType.*;

/** Query-only fixture bootstrap; producers keep their real existing event/business implementations. */
public final class GrantHistoryQueries {
    private GrantHistoryQueries() { }
    public static long bootstrap(JdbcTemplate db,DataSignatureService signatures,PlatformTransactionManager manager,
            PermissionService permissions,long base,long department) {
        long role=base+80,app=base+81,menu=base+82,reader=base+90;
        db.update("INSERT INTO sys_user(id,username,real_name_ciphertext,phone,phone_hash,password_hash,dept_id,source,status,password_initialized) VALUES(?,?,'fixture','fixture',?,'fixture',?,3,1,1)",reader,"HISTORY"+reader,String.format("%064x",reader),department);
        db.update("INSERT INTO sys_admin(user_id,admin_level,dept_id,status) VALUES(?,1,?,1)",reader,department);
        db.update("INSERT INTO sys_role(id,role_code,role_name,role_type) VALUES(?,?,'Fixture',1)",role,"HISTORY"+role);
        db.update("INSERT INTO sys_application(id,app_code,app_name) VALUES(?,?,'Fixture')",app,"HISTORY"+base);
        db.update("INSERT INTO sys_menu(id,app_id,parent_id,menu_code,menu_name,menu_type) VALUES(?,?,0,'IAM_GRANTS_VIEW','Fixture',2)",menu,app);
        db.update("INSERT INTO sys_role_permission(role_id,menu_id) VALUES(?,?)",role,menu);
        db.update("INSERT INTO sys_user_role_source(user_id,role_id,source_type,source_ref,granted_by) VALUES(?,?,'MANUAL','QUERY-BOOTSTRAP',?)",reader,role,reader);
        db.update("INSERT INTO sys_user_role(user_id,role_id,granted_by) VALUES(?,?,?)",reader,role,reader);
        new TransactionTemplate(manager).executeWithoutResult(s -> {
            signatures.sign(USER_IDENTITY,reader); signatures.sign(USER_GRANTS,reader);
            signatures.sign(ADMIN,reader); signatures.sign(ADMIN_DELEGABLE_ROLES,reader);
            signatures.sign(RESOURCE_APPLICATION,app); signatures.sign(RESOURCE_MENU,menu); signatures.sign(MENU_APIS,menu); signatures.sign(ROLE_MENUS,role);
        });
        org.springframework.test.util.ReflectionTestUtils.setField((Object)org.springframework.test.util.AopTestUtils.getUltimateTargetObject(permissions),"managementApplication","HISTORY"+base);
        return reader;
    }
    public static com.whatname.iam.dto.grant.GrantDtos.HistoryPage read(GrantHistoryService history,JwtTokenService jwt,
            long reader,long target,String role,int page,int size) {
        var previous=RequestContextHolder.getRequestAttributes();
        var request=new MockHttpServletRequest(); request.addHeader("Authorization","Bearer "+jwt.issue(reader,1));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try { return history.history(Long.toString(target),role,null,null,page,size); }
        finally { if(previous==null) RequestContextHolder.resetRequestAttributes(); else RequestContextHolder.setRequestAttributes(previous); }
    }
}
