//! Server configuration (port of `McpServerConfig.kt`). The serde shape is
//! the persisted DataStore `mcp_servers_json` format — renaming a field
//! would orphan users' server lists, so names mirror the Kotlin data class.

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum McpTransportType {
    STDIO,
    SSE,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct McpServerConfig {
    pub id: String,
    pub name: String,
    #[serde(rename = "transportType")]
    pub transport_type: McpTransportType,
    #[serde(rename = "isEnabled")]
    pub is_enabled: bool,
    // Stdio / Command transport
    pub command: String,
    pub args: Vec<String>,
    pub env: std::collections::HashMap<String, String>,
    // SSE transport
    pub url: String,
    pub headers: std::collections::HashMap<String, String>,
}

impl Default for McpServerConfig {
    fn default() -> Self {
        Self {
            id: String::new(),
            name: String::new(),
            transport_type: McpTransportType::STDIO,
            is_enabled: true,
            command: String::new(),
            args: Vec::new(),
            env: Default::default(),
            url: String::new(),
            headers: Default::default(),
        }
    }
}

/// Serialize/parse the whole server list (the `mcp_servers_json` value).
pub fn encode_server_list(servers: &[McpServerConfig]) -> String {
    serde_json::to_string(servers).unwrap_or_else(|_| "[]".to_string())
}

pub fn decode_server_list(json: &str) -> Vec<McpServerConfig> {
    serde_json::from_str(json).unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;

    // -- ported: McpTest.test McpServerConfig serialization --

    #[test]
    fn server_list_round_trips_kotlin_shape() {
        let servers = vec![
            McpServerConfig {
                id: "srv-1".into(),
                name: "Local Everything".into(),
                transport_type: McpTransportType::STDIO,
                is_enabled: true,
                command: "npx".into(),
                args: vec!["-y".into(), "@modelcontextprotocol/server-everything".into()],
                env: [("DEBUG".to_string(), "1".to_string())].into_iter().collect(),
                ..Default::default()
            },
            McpServerConfig {
                id: "srv-2".into(),
                name: "Remote SSE".into(),
                transport_type: McpTransportType::SSE,
                is_enabled: false,
                url: "https://example.com/sse".into(),
                headers: [("Authorization".to_string(), "Bearer test-token".to_string())]
                    .into_iter()
                    .collect(),
                ..Default::default()
            },
        ];
        let serialized = encode_server_list(&servers);
        assert!(serialized.contains("\"transportType\":\"STDIO\""));
        let deserialized = decode_server_list(&serialized);
        assert_eq!(deserialized.len(), 2);
        assert_eq!(deserialized[0].name, "Local Everything");
        assert_eq!(deserialized[0].transport_type, McpTransportType::STDIO);
        assert_eq!(deserialized[0].args, vec!["-y", "@modelcontextprotocol/server-everything"]);
        assert_eq!(deserialized[1].name, "Remote SSE");
        assert_eq!(deserialized[1].transport_type, McpTransportType::SSE);
        assert!(!deserialized[1].is_enabled);
    }
}
