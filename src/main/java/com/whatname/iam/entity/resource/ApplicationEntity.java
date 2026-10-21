package com.whatname.iam.entity.resource;

public class ApplicationEntity {
    private Long id;
    private String appCode;
    private String appName;
    private String appDesc;
    private Integer status;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAppCode() {
        return appCode;
    }

    public void setAppCode(String appCode) {
        this.appCode = appCode;
    }

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public String getAppDesc() {
        return appDesc;
    }

    public void setAppDesc(String appDesc) {
        this.appDesc = appDesc;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
