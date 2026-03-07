#!/usr/bin/env node
/**
 * Claude Code Hooks Trace Handler (JavaScript)
 * A comprehensive trace handler that records all Claude Code activities.
 * 
 * Usage: node trace_handler.js <event_type>
 */

const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const VERSION = '0.1.0';

class TraceHandler {
  constructor() {
    this.projectDir = process.env.CLAUDE_PROJECT_DIR || process.cwd();
    this.traceDir = path.join(this.projectDir, '.agent-trace');
    if (!fs.existsSync(this.traceDir)) {
      fs.mkdirSync(this.traceDir, { recursive: true });
    }
  }

  getCurrentTraceFile() {
    const markerFile = path.join(this.traceDir, '.current_trace_file');
    if (fs.existsSync(markerFile)) {
      return fs.readFileSync(markerFile, 'utf-8').trim();
    }
    const timestamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
    return path.join(this.traceDir, `session-${timestamp}.jsonl`);
  }

  getGitInfo() {
    const gitInfo = { type: 'git', commit: '', branch: '' };
    try {
      gitInfo.commit = execSync('git rev-parse HEAD', { cwd: this.projectDir, encoding: 'utf-8' }).trim();
      gitInfo.branch = execSync('git rev-parse --abbrev-ref HEAD', { cwd: this.projectDir, encoding: 'utf-8' }).trim();
    } catch (e) {}
    return gitInfo;
  }

  writeTrace(record) {
    const traceFile = this.getCurrentTraceFile();
    fs.appendFileSync(traceFile, JSON.stringify(record) + '\n');
  }

  createBaseRecord(eventType, input) {
    return {
      version: VERSION,
      event_type: eventType,
      session_id: input.session_id || '',
      timestamp: new Date().toISOString(),
    };
  }

  handleSessionStart(input) {
    const timestamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
    const traceFile = path.join(this.traceDir, `session-${timestamp}.jsonl`);
    fs.writeFileSync(path.join(this.traceDir, '.current_trace_file'), traceFile);
    fs.writeFileSync(path.join(this.traceDir, '.current_session'), input.session_id || '');
    
    const record = this.createBaseRecord('session_start', input);
    record.context = { project_dir: this.projectDir, vcs: this.getGitInfo() };
    record.trace_file = traceFile;
    this.writeTrace(record);
    return { systemMessage: 'Trace session initialized' };
  }

  handlePreToolUse(input) {
    const spanId = `span-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`;
    fs.writeFileSync(path.join(this.traceDir, '.current_span_id'), spanId);
    
    const record = this.createBaseRecord('pre_tool_use', input);
    record.span_id = spanId;
    record.tool = { name: input.tool_name || 'unknown', input: input.tool_input };
    const filePath = input.tool_input?.file_path || input.tool_input?.path;
    if (filePath) record.file_path = filePath;
    this.writeTrace(record);
    return {};
  }

  handlePostToolUse(input) {
    const spanIdFile = path.join(this.traceDir, '.current_span_id');
    const spanId = fs.existsSync(spanIdFile) ? fs.readFileSync(spanIdFile, 'utf-8').trim() : '';
    
    const record = this.createBaseRecord('post_tool_use', input);
    record.span_id = spanId;
    record.tool = { name: input.tool_name || 'unknown', output: input.tool_output };
    
    const filePath = input.tool_input?.file_path || input.tool_input?.path;
    if (filePath) {
      const fullPath = path.join(this.projectDir, filePath);
      record.file = {
        path: filePath,
        exists: fs.existsSync(fullPath),
        line_count: fs.existsSync(fullPath) ? fs.readFileSync(fullPath, 'utf-8').split('\n').length : 0
      };
    }
    try { fs.unlinkSync(spanIdFile); } catch (e) {}
    this.writeTrace(record);
    return {};
  }

  handleStop(input) {
    if (input.stop_hook_active) return {};
    const record = this.createBaseRecord('session_stop', input);
    const traceFile = this.getCurrentTraceFile();
    if (fs.existsSync(traceFile)) {
      record.summary = { event_count: fs.readFileSync(traceFile, 'utf-8').split('\n').filter(l => l).length };
    }
    this.writeTrace(record);
    return {};
  }

  handleNotification(input) {
    const record = this.createBaseRecord('notification', input);
    record.notification = { type: input.type, title: input.title, message: input.message };
    this.writeTrace(record);
    return {};
  }
}

async function main() {
  const eventType = process.argv[2];
  if (!eventType) {
    console.error('Usage: node trace_handler.js <event_type>');
    process.exit(1);
  }

  let inputData = '';
  for await (const chunk of process.stdin) inputData += chunk;
  const input = JSON.parse(inputData);
  
  const handler = new TraceHandler();
  const handlers = {
    SessionStart: (i) => handler.handleSessionStart(i),
    PreToolUse: (i) => handler.handlePreToolUse(i),
    PostToolUse: (i) => handler.handlePostToolUse(i),
    Stop: (i) => handler.handleStop(i),
    Notification: (i) => handler.handleNotification(i),
  };

  if (handlers[eventType]) {
    const result = handlers[eventType](input);
    if (Object.keys(result).length > 0) console.log(JSON.stringify(result));
  }
}

main();

