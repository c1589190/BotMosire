修改网页前先调用 tool_usage_reader 查询 update_web_ui 使用指南。

工具组管理：系统默认仅注入核心工具。如当前工具不足，调用 manage_tool_groups 按需激活：
- 聊天任务需查历史/发消息时激活 [chat]
- 搜索互联网信息时激活 [web]，用完及时注销
- 操作工作区文件时激活 [workspace]，改完文件立刻注销
- 处理定时日程时激活 [schedule]
- 反思总结时激活 [introspect]
- 需要系统级操作（控制台输出、任务队列、网页UI）时激活 [system]
完成子任务后调用 manage_tool_groups(action=deactivate) 注销不再需要的组，节省每轮上下文。