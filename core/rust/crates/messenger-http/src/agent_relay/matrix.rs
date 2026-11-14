//! Stateless request/response protocol matrix. Stream events are projected
//! by the sibling stream module; stateful features remain unsupported.
use serde_json::{json, Value};
use messenger_llm::{formats::{self, ApiFormat}, request::*};

fn array(v: &Value) -> Result<&Vec<Value>, String> { v.as_array().ok_or_else(|| "Expected an array".into()) }
fn text(v: &Value) -> Result<String,String> {
    if let Some(s)=v.as_str() { return Ok(s.into()); }
    let mut out=String::new();
    for part in array(v)? {
        if !matches!(part["type"].as_str(),Some("text" | "input_text" | "output_text")) { return Err("Unsupported content block".into()); }
        out.push_str(part["text"].as_str().ok_or("Expected text")?);
    }
    Ok(out)
}
pub fn request(source: &str, target: &str, body: &Value, model: &str) -> Result<Value,String> {
    if source=="responses" && body["tools"].as_array().is_some_and(|tools|tools.iter().any(|tool|tool["type"]=="custom")) {return Err("Custom tools are only supported for Responses-to-Chat conversion".into());}
    let streaming=body["stream"].as_bool().unwrap_or(false);
    for key in ["previous_response_id","previous_interaction_id","conversation"] {
        if body.get(key).is_some_and(|v|!v.is_null()) { return Err(format!("Stateful field {key} cannot be converted")); }
    }
    let allowed: &[&str] = match source {
        "messages" => &["model","messages","system","tools","temperature","top_p","max_tokens","stream"],
        "interactions" => &["model","input","system_instruction","tools","temperature","top_p","max_tokens","stream"],
        _ => &[],
    };
    if !allowed.is_empty() { for (key,value) in body.as_object().ok_or("Expected a request object")? { if !value.is_null() && !allowed.contains(&key.as_str()) { return Err(format!("Unsupported request field: {key}")); } } }
    let chat=match source {
        "chat" => body.clone(),
        "responses" => super::convert::request(body,model)?.body,
        "messages" | "interactions" => {
            let mut messages=Vec::new();
            let system=if source=="messages" { &body["system"] } else { &body["system_instruction"] };
            if !system.is_null() { messages.push(json!({"role":"system","content":text(system)?})); }
            let input=if source=="messages" { &body["messages"] } else { &body["input"] };
            if input.is_string() { messages.push(json!({"role":"user","content":input})); }
            else { for item in array(input)? {
                let kind=item["type"].as_str().unwrap_or("");
                if source=="interactions" && kind=="function_call" {
                    messages.push(json!({"role":"assistant","content":null,"tool_calls":[{"id":item["id"],"type":"function","function":{"name":item["name"],"arguments":item["arguments"].to_string()}}]})); continue;
                }
                if source=="interactions" && kind=="function_result" {
                    messages.push(json!({"role":"tool","tool_call_id":item["call_id"],"content":text(&item["result"])?})); continue;
                }
                let role=if source=="messages" { item["role"].as_str().ok_or("Missing role")? } else { match kind { "user_input"=>"user", "model_output"=>"assistant", _=>return Err(format!("Unsupported Interactions input: {kind}")) } };
                if item["content"].is_string() { messages.push(json!({"role":role,"content":item["content"]})); continue; }
                let mut content=String::new(); let mut calls=Vec::new();
                for block in array(&item["content"])? {
                    match block["type"].as_str().ok_or("Missing block type")? {
                        "text"=>content.push_str(block["text"].as_str().ok_or("Missing text")?),
                        "tool_use"=>calls.push(json!({"id":block["id"],"type":"function","function":{"name":block["name"],"arguments":block["input"].to_string()}})),
                        "tool_result"=> { if block["is_error"]==true { return Err("Error tool results cannot be represented without loss".into()); } messages.push(json!({"role":"tool","tool_call_id":block["tool_use_id"],"content":text(&block["content"])?})); },
                        other=>return Err(format!("Unsupported content block: {other}")),
                    }
                }
                if !content.is_empty() || !calls.is_empty() { let mut message=json!({"role":role,"content":content}); if !calls.is_empty(){message["tool_calls"]=json!(calls);} messages.push(message); }
            }}
            let mut result=json!({"messages":messages,"model":model});
            for key in ["temperature","top_p","max_tokens"] { if let Some(value)=body.get(key) {result[key]=value.clone();} }
            if let Some(tools)=body.get("tools") {
                let mut converted=Vec::new();
                for tool in array(tools)? { if tool.get("type").and_then(Value::as_str).is_some_and(|s|s!="function") {return Err("Unsupported hosted tool".into());}
                    converted.push(json!({"type":"function","function":{"name":tool["name"],"description":tool.get("description").cloned().unwrap_or(json!("")),"parameters":if source=="messages" {&tool["input_schema"]} else {&tool["parameters"]}}})); }
                result["tools"]=json!(converted);
            }
            result
        }
        _=>return Err("Unknown source format".into()),
    };
    if target=="chat" { let mut chat=chat;chat["stream"]=json!(streaming); if streaming {chat["stream_options"]=json!({"include_usage":true});} chat["model"]=json!(model);return Ok(chat); }
    for (key,value) in chat.as_object().ok_or("Expected chat object")? {
        if !value.is_null() && !["model","messages","temperature","top_p","max_tokens","reasoning_effort","tools","stream","stream_options"].contains(&key.as_str()) {return Err(format!("Cannot preserve request field {key} for this target"));}
    }
    for message in array(&chat["messages"])? { if message.get("reasoning_content").is_some_and(|v| !v.is_null()) {return Err("Cannot preserve reasoning history for this target".into());} }
    for key in ["tool_choice","response_format","parallel_tool_calls","reasoning_content"] {if chat.get(key).is_some_and(|v|!v.is_null()){return Err(format!("Cannot preserve {key} for selected target"));}}
    let mut messages=Vec::new();
    for message in array(&chat["messages"])? {
        let mut calls=Vec::new();
        if let Some(items)=message.get("tool_calls") {for call in array(items)? {calls.push(WireToolCall {id:call["id"].as_str().ok_or("Missing call ID")?.into(),kind:"function".into(),function:WireToolCallFunction{name:call["function"]["name"].as_str().ok_or("Missing tool name")?.into(),arguments:call["function"]["arguments"].as_str().ok_or("Invalid arguments")?.into()}});}}
        messages.push(WireMessage{role:message["role"].as_str().ok_or("Missing role")?.into(),content:message["content"].clone(),reasoning_content:None,tool_calls:if calls.is_empty(){None}else{Some(calls)},tool_call_id:message["tool_call_id"].as_str().map(str::to_owned)});
    }
    let mut tools=Vec::new();
    if let Some(items)=chat.get("tools") {for tool in array(items)? {let f=&tool["function"];tools.push(ToolSpec{kind:"function".into(),function:ToolSpecFunction{name:f["name"].as_str().ok_or("Missing tool name")?.into(),description:f["description"].as_str().unwrap_or("").into(),parameters:f["parameters"].clone()}});}}
    Ok(formats::encode_request(ApiFormat::from_store(Some(target)), &ChatCompletionRequest {native_web_search:false,model:model.into(),messages,temperature:chat["temperature"].as_f64(),top_p:chat["top_p"].as_f64(),max_tokens:chat["max_tokens"].as_i64(),reasoning_effort:chat["reasoning_effort"].as_str().map(str::to_owned),thinking:None,tools:if tools.is_empty(){None}else{Some(tools)},stream:streaming}))
}

pub fn response(source: &str, target: &str, payload: &Value, model: &str) -> Result<Value,String> {
    if payload.get("error").is_some_and(|v|!v.is_null()) {return Err(payload["error"].to_string());}
    let expected=match source {"chat"=>"choices","responses"=>"output","messages"=>"content",_=>"outputs"};
    if !payload[expected].is_array() {return Err(format!("Upstream response lacks {expected}"));}
    let decoded=formats::decode_response(ApiFormat::from_store(Some(source)),payload);
    if !matches!(target,"chat"|"responses") && decoded.reasoning.is_some() { return Err("Reasoning output cannot yet be preserved for this target".into()); }
    let calls:Vec<_>=decoded.tool_calls.iter().map(|call|json!({"id":call.call_id,"type":"function","function":{"name":call.name,"arguments":call.arguments}})).collect();
    let mut message=json!({"role":"assistant","content":decoded.text});
    if !calls.is_empty(){message["tool_calls"]=json!(calls);}
    if let Some(reasoning)=decoded.reasoning {message["reasoning_content"]=json!(reasoning);}
    let mut chat=json!({"id":format!("chatcmpl_{}",uuid::Uuid::new_v4()),"object":"chat.completion","model":model,"choices":[{"index":0,"message":message,"finish_reason":decoded.finish_reason}]});
    if let Some(usage)=decoded.usage {chat["usage"]=json!({"prompt_tokens":usage.prompt_tokens,"completion_tokens":usage.completion_tokens,"total_tokens":usage.total_tokens});}
    if target=="chat" {return Ok(chat);}
    if target=="responses" {return super::convert::nonstream(&chat,model.into(),Default::default());}
    let mut content=Vec::new();
    if !decoded.text.is_empty(){content.push(json!({"type":"text","text":decoded.text}));}
    for call in decoded.tool_calls {let args:Value=serde_json::from_str(&call.arguments).map_err(|e|e.to_string())?;content.push(if target=="messages" {json!({"type":"tool_use","id":call.call_id,"name":call.name,"input":args})}else{json!({"type":"function_call","id":call.call_id,"name":call.name,"arguments":args})});}
    if target=="messages" {Ok(json!({"id":format!("msg_{}",uuid::Uuid::new_v4()),"type":"message","role":"assistant","model":model,"content":content,"stop_reason":match decoded.finish_reason.as_str(){"tool_calls"=>"tool_use","length"=>"max_tokens",_=>"end_turn"},"usage":{"input_tokens":chat["usage"]["prompt_tokens"],"output_tokens":chat["usage"]["completion_tokens"]}}))}
    else {Ok(json!({"id":format!("interaction_{}",uuid::Uuid::new_v4()),"status":"completed","model":model,"outputs":content,"usage":{"total_input_tokens":chat["usage"]["prompt_tokens"],"total_output_tokens":chat["usage"]["completion_tokens"],"total_tokens":chat["usage"]["total_tokens"]}}))}
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn request_matrix_encodes_all_provider_targets() {
        for (source,body) in [
            ("chat",json!({"messages":[{"role":"user","content":"hello"}]})),
            ("responses",json!({"input":"hello"})),
            ("messages",json!({"messages":[{"role":"user","content":"hello"}],"max_tokens":100})),
            ("interactions",json!({"input":"hello"})),
        ] {
            for target in ["chat","responses","messages","interactions"] {
                let result=request(source,target,&body,"upstream").unwrap();
                assert_eq!(result["model"],"upstream","{source} -> {target}");
                assert!(result.to_string().contains("hello"));
            }
        }
    }
    #[test]
    fn messages_tool_history_reaches_chat() {
        let result=request("messages","chat",&json!({"messages":[
            {"role":"assistant","content":[{"type":"tool_use","id":"c","name":"read","input":{"path":"file"}}]},
            {"role":"user","content":[{"type":"tool_result","tool_use_id":"c","content":"result"},{"type":"text","text":"continue"}]}
        ]}),"upstream").unwrap();
        assert_eq!(result["messages"][0]["tool_calls"][0]["id"],"c");
        assert_eq!(result["messages"][1]["tool_call_id"],"c");
        assert_eq!(result["messages"][2]["content"],"continue");
    }
    #[test]
    fn response_matrix_preserves_text_and_calls() {
        let payload=json!({"choices":[{"message":{"content":"answer","tool_calls":[{"id":"c","type":"function","function":{"name":"read","arguments":"{}"}}]},"finish_reason":"tool_calls"}]});
        for target in ["chat","responses","messages","interactions"] {
            let result=response("chat",target,&payload,"alias").unwrap();
            assert!(result.to_string().contains("answer"));
            assert!(result.to_string().contains("read"));
        }
    }
}
