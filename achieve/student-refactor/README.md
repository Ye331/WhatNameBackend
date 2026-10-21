# 学生可读性重构归档

- src/main/java：被替代或移除的阶段占位装配。
- before-renames：改为功能名称之前的类与测试，保存迁移前版本。
- before-services：本轮开始前的账号、认证、部门 Service。
- placeholders：40 个空包说明文件。
- TESTING-before.md：本轮干净重测前的历史验证记录。

归档不进入 Maven 源码目录。不要将旧实现拷回正式目录同时运行。当前代码与说明见 [功能说明](../../docs/GUIDE.md)。
