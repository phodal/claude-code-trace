# Claude Code Hooks Trace

基于 [Claude Code Hooks API](https://code.claude.com/docs/en/hooks) 的 Agent Trace 实现模板。

## 概述

此模板提供了一套完整的 Claude Code Hooks 配置和脚本，用于追踪 AI 代码生成会话中的所有活动。与基于 OTEL 的 `agent-trace-core` 不同，此方案直接利用 Claude Code 的原生 Hooks 机制，无需额外的代理或中间件。

## 特性

- 🪝 **原生 Hooks 集成** - 直接使用 Claude Code 的 Hooks API
- 📝 **多语言支持** - 提供 Bash、Python、TypeScript 三种实现
- 📊 **会话追踪** - 完整记录会话生命周期
- 🔧 **工具追踪** - 追踪 Write、Edit、Bash 等工具调用
- 📁 **文件追踪** - 记录所有文件修改
- 🔄 **OTEL 导出** - 支持导出为 OpenTelemetry 格式
- 🔍 **分析工具** - 提供 trace 分析脚本

## 快速开始

### 1. 复制模板到你的项目

```bash
cp -r claude-code-hooks-trace/.claude /your/project/
```

### 2. 设置脚本权限

```bash
chmod +x /your/project/.claude/hooks/*.sh
```

### 3. 选择实现语言

**Bash (默认)**
```bash
# 使用默认的 settings.json，无需额外配置
```

**Python**
```bash
cp .claude/settings.python.json .claude/settings.json
```

**TypeScript**
```bash
# 安装依赖
npm install -g ts-node typescript @types/node

# 配置 settings.json 使用 ts-node
```

### 4. 开始使用

正常使用 Claude Code，所有活动将自动被追踪到 `.agent-trace/` 目录。

## 目录结构

```
.claude/
├── settings.json              # Hooks 配置文件
├── settings.python.json       # Python 版本配置
└── hooks/
    ├── session-start.sh       # 会话开始 hook
    ├── pre-tool-use.sh        # 工具调用前 hook
    ├── post-tool-use.sh       # 工具调用后 hook
    ├── session-stop.sh        # 会话结束 hook
    ├── notification.sh        # 通知 hook
    ├── trace_handler.py       # Python 处理器
    └── trace_handler.ts       # TypeScript 处理器

scripts/
├── trace_analyzer.py          # Trace 分析工具
└── export_to_otel.py          # OTEL 格式导出

.agent-trace/                  # Trace 输出目录
├── session-YYYYMMDD-HHMMSS.jsonl
└── ...
```

## Hook 事件

| 事件 | 触发时机 | 用途 |
|------|----------|------|
| `SessionStart` | 会话开始 | 初始化 trace 文件 |
| `PreToolUse` | 工具执行前 | 记录工具调用意图 |
| `PostToolUse` | 工具执行后 | 记录工具执行结果 |
| `Stop` | 会话结束 | 生成会话摘要 |
| `Notification` | 需要用户注意 | 记录通知事件 |

## Trace 数据格式

每条 trace 记录为一行 JSON (JSONL 格式):

```json
{
  "version": "0.1.0",
  "event_type": "post_tool_use",
  "session_id": "abc123",
  "span_id": "span-1234567890",
  "timestamp": "2024-01-15T10:30:00Z",
  "tool": {
    "name": "Write",
    "output": {...}
  },
  "file": {
    "path": "src/main.py",
    "line_count": 42,
    "git_status": "M"
  }
}
```

## 分析 Trace

```bash
# 查看会话摘要
python scripts/trace_analyzer.py .agent-trace/session-*.jsonl

# 导出为 JSON
python scripts/trace_analyzer.py .agent-trace/session-*.jsonl -o analysis.json

# 导出为 OTEL 格式
python scripts/export_to_otel.py .agent-trace/session-*.jsonl -o otel-traces.json
```

## 自定义

### 添加新的 Hook 事件

1. 在 `settings.json` 中添加事件配置
2. 创建对应的处理脚本
3. 重新加载 Claude Code (运行 `/hooks` 命令)

### 集成外部系统

- **HTTP Hooks**: 修改 `settings.json` 使用 `type: "http"`
- **数据库存储**: 修改处理脚本写入数据库
- **实时监控**: 集成 Prometheus/Grafana

## 与 agent-trace-core 对比

| 特性 | claude-code-hooks-trace | agent-trace-core (OTEL) |
|------|-------------------------|-------------------------|
| 依赖 | Claude Code 原生 | OTEL SDK + 代理服务器 |
| 部署 | 复制文件即可 | 需要启动服务 |
| 数据格式 | JSONL | OTEL Protobuf/JSON |
| 可视化 | 自定义分析 | Jaeger/Zipkin |
| 实时性 | 文件追加 | 实时上报 |

## 许可证

MIT License

