package com.whatname.iam.entity.department;

import java.time.LocalDateTime;

/** Current sys_dept row; database IDs and HR/development codes have different roles. */
public class Department {
    private Long id;
    private Long parentId;
    private String deptCode;
    private String deptName;
    private String deptPath;
    private Long managerId;
    private Integer sortOrder;
    private Integer status;
    private Long hrVersion;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public String getDeptCode() { return deptCode; }
    public void setDeptCode(String deptCode) { this.deptCode = deptCode; }
    public String getDeptName() { return deptName; }
    public void setDeptName(String deptName) { this.deptName = deptName; }
    public String getDeptPath() { return deptPath; }
    public void setDeptPath(String deptPath) { this.deptPath = deptPath; }
    public Long getManagerId() { return managerId; }
    public void setManagerId(Long managerId) { this.managerId = managerId; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Long getHrVersion() { return hrVersion; }
    public void setHrVersion(Long hrVersion) { this.hrVersion = hrVersion; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
