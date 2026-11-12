//! Streaming protocol projection through Responses events. No transport buffering
//! beyond a single SSE frame is required.
use serde_json::{json,Value};
use messenger_llm::{formats::{ApiFormat,FormatStreamDecoder},ChatStreamEvent};
use std::collections::HashSet;

pub struct Converter {
    source:String, target:String, model:String, decoder:FormatStreamDecoder,
    responses:super::convert::Response, reasoning:bool, index:usize,
}
fn event(kind:&str, value:Value)->String {format!("event: {kind}\ndata: {value}\n\n")}
impl Converter {
    pub fn new(source:String,target:String,model:String,custom:HashSet<String>)->Self {
        Self {decoder:FormatStreamDecoder::new(ApiFormat::from_store(Some(&source))),responses:super::convert::Response::new(model.clone(),custom),source,target,model,reasoning:false,index:0}
    }
    pub fn is_finished(&self)->bool {self.responses.is_finished()}
    pub fn start(&mut self)->String {let events=self.responses.start();self.project(events)}
    pub fn failed(&mut self,message:&str)->String {let events=self.responses.failed(message);self.project(events)}
    pub fn feed(&mut self,payload:&str)->Result<String,String> {
        if self.source=="chat" {let events=self.responses.feed(payload)?;return Ok(self.project(events));}
        if payload=="[DONE]" {return Err("Upstream ended without a protocol terminal event".into());}
        let record:Value=serde_json::from_str(payload).map_err(|e|format!("Invalid upstream event: {e}"))?;
        let kind=record["type"].as_str().or_else(||record["event_type"].as_str()).unwrap_or("").to_owned();
        let incomplete=kind=="response.incomplete" || (kind=="message_delta" && record["delta"]["stop_reason"]=="max_tokens");
        let payload=record.to_string();
        let mut out=String::new();
        for item in self.decoder.feed(&payload) {
            let chunk=match item {
                ChatStreamEvent::Content(text)=> {
                    if text=="<think>" {self.reasoning=true;continue;}
                    if text=="</think>\n" {self.reasoning=false;continue;}
                    let delta=if self.reasoning {json!({"reasoning_content":text})}else{json!({"content":text})};
                    json!({"choices":[{"delta":delta,"finish_reason":null}]})
                }
                ChatStreamEvent::ReasoningDetected{..}=>continue,
                ChatStreamEvent::Error(message)=>return Err(message),
                ChatStreamEvent::Done{finish_reason,usage,tool_calls}=> {
                    let calls:Vec<_>=tool_calls.iter().enumerate().map(|(index,c)|json!({"index":index,"id":c.call_id,"type":"function","function":{"name":c.name,"arguments":c.arguments}})).collect();
                    let mut chunk=json!({"choices":[{"delta":{"tool_calls":calls},"finish_reason":if incomplete {"length".into()} else {finish_reason.unwrap_or_else(||if calls.is_empty(){"stop".into()}else{"tool_calls".into()})}}]});
                    if let Some(u)=usage {chunk["usage"]=json!({"prompt_tokens":u.prompt_tokens,"completion_tokens":u.completion_tokens,"total_tokens":u.total_tokens});}
                    let events=self.responses.feed(&chunk.to_string())?;out+=&self.project(events);
                    let events=self.responses.feed("[DONE]")?;out+=&self.project(events);continue;
                }
            };
            let events=self.responses.feed(&chunk.to_string())?;out+=&self.project(events);
        }
        Ok(out)
    }
    fn project(&mut self,events:String)->String {
        if self.target=="responses" {return events;}
        let mut out=String::new();
        for line in events.lines().filter_map(|line|line.strip_prefix("data: ")) {
            let v:Value=serde_json::from_str(line).expect("internally generated event");
            let kind=v["type"].as_str().unwrap_or("");
            let index=v["output_index"].as_u64().unwrap_or(self.index as u64);
            if kind=="response.output_item.added" {self.index=index as usize;}
            if self.target=="chat" {
                let delta=match kind {
                    "response.output_text.delta"=>Some(json!({"content":v["delta"]})),
                    "response.reasoning_summary_text.delta"=>Some(json!({"reasoning_content":v["delta"]})),
                    "response.output_item.done" if v["item"]["type"]=="function_call"=>Some(json!({"tool_calls":[{"index":index,"id":v["item"]["call_id"],"type":"function","function":{"name":v["item"]["name"],"arguments":v["item"]["arguments"]}}]})),
                    _=>None,
                };
                if let Some(delta)=delta {out+=&format!("data: {}\n\n",json!({"id":v.get("item_id").unwrap_or(&json!("relay")),"object":"chat.completion.chunk","model":self.model,"choices":[{"index":0,"delta":delta,"finish_reason":null}]}));}
            } else if self.target=="messages" {
                match kind {
                    "response.created"=>out+=&event("message_start",json!({"type":"message_start","message":{"id":v["response"]["id"],"type":"message","role":"assistant","model":self.model,"content":[],"stop_reason":null,"usage":{"input_tokens":0,"output_tokens":0}}})),
                    "response.content_part.added"=>out+=&event("content_block_start",json!({"type":"content_block_start","index":index,"content_block":{"type":"text","text":""}})),
                    "response.output_item.added" if v["item"]["type"]=="function_call"=>out+=&event("content_block_start",json!({"type":"content_block_start","index":index,"content_block":{"type":"tool_use","id":v["item"]["call_id"],"name":v["item"]["name"],"input":{}}})),
                    "response.output_item.added" if v["item"]["type"]=="reasoning"=>out+=&event("content_block_start",json!({"type":"content_block_start","index":index,"content_block":{"type":"thinking","thinking":""}})),
                    "response.output_text.delta" | "response.function_call_arguments.delta" | "response.reasoning_summary_text.delta"=>{
                        let delta=match kind {"response.output_text.delta"=>json!({"type":"text_delta","text":v["delta"]}),"response.function_call_arguments.delta"=>json!({"type":"input_json_delta","partial_json":v["delta"]}),_=>json!({"type":"thinking_delta","thinking":v["delta"]})};
                        out+=&event("content_block_delta",json!({"type":"content_block_delta","index":index,"delta":delta}));
                    }
                    "response.output_item.done"=>out+=&event("content_block_stop",json!({"type":"content_block_stop","index":index})),
                    _=>{}
                }
            } else {
                match kind {
                    "response.created"=>out+=&event("interaction.created",json!({"event_type":"interaction.created","interaction":{"id":v["response"]["id"],"model":self.model,"status":"in_progress"}})),
                    "response.output_item.added"=>{
                        let item=&v["item"];
                        let step=match item["type"].as_str(){Some("function_call")=>json!({"type":"function_call","id":item["call_id"],"name":item["name"],"arguments":{}}),Some("reasoning")=>json!({"type":"thought","summary":[]}),_=>json!({"type":"model_output","content":[]})};
                        out+=&event("step.start",json!({"event_type":"step.start","index":index,"step":step}));
                    }
                    "response.output_text.delta" | "response.function_call_arguments.delta" | "response.reasoning_summary_text.delta"=>{
                        let delta=match kind {"response.output_text.delta"=>json!({"type":"text","text":v["delta"]}),"response.function_call_arguments.delta"=>json!({"type":"arguments_delta","arguments":v["delta"]}),_=>json!({"type":"thought_summary","content":{"type":"text","text":v["delta"]}})};
                        out+=&event("step.delta",json!({"event_type":"step.delta","index":index,"delta":delta}));
                    }
                    "response.output_item.done"=>out+=&event("step.stop",json!({"event_type":"step.stop","index":index})),
                    _=>{}
                }
            }
            if kind=="response.failed" {
                let err=json!({"type":"error","event_type":"error","error":v["response"]["error"]});
                out+=&event("error",err);
            }
            if matches!(kind,"response.completed"|"response.incomplete") {
                let r=&v["response"];let u=&r["usage"];
                let calls=r["output"].as_array().is_some_and(|items|items.iter().any(|item|item["type"]=="function_call"));
                let reason=if kind=="response.incomplete" {"length"}else if calls {"tool_calls"}else{"stop"};
                if self.target=="chat" {
                    out+=&format!("data: {}\n\n",json!({"id":r["id"],"object":"chat.completion.chunk","model":self.model,"choices":[{"index":0,"delta":{},"finish_reason":reason}],"usage":{"prompt_tokens":u["input_tokens"],"completion_tokens":u["output_tokens"],"total_tokens":u["total_tokens"]}}));out+="data: [DONE]\n\n";
                } else if self.target=="messages" {
                    out+=&event("message_delta",json!({"type":"message_delta","delta":{"stop_reason":match reason{"length"=>"max_tokens","tool_calls"=>"tool_use",_=>"end_turn"},"stop_sequence":null},"usage":{"input_tokens":u["input_tokens"],"output_tokens":u["output_tokens"]}}));out+=&event("message_stop",json!({"type":"message_stop"}));
                } else {
                    out+=&event("interaction.completed",json!({"event_type":"interaction.completed","interaction":{"id":r["id"],"model":self.model,"status":r["status"],"steps":r["output"].as_array().map(|items|items.iter().map(|item|match item["type"].as_str(){Some("function_call")=>json!({"type":"function_call","id":item["call_id"],"name":item["name"],"arguments":serde_json::from_str::<Value>(item["arguments"].as_str().unwrap_or("{}")).unwrap_or(Value::Null)}),Some("reasoning")=>json!({"type":"thought","summary":item["summary"]}),_=>json!({"type":"model_output","content":item["content"]})}).collect::<Vec<_>>()).unwrap_or_default(),"usage":{"total_input_tokens":u["input_tokens"],"total_output_tokens":u["output_tokens"],"total_tokens":u["total_tokens"]}}}));
                }
            }
        }
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn stream_matrix_projects_text_tools_and_terminal_events() {
        let fixtures=vec![
            ("chat",vec![json!({"choices":[{"delta":{"content":"hello","tool_calls":[{"index":0,"id":"c","function":{"name":"read","arguments":"{}"}}]},"finish_reason":"tool_calls"}]})]),
            ("responses",vec![json!({"type":"response.output_text.delta","delta":"hello"}),json!({"type":"response.output_item.added","output_index":1,"item":{"type":"function_call","call_id":"c","name":"read"}}),json!({"type":"response.function_call_arguments.delta","output_index":1,"delta":"{}"}),json!({"type":"response.completed","response":{"output":[{"type":"function_call","call_id":"c","name":"read","arguments":"{}"},{"type":"function_call","call_id":"c2","name":"write","arguments":"{}"}],"usage":{"input_tokens":2,"output_tokens":3}}})]),
            ("messages",vec![json!({"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hello"}}),json!({"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"c","name":"read"}}),json!({"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{}"}}),json!({"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":3}})]),
            ("interactions",vec![json!({"event_type":"step.delta","index":0,"delta":{"type":"text","text":"hello"}}),json!({"event_type":"step.start","index":1,"step":{"type":"function_call","id":"c","name":"read"}}),json!({"event_type":"step.delta","index":1,"delta":{"type":"arguments_delta","arguments":"{}"}}),json!({"event_type":"interaction.completed","interaction":{"usage":{"total_input_tokens":2,"total_output_tokens":3}}})]),
        ];
        for (source,fixture) in fixtures {
            for target in ["chat","responses","messages","interactions"] {
                let mut c=Converter::new(source.into(),target.into(),"alias".into(),Default::default());
                let mut out=c.start();
                for item in &fixture {out+=&c.feed(&item.to_string()).unwrap();}
                if source=="chat" {out+=&c.feed("[DONE]").unwrap();}
                assert!(c.is_finished(),"{source} -> {target}");
                if source=="responses" {assert!(out.contains("write"),"Parallel final calls must survive: {out}");}
                assert!(out.contains("hello") && out.contains("read"),"{source} -> {target}: {out}");
                assert!(out.contains(match target{"chat"=>"[DONE]","responses"=>"response.completed","messages"=>"message_stop",_=>"interaction.completed"}));
            }
        }
    }
}

#[cfg(test)]
mod failure_tests {
    use super::*;
    #[test]
    fn errors_and_incomplete_streams_are_not_reported_as_success() {
        for target in ["chat","responses","messages","interactions"] {
            let mut converter=Converter::new("messages".into(),target.into(),"alias".into(),Default::default());
            assert!(converter.feed("[DONE]").is_err());
            let failed=converter.failed("truncated stream");
            assert!(failed.contains("truncated stream"));
            assert!(!failed.contains("response.completed"));
            let mut converter=Converter::new("messages".into(),target.into(),"alias".into(),Default::default());
            let terminal=converter.feed(&json!({"type":"message_delta","delta":{"stop_reason":"max_tokens"}}).to_string()).unwrap();
            assert!(terminal.contains(match target {"chat"=>"length","responses"=>"response.incomplete","messages"=>"max_tokens",_=>"incomplete"}));
        }
    }
}
