# 测试说明与验证结果

验证日期：2026-10-23。使用干净构建连续执行全部默认Java测试和8个集成测试类，结果为BUILD SUCCESS，后端耗时18分27秒。

| 验证范围 | 结果 |
| --- | --- |
| 后端默认测试（Surefire） | 498项通过 |
| 后端集成测试（Failsafe） | 250项通过 |
| 后端 Java 合计 | **748项通过，0失败、0错误、0跳过** |
| Python 数据整理脚本测试 | 4项通过，另计 |
| 前端 Playwright 浏览器测试 | **39项通过**，约44秒 |
| 前端生产构建 | 通过 |
| 后端行覆盖率 | **92.53%（6674/7213）** |
| 后端分支覆盖率 | **76.63%（2492/3252）** |
| 同步批量 BatchService | 行覆盖34/34、分支覆盖12/12，均100% |

## 验证内容

后端测试验证逐人独立事务、单人业务失败后继续、全部失败、指定来源回收、完整性错误中止、提交确认丢失、无效会话和外层事务拒绝。真实数据库回滚由现有 GrantIT 等集成用例同时验证；Mockito用例只验证编排边界。前端测试检查缺少逐人结果、旧异步状态，确保不展示成功或允许重试未知结果。

## 执行方法

先启动 Docker Desktop，安装JDK21、Python、Node.js及Chrome。在PowerShell执行：

```powershell
cd D:\WhatName\backend
.\scripts\dev.ps1 test -Integration

cd D:\WhatName\frontend
npm ci
npm test -- --workers=2
npm run build
```

后端脚本先跑Python测试，再执行Maven `-Pcoverage,integration-tests clean verify`。如果找不到Java，先将JAVA_HOME设置为自己的JDK21安装目录。数据库测试使用临时MySQL和测试配置，不使用个人演示数据库。前端需要5175、5176端口空闲。

覆盖率报告为 `backend/target/site/jacoco/index.html`，计数来自同目录 `jacoco.xml` 的顶层LINE、BRANCH counter；测试报告分别在 `target/surefire-reports` 和 `target/failsafe-reports`。运行日志可在执行命令时重定向保存，日志文件不纳入版本控制。

前端测试使用模拟接口，不代表真实前后端页面联调，也没有统计前端代码覆盖率。后端可信服务端到端测试使用真实应用链路与临时数据库。覆盖率只能表明执行范围，不能保证没有缺陷。
