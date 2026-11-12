//! Responses API client to Chat Completions upstream conversion.
//!
//! This intentionally rejects input it cannot represent, rather than silently
//! discarding conversation items or tool definitions.
use std::collections::{BTreeMap, HashSet};
use serde_json::{json, Map, Value};

#[derive(Debug)]
pub struct Request {
    pub body: Value,
    pub stream: bool,
    pub custom_tools: HashSet<String>,
}

fn string<'a>(object: &'a Value, key: &str) -> Result<&'a str, String> {
    object.get(key).and_then(Value::as_str).filter(|s| !s.is_empty())
        .ok_or_else(|| format!("Expected a non-empty {key}"))
}

fn object(value: &Value) -> Result<&Map<String, Value>, String> {
    value.as_object().ok_or_else(|| "Expected a JSON object".into())
}

fn check_keys(value: &Value, allowed: &[&str], context: &str) -> Result<(), String> {
    for (key, value) in object(value)? {
        if !value.is_null() && !allowed.contains(&key.as_str()) {
            return Err(format!("Unsupported {context} field: {key}"));
        }
    }
    Ok(())
}

fn parts(content: &Value) -> Result<Value, String> {
    if content.is_string() { return Ok(content.clone()); }
    let list = content.as_array().ok_or("Message content must be text or an array")?;
    let mut output = Vec::new();
    for item in list {
        match string(item, "type")? {
            "input_text" | "output_text" | "text" => {
                check_keys(item, &["type", "text", "annotations", "logprobs"], "text content")?;
                if item.get("annotations").and_then(Value::as_array).is_some_and(|a| !a.is_empty()) {
                    return Err("Annotated text is not supported in converted history".into());
                }
                output.push(json!({"type":"text","text":item.get("text").and_then(Value::as_str).ok_or("Missing text")?}));
            }
            "input_image" => {
                check_keys(item, &["type", "image_url", "detail"], "image content")?;
                let url = item.get("image_url").and_then(Value::as_str).ok_or("Image requires image_url")?;
                let mut image = json!({"url":url});
                if let Some(detail) = item.get("detail") { image["detail"] = detail.clone(); }
                output.push(json!({"type":"image_url","image_url":image}));
            }
            kind => return Err(format!("Unsupported Responses content type: {kind}")),
        }
    }
    Ok(Value::Array(output))
}

fn append_call(messages: &mut Vec<Value>, call: Value) {
    if let Some(last) = messages.last_mut() {
        if last.get("role").and_then(Value::as_str) == Some("assistant") {
            if last.get("tool_calls").is_none() { last["tool_calls"] = json!([]); }
            if let Some(calls) = last.get_mut("tool_calls").and_then(Value::as_array_mut) {
                calls.push(call);
                return;
            }
        }
    }
    messages.push(json!({"role":"assistant","content":null,"tool_calls":[call]}));
}

pub fn request(body: &Value, upstream_model: &str) -> Result<Request, String> {
    check_keys(body, &["model","input","instructions","stream","store","parallel_tool_calls",
        "temperature","top_p","max_output_tokens","tool_choice","tools","reasoning","include",
        "text","metadata","service_tier","prompt_cache_key","safety_identifier"], "Responses request")?;
    if body.get("store").and_then(Value::as_bool) == Some(true) {
        return Err("Stored Responses are not supported by a Chat Completions provider".into());
    }
    if let Some(include) = body.get("include") {
        let include = include.as_array().ok_or("include must be an array")?;
        if include.iter().any(|v| v.as_str() != Some("reasoning.encrypted_content")) {
            return Err("The requested Responses include field is not supported".into());
        }
    }
    let mut messages = Vec::new();
    if let Some(instructions) = body.get("instructions").filter(|v| !v.is_null()) {
        let text = instructions.as_str().ok_or("instructions must be text")?;
        if !text.is_empty() { messages.push(json!({"role":"system","content":text})); }
    }
    let input = body.get("input").ok_or("Responses input is required")?;
    if let Some(text) = input.as_str() {
        messages.push(json!({"role":"user","content":text}));
    } else {
        for item in input.as_array().ok_or("Responses input must be text or an array")? {
            let kind = item.get("type").and_then(Value::as_str).unwrap_or("message");
            match kind {
                "message" => {
                    check_keys(item, &["type","id","status","role","content"], "message")?;
                    let role = match string(item, "role")? {
                        "developer" => "system", "system" => "system", "user" => "user", "assistant" => "assistant",
                        role => return Err(format!("Unsupported Responses role: {role}")),
                    };
                    let content = parts(item.get("content").ok_or("Message content is required")?)?;
                    if role == "assistant" && messages.last().is_some_and(|last: &Value| last.get("reasoning_content").is_some() && last.get("content").is_some_and(Value::is_null) && last.get("tool_calls").is_none()) {
                        messages.last_mut().expect("checked above")["content"] = content;
                    } else { messages.push(json!({"role":role,"content":content})); }
                }
                "reasoning" => {
                    check_keys(item, &["type","id","summary","encrypted_content","status"], "reasoning item")?;
                    if item.get("encrypted_content").is_some_and(|value| !value.is_null()) {
                        return Err("Encrypted reasoning cannot be converted to Chat Completions".into());
                    }
                    let summary = item.get("summary").and_then(Value::as_array).ok_or("Reasoning summary must be an array")?;
                    let mut text = String::new();
                    for part in summary {
                        if string(part, "type")? != "summary_text" { return Err("Unsupported reasoning summary".into()); }
                        text.push_str(part.get("text").and_then(Value::as_str).ok_or("Reasoning summary must be text")?);
                    }
                    if !text.is_empty() { messages.push(json!({"role":"assistant","content":null,"reasoning_content":text})); }
                }
                "function_call" | "custom_tool_call" => {
                    check_keys(item, &["type","id","call_id","name","arguments","input","status"], "tool call")?;
                    let id = string(item, "call_id")?;
                    let name = string(item, "name")?;
                    let args = if kind == "custom_tool_call" {
                        json!({"input":item.get("input").and_then(Value::as_str).ok_or("Custom tool input must be text")?}).to_string()
                    } else { item.get("arguments").and_then(Value::as_str).ok_or("Tool arguments must be text")?.to_owned() };
                    append_call(&mut messages, json!({"id":id,"type":"function","function":{"name":name,"arguments":args}}));
                }
                "function_call_output" | "custom_tool_call_output" => {
                    check_keys(item, &["type","id","call_id","output","status"], "tool output")?;
                    let output = item.get("output").and_then(Value::as_str).ok_or("Tool output must be text")?;
                    messages.push(json!({"role":"tool","tool_call_id":string(item,"call_id")?,"content":output}));
                }
                kind => return Err(format!("Unsupported Responses input item: {kind}")),
            }
        }
    }
    let mut result = json!({"model":upstream_model,"messages":messages,"stream":body.get("stream").and_then(Value::as_bool).unwrap_or(false)});
    if let Some(text) = body.get("text").filter(|v| !v.is_null()) {
        check_keys(text, &["format","verbosity"], "text")?;
        if let Some(verbosity) = text.get("verbosity").filter(|v| !v.is_null()) { result["verbosity"] = verbosity.clone(); }
        if let Some(format) = text.get("format").filter(|v| !v.is_null()) {
            let kind = string(format, "type")?;
            result["response_format"] = match kind {
                "text" | "json_object" => {
                    check_keys(format, &["type"], "text format")?;
                    json!({"type":kind})
                }
                "json_schema" => {
                    check_keys(format, &["type","name","description","schema","strict"], "JSON schema")?;
                    let mut schema = format.clone();
                    schema.as_object_mut().expect("validated object").remove("type");
                    json!({"type":"json_schema","json_schema":schema})
                }
                _ => return Err(format!("Unsupported Responses text format: {kind}")),
            };
        }
    }
    for key in ["temperature","top_p","parallel_tool_calls","metadata","service_tier","prompt_cache_key","safety_identifier"] {
        if let Some(v) = body.get(key).filter(|v| !v.is_null()) { result[key] = v.clone(); }
    }
    if let Some(v) = body.get("max_output_tokens").filter(|v| !v.is_null()) { result["max_tokens"] = v.clone(); }
    if let Some(reasoning) = body.get("reasoning").filter(|v| !v.is_null()) {
        check_keys(reasoning, &["effort","summary"], "reasoning")?;
        if let Some(v) = reasoning.get("effort").filter(|v| !v.is_null()) { result["reasoning_effort"] = v.clone(); }
    }
    let mut custom_tools = HashSet::new();
    if let Some(tools) = body.get("tools").filter(|v| !v.is_null()) {
        let mut converted = Vec::new();
        for tool in tools.as_array().ok_or("tools must be an array")? {
            let name = string(tool,"name")?;
            let kind = string(tool,"type")?;
            let function = match kind {
                "function" => {
                    check_keys(tool, &["type","name","description","parameters","strict"], "function tool")?;
                    let mut function = json!({"name":name,"parameters":tool.get("parameters").cloned().unwrap_or(json!({}))});
                    for key in ["description","strict"] { if let Some(v) = tool.get(key) { function[key] = v.clone(); } }
                    function
                }
                "custom" => {
                    check_keys(tool, &["type","name","description","format"], "custom tool")?;
                    if tool.get("format").is_some_and(|v| !v.is_null() && v.get("type").and_then(Value::as_str) != Some("text")) {
                        return Err("Grammar-constrained custom tools cannot be converted".into());
                    }
                    custom_tools.insert(name.to_owned());
                    let mut function = json!({"name":name,"parameters":{"type":"object","properties":{"input":{"type":"string"}},"required":["input"],"additionalProperties":false}});
                    if let Some(v) = tool.get("description") { function["description"] = v.clone(); }
                    function
                }
                kind => return Err(format!("Unsupported Responses tool type: {kind}")),
            };
            converted.push(json!({"type":"function","function":function}));
        }
        if !converted.is_empty() { result["tools"] = json!(converted); }
    }
    if let Some(choice) = body.get("tool_choice").filter(|v| !v.is_null()) {
        result["tool_choice"] = if choice.is_string() { choice.clone() } else {
            let kind = string(choice,"type")?;
            if kind != "function" && kind != "custom" { return Err(format!("Unsupported tool choice: {kind}")); }
            json!({"type":"function","function":{"name":string(choice,"name")?}})
        };
    }
    let stream = result["stream"].as_bool().unwrap_or(false);
    if stream { result["stream_options"] = json!({"include_usage":true}); }
    Ok(Request { body: result, stream, custom_tools })
}

#[derive(Default)]
struct Call { id: String, name: String, arguments: String }

/// Accumulates only the pieces needed for the final Responses object; text
/// deltas are emitted immediately and tool arguments stay ordered by index.
pub struct Response {
    id: String,
    created_at: u64,
    model: String,
    custom_tools: HashSet<String>,
    calls: BTreeMap<i64, Call>,
    text: String,
    output: Vec<Value>,
    usage: Option<Value>,
    finish_reason: Option<String>,
    message_open: bool,
    reasoning_open: bool,
    reasoning_text: String,
    finished: bool,
    sequence: u64,
}

impl Response {
    pub fn new(model: String, custom_tools: HashSet<String>) -> Self {
        Self { id: format!("resp_{}", uuid::Uuid::new_v4().simple()),
            created_at: std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default().as_secs(), model, custom_tools,
            calls: BTreeMap::new(), text: String::new(), output: Vec::new(), usage: None,
            finish_reason: None, message_open: false, reasoning_open: false, reasoning_text: String::new(), finished: false, sequence: 0 }
    }
    fn response(&self, status: &str) -> Value {
        let mut response = json!({"id":self.id,"object":"response","created_at":self.created_at,"status":status,
            "model":self.model,"output":self.output,"error":null,"incomplete_details":null});
        if let Some(usage) = &self.usage { response["usage"] = usage.clone(); }
        if status == "incomplete" { response["incomplete_details"] = json!({"reason":"max_output_tokens"}); }
        response
    }
    fn event(&mut self, kind: &str, mut data: Value) -> String {
        data["type"] = json!(kind);
        data["sequence_number"] = json!(self.sequence);
        self.sequence += 1;
        format!("event: {kind}\ndata: {data}\n\n")
    }
    pub fn start(&mut self) -> String {
        let response = self.response("in_progress");
        self.event("response.created", json!({"response":response}))
    }
    pub fn failed(&mut self, message: &str) -> String {
        self.finished = true;
        let mut response = self.response("failed");
        response["error"] = json!({"code":"upstream_error","message":message});
        self.event("response.failed", json!({"response":response}))
    }
    fn close_message(&mut self) -> String {
        if !self.message_open { return String::new(); }
        self.message_open = false;
        let index = self.output.len();
        let id = format!("msg_{}_{}", self.id, index);
        let text = std::mem::take(&mut self.text);
        let part = json!({"type":"output_text","text":text,"annotations":[]});
        let item = json!({"id":id,"type":"message","role":"assistant","status":"completed","content":[part]});
        let mut out = self.event("response.output_text.done", json!({"item_id":id,"output_index":index,"content_index":0,"text":text}));
        out += &self.event("response.content_part.done", json!({"item_id":id,"output_index":index,"content_index":0,"part":part}));
        out += &self.event("response.output_item.done", json!({"output_index":index,"item":item}));
        self.output.push(item);
        out
    }
    fn text_delta(&mut self, text: &str) -> String {
        let mut out = self.close_reasoning();
        let index = self.output.len();
        let id = format!("msg_{}_{}", self.id, index);
        if !self.message_open {
            self.message_open = true;
            out += &self.event("response.output_item.added", json!({"output_index":index,"item":{"id":id,"type":"message","role":"assistant","status":"in_progress","content":[]}}));
            out += &self.event("response.content_part.added", json!({"item_id":id,"output_index":index,"content_index":0,"part":{"type":"output_text","text":"","annotations":[]}}));
        }
        self.text.push_str(text);
        out += &self.event("response.output_text.delta", json!({"item_id":id,"output_index":index,"content_index":0,"delta":text,"logprobs":[]}));
        out
    }
    fn close_reasoning(&mut self) -> String {
        if !self.reasoning_open { return String::new(); }
        self.reasoning_open = false;
        let index = self.output.len();
        let id = format!("rs_{}_{}", self.id, index);
        let text = std::mem::take(&mut self.reasoning_text);
        let part = json!({"type":"summary_text","text":text});
        let item = json!({"type":"reasoning","id":id,"summary":[part]});
        let mut out = self.event("response.reasoning_summary_text.done", json!({"item_id":id,"output_index":index,"summary_index":0,"text":text}));
        out += &self.event("response.reasoning_summary_part.done", json!({"item_id":id,"output_index":index,"summary_index":0,"part":part}));
        out += &self.event("response.output_item.done", json!({"output_index":index,"item":item}));
        self.output.push(item);
        out
    }
    fn reasoning_delta(&mut self, text: &str) -> String {
        let mut out = self.close_message();
        let index = self.output.len();
        let id = format!("rs_{}_{}", self.id, index);
        if !self.reasoning_open {
            self.reasoning_open = true;
            out += &self.event("response.output_item.added", json!({"output_index":index,"item":{"id":id,"type":"reasoning","summary":[]}}));
            out += &self.event("response.reasoning_summary_part.added", json!({"item_id":id,"output_index":index,"summary_index":0,"part":{"type":"summary_text","text":""}}));
        }
        self.reasoning_text.push_str(text);
        out += &self.event("response.reasoning_summary_text.delta", json!({"item_id":id,"output_index":index,"summary_index":0,"delta":text}));
        out
    }
    pub fn feed(&mut self, payload: &str) -> Result<String, String> {
        if self.finished { return Ok(String::new()); }
        if payload == "[DONE]" { return self.finish(); }
        let chunk: Value = serde_json::from_str(payload).map_err(|e| format!("Invalid upstream SSE JSON: {e}"))?;
        if let Some(err) = chunk.get("error") { return Err(err.get("message").and_then(Value::as_str).unwrap_or("Upstream stream error").into()); }
        if let Some(usage) = chunk.get("usage").filter(|v| v.is_object()) {
            let input = usage.get("prompt_tokens").and_then(Value::as_i64).unwrap_or(0);
            let output = usage.get("completion_tokens").and_then(Value::as_i64).unwrap_or(0);
            let mut result = json!({"input_tokens":input,"output_tokens":output,"total_tokens":usage.get("total_tokens").and_then(Value::as_i64).unwrap_or(input+output),"input_tokens_details":{"cached_tokens":0},"output_tokens_details":{"reasoning_tokens":0}});
            if let Some(v) = usage.pointer("/prompt_tokens_details/cached_tokens") { result["input_tokens_details"]["cached_tokens"] = v.clone(); }
            if let Some(v) = usage.pointer("/completion_tokens_details/reasoning_tokens") { result["output_tokens_details"]["reasoning_tokens"] = v.clone(); }
            self.usage = Some(result);
        }
        let Some(choices) = chunk.get("choices").and_then(Value::as_array) else { return Err("Upstream chunk lacks choices".into()); };
        if choices.len() > 1 { return Err("Multiple upstream choices cannot be converted".into()); }
        let Some(choice) = choices.first() else { return Ok(String::new()); };
        if let Some(reason) = choice.get("finish_reason").and_then(Value::as_str) { self.finish_reason = Some(reason.into()); }
        let delta = choice.get("delta").ok_or("Upstream chunk lacks delta")?;
        check_keys(delta, &["role","content","reasoning_content","reasoning","tool_calls","refusal","annotations"], "upstream delta")?;
        if delta.get("annotations").and_then(Value::as_array).is_some_and(|items| !items.is_empty()) {
            return Err("Upstream annotations cannot be converted".into());
        }
        if delta.get("role").and_then(Value::as_str).is_some_and(|role| role != "assistant") {
            return Err("Upstream returned an unsupported role".into());
        }
        if delta.get("refusal").is_some_and(|v| !v.is_null()) { return Err("Upstream refusal cannot be converted".into()); }
        let mut out = String::new();
        if let Some(reasoning) = delta.get("reasoning_content").or_else(|| delta.get("reasoning")).filter(|v| !v.is_null()) {
            let text = reasoning.as_str().ok_or("Upstream reasoning delta is not text")?;
            if !text.is_empty() { out += &self.reasoning_delta(text); }
        }
        if let Some(content) = delta.get("content").filter(|v| !v.is_null()) {
            let text = content.as_str().ok_or("Upstream content delta is not text")?;
            if !text.is_empty() { out += &self.text_delta(text); }
        }
        if let Some(calls) = delta.get("tool_calls").filter(|v| !v.is_null()) {
            for call in calls.as_array().ok_or("Invalid tool call delta")? {
                check_keys(call, &["index","id","type","function"], "upstream tool call")?;
                if call.get("type").and_then(Value::as_str).is_some_and(|kind| kind != "function") {
                    return Err("Upstream returned an unsupported tool call type".into());
                }
                if let Some(function) = call.get("function") { check_keys(function, &["name","arguments"], "upstream function call")?; }
                let index = call.get("index").and_then(Value::as_i64).ok_or("Missing tool call index")?;
                let entry = self.calls.entry(index).or_default();
                if let Some(id) = call.get("id").and_then(Value::as_str) { entry.id.push_str(id); }
                if let Some(name) = call.pointer("/function/name").and_then(Value::as_str) { entry.name.push_str(name); }
                if let Some(args) = call.pointer("/function/arguments").and_then(Value::as_str) { entry.arguments.push_str(args); }
            }
        }
        Ok(out)
    }
    pub fn finish(&mut self) -> Result<String, String> {
        if self.finished { return Ok(String::new()); }
        let reason = self.finish_reason.as_deref().ok_or("Upstream ended without a finish reason")?.to_owned();
        if !matches!(reason.as_str(), "stop" | "tool_calls" | "length") { return Err(format!("Unsupported upstream finish reason: {reason}")); }
        let mut out = self.close_reasoning();
        out += &self.close_message();
        for (_, call) in std::mem::take(&mut self.calls) {
            if call.id.is_empty() || call.name.is_empty() { return Err("Incomplete upstream tool call".into()); }
            let index = self.output.len();
            let item_id = format!("fc_{}_{}", self.id, index);
            let custom = self.custom_tools.contains(&call.name);
            let mut item = if custom {
                let parsed: Value = serde_json::from_str(&call.arguments).map_err(|_| "Invalid custom tool arguments")?;
                let input = parsed.get("input").and_then(Value::as_str).ok_or("Custom tool arguments require input text")?;
                json!({"type":"custom_tool_call","id":item_id,"call_id":call.id,"name":call.name,"input":input})
            } else {
                json!({"type":"function_call","id":item_id,"call_id":call.id,"name":call.name,"arguments":call.arguments,"status":"completed"})
            };
            let mut started = item.clone();
            if custom { started["input"] = json!(""); } else { started["arguments"] = json!(""); started["status"] = json!("in_progress"); }
            out += &self.event("response.output_item.added", json!({"output_index":index,"item":started}));
            if custom {
                out += &self.event("response.custom_tool_call_input.delta", json!({"item_id":item_id,"output_index":index,"delta":item["input"]}));
                out += &self.event("response.custom_tool_call_input.done", json!({"item_id":item_id,"output_index":index,"input":item["input"]}));
            } else {
                out += &self.event("response.function_call_arguments.delta", json!({"item_id":item_id,"output_index":index,"delta":call.arguments}));
                out += &self.event("response.function_call_arguments.done", json!({"item_id":item_id,"output_index":index,"arguments":call.arguments}));
            }
            if !custom { item["status"] = json!("completed"); }
            out += &self.event("response.output_item.done", json!({"output_index":index,"item":item}));
            self.output.push(item);
        }
        self.finished = true;
        let status = if reason == "length" { "incomplete" } else { "completed" };
        let response = self.response(status);
        let event = if status == "incomplete" { "response.incomplete" } else { "response.completed" };
        out += &self.event(event, json!({"response":response}));
        Ok(out)
    }
    pub fn is_finished(&self) -> bool { self.finished }
}

pub fn nonstream(payload: &Value, model: String, custom_tools: HashSet<String>) -> Result<Value, String> {
    let choices = payload.get("choices").and_then(Value::as_array).ok_or("Upstream response lacks choices")?;
    if choices.len() != 1 { return Err("Expected exactly one upstream choice".into()); }
    let choice = &choices[0];
    let message = choice.get("message").ok_or("Upstream response lacks message")?;
    let mut delta = message.clone();
    if let Some(calls) = delta.get_mut("tool_calls").and_then(Value::as_array_mut) {
        for (index, call) in calls.iter_mut().enumerate() { call["index"] = json!(index); }
    }
    let chunk = json!({"choices":[{"delta":delta,"finish_reason":choice.get("finish_reason")}],"usage":payload.get("usage")});
    let mut response = Response::new(model, custom_tools);
    response.feed(&chunk.to_string())?;
    let events = response.finish()?;
    let data = events.lines().rev().find_map(|line| line.strip_prefix("data: ")).ok_or("Missing converted response")?;
    let event: Value = serde_json::from_str(data).map_err(|_| "Invalid converted response")?;
    event.get("response").cloned().ok_or_else(|| "Missing converted response".into())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn custom_tool_and_reasoning_round_trip_without_losing_history() {
        let input = json!({"model":"messenger/m", "input":[
            {"type":"message","role":"user","content":[{"type":"input_text","text":"edit"}]},
            {"type":"reasoning","id":"rs","summary":[{"type":"summary_text","text":"think"}]},
            {"type":"custom_tool_call","call_id":"call-1","name":"apply_patch","input":"patch text"},
            {"type":"custom_tool_call_output","call_id":"call-1","output":"ok"}
        ],"tools":[{"type":"custom","name":"apply_patch","format":{"type":"text"}}]});
        let converted = request(&input,"upstream").unwrap();
        assert_eq!(converted.body["messages"][1]["reasoning_content"], "think");
        assert_eq!(converted.body["messages"][1]["tool_calls"][0]["function"]["arguments"], r#"{"input":"patch text"}"#);
        assert_eq!(converted.body["messages"][2]["tool_call_id"], "call-1");
        let output = nonstream(&json!({"choices":[{"message":{"role":"assistant","content":null,
            "reasoning_content":"reason", "tool_calls":[{"id":"call-2","type":"function","function":{"name":"apply_patch","arguments":r#"{"input":"patch text"}"#}}]},
            "finish_reason":"tool_calls"}]}), "messenger/m".into(), converted.custom_tools).unwrap();
        assert_eq!(output["output"][0]["summary"][0]["text"], "reason");
        assert_eq!(output["output"][1]["type"], "custom_tool_call");
        assert_eq!(output["output"][1]["input"], "patch text");
    }

    #[test]
    fn rejects_untranslatable_input_instead_of_dropping_it() {
        assert!(request(&json!({"input":[{"type":"reasoning","encrypted_content":"opaque","summary":[]}]}), "model").unwrap_err().contains("Encrypted reasoning"));
        assert!(request(&json!({"input":"hi","tools":[{"type":"web_search","name":"search"}]}), "model").unwrap_err().contains("web_search"));
        assert!(request(&json!({"input":"hi","store":true}), "model").unwrap_err().contains("Stored Responses"));
    }

    #[test]
    fn converts_structured_output_and_verbosity() {
        let input = json!({"input":"hi", "text":{"verbosity":"low", "format":{
            "type":"json_schema", "name":"answer", "strict":true,
            "schema":{"type":"object", "properties":{}}
        }}});
        let converted = request(&input, "model").unwrap();
        assert_eq!(converted.body["verbosity"], "low");
        assert_eq!(converted.body["response_format"]["json_schema"]["name"], "answer");
        assert_eq!(converted.body["response_format"]["json_schema"]["schema"]["type"], "object");
    }
}
