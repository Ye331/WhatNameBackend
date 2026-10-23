package com.whatname.iam.repository.admin;

import java.util.List;

public interface ResourceLockRepositoryOperations {
    List<Long> applications();

    List<Long> menus();

    List<Long> apis();

    List<Long> roleMenus();

    List<Long> menuApis();

}
