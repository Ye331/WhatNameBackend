package com.whatname.iam.service.permission;

/** Fixed schema for sign_version=1. SQL identifiers never come from HTTP input. */
public enum SignedDataType {
    USER_IDENTITY("sys_user", "id", "id,username,real_name_ciphertext,phone,phone_hash,password_hash,employee_no,dept_id,status,source,employment_type,password_initialized,session_version,grant_revision,hr_version,created_by,temp_reason"),
    USER_GRANTS("sys_user", "id", "id,grant_revision"),
    ROLE_MENUS("sys_role", "id", "id,role_code,role_type,status,menu_revision"),
    RESOURCE_APPLICATION("sys_application", "id", "id,app_code,status"),
    RESOURCE_MENU("sys_menu", "id", "id,app_id,parent_id,menu_code,menu_name,menu_type,path,icon,sort_order,status,api_revision"),
    RESOURCE_API("sys_api", "id", "id,app_id,api_code,api_path,canonical_path,http_method,status"),
    MENU_APIS("sys_menu", "id", "id,app_id,menu_type,status,api_revision"),
    DEPARTMENT("sys_dept", "id", "id,parent_id,dept_code,dept_name,dept_path,status,merged_into_dept_id,manager_id,hr_version"),
    ORGANIZATION("sys_dept", "id", "id,parent_id,dept_code,dept_name,dept_path,status,merged_into_dept_id,manager_id,hr_version"),
    ADMIN("sys_admin", "user_id", "user_id,admin_level,dept_id,appointed_by,status,delegable_revision"),
    ADMIN_DELEGABLE_ROLES("sys_admin", "user_id", "user_id,delegable_revision"),
    SERVICE_IDENTITY("sys_service_identity", "id", "id,app_id,client_id,credential_hash,public_key,key_id,allowed_scope,allowed_role_ids,allowed_dept_ids,allowed_operations,status");

    public final String table, idColumn, fields;
    SignedDataType(String table, String idColumn, String fields) {
        this.table = table; this.idColumn = idColumn; this.fields = fields;
    }
}
